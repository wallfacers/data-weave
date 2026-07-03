"""041 评估门槛闸（SC-006，FR-014）。

指标（heldout ≥200 条，与训练形态隔离）：
  1. 表级 precision ≥ 0.85（全集）
  2. 规则未覆盖子集（meta.rule_covered=False）表级召回 ≥ 0.60（模型对规则的增量价值）
  3. 幻觉率 ≤ 0.02（输出表名无法在脚本文本中定位的比例，按校验前原始输出计）
参考值：字段级正确率（不设闸）。

退出码：0=过闸可接入；1=不过闸（US4 以差距报告收尾，平台侧保持旁路）。

用法：python eval/evaluate.py --model out/run1/merged --data data/out/heldout.jsonl --report out/eval-report.md
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

SYSTEM_PROMPT = (
    "You are a data lineage extractor for ETL scripts. Given a PYTHON or SHELL task "
    "script, output ONLY a JSON object {\"reads\": [...], \"writes\": [...]} where each "
    "item is {\"table\": str, \"columns\": [str] or null}. Rules: include a table only if "
    "its literal name appears in the script text; ignore dynamically-built table names, "
    "commented-out SQL, and SQL that is merely printed or logged; if nothing is read or "
    "written, output {\"reads\": [], \"writes\": []}."
)

GATE = {"precision": 0.85, "uncovered_recall": 0.60, "hallucination": 0.02}


def predict(model, tok, row: dict) -> dict:
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


def tables(items) -> set[str]:
    out = set()
    for it in items:
        if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip():
            out.add(it["table"].strip().lower())
    return out


def main() -> None:
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

    tp = fp = fn = 0
    unc_tp = unc_fn = 0
    halluc = 0
    pred_total = 0
    invalid = 0
    col_total = col_correct = 0
    failures = []

    for i, row in enumerate(rows):
        pred = predict(model, tok, row)
        if pred.get("_invalid"):
            invalid += 1
        gold_r, gold_w = tables(row["labels"]["reads"]), tables(row["labels"]["writes"])
        pred_r, pred_w = tables(pred["reads"]), tables(pred["writes"])
        content_lower = row["content"].lower()

        for direction, gold, got in [("R", gold_r, pred_r), ("W", gold_w, pred_w)]:
            tp += len(gold & got)
            fp += len(got - gold)
            fn += len(gold - got)
            if not row["meta"].get("rule_covered", True):
                unc_tp += len(gold & got)
                unc_fn += len(gold - got)
            for t in got:
                pred_total += 1
                if t not in content_lower:
                    halluc += 1
            if got != gold and len(failures) < 20:
                failures.append(f"[{row['meta']['template_id']}#{i}] {direction} gold={sorted(gold)} got={sorted(got)}")

        # 字段级参考：gold 有列清单的写表，比对列集合
        for g in row["labels"]["writes"]:
            if g.get("columns"):
                col_total += 1
                match = next((p for p in pred["writes"]
                              if isinstance(p, dict) and str(p.get("table", "")).lower() == g["table"].lower()), None)
                if match and match.get("columns") and set(map(str.lower, match["columns"])) == set(map(str.lower, g["columns"])):
                    col_correct += 1

        if (i + 1) % 40 == 0:
            print(f"  eval {i + 1}/{len(rows)}")

    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 1.0
    unc_recall = unc_tp / (unc_tp + unc_fn) if unc_tp + unc_fn else 1.0
    halluc_rate = halluc / pred_total if pred_total else 0.0
    col_acc = col_correct / col_total if col_total else None

    passed = (precision >= GATE["precision"] and unc_recall >= GATE["uncovered_recall"]
              and halluc_rate <= GATE["hallucination"])

    report = f"""# 041 模型评估报告（SC-006 门槛闸）

- 样本：{len(rows)}（heldout，形态隔离）
- 表级 precision：**{precision:.4f}**（闸 ≥ {GATE['precision']}）
- 表级 recall（参考）：{recall:.4f}
- 规则未覆盖子集召回：**{unc_recall:.4f}**（闸 ≥ {GATE['uncovered_recall']}）
- 幻觉率：**{halluc_rate:.4f}**（闸 ≤ {GATE['hallucination']}）
- 字段级正确率（参考）：{f'{col_acc:.4f}' if col_acc is not None else 'n/a'}（{col_correct}/{col_total}）
- 非法输出：{invalid}
- **结论：{'✅ 过闸' if passed else '❌ 不过闸'}**

## 失败样例（≤20）
""" + "\n".join(f"- {f}" for f in failures)

    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
