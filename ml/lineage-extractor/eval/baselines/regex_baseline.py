"""041-R 正则基线：论文对比参照系（纯函数，无外部依赖）。"""
from __future__ import annotations
import re

_READ = re.compile(r"\b(?:FROM|JOIN)\s+([A-Za-z_][\w.]*)", re.IGNORECASE)
_WRITE = re.compile(
    r"(?:INSERT\s+INTO|INSERT\s+OVERWRITE\s+TABLE|saveAsTable\(|to_sql\(|writeTo\()\s*[\"']?([A-Za-z_][\w.]*)",
    re.IGNORECASE,
)


def predict(row: dict) -> dict:
    c = row["content"]
    reads = [{"table": t, "columns": None} for t in dict.fromkeys(m.group(1) for m in _READ.finditer(c))]
    writes = [{"table": t, "columns": None} for t in dict.fromkeys(m.group(1) for m in _WRITE.finditer(c))]
    wt = {w["table"] for w in writes}
    reads = [r for r in reads if r["table"] not in wt]  # 写表不重复计读
    return {"reads": reads, "writes": writes}
