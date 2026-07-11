"""059：上下文感知的语义 grounding 过滤器（离线，确定性）。

literal-grounding（`analyze_grounding`）只查「叶名字面出现在脚本某处」，杀不掉
**grounded-but-wrong** 假阳——叶名确实出现，但出现位置是注释 / import / 文件路径 /
变量声明 / 临时视图，而非真表引用。本模块升级为**上下文感知**：定位叶名的每处出现，
判其句法上下文，按**黑名单**裁决——某表的**所有**出现都落在约定已排除的位置 → 剔；
**任一**落在真表位置（SQL FROM/JOIN/INSERT/UPDATE/MERGE 或 spark.table/saveAsTable/
insertInto/read.table）→ 留（保护召回：变量持有的真表只要在别处有真引用就保住）。

设计取舍（见 docs/superpowers/specs/2026-07-11-semantic-grounding-design.md）：
- 黑名单（只剔约定已排除位置），不用白名单（只留确认表位置），以保护召回。
- 行/出现位置正则启发式，不用 AST/sqlglot（真实脚本 py 混嵌 SQL+shell+notebook MAGIC，
  解析器在脏输入上脆；现有确定性过滤器同一哲学）。
- Databricks `# MAGIC` 是被执行的代码（非注释），去前缀后按代码分类。
"""
from __future__ import annotations

import re

from realeval.analyze_grounding import is_dynamic, leaf

# 约定已排除的上下文（黑名单，保守集）；table_ref / other / var_decl / param / attribute
# 视为「非排除」→ 留。
#
# ★保守集经验校准（gold C 实测，见设计文档）：初始设计把 var_decl/param/attribute 也纳入
# 排除，但实测它们误杀 22 个真表（召回 0.633→0.487 崩）——因为「变量持有的表名」
# （`SOURCE_TABLE = "orders"`）与「限定表名」（`db.dev.stats` 叶名前带 `.` 被误判 attribute）
# 正是连 pro 都裁不清的**标签歧义边界**，真血缘也住那。黑名单只保留**无歧义**的排除
# （注释/import/文件路径/临时视图）→ ALL-p +4.2pt / 非空-p +3.0pt 且**召回、方向零退化**。
# 注：param/attribute/var_decl 仍被 `occurrence_contexts` 标注（供审计），只是不进 _EXCLUDED。
_EXCLUDED = {"comment", "import", "path", "temp_view"}

_MAGIC_PREFIX = re.compile(r"^#\s*MAGIC\s?", re.IGNORECASE)
_IS_MAGIC = re.compile(r"^#\s*MAGIC\b", re.IGNORECASE)
_TEMP_VIEW = re.compile(
    r"(createOrReplaceTempView|createGlobalTempView|createTempView|registerTempTable)\s*\(",
    re.IGNORECASE)
_IMPORT = re.compile(r"(from\s+\S+\s+)?import\b")
_DATA_SUFFIX = re.compile(r"\.(csv|parquet|json|txt|orc|avro|tsv|gz|xlsx?)\b")
_SQL_KW = re.compile(r"\b(from|join|into|update|table|merge)\b")
_TABLE_API = re.compile(r"(saveastable|insertinto|spark\.table|read\.table|\breadtable\b|\.table\s*\()")
_DEF = re.compile(r"def\s+\w+\s*\(")
_VAR_DECL = re.compile(r"""^[A-Za-z_]\w*\s*=\s*[f]?["']""")


def _word_re(lf: str) -> re.Pattern:
    """把叶名当独立标识符 token 匹配（不匹配嵌入更大标识符内的子串）。"""
    return re.compile(r"(?<![0-9A-Za-z_])" + re.escape(lf) + r"(?![0-9A-Za-z_])")


def _classify_line(line: str, pat: re.Pattern) -> str:
    """给单行里叶名的出现打一个上下文标签（精度优先的固定优先级）。"""
    s = line.strip()
    is_magic = bool(_IS_MAGIC.match(s))
    # 非 MAGIC 的注释行 → comment；MAGIC 去前缀后当代码分类。
    if not is_magic and (s.startswith("#") or s.startswith("--") or s.startswith("//")):
        return "comment"
    code = _MAGIC_PREFIX.sub("", s) if is_magic else s
    low = code.lower()

    if _TEMP_VIEW.search(code):
        return "temp_view"
    if _IMPORT.match(low):                       # import 必须在 table_ref 前（import 行含 "from"）
        return "import"
    if "/" in code or _DATA_SUFFIX.search(low):  # 文件路径 / 数据文件后缀
        return "path"
    if _SQL_KW.search(low) or _TABLE_API.search(low):
        return "table_ref"
    if _DEF.match(code):
        return "param"
    for m in pat.finditer(code):                 # 叶名紧跟 '.' 前 → 属性访问
        if m.start() > 0 and code[m.start() - 1] == ".":
            return "attribute"
    if _VAR_DECL.match(code):
        return "var_decl"
    return "other"


def occurrence_contexts(table: str, content: str) -> set[str]:
    """返回叶名在脚本中每处 word-boundary 出现的上下文标签集合（供审计/测试）。"""
    lf = leaf(table)
    if not lf or "*" in lf:
        return set()
    pat = _word_re(lf)
    labels: set[str] = set()
    for line in content.splitlines():
        if pat.search(line.lower()):
            labels.add(_classify_line(line, pat))
    return labels


def keep_table_semantic(table: str, content: str) -> bool:
    """黑名单裁决：动态名剔；无干净 token 出现剔；所有出现都在排除位置剔；否则留。"""
    if is_dynamic(table):
        return False
    lf = leaf(table)
    if not lf or "*" in lf:                       # glob → 文件路径，剔
        return False
    ctxs = occurrence_contexts(table, content)
    if not ctxs:                                  # 只是嵌入更大标识符的子串，非真引用
        return False
    return any(c not in _EXCLUDED for c in ctxs)  # 任一非排除位置 → 留


def filter_pred_semantic(pred: dict, content: str) -> dict:
    """对一条预测施加语义 grounding，保留 _invalid 标记（与 analyze_grounding.filter_pred 同形）。"""
    def keep(items):
        return [it for it in (items or [])
                if isinstance(it, dict) and keep_table_semantic(it.get("table", ""), content)]
    out = {"reads": keep(pred.get("reads")), "writes": keep(pred.get("writes"))}
    if pred.get("_invalid"):
        out["_invalid"] = True
    return out
