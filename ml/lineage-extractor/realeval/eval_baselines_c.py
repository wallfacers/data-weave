"""065 T009（US2）：工具基线分层对照 @ gold C → out/baselines-c.md。

regex + SQLLineage（CPU，无 GPU）与（可选）落盘 model preds 同尺子跑分，按 subset ∈ {all,sql,script}
分层，各格附 bootstrap 95% CI。**SC-003 裁决**：SQLLineage 在脚本子集 recall ≤0.10（结构性失效），
model 在脚本子集 recall 显著高于工具（配对差值 CI 不含 0）。

用法: PYTHONPATH=. python3 realeval/eval_baselines_c.py \
        --gold realeval/gold/real-c-arbitrated.jsonl --preds-dir out/preds --report out/baselines-c.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.baselines import regex_baseline, sqllineage_baseline
from eval.significance import bootstrap_metric_ci, paired_bootstrap_diff
from realeval.counts_adapter import filter_counts, per_script_counts

_SUBSETS = ("all", "sql", "script")
_METRICS = ("recall", "precision", "f1")
_SCRIPT_RECALL_MAX = 0.10  # SC-003：工具在脚本子集 recall 上界（结构性失效锚点）


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _sub(counts, subset):
    return filter_counts(counts, subset=None if subset == "all" else subset, nonempty=True)


def build_baseline_report(gold_rows, model_preds_by=None, *, seed=20260712, n_resamples=10000) -> str:
    model_preds_by = model_preds_by or {}
    predictors = {
        "regex": [regex_baseline.predict(r) for r in gold_rows],
        "sqllineage": [sqllineage_baseline.predict(r) for r in gold_rows],
        **model_preds_by,
    }
    counts_by = {name: per_script_counts(gold_rows, preds) for name, preds in predictors.items()}

    L = ["# 065 工具基线分层对照 @ gold C（LLM vs 现有工具）", "",
         f"- bootstrap {n_resamples} 次 · seed {seed} · 口径=非空子集", ""]
    for subset in _SUBSETS:
        L += [f"## 子集：{subset}", "",
              "| predictor | n | " + " | ".join(f"{m} [95%CI]" for m in _METRICS) + " |",
              "| --- | --- | " + " | ".join("---" for _ in _METRICS) + " |"]
        for name, counts in counts_by.items():
            cs = _sub(counts, subset)
            cells = []
            for m in _METRICS:
                r = bootstrap_metric_ci(cs, m, n_resamples=n_resamples, seed=seed)
                cells.append(f"{r['point']:.3f} [{r['lo95']:.3f}, {r['hi95']:.3f}]")
            L.append(f"| {name} | {len(cs)} | " + " | ".join(cells) + " |")
        L.append("")

    # SC-003 裁决
    L += ["## SC-003 裁决（脚本子集：工具≈0 / 模型救回）", ""]
    sql_script = _sub(counts_by["sqllineage"], "script")
    sql_recall = bootstrap_metric_ci(sql_script, "recall", n_resamples=n_resamples, seed=seed)
    tool_ok = sql_recall["point"] <= _SCRIPT_RECALL_MAX
    L.append(f"- SQLLineage@script recall = {sql_recall['point']:.3f} "
             f"[{sql_recall['lo95']:.3f}, {sql_recall['hi95']:.3f}] "
             f"→ {'✅ ≤0.10 结构性失效成立' if tool_ok else '❌ 超 0.10'}")
    for name in model_preds_by:
        m_script = _sub(counts_by[name], "script")
        if len(m_script) == len(sql_script) and m_script:
            d = paired_bootstrap_diff(m_script, sql_script, "recall",
                                      n_resamples=n_resamples, seed=seed)
            L.append(f"- {name}@script vs SQLLineage：Δrecall = {d['diff']:+.3f} "
                     f"[{d['lo95']:+.3f}, {d['hi95']:+.3f}] "
                     f"→ {'✅ 显著救回' if d['significant'] and d['diff'] > 0 else '❌不显著'}")
    L += ["", "> 招牌对比：确定性 SQL 工具在命令式脚本上结构性失效（recall≈0），"
          "学习模型能救回——这正是\"为何需要学习式抽取器\"的实证动机。"]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--preds-dir", default="out/preds")
    ap.add_argument("--report", default="out/baselines-c.md")
    ap.add_argument("--seed", type=int, default=20260712)
    ap.add_argument("--resamples", type=int, default=10000)
    args = ap.parse_args(argv)

    gold = _load_jsonl(args.gold)
    preds_dir = Path(args.preds_dir)
    model_preds = {p.stem: _load_jsonl(p) for p in sorted(preds_dir.glob("model-*.jsonl"))} \
        if preds_dir.is_dir() else {}
    report = build_baseline_report(gold, model_preds, seed=args.seed, n_resamples=args.resamples)
    out = Path(args.report); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
