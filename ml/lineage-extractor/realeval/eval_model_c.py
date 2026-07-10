"""059：模型单体在 gold C（auto-gold held-out）上的 precision/recall/方向——纯参照。

evaluate.py 的 run_eval 依赖 row["meta"]（form_family/template_id/rule_covered），gold C
（build_gold_b 输出）无此字段。本脚本只用 eval.metrics.score_row/aggregate + evaluate.predict
（含 </think> 解析 + max_new_tokens 512），量模型单体指标，不改 gold C 格式。

用法: MODEL=<merged> PYTHONPATH=. python3 realeval/eval_model_c.py \
        --model <merged> --gold realeval/gold/real-c.jsonl --report out/eval-c-<tag>.md
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from eval.metrics import aggregate, score_row


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("MODEL", "out/run1/merged"))
    ap.add_argument("--gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--report", default="out/eval-c.md")
    ap.add_argument("--max-new-tokens", type=int, default=512)
    args = ap.parse_args(argv)

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from eval.evaluate import predict

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()

    counts_all, counts_ne = [], []
    for i, r in enumerate(rows):
        pred = predict(model, tok, r, max_new_tokens=args.max_new_tokens)
        c = score_row(r["labels"], pred, r["content"])
        counts_all.append(c)
        if not r.get("is_empty"):
            counts_ne.append(c)
        if (i + 1) % 40 == 0:
            print(f"  eval {i+1}/{len(rows)}", flush=True)

    agg_all = aggregate(counts_all)
    agg_ne = aggregate(counts_ne)
    L = [f"# 059 模型单体 @ gold C — {Path(args.model).parent.name}/{Path(args.model).name}", "",
         f"- gold C: {len(rows)} 条（非空 {len(counts_ne)}）", "",
         "| 口径 | precision | recall | f1 | 方向 | 幻觉 | invalid |",
         "| --- | --- | --- | --- | --- | --- | --- |"]
    for name, m in [("全部", agg_all), ("非空", agg_ne)]:
        L.append(f"| {name} | {m['precision']:.4f} | {m['recall']:.4f} | {m['f1']:.4f} | "
                 f"{m['direction_acc']:.4f} | {m['hallucination']:.4f} | {m['invalid']} |")
    report = "\n".join(L) + "\n"
    out = Path(args.report); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(
        json.dumps({"all": agg_all, "nonempty": agg_ne, "n": len(rows), "n_nonempty": len(counts_ne),
                    "model": args.model}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
