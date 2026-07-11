"""063 T010：置信度分层（模型 ∪ SQL-AST 通道 → 自动采纳 / 复核候选）。

纯确定性函数，无 torch/GPU（复用 channel_router 健壮性补丁）。contracts/tier-classify.md。

管线（部署 postprocess 链尾）：model_pred（已过语义 grounding + dir_fix）与 SQL-AST 通道表集
在 canon 下合并为互斥候选边（`_canonical_edges`），每条打 tier（通道归属×名字限定性）+
冻结 confidence（点估计 precision），按 **CV 去偏的治理诚实采纳集**（`autoaccept_tiers(thr)`）
切自动/复核；复核按 confidence 降序。方向沿用各通道（agree 冲突取 SQL-AST，FR-010）。
"""
from __future__ import annotations

from eval.metrics import tables
from realeval.channel_router import extract_sql_lineage
from realeval.confidence_calibration import _canonical_edges
from realeval.tier_classify_constants import autoaccept_tiers, frozen_precision

_SQL_TIERS = {"agree", "sql_qual", "sql_bare"}  # 名字取自 SQL 侧串 → 方向/列查 SQL 通道


def _dir_cols(items: list[dict], direction: str) -> tuple[dict, dict]:
    """{lower_name: direction}, {lower_name: columns}。"""
    d, c = {}, {}
    for it in items or []:
        t = it.get("table")
        if isinstance(t, str) and t.strip():
            k = t.strip().lower()
            d[k] = direction
            c[k] = it.get("columns")
    return d, c


def classify_tiers(model_pred: dict, content: str, thr: float = 0.95) -> dict:
    """model_pred={reads,writes}（已 grounding+dir_fix）+ content → 分层信封。

    返回 {auto:{reads,writes}, review:{reads,writes}, tiered:bool}；
    每项 {table, columns, tier, confidence}。确定性、只读冻结常量。
    """
    sp = extract_sql_lineage(content, exec_gated=True)
    s_dir_r, s_col_r = _dir_cols(sp.get("reads"), "read")
    s_dir_w, s_col_w = _dir_cols(sp.get("writes"), "write")
    sql_dir = {**s_dir_r, **s_dir_w}
    sql_col = {**s_col_r, **s_col_w}

    m_dir_r, m_col_r = _dir_cols(model_pred.get("reads"), "read")
    m_dir_w, m_col_w = _dir_cols(model_pred.get("writes"), "write")
    mdl_dir = {**m_dir_r, **m_dir_w}
    mdl_col = {**m_col_r, **m_col_w}

    S = tables(sp.get("reads")) | tables(sp.get("writes"))
    M = tables(model_pred.get("reads")) | tables(model_pred.get("writes"))

    accept = set(autoaccept_tiers(thr))
    auto = {"reads": [], "writes": []}
    review = {"reads": [], "writes": []}

    for name, tier in _canonical_edges(S, M):
        if tier in _SQL_TIERS:                       # agree 冲突取 SQL-AST（FR-010）
            direction = sql_dir.get(name, "read")
            cols = sql_col.get(name)
        else:
            direction = mdl_dir.get(name, "read")
            cols = mdl_col.get(name)
        item = {"table": name, "columns": cols, "tier": tier,
                "confidence": frozen_precision(tier)}
        bucket = auto if tier in accept else review
        bucket["writes" if direction == "write" else "reads"].append(item)

    # 复核层按 confidence 降序（FR-006）
    for k in ("reads", "writes"):
        review[k].sort(key=lambda x: -x["confidence"])

    tiered = bool(review["reads"] or review["writes"])
    return {"auto": auto, "review": review, "tiered": tiered}
