"""052：把蒸馏模型对 gold 的逐行预测 dump 成 jsonl（供 tiered_envelope 算并集/一致层）。

每行 {"idx": i, "reads": [...], "writes": [...]}。确定性解码，同权重同输入必同输出。
用法: MODEL=../../../weft-lineage-weights/run-distill-3b/merged \
      PYTHONPATH=. python3 realeval/dump_model_preds.py --gold realeval/gold/real.jsonl --out out/model-preds-A.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--out", default="out/model-preds-A.jsonl")
    ap.add_argument("--model", default=os.environ.get("MODEL", "out/run-distill-3b/merged"))
    args = ap.parse_args(argv)

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from eval.evaluate import predict

    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for i, r in enumerate(rows):
            p = predict(model, tok, r)
            f.write(json.dumps({"idx": i, "reads": p.get("reads") or [],
                                "writes": p.get("writes") or []}, ensure_ascii=False) + "\n")
            if i % 20 == 0:
                print(f"{i}/{len(rows)}", flush=True)
    print(f"done → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
