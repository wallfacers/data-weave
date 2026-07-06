"""050 Tier 0：一次推理 → dump 逐例预测 → 同口径算 canon / 非canon 指标。

041-R 的 eval 从不保存逐例预测，换指标就得重推理。本脚本一次 GPU 推理把 sft 在真实
金标上的预测 dump 成 `<out>.preds.jsonl`（{task_type, content, gold, pred}），并**同时**
用 `score_preds` 在 canon=False / canon=True 两种口径下算全集 + 非空指标，落 `<out>.json`。

Tier 0 目的：修正「模型解析出完整 schema 限定名 vs 金标用短名」被误判为 fp+fn 的假象
（见 eval.metrics.canon_match），拿到**真实天花板**——不改模型、只让数字诚实。

用法：
  PYTHONPATH=. python realeval/dump_and_score.py --model <merged> \
      --gold realeval/gold/real.jsonl --name sft-b3 --out out/canon-b3
离线复算（有 preds.jsonl 即可，无需 GPU）：--preds out/canon-b3.preds.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row, tables


def _nonempty(rows):
    return [r for r in rows if tables(r["gold"]["reads"]) or tables(r["gold"]["writes"])]


def score_preds(pairs: list[dict], canon: bool) -> dict:
    """pairs = [{content, gold, pred}]；返回 {full, nonempty, nonempty_n}（纯函数，可无 GPU 单测）。"""
    full = aggregate([score_row(p["gold"], p["pred"], p["content"], canon=canon) for p in pairs])
    ne = _nonempty(pairs)
    nonempty = aggregate([score_row(p["gold"], p["pred"], p["content"], canon=canon) for p in ne])
    return {"full": full, "nonempty": nonempty, "nonempty_n": len(ne)}


def _load_model(model_dir: str):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from eval.evaluate import predict as sft_predict
    tok = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()
    return lambda row: sft_predict(model, tok, row)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--model", default=None)
    ap.add_argument("--preds", default=None, help="已有 preds.jsonl 则跳过推理，纯离线复算")
    ap.add_argument("--name", default="sft")
    ap.add_argument("--out", default="out/canon-eval")
    args = ap.parse_args(argv)

    if args.preds:
        pairs = [json.loads(l) for l in Path(args.preds).read_text(encoding="utf-8").splitlines() if l.strip()]
    else:
        gold = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
        predict_fn = _load_model(args.model)
        pairs = []
        for r in gold:
            p = predict_fn(r)
            pairs.append({"task_type": r.get("task_type"),
                          "template_id": r["meta"].get("template_id"),
                          "content": r["content"], "gold": r["labels"],
                          "pred": {"reads": p.get("reads") or [], "writes": p.get("writes") or []}})
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.out + ".preds.jsonl", "w", encoding="utf-8") as f:
            for pr in pairs:
                f.write(json.dumps(pr, ensure_ascii=False) + "\n")

    plain = score_preds(pairs, canon=False)
    canon = score_preds(pairs, canon=True)
    result = {"name": args.name, "gold_n": len(pairs),
              "nonempty_n": plain["nonempty_n"], "plain": plain, "canon": canon}

    def _line(tag, m):
        f, ne = m["full"], m["nonempty"]
        return (f"| {tag} | {f['precision']:.3f} | {f['hallucination']:.3f} | "
                f"{ne['recall']:.3f} | {ne['direction_acc']:.3f} | {ne['f1']:.3f} |")

    md = ["# 050 Tier 0 规范化匹配前后对比（{}）".format(args.name), "",
          "canon = schema 限定的点分尾段匹配，修正「完整限定名 vs 短名」假象（保守，粒度真错不放水）。", "",
          "| 口径 | precision(全) | 幻觉率(全) | recall(非空) | 方向(非空) | f1(非空) |",
          "| --- | --- | --- | --- | --- | --- |",
          _line("plain(旧)", plain), _line("canon(Tier0)", canon), ""]
    Path(args.out + ".json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    Path(args.out + ".md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
