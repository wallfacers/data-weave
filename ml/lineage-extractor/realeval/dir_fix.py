"""052 Foundational: dir_fix 核心——US1 银标方向 + US2 sidecar 共用。

策略=dir_fix（050 实测最优、无 trade-off，见 research R6）：**表集由模型定**，对 sqlglot AST
也识别到的表，**方向以 AST 为准**覆盖模型方向（非 override——不丢模型独有的表）。复用
`channel_router.extract_sql_lineage` 的全部 050 健壮性补丁（片段窗封顶 800 / 跳 Jinja·$CONDITIONS
模板标记 / MAX_SQL_LEN / 逐片段 SIGALRM 限时），畸形超大脚本不回溯爆内存。纯函数、无 torch。
"""
from __future__ import annotations

from realeval.channel_router import extract_sql_lineage


def _norm(name) -> str | None:
    if name is None:
        return None
    s = str(name).strip().lower()
    return s or None


def sql_direction(content: str) -> dict[str, str]:
    """脚本内 SQL 片段经 AST 得到的 {规范化表名: 'r'|'w'}（写优先）。无 SQL 命中则空。"""
    out = extract_sql_lineage(content)
    role: dict[str, str] = {}
    for it in out.get("writes") or []:
        k = _norm(it.get("table") if isinstance(it, dict) else it)
        if k:
            role[k] = "w"
    for it in out.get("reads") or []:
        k = _norm(it.get("table") if isinstance(it, dict) else it)
        if k and k not in role:
            role[k] = "r"
    return role


def _as_item(x) -> dict:
    return x if isinstance(x, dict) else {"table": x}


def apply_dir_fix(model_pred: dict, content: str) -> dict:
    """表集取模型（保留原名/列）；对 AST 也识别到的表用 AST 方向重分类。返回含 `dir_fixed` 标记。"""
    role = sql_direction(content)
    tagged = [(_as_item(i), False) for i in (model_pred.get("reads") or [])] + \
             [(_as_item(i), True) for i in (model_pred.get("writes") or [])]
    reads, writes = [], []
    seen: set[str] = set()
    fixed = False
    for item, is_write in tagged:
        key = _norm(item.get("table"))
        if key is None or key in seen:
            continue
        seen.add(key)
        target_write = is_write
        if key in role:
            target_write = role[key] == "w"
            if target_write != is_write:
                fixed = True
        (writes if target_write else reads).append(item)
    return {"reads": reads, "writes": writes, "dir_fixed": fixed}
