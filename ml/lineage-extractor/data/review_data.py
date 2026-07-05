"""041-R 合成数据质量门：规则自检 + M2 critic 抽查。只作用训练集。"""

import re


def _present(name: str, content_lower: str) -> bool:
    """表名（全名或 leaf 段）是否作为独立标识符出现在 content 中（token 边界，非子串）。"""
    for cand in {name.lower(), name.lower().split(".")[-1]}:
        if cand and re.search(rf'(?<![\w.]){re.escape(cand)}(?![\w])', content_lower):
            return True
    return False


def self_consistency_flags(row: dict) -> list[str]:
    cl = row["content"].lower()
    flags = []
    for d, items in (("read", row["labels"].get("reads", [])),
                     ("write", row["labels"].get("writes", []))):
        for it in items:
            t = str(it.get("table", ""))
            if t and not _present(t, cl):
                flags.append(f"{d}_table_absent:{it['table']}")
    rt = {r["table"] for r in row["labels"].get("reads", [])}
    wt = {w["table"] for w in row["labels"].get("writes", [])}
    for t in rt & wt:
        flags.append(f"read_write_overlap:{t}")
    return flags


def review_sample(client, row: dict) -> dict:
    issues = self_consistency_flags(row)
    verdict = client.extract(row["task_type"], row["content"]) if client else None
    return {"template_id": row.get("meta", {}).get("template_id"), "issues": issues, "llm_verdict": verdict}
