"""041 评估门槛闸（SC-006，FR-014）。

指标（heldout ≥200 条，与训练形态隔离）：
  1. 表级 precision ≥ 0.85（全集）
  2. 规则未覆盖子集（meta.rule_covered=False）表级召回 ≥ 0.60（模型对规则的增量价值）
  3. 幻觉率 ≤ 0.02（输出表名无法在脚本文本中定位的比例，按校验前原始输出计）
  4. 方向准确率 ≥ 0.95（读/写方向不混淆）
参考值：字段级正确率（不设闸）。

评估器与被评对象解耦：`run_eval(predict_fn, rows)` 只依赖一个
`predict_fn(row) -> {"reads": [...], "writes": [...]}` 可调用对象，不关心它背后是
本模型推理、大模型 baseline 还是纯规则/正则实现。`main()` 里的 `predict()` 仍是
真实 transformers 推理，但 torch/transformers 的 import 延后到函数内部（惰性），
使得 `from eval.evaluate import run_eval` 在不安装 torch 的环境也能成功，
`tests/test_evaluate_layers.py` 无需加载模型即可对 run_eval 单测。

退出码：0=过闸可接入；1=不过闸（US4 以差距报告收尾，平台侧保持旁路）。

用法：python eval/evaluate.py --model out/run1/merged --data data/out/heldout.jsonl --report out/eval-report.md
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from eval.metrics import GATE, aggregate, score_row, tables

# 与 train/sft_qlora.py 的 SYSTEM_PROMPT 保持逐字一致（训练/评测 prompt 必须同源，
# 否则模型在推理时看到与训练不同的系统提示 → 掉分）。041-R JVM 扩语言后同步为四语言。
SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON, SHELL, SCALA or "
    "JAVA task script (Spark/Flink jobs included), output ONLY a JSON object "
    "{\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)


def predict(model, tok, row: dict) -> dict:
    """真实模型推理（惰性 import torch，避免拖累 run_eval 的无 torch 单测）。"""
    import torch

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"task_type: {row['task_type']}\nscript:\n{row['content']}"},
    ]
    inputs = tok.apply_chat_template(messages, add_generation_prompt=True, tokenize=True,
                                     return_dict=True, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**inputs, max_new_tokens=256, do_sample=False,
                             pad_token_id=tok.pad_token_id or tok.eos_token_id)
    text = tok.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        return {"reads": [], "writes": [], "_invalid": True}
    try:
        obj = json.loads(m.group(0))
        return {"reads": obj.get("reads") or [], "writes": obj.get("writes") or []}
    except Exception:
        return {"reads": [], "writes": [], "_invalid": True}


def run_eval(predict_fn, rows: list) -> dict:
    """评估器主体：只依赖 predict_fn(row)->{reads,writes} 与 eval.metrics，解耦被评对象。"""
    counts, by_fam, by_src, failures = [], {}, {}, []
    for i, row in enumerate(rows):
        pred = predict_fn(row)
        c = score_row(row["labels"], pred, row["content"])
        counts.append(c)
        fam = row["meta"].get("form_family", "?")
        src = "synth" if "synth" in str(row["meta"].get("source_dataset", "synth")) else "real"
        by_fam.setdefault(fam, []).append(c)
        by_src.setdefault(src, []).append(c)
        for d, g, p in [("R", tables(row["labels"]["reads"]), tables(pred.get("reads"))),
                        ("W", tables(row["labels"]["writes"]), tables(pred.get("writes")))]:
            if g != p and len(failures) < 30:
                failures.append(f"[{row['meta']['template_id']}#{i}] {d} gold={sorted(g)} got={sorted(p)}")
    return {"overall": aggregate(counts), "n": len(rows),
            "by_family": {k: aggregate(v) for k, v in by_fam.items()},
            "by_source": {k: aggregate(v) for k, v in by_src.items()},
            "uncovered": aggregate([c for c, r in zip(counts, rows)
                                    if not r["meta"].get("rule_covered", True)]),
            "failures": failures}


def _fmt_metrics_row(name: str, m: dict) -> str:
    return (f"| {name} | {m['precision']:.4f} | {m['recall']:.4f} | "
            f"{m['f1']:.4f} | {m['direction_acc']:.4f} | {m['hallucination']:.4f} | {m['invalid']} |")


def gate_pass(overall: dict, uncovered: dict) -> bool:
    """SC-006 门槛闸判定（4 条件与 GATE 阈值），供 write_report/main 共用，避免重复。"""
    return (overall["precision"] >= GATE["precision"]
            and uncovered["recall"] >= GATE["uncovered_recall"]
            and overall["hallucination"] <= GATE["hallucination"]
            and overall["direction_acc"] >= GATE["direction_acc"])


def write_report(result: dict, path: str) -> None:
    """把分层结果渲染成 md（沿用 eval-report.md 风格，含方向准确率+分形态/来源表），并 json.dump 到同名 .json。"""
    overall, uncovered = result["overall"], result["uncovered"]
    by_fam, by_src = result["by_family"], result["by_source"]

    passed = gate_pass(overall, uncovered)

    fam_rows = "\n".join(_fmt_metrics_row(k, v) for k, v in sorted(by_fam.items()))
    src_rows = "\n".join(_fmt_metrics_row(k, v) for k, v in sorted(by_src.items()))

    report = f"""# 041 模型评估报告（SC-006 门槛闸）

- 样本：{result['n']}（heldout，形态隔离）
- 表级 precision：**{overall['precision']:.4f}**（闸 ≥ {GATE['precision']}）
- 表级 recall（参考）：{overall['recall']:.4f}
- 方向准确率：**{overall['direction_acc']:.4f}**（闸 ≥ {GATE['direction_acc']}）
- 规则未覆盖子集召回：**{uncovered['recall']:.4f}**（闸 ≥ {GATE['uncovered_recall']}）
- 幻觉率：**{overall['hallucination']:.4f}**（闸 ≤ {GATE['hallucination']}）
- 非法输出：{overall['invalid']}
- **结论：{'✅ 过闸' if passed else '❌ 不过闸'}**

## 按形态（form_family）

| 形态 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
{fam_rows}

## 按数据源（synth / real）

| 来源 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
{src_rows}

## 规则未覆盖子集（meta.rule_covered=False）

| 子集 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| uncovered | {uncovered['precision']:.4f} | {uncovered['recall']:.4f} | {uncovered['f1']:.4f} | {uncovered['direction_acc']:.4f} | {uncovered['hallucination']:.4f} | {uncovered['invalid']} |

## 失败样例（≤30）
""" + "\n".join(f"- {f}" for f in result["failures"])

    out_path = Path(path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")
    out_path.with_suffix(".json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)


def main() -> None:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="out/run1/merged")
    ap.add_argument("--data", default="data/out/heldout.jsonl")
    ap.add_argument("--report", default="out/eval-report.md")
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.data).read_text(encoding="utf-8").splitlines() if l.strip()]
    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.bfloat16,
                                                 device_map="cuda" if torch.cuda.is_available() else "cpu")
    model.eval()

    progress = {"i": 0}

    def predict_fn(row: dict) -> dict:
        progress["i"] += 1
        if progress["i"] % 40 == 0:
            print(f"  eval {progress['i']}/{len(rows)}")
        return predict(model, tok, row)

    result = run_eval(predict_fn, rows)
    write_report(result, args.report)

    overall, uncovered = result["overall"], result["uncovered"]
    passed = gate_pass(overall, uncovered)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
