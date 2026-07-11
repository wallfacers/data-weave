"""065 T003：逐脚本 counts 采集适配层（data-model 稳定接缝）。

消费既有 `eval.metrics.score_row`（**不改打分逻辑**，守 FR-003），在其输出上派生：
- `exact_match = (fp==0 and fn==0)`——reads∪writes 集合与 gold 精确匹配（McNemar 的二元判据）
- `subset`——sql/script 分层（`eval.subset.classify`）
- `script_id` / `is_empty`——样本标识与空标签口径

产出 `list[PerScriptCounts]` 供统计诚实层（significance）与基线对照（eval_baselines_c）复用。
"""
from __future__ import annotations

from eval.metrics import score_row
from eval.subset import classify


def per_script_counts(rows, preds, *, canon: bool = False) -> list[dict]:
    """rows: gold 行（含 `labels`/`content`）；preds: 与 rows 等长同序的 pred dict。"""
    if len(rows) != len(preds):
        raise ValueError(f"gold({len(rows)}) 与 pred({len(preds)}) 必须等长同序")
    out = []
    for i, (r, p) in enumerate(zip(rows, preds)):
        c = dict(score_row(r["labels"], p, r["content"], canon))
        c["script_id"] = r.get("id") or r.get("path") or f"row-{i}"
        c["subset"] = classify(r)
        c["exact_match"] = (c["fp"] == 0 and c["fn"] == 0)
        c["is_empty"] = bool(r.get("is_empty"))
        out.append(c)
    return out


def filter_counts(counts, *, subset: str | None = None, nonempty: bool = False) -> list[dict]:
    out = list(counts)
    if subset is not None:
        out = [c for c in out if c["subset"] == subset]
    if nonempty:
        out = [c for c in out if not c["is_empty"]]
    return out
