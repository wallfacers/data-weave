"""065 T002：sql / script 子集分类器（消化 rigor CHK010）。

SC-003 的招牌对比按此把每个样本归入「纯 SQL」或「命令式脚本」子集。纯函数、确定性。
判据优先级：显式 type/lang 字段 → 文件扩展名 → 内容启发式 → 保守兜底。

兜底选择 = **sql**：脚本子集是学习模型的有利战场（工具在此≈0），未定样本归 sql
不夸大脚本子集，守诚实脊椎（宁可低估模型优势，不高估）。
"""
from __future__ import annotations

import re

_SCRIPT_EXT = (".py", ".sh", ".bash", ".scala", ".java", ".kt", ".ipynb")
_SQL_EXT = (".sql", ".hql")

# 命令式脚本信号（import/def/shebang/DataFrame/写表 API 等）
_SCRIPT_SIGNAL = re.compile(
    r"(?m)^\s*(?:import\s|from\s+\w+\s+import|def\s|class\s|#!)"
    r"|\bspark\.|\bpd\.|\bdf\.|subprocess|os\.system|saveAsTable|to_sql|writeTo\(",
    re.IGNORECASE,
)
# SQL 信号
_SQL_SIGNAL = re.compile(
    r"\b(?:insert\s+into|insert\s+overwrite|create\s+table|merge\s+into"
    r"|select\b[\s\S]*\bfrom\b)",
    re.IGNORECASE,
)

_TYPE_SQL = {"sql", "hive", "hql"}
_TYPE_SCRIPT = {"python", "py", "shell", "sh", "bash", "spark", "pyspark",
                "scala", "java", "jvm", "kotlin"}


def classify(row: dict) -> str:
    """返回 'sql' | 'script'。"""
    t = str(row.get("type") or row.get("lang") or "").strip().lower()
    if t in _TYPE_SQL:
        return "sql"
    if t in _TYPE_SCRIPT:
        return "script"

    path = str(row.get("path") or row.get("file") or "").lower()
    if path.endswith(_SCRIPT_EXT):
        return "script"
    if path.endswith(_SQL_EXT):
        return "sql"

    content = row.get("content") or ""
    if _SCRIPT_SIGNAL.search(content):
        return "script"
    if _SQL_SIGNAL.search(content):
        return "sql"
    return "sql"  # 保守兜底（见模块 docstring）
