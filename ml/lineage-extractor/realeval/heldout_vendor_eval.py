"""068 T023（US3/FR-009/门③）：held-out 厂商泛化——破循环量化证据。

核心论证：模型（run-col-3b-mit / model-3b）训练只见 qwen∩deepseek、**从未见过 GPT-5.6**；
若它仍能 recover GPT-5.6 **独立确认**的血缘边 → 学到的是真血缘，非 teacher 记忆。
= 对训练中未见厂商的泛化。（对 run-tri：GPT 已进训练 silver，故对 run-tri 是"已见厂商"对照，非 held-out。）

做法：以 m_gpt 标注为"GPT 独立确认"过滤器，取 gold 中 GPT 也命名的边子集，评模型在其上的召回。
纯读文件 + 复用打分核，无网络/torch。
用法: PYTHONPATH=. python3 -m realeval.heldout_vendor_eval \
        --gold realeval/gold/real-c-tri.jsonl --gpt realeval/teacher_labels-c/m_gpt.jsonl \
        --preds out/preds-tri/run-col-3b-mit.jsonl --held-out --out out/heldout-vendor-068.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import score_row


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _gpt_tables(rec: dict) -> set[str]:
    ts = set()
    if rec:
        for side in ("reads", "writes"):
            for it in rec.get(side) or []:
                t = it.get("table") if isinstance(it, dict) else it
                if t:
                    ts.add(str(t).strip().lower())
    return ts


def gpt_confirmed_gold(gold_rows: list[dict], gpt_by_chash: dict) -> list[dict]:
    """取 gold 中 GPT 也命名的边子集（GPT 独立确认）。GPT 弃权/缺失的行 → 该行子集为空。"""
    out = []
    for row in gold_rows:
        gtabs = _gpt_tables(gpt_by_chash.get(row.get("chash")))
        lab = row.get("labels") or {}
        reads = [e for e in lab.get("reads") or [] if e["table"].strip().lower() in gtabs]
        writes = [e for e in lab.get("writes") or [] if e["table"].strip().lower() in gtabs]
        out.append({**row, "labels": {"reads": reads, "writes": writes}})
    return out


def score(gold_rows, preds, canon=True) -> dict:
    counts = []
    for i, row in enumerate(gold_rows):
        gold = row.get("labels") or {}
        pred = preds[i] if i < len(preds) else {"reads": [], "writes": []}
        counts.append(score_row(gold, {"reads": pred.get("reads") or [], "writes": pred.get("writes") or []},
                                row.get("content", ""), canon=canon))

    def s(k):
        return sum(c.get(k, 0) for c in counts)
    tp, fp, fn = s("tp"), s("fp"), s("fn")
    ctp, cfn = s("col_tp"), s("col_fn")
    return {"tp": tp, "fp": fp, "fn": fn,
            "recall": tp / (tp + fn) if (tp + fn) else 0.0,
            "col_tp": ctp, "col_fn": cfn,
            "col_recall": ctp / (ctp + cfn) if (ctp + cfn) else 0.0}


def build_report(gold_rows, gpt_by_chash, preds, model_name: str, held_out: bool) -> str:
    conf = gpt_confirmed_gold(gold_rows, gpt_by_chash)
    sc = score(conf, preds)
    tag = ("训练**未见** GPT-5.6 → 此为真 held-out 厂商泛化" if held_out
           else "训练**已含** GPT silver → 此为已见厂商对照（非 held-out）")
    L = [
        "# 068 held-out 厂商泛化（GPT-5.6 独立确认边）", "",
        f"模型 **{model_name}**：{tag}。", "",
        "在 GPT-5.6 **独立确认**的 gold 边子集上评模型召回——recover 越多=越像学到真血缘而非 teacher 记忆。", "",
        "| 粒度 | tp | fn | **召回** |",
        "| --- | --- | --- | --- |",
        f"| 表级 | {sc['tp']} | {sc['fn']} | **{sc['recall']:.3f}** |",
        f"| 列级 | {sc['col_tp']} | {sc['col_fn']} | **{sc['col_recall']:.3f}** |", "",
        ("> **破循环读法**：一个只在 qwen∩deepseek 上训练的模型，在未见厂商 GPT-5.6 独立确认的边上仍高召回 "
         "→ 学到的是真血缘、非记住两家 teacher 的癖好。" if held_out else
         "> **对照读法**：run-tri 训练已含 GPT silver，此数字为已见厂商参照，不作 held-out 泛化结论。"),
    ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-tri.jsonl")
    ap.add_argument("--gpt", default="realeval/teacher_labels-c/m_gpt.jsonl")
    ap.add_argument("--preds", required=True)
    ap.add_argument("--model-name", default=None)
    ap.add_argument("--held-out", action="store_true", help="模型训练未见 GPT（真 held-out 泛化）")
    ap.add_argument("--out", default="out/heldout-vendor-068.md")
    args = ap.parse_args(argv)

    gold = _load_jsonl(args.gold)
    gpt = {r["chash"]: r for r in _load_jsonl(args.gpt)}
    preds = _load_jsonl(args.preds)
    name = args.model_name or Path(args.preds).stem
    md = build_report(gold, gpt, preds, name, args.held_out)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md, encoding="utf-8")
    print(f"heldout_vendor_eval → {out}")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
