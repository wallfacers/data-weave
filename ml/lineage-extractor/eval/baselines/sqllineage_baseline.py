"""065 T008（W2）：SQLLineage 库级工具基线（LLM vs 现有工具对照）。

`predict(row) -> {reads, writes}`，与 `regex_baseline` 同契约（columns 恒 None）。
**非 SQL / 解析失败 → 空**（命令式脚本子集召回 0，坐实"确定性 SQL 工具结构性失效"，SC-003）。
纯函数、**永不抛**（内部 catch）。

sqllineage 1.5.x：`source_tables`/`target_tables` 是属性（list），无 schema 表带 `<default>.`
占位前缀——剥掉以对齐 gold 表名口径（真实限定名 `db.schema.table` 保留，交 metrics.canon_match）。
"""
from __future__ import annotations

_PLACEHOLDER = "<default>."


def _strip(name) -> str:
    n = str(name)
    return n[len(_PLACEHOLDER):] if n.startswith(_PLACEHOLDER) else n


def _column_map(runner) -> dict:
    """067 US4：从 get_column_lineage() 聚合每表列集 {限定表名: set(列名)}。
    永不抛（内部 catch）；无列信息的表在调用侧回退 columns=None（弃权）。"""
    out: dict = {}
    try:
        for chain in runner.get_column_lineage():
            for col in chain:
                parent = getattr(col, "parent", None)
                name = getattr(col, "raw_name", None)
                if parent is None or not name or name == "*":
                    continue
                out.setdefault(_strip(str(parent)), set()).add(str(name))
    except Exception:
        return {}
    return out


def predict(row: dict, *, with_columns: bool = False) -> dict:
    content = row.get("content") or ""
    try:
        from sqllineage.runner import LineageRunner
        r = LineageRunner(content)
        cmap = _column_map(r) if with_columns else {}
        # 属性访问触发惰性解析；非 SQL 在此抛 InvalidSyntaxException
        # 067：表命中但工具未给列 → columns=None（弃权，非空集），与三态列语义一致。
        def _cols(t):
            if not with_columns:
                return None
            got = cmap.get(_strip(str(t)))
            return sorted(got) if got else None
        reads = [{"table": _strip(t), "columns": _cols(t)} for t in r.source_tables]
        writes = [{"table": _strip(t), "columns": _cols(t)} for t in r.target_tables]
    except Exception:
        return {"reads": [], "writes": []}
    # 去重 + 写表不重复计读（与 regex_baseline 语义一致）
    reads = list({x["table"]: x for x in reads}.values())
    writes = list({x["table"]: x for x in writes}.values())
    wt = {w["table"] for w in writes}
    reads = [x for x in reads if x["table"] not in wt]
    return {"reads": reads, "writes": writes}
