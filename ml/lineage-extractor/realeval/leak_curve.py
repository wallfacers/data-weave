"""041-R 加强实验 C：逐规模记忆泄漏曲线（0.5B / 1.5B / 3B …）。

对每个模型规模（同一合成训练数据 + 同一 LoRA 配方，仅 base 不同），在
① 合成 held-out（域内）② 真实集（OOD）分别评 sft 列，并算真实集上的**记忆泄漏率**
（幻觉表名中逐字命中合成训练池 / 形态酷似合成池的占比）。只跑 sft，不调 M1/M2。

论点验证：若泄漏率随规模下降 → "合成-only 训练的记忆泄漏是小模型特有病、随容量缓解"，
这是 future-work 招牌图；若不降 → 泄漏是合成-only 训练范式的系统病，与规模无关（更强警示）。

用法：PYTHONPATH=. python3 realeval/leak_curve.py \
        --heldout data/out/heldout.jsonl --real realeval/gold/real.jsonl \
        --point 0.5B=out/run-0.5b/merged --point 1.5B=out/run3/merged --point 3B=out/run-3b/merged
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.evaluate import run_eval
from eval.metrics import tables
from realeval.eval_real import _nonempty_metrics
from realeval.leak_analysis import analyze, train_table_pool


def _load(path: str) -> list:
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _sft_fn(model_dir: str):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    from eval.evaluate import predict as sft_predict
    tok = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir, dtype=torch.bfloat16,
        device_map="cuda" if torch.cuda.is_available() else "cpu").eval()
    return lambda row: sft_predict(model, tok, row)


def evaluate_scale(label: str, model_dir: str, heldout: list, real: list, pool: set) -> dict:
    fn = _sft_fn(model_dir)
    syn = run_eval(fn, heldout)["overall"]
    real_full = run_eval(fn, real)["overall"]
    real_ne, ne_n = _nonempty_metrics(fn, real)
    leak = analyze(label, fn, real, pool)
    return {
        "label": label, "model": model_dir,
        "syn": {k: syn[k] for k in ("precision", "direction_acc", "hallucination")},
        "real_full": {k: real_full[k] for k in ("precision", "hallucination")},
        "real_nonempty": {k: real_ne[k] for k in ("recall", "direction_acc", "f1")},
        "real_nonempty_n": ne_n,
        "leak": {k: leak[k] for k in ("hallucinations", "verbatim_train_names", "verbatim_rate",
                                      "synthetic_shaped", "shaped_rate")},
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--heldout", default="data/out/heldout.jsonl")
    ap.add_argument("--real", default="realeval/gold/real.jsonl")
    ap.add_argument("--point", action="append", default=[], help="LABEL=model_dir，可多次")
    ap.add_argument("--report", default="out/leak-curve.md")
    args = ap.parse_args(argv)

    heldout, real = _load(args.heldout), _load(args.real)
    pool = train_table_pool()
    print(f"heldout {len(heldout)} · real {len(real)} · 训练表池 {len(pool)}")

    results = []
    for spec in args.point:
        label, _, model_dir = spec.partition("=")
        if not Path(model_dir).exists():
            print(f"[warn] 跳过 {label}：{model_dir} 不存在")
            continue
        print(f"评测 {label} ← {model_dir}")
        results.append(evaluate_scale(label, model_dir, heldout, real, pool))

    lines = [
        "# 041-R 逐规模记忆泄漏曲线（加强实验 C）", "",
        "同一合成训练数据 + 同一 LoRA 配方（SEED/r16α32/2ep/max2048），仅 base 规模不同。",
        "只评 sft 列。泄漏 = 真实集幻觉表名中逐字命中合成训练池 / 形态酷似合成池的占比。", "",
        "| 规模 | 合成 prec | 合成 方向 | 合成 幻觉 | 真实 prec | 真实 幻觉 | 真实 recall | 真实 方向 | **逐字泄漏** | **合成形态** |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        s, rf, rn, lk = r["syn"], r["real_full"], r["real_nonempty"], r["leak"]
        lines.append(
            f"| {r['label']} | {s['precision']:.3f} | {s['direction_acc']:.3f} | {s['hallucination']:.4f} "
            f"| {rf['precision']:.3f} | {rf['hallucination']:.3f} | {rn['recall']:.3f} | {rn['direction_acc']:.3f} "
            f"| {lk['verbatim_train_names']}/{lk['hallucinations']} ({lk['verbatim_rate']:.3f}) "
            f"| {lk['synthetic_shaped']}/{lk['hallucinations']} ({lk['shaped_rate']:.3f}) |")
    lines += ["", f"真实集非空子集 n={results[0]['real_nonempty_n'] if results else 0}。"]

    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
