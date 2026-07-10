"""059：跑模型在 gold C 上的推理，**落盘每行预测**（content/labels/is_empty/pred），
供离线分析 FP 构成 + 试各种 grounding 过滤器，无需反复烧 GPU。

用法: MODEL=out/run059-plain/merged PYTHONPATH=. python3 realeval/dump_preds.py \
        --model out/run059-plain/merged --gold realeval/gold/real-c.jsonl \
        --out out/preds-c-run059-plain.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("MODEL", "out/run1/merged"))
    ap.add_argument("--gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--out", required=True)
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

    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for i, r in enumerate(rows):
            pred = predict(model, tok, r, max_new_tokens=args.max_new_tokens)
            rec = {"content": r["content"], "labels": r["labels"],
                   "is_empty": bool(r.get("is_empty")), "task_type": r.get("task_type"),
                   "pred": {"reads": pred.get("reads") or [], "writes": pred.get("writes") or [],
                            "_invalid": bool(pred.get("_invalid") or pred.get("_error"))}}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            if (i + 1) % 40 == 0:
                print(f"  dump {i+1}/{len(rows)}", flush=True)
    print(f"dump_preds: {len(rows)} rows → {out}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
