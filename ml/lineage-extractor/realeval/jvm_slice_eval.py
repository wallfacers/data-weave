"""041-R JVM 扩语言：held-out 切片对比评测（before/after）。

比较多个模型在**同一 JVM 增强 held-out**（data/out-jvm/heldout.jsonl）上按 form_family
的表现，核心回答两问：
  ① run-jvm（学过 JVM）在 jvm 切片上是否学会抽取，而 run3（没学过 JVM）在 jvm 切片上失败
     → 证明合成 JVM 训练闭环有效、"C-下半 0 覆盖"缺口被填。
  ② run-jvm 在 python/shell 等既有切片上是否保持（无回归）→ 加 JVM 没伤既有语言。

顺序加载/释放模型以适配 12G 显存（一次只驻留一个模型）。

用法：PYTHONPATH=. python3 realeval/jvm_slice_eval.py \
        --data data/out-jvm/heldout.jsonl \
        --point run3(before)=out/run3/merged --point run-jvm(after)=out/run-jvm/merged \
        --report out/jvm-slice.md
"""
from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

from eval.evaluate import run_eval

# JVM 语言的 task_type（切片汇总用）
_JVM_TT = {"SCALA", "JAVA"}


def _load(path: str) -> list:
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _model_fn(model_dir: str):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    from eval.evaluate import predict as _predict
    tok = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()

    def fn(row):
        return _predict(model, tok, row)
    return fn, model


def _by_task_type(fn, rows: list) -> dict:
    """按 task_type 聚合（JVM=SCALA∪JAVA vs 既有 PYTHON/SHELL），补 run_eval 的 by_family。"""
    from eval.metrics import aggregate, score_row
    buckets: dict[str, list] = {}
    for row in rows:
        c = score_row(row["labels"], fn(row), row["content"])
        buckets.setdefault(row["task_type"], []).append(c)
        buckets.setdefault("JVM(scala+java)" if row["task_type"] in _JVM_TT else "existing(py+sh)", []).append(c)
    return {k: aggregate(v) for k, v in buckets.items()}


def evaluate_point(label: str, model_dir: str, rows: list) -> dict:
    import torch
    fn, model = _model_fn(model_dir)
    res = run_eval(fn, rows)
    tt = _by_task_type(fn, rows)
    del model, fn
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    jvm = res["by_family"].get("jvm", {})
    return {"label": label, "model": model_dir, "overall": res["overall"],
            "by_family": res["by_family"], "by_task_type": tt, "jvm_family": jvm}


def _row(name: str, m: dict) -> str:
    if not m:
        return f"| {name} | – | – | – | – |"
    return (f"| {name} | {m['precision']:.4f} | {m['recall']:.4f} | "
            f"{m['direction_acc']:.4f} | {m['hallucination']:.4f} |")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/out-jvm/heldout.jsonl")
    ap.add_argument("--point", action="append", default=[], help="LABEL=model_dir，可多次")
    ap.add_argument("--report", default="out/jvm-slice.md")
    args = ap.parse_args(argv)

    rows = _load(args.data)
    n_jvm = sum(1 for r in rows if r["task_type"] in _JVM_TT)
    print(f"held-out {len(rows)}（JVM 切片 {n_jvm}）")

    results = []
    for spec in args.point:
        label, _, model_dir = spec.partition("=")
        if not Path(model_dir).exists():
            print(f"[warn] 跳过 {label}：{model_dir} 不存在")
            continue
        print(f"评测 {label} ← {model_dir}")
        results.append(evaluate_point(label, model_dir, rows))

    lines = [
        "# 041-R JVM 扩语言 held-out 切片对比（加强实验 D）", "",
        f"同一 JVM 增强 held-out（n={len(rows)}，其中 JVM 切片 scala+java = {n_jvm}）。",
        "before = run3（Python/Shell-only 训练，未见 JVM）；after = run-jvm（四语言训练）。", "",
        "## 按 task_type 切片（JVM vs 既有语言）", "",
        "| 模型 · 切片 | precision | recall | 方向准确率 | 幻觉率 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for r in results:
        tt = r["by_task_type"]
        for key in ("JVM(scala+java)", "existing(py+sh)", "SCALA", "JAVA", "PYTHON", "SHELL"):
            if key in tt:
                lines.append(_row(f"{r['label']} · {key}", tt[key]))
        lines.append("| | | | | |")

    lines += ["", "## 整体 + jvm form_family", "",
              "| 模型 | 整体 prec | 整体 方向 | 整体 幻觉 | jvm-fam prec | jvm-fam 方向 | jvm-fam 幻觉 |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
    for r in results:
        o, j = r["overall"], r["jvm_family"]
        jf = (f"{j['precision']:.4f} | {j['direction_acc']:.4f} | {j['hallucination']:.4f}"
              if j else "– | – | –")
        lines.append(f"| {r['label']} | {o['precision']:.4f} | {o['direction_acc']:.4f} "
                     f"| {o['hallucination']:.4f} | {jf} |")

    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
