"""068 US6：双专家融合离线评测（无 GPU，复用 out/preds-tri dump）。

对齐 gold(real-c-tri.jsonl)↔表专家(run-tri-3b-col50)↔列专家(run-tri-3b)，离线对比：
  表专家 solo / 列专家 solo / lw3 均衡 solo / fuse-table(A) / fuse-union(B)
在 canon=False（068 published 主口径）+ canon=True 两口径下的表 P/R/F1 与列 P/R/F1。

用法：PYTHONPATH=. python3 realeval/eval_fusion.py --report out/fusion-068.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row, tables
from realeval.specialist_fusion import fuse

ROOT = Path(__file__).resolve().parent.parent


def _load(p):
    return [json.loads(l) for l in Path(p).read_text(encoding="utf-8").splitlines() if l.strip()]


def _nonempty(gold):
    return [i for i, g in enumerate(gold)
            if tables(g["labels"]["reads"]) or tables(g["labels"]["writes"])]


def _score(gold, preds_by_idx, canon):
    """preds_by_idx(i)->{reads,writes}；返回非空集聚合。"""
    idx = _nonempty(gold)
    return aggregate([score_row(gold[i]["labels"], preds_by_idx(i), gold[i]["content"], canon=canon)
                      for i in idx])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default=str(ROOT / "realeval/gold/real-c-tri.jsonl"))
    ap.add_argument("--table-preds", default=str(ROOT / "out/preds-tri/run-tri-3b-col50.jsonl"))
    ap.add_argument("--col-preds", default=str(ROOT / "out/preds-tri/run-tri-3b.jsonl"))
    ap.add_argument("--lw3-preds", default=str(ROOT / "out/preds-tri/run-tri-3b-lw3.jsonl"))
    ap.add_argument("--report", default=str(ROOT / "out/fusion-068.md"))
    args = ap.parse_args(argv)

    gold = _load(args.gold)
    tpred = _load(args.table_preds)
    cpred = _load(args.col_preds)
    lw3 = _load(args.lw3_preds)

    def raw(preds):
        return lambda i: {"reads": preds[i].get("reads") or [], "writes": preds[i].get("writes") or []}

    def fused(strategy):
        return lambda i: fuse({"reads": tpred[i].get("reads") or [], "writes": tpred[i].get("writes") or []},
                              {"reads": cpred[i].get("reads") or [], "writes": cpred[i].get("writes") or []},
                              strategy=strategy)

    variants = [
        ("表专家 run-tri-3b-col50", raw(tpred)),
        ("列专家 run-tri-3b", raw(cpred)),
        ("均衡 lw3 run-tri-3b-lw3", raw(lw3)),
        ("融合A fuse-table", fused("table")),
        ("融合B fuse-union", fused("union")),
    ]

    n_ne = len(_nonempty(gold))
    out = {"gold_n": len(gold), "nonempty_n": n_ne, "canon_false": {}, "canon_true": {}}
    md = ["# 068 US6 双专家融合离线评测（非空 gold {} 条，无 GPU）".format(n_ne), "",
          "表专家定表集/列专家嫁接列。融合A=表集取表专家；融合B=表集取并集。", ""]
    for canon in (False, True):
        md += [f"## canon={canon}" + ("（068 published 主口径）" if not canon else "（限定名尾段匹配）"), "",
               "| 变体 | 表 P | 表 R | 表 F1 | 列 P | 列 R | 列 F1 | 方向 | 幻觉 |",
               "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for name, fn in variants:
            m = _score(gold, fn, canon)
            out["canon_true" if canon else "canon_false"][name] = m
            md.append(f"| {name} | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} | "
                      f"{m['col_precision']:.3f} | {m['col_recall']:.3f} | {m['col_f1']:.3f} | "
                      f"{m['direction_acc']:.3f} | {m['hallucination']:.3f} |")
        md.append("")

    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text("\n".join(md) + "\n", encoding="utf-8")
    Path(args.report).with_suffix(".json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
