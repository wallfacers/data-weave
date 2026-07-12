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


def predict(row: dict) -> dict:
    content = row.get("content") or ""
    try:
        from sqllineage.runner import LineageRunner
        r = LineageRunner(content)
        # 属性访问触发惰性解析；非 SQL 在此抛 InvalidSyntaxException
        reads = [{"table": _strip(t), "columns": None} for t in r.source_tables]
        writes = [{"table": _strip(t), "columns": None} for t in r.target_tables]
    except Exception:
        return {"reads": [], "writes": []}
    # 去重 + 写表不重复计读（与 regex_baseline 语义一致）
    reads = list({x["table"]: x for x in reads}.values())
    writes = list({x["table"]: x for x in writes}.values())
    wt = {w["table"] for w in writes}
    reads = [x for x in reads if x["table"] not in wt]
    return {"reads": reads, "writes": writes}
