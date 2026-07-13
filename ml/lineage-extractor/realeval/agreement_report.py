"""068 T011（US1/FR-004）：GPT-5.6 与既有 067 gold（qwen∩deepseek）的一致率。

把 067 gold 当参照、m_gpt 标注当 pred，用 metrics.score_row 打分：
  · recall = GPT 独立确认的 067 gold 边 / 067 gold 边总数 = **一致率**（高=既有 gold 厂商稳健、非单家族臆造）；
  · precision = GPT 边落在 067 gold 内 / GPT 边总数；
  · 列级同理（条件于表命中）。
纯读文件 + 复用打分核，无网络/torch。
用法: PYTHONPATH=. python3 -m realeval.agreement_report \
        --base realeval/gold/real-c.jsonl --gpt realeval/teacher_labels-c/m_gpt.jsonl --out out/agreement-068.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import score_row, aggregate


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def build_report(base_gold: list[dict], gpt_labels: dict, canon: bool = True) -> tuple[dict, dict, int]:
    """返回 (表级 agg, 列级同 agg 内, 参与行数)。base_gold=067 gold 行；gpt_labels={chash: rec}。"""
    counts = []
    n = 0
    for row in base_gold:
        ch = row["chash"]
        gpt = gpt_labels.get(ch)
        if gpt is None or gpt.get("error"):
            continue                       # GPT 对该条弃权/缺失 → 不计入一致率分母
        gold = row.get("labels") or {"reads": row.get("reads", []), "writes": row.get("writes", [])}
        pred = {"reads": gpt.get("reads") or [], "writes": gpt.get("writes") or []}
        counts.append(score_row(gold, pred, row.get("content", ""), canon=canon))
        n += 1
    return aggregate(counts), counts, n


def _fmt(counts: list[dict]) -> str:
    def s(k):
        return sum(c.get(k, 0) for c in counts)
    tp, fp, fn = s("tp"), s("fp"), s("fn")
    p = tp / (tp + fp) if (tp + fp) else 0.0
    r = tp / (tp + fn) if (tp + fn) else 0.0
    ctp, cfp, cfn = s("col_tp"), s("col_fp"), s("col_fn")
    cp = ctp / (ctp + cfp) if (ctp + cfp) else 0.0
    cr = ctp / (ctp + cfn) if (ctp + cfn) else 0.0
    return (f"| 表级 | {tp} | {fp} | {fn} | {p:.3f} | **{r:.3f}** |\n"
            f"| 列级 | {ctp} | {cfp} | {cfn} | {cp:.3f} | **{cr:.3f}** |")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="realeval/gold/real-c.jsonl", help="067 gold(qwen∩deepseek)")
    ap.add_argument("--gpt", default="realeval/teacher_labels-c/m_gpt.jsonl")
    ap.add_argument("--out", default="out/agreement-068.md")
    args = ap.parse_args(argv)

    base = _load_jsonl(args.base)
    gpt = {r["chash"]: r for r in _load_jsonl(args.gpt)}
    agg, counts, n = build_report(base, gpt)

    md = ["# 068 一致率：GPT-5.6 vs 067 gold（qwen∩deepseek）\n",
          f"参照=067 gold（{args.base}），pred=GPT-5.6 标注。**recall=一致率**（GPT 独立确认的既有 gold 边占比）。\n",
          f"参与行 n={n}（GPT 弃权/缺失的行不计入分母）。\n",
          "| 粒度 | 确认(tp) | GPT多出(fp) | GPT漏(fn) | precision | **一致率(recall)** |",
          "|---|---|---|---|---|---|",
          _fmt(counts),
          "\n**读法**：一致率高=既有 067 gold 非 qwen∩deepseek 单家族臆造，第三厂商 GPT-5.6 也认可 → gold 厂商稳健。"]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"agreement_report → {out}  n={n}")
    print("\n".join(md[-4:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
