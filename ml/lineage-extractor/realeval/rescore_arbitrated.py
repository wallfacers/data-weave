"""059：在 pro 仲裁校准后的 gold C 上重算 Run A 的 ALL-p（离线，复用已落盘预测，无 GPU）。

- 校准 gold：pro 翻掉的假空 → 非空(pro grounded 标签)。
- 加严：动态名(含 $ { } %)的翻标表按任务规则剔除(既不进 gold 也不进 pred)。
- 输出矩阵：原 gold vs 校准 gold × raw vs grounded 过滤器，ALL-p/非空-p。

用法: PYTHONPATH=. python3 realeval/rescore_arbitrated.py \
        --preds out/preds-c-run-059-plain.jsonl \
        --gold-orig realeval/gold/real-c.jsonl --gold-arb realeval/gold/real-c-arbitrated.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row
from realeval.analyze_grounding import filter_pred


def _dynamic(t: str) -> bool:
    return any(ch in (t or "") for ch in "${}%")


def _strip_dynamic(labels: dict) -> dict:
    def keep(items):
        return [it for it in (items or []) if not _dynamic(it.get("table", ""))]
    return {"reads": keep(labels.get("reads")), "writes": keep(labels.get("writes"))}


def score(preds, gold_rows, use_filter):
    ca, cn = [], []
    for p, g in zip(preds, gold_rows):
        labels = _strip_dynamic(g["labels"])
        pred = filter_pred(p["pred"], p["content"]) if use_filter else p["pred"]
        c = score_row(labels, pred, p["content"])
        ca.append(c)
        if not g.get("is_empty"):
            cn.append(c)
    return aggregate(ca), aggregate(cn), len(cn)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", required=True)
    ap.add_argument("--gold-orig", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--gold-arb", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--report", default="out/rescore-arbitrated.md")
    args = ap.parse_args(argv)

    preds = [json.loads(l) for l in Path(args.preds).read_text(encoding="utf-8").splitlines() if l.strip()]
    orig = [json.loads(l) for l in Path(args.gold_orig).read_text(encoding="utf-8").splitlines() if l.strip()]
    arb = [json.loads(l) for l in Path(args.gold_arb).read_text(encoding="utf-8").splitlines() if l.strip()]
    flips = sum(1 for g in arb if g.get("arbitrated"))

    L = [f"# 059 Run A @ 校准 gold C（pro 仲裁 {flips} 处翻标 + 动态名加严）", "",
         f"- gold C: {len(preds)} 条", "",
         "| gold | 过滤器 | ALL-p | ALL-r | ALL-dir | ALL-hall | 非空-p | 非空-r | 非空数 |",
         "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for name, grows in [("原 gold", orig), ("校准 gold", arb)]:
        for use_filter in (False, True):
            a, n, ne = score(preds, grows, use_filter)
            tag = "grounded" if use_filter else "raw"
            L.append(f"| {name} | {tag} | {a['precision']:.4f} | {a['recall']:.4f} | "
                     f"{a['direction_acc']:.4f} | {a['hallucination']:.4f} | "
                     f"{n['precision']:.4f} | {n['recall']:.4f} | {ne} |")
    report = "\n".join(L) + "\n"
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
