"""050 Tier 1 追加：离线对比多种「模型×SQL 通道」合并策略（复用 preds dump，无 GPU）。

system_eval 的默认 hybrid = 「SQL 命中则整条覆盖模型」，实测对强模型(B2)有害——启发式
SQL 提取 recall 低，覆盖丢掉模型的正确抽取。本脚本从已 dump 的逐例模型预测出发，离线
对比更细的合并策略，找诚实的最优（若存在）：

- model      : 模型独跑（基准）
- override   : SQL 命中整条覆盖（= system_eval 默认）
- union      : 读写各取模型 ∪ SQL（SQL 方向优先：同名冲突以 SQL 判定的方向为准）
- dir_fix    : 表集用模型的，但对 SQL 也识别到的表，用 SQL 的方向覆盖模型方向

用法：PYTHONPATH=. python realeval/offline_hybrid.py --preds out/canon-b2.preds.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row, tables
from realeval.channel_router import extract_sql_lineage


def _tset(items):
    return tables(items)


def _mk(reads, writes):
    return {"reads": [{"table": t} for t in sorted(reads)],
            "writes": [{"table": t} for t in sorted(writes)]}


def merge(strategy: str, model_pred: dict, sql_out: dict) -> dict:
    mr, mw = _tset(model_pred["reads"]), _tset(model_pred["writes"])
    sr, sw = _tset(sql_out["reads"]), _tset(sql_out["writes"])
    sql_hit = bool(sr or sw)
    if strategy == "model" or not sql_hit:
        return _mk(mr, mw)
    if strategy == "override":
        return _mk(sr, sw)
    if strategy == "union":
        # SQL 方向优先：SQL 判为写的不再进读，反之亦然
        reads = (mr | sr) - sw
        writes = (mw | sw) - sr
        return _mk(reads, writes)
    if strategy == "dir_fix":
        # 表集 = 模型的；对 SQL 也识别到的表，用 SQL 方向替换
        sql_role = {t: "w" for t in sw}
        sql_role.update({t: "r" for t in sr if t not in sw})
        reads, writes = set(mr), set(mw)
        for t in list(reads | writes):
            if t in sql_role:
                reads.discard(t); writes.discard(t)
                (writes if sql_role[t] == "w" else reads).add(t)
        return _mk(reads, writes)
    raise ValueError(strategy)


def evaluate(pairs, strategy, canon=True):
    ne = [p for p in pairs if _tset(p["gold"]["reads"]) or _tset(p["gold"]["writes"])]
    def pred(p):
        return merge(strategy, p["pred"], extract_sql_lineage(p["content"]))
    full = aggregate([score_row(p["gold"], pred(p), p["content"], canon=canon) for p in pairs])
    non = aggregate([score_row(p["gold"], pred(p), p["content"], canon=canon) for p in ne])
    return full, non


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", required=True)
    ap.add_argument("--report", default="out/offline-hybrid.md")
    args = ap.parse_args(argv)
    pairs = [json.loads(l) for l in Path(args.preds).read_text(encoding="utf-8").splitlines() if l.strip()]

    md = ["# 050 Tier 1：模型×SQL 通道合并策略离线对比（canon 口径）", "",
          f"- 来源：{args.preds}（{len(pairs)} 条逐例模型预测 + sqlglot SQL 通道离线合并）。",
          "- override=SQL 整条覆盖；union=读写并集(SQL 方向优先)；dir_fix=表集用模型、SQL 识别到的表纠方向。", "",
          "| 策略 | precision(全) | 幻觉率(全) | recall(非空) | 方向(非空) | f1(非空) |",
          "| --- | --- | --- | --- | --- | --- |"]
    rows = {}
    for strat in ["model", "override", "union", "dir_fix"]:
        f, ne = evaluate(pairs, strat)
        rows[strat] = (f, ne)
        md.append(f"| {strat} | {f['precision']:.3f} | {f['hallucination']:.3f} | "
                  f"{ne['recall']:.3f} | {ne['direction_acc']:.3f} | {ne['f1']:.3f} |")
    md.append("")
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
