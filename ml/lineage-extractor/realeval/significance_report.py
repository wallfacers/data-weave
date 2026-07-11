"""065 T006（US1）：统计诚实层编排——CI + 配对差值 + McNemar → out/significance-c.md。

离线复用落盘 preds（无 GPU）。preds 目录含 `<predictor>.jsonl`，每行一个 `{"reads":[],"writes":[]}`
按 index 对齐 gold。对每个 predictor 出 precision/recall/f1 的 95% CI；对 primary（默认 model-3b）
vs 每个 teacher 出配对差值 CI + McNemar 精确 p + **诚实"是否显著"判定**（SC-002）。

用法: PYTHONPATH=. python3 realeval/significance_report.py \
        --gold realeval/gold/real-c-arbitrated.jsonl --preds-dir out/preds \
        --primary model-3b --report out/significance-c.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.significance import bootstrap_metric_ci, mcnemar_exact, paired_bootstrap_diff
from realeval.counts_adapter import filter_counts, per_script_counts

_METRICS = ("precision", "recall", "f1")


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def build_report(gold_rows, preds_by_predictor, *, primary="model-3b",
                 seed=20260712, n_resamples=10000, leak_curve_ref="out/leak-curve.md") -> str:
    """纯函数：装配 significance 报告 markdown（供 CLI 与单测复用）。"""
    # 每 predictor 的非空逐脚本 counts
    counts = {name: filter_counts(per_script_counts(gold_rows, preds), nonempty=True)
              for name, preds in preds_by_predictor.items()}
    n_ne = len(next(iter(counts.values()))) if counts else 0

    L = ["# 065 统计诚实层 @ gold C（脚本级 bootstrap CI + McNemar）", "",
         f"- 非空 gold: {n_ne} 条 · bootstrap {n_resamples} 次 · seed {seed}",
         f"- 头条锚点：泄漏曲线见 [{leak_curve_ref}]({leak_curve_ref})（逐规模逐字泄漏 + 合成 vs 真实塌缩）",
         "", "## 各 predictor 指标 + 95% 置信区间", "",
         "| predictor | " + " | ".join(f"{m} [95%CI]" for m in _METRICS) + " |",
         "| --- | " + " | ".join("---" for _ in _METRICS) + " |"]
    for name, cs in counts.items():
        cells = []
        for m in _METRICS:
            r = bootstrap_metric_ci(cs, m, n_resamples=n_resamples, seed=seed)
            cells.append(f"{r['point']:.3f} [{r['lo95']:.3f}, {r['hi95']:.3f}]")
        L.append(f"| {name} | " + " | ".join(cells) + " |")

    # primary vs teacher：配对差值 CI + McNemar
    if primary in counts:
        L += ["", f"## {primary} vs teacher（配对差值 CI + McNemar 精确检验）", "",
              "| 对手 | metric | diff [95%CI] | 显著? | McNemar b/c | p | 显著? |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
        cp = counts[primary]
        em_p = [c["exact_match"] for c in cp]
        for name, cs in counts.items():
            if name == primary or len(cs) != len(cp):
                continue
            d = paired_bootstrap_diff(cp, cs, "precision", n_resamples=n_resamples, seed=seed)
            mc = mcnemar_exact(em_p, [c["exact_match"] for c in cs])
            L.append(f"| {name} | precision | {d['diff']:+.3f} [{d['lo95']:+.3f}, {d['hi95']:+.3f}] "
                     f"| {'✅' if d['significant'] else '❌不显著'} "
                     f"| {mc['b']}/{mc['c']} | {mc['p_value']:.3f} "
                     f"| {'✅' if mc['significant'] else '❌不显著'} |")
        L += ["",
              "> **诚实声明**：本表如实呈现区间宽度与显著性。在当前非空样本量下，若差值 CI 含 0 "
              "或 McNemar p≥0.05，则\"超越 teacher\"的结论在统计上**不成立**——这正是本文不以\"超 "
              "teacher\"为头条、改以泄漏科学为脊椎的原因。"]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--preds-dir", default="out/preds")
    ap.add_argument("--primary", default="model-3b")
    ap.add_argument("--report", default="out/significance-c.md")
    ap.add_argument("--seed", type=int, default=20260712)
    ap.add_argument("--resamples", type=int, default=10000)
    args = ap.parse_args(argv)

    gold = _load_jsonl(args.gold)
    preds_dir = Path(args.preds_dir)
    preds_by = {p.stem: _load_jsonl(p) for p in sorted(preds_dir.glob("*.jsonl"))}
    if not preds_by:
        print(f"[significance] 无 preds：{preds_dir}/*.jsonl 为空（需先落盘 preds）")
        return 1
    report = build_report(gold, preds_by, primary=args.primary,
                          seed=args.seed, n_resamples=args.resamples)
    out = Path(args.report); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
