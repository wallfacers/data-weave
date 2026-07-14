"""067 US4：SQLLineage 列级基线 vs 模型列级对照（SQL 子集）。

承 065 招牌对比（工具@脚本 recall≈0）到列级：确定性 SQL 工具即便在 SQL 上，列级抽取也弱于
联合重训模型。非 SQL/解析失败→列弃权（None，不计分）。复用 `eval.metrics.score_row` 的
**条件列打分**（门①正交，表级 counts 不受列影响）+ `counts_adapter` 子集过滤。纯离线，无 GPU/无 API。

用法: PYTHONPATH=. python3 realeval/eval_col_baseline.py \
        --gold realeval/gold/real-c.jsonl --model-preds out/preds/run-col-3b-mit.jsonl \
        --report out/col-baseline-c.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.baselines import sqllineage_baseline
from eval.metrics import aggregate


def _load(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _col_row(cs):
    a = aggregate(cs)
    return a.get("col_precision", 1.0), a.get("col_recall", 1.0), a.get("col_f1", 0.0), a.get("col_eval_tables", 0)


def build(gold_rows, model_preds, *, subset="sql"):
    from realeval.counts_adapter import filter_counts, per_script_counts
    sql_preds = [sqllineage_baseline.predict(r, with_columns=True) for r in gold_rows]
    c_sql = filter_counts(per_script_counts(gold_rows, sql_preds), subset=subset, nonempty=True)
    c_mdl = filter_counts(per_script_counts(gold_rows, model_preds), subset=subset, nonempty=True)
    return c_sql, c_mdl


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--model-preds", default="out/preds/run-col-3b-mit.jsonl")
    ap.add_argument("--report", default="out/col-baseline-c.md")
    ap.add_argument("--subset", default="sql")
    args = ap.parse_args(argv)

    gold = _load(args.gold)
    model = _load(args.model_preds)
    c_sql, c_mdl = build(gold, model, subset=args.subset)

    sp, sr, sf, sn = _col_row(c_sql)
    mp, mr, mf, mn = _col_row(c_mdl)
    L = [f"# 067 US4：SQLLineage 列级基线 vs 模型列（子集={args.subset}）", "",
         f"- gold: {args.gold} · model: {Path(args.model_preds).name}", "",
         "| 抽取器 | col_eval_n | col_precision | col_recall | col_f1 |",
         "| --- | --- | --- | --- | --- |",
         f"| SQLLineage(get_column_lineage) | {sn} | {sp:.3f} | {sr:.3f} | {sf:.3f} |",
         f"| run-col-3b-mit（模型）| {mn} | {mp:.3f} | {mr:.3f} | {mf:.3f} |", "",
         f"> **招牌对比（列级）**：确定性 SQL 工具在 SQL 子集列级 recall={sr:.3f}，"
         f"模型列级 recall={mr:.3f}（Δ={mr - sr:+.3f}）。承 065「工具结构性弱、模型救回」到列级。"
         f" col_eval_n 差异源于两者表命中集不同（列评条件于表命中），如实呈现。"]
    out = Path(args.report); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
