"""059：语义 grounding 收益测量（离线，复用已落盘预测，无 GPU）。

对多个模型的预测（自托管 3B / qwen-max / deepseek-pro），在**校准 gold**（pro 仲裁 +
动态名加严，与 rescore_arbitrated 同尺子）上跑三档过滤对照：
- raw：无过滤
- literal：现状 literal-grounding（`analyze_grounding.filter_pred`）
- semantic：上下文感知语义 grounding（`semantic_grounding.filter_pred_semantic`）

一眼看 semantic 档是否把 ALL-p 从 literal 推向 oracle 上限，且非空召回不退化。

用法: PYTHONPATH=. python3 realeval/rescore_semantic.py \
        --preds 3B:out/preds-c-run-059-runc.jsonl \
        --preds qwen-max:out/preds-c-teacher-qwen-max.jsonl \
        --preds deepseek-pro:out/preds-c-teacher-deepseek-pro.jsonl \
        --gold-arb realeval/gold/real-c-arbitrated.jsonl --report out/rescore-semantic.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row
from realeval.analyze_grounding import filter_pred
from realeval.rescore_arbitrated import _strip_dynamic
from realeval.semantic_grounding import filter_pred_semantic

_MODES = {
    "raw": lambda pred, content: pred,
    "literal": lambda pred, content: filter_pred(pred, content),
    "semantic": lambda pred, content: filter_pred_semantic(pred, content),
}


def score(preds, gold_rows, mode):
    apply = _MODES[mode]
    ca, cn = [], []
    for p, g in zip(preds, gold_rows):
        labels = _strip_dynamic(g["labels"])
        pred = apply(p["pred"], p["content"])
        c = score_row(labels, pred, p["content"])
        ca.append(c)
        if not g.get("is_empty"):
            cn.append(c)
    return aggregate(ca), aggregate(cn), len(cn)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", action="append", required=True,
                    help="label:path，可重复；如 3B:out/preds-c-run-059-runc.jsonl")
    ap.add_argument("--gold-arb", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--report", default="out/rescore-semantic.md")
    args = ap.parse_args(argv)

    gold = [json.loads(l) for l in Path(args.gold_arb).read_text(encoding="utf-8").splitlines() if l.strip()]

    L = ["# 059 语义 grounding 收益 @ 校准 gold C（raw / literal / semantic 三档）", "",
         f"- gold C: {len(gold)} 条 · 过滤器对表施加，gold 侧动态名加严", "",
         "| 模型 | 过滤器 | ALL-p | ALL-r | ALL-dir | ALL-hall | 非空-p | 非空-r |",
         "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for spec in args.preds:
        label, path = spec.split(":", 1)
        preds = [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]
        if len(preds) != len(gold):
            raise SystemExit(f"{label}: preds {len(preds)} != gold {len(gold)}（顺序须一致）")
        for mode in ("raw", "literal", "semantic"):
            a, n, ne = score(preds, gold, mode)
            L.append(f"| {label} | {mode} | {a['precision']:.4f} | {a['recall']:.4f} | "
                     f"{a['direction_acc']:.4f} | {a['hallucination']:.4f} | "
                     f"{n['precision']:.4f} | {n['recall']:.4f} |")
        L.append("| | | | | | | | |")
    report = "\n".join(L) + "\n"
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
