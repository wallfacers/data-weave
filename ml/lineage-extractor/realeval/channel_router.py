"""050 Tier 1：通道分工路由（SQL→sqlglot AST / 残差→模型）。

041-R §7 结论：读写方向混淆是独立病，对独立 LM 有硬天花板——**有 SQL 时解析器严格
优于模型**（方向由 AST 构造保证）。本模块把"含 SQL 的脚本"路由给 sqlglot 抽血缘、
其余残差留给模型，量化"通道分工把系统抬到哪"。这不是绕开问题——这就是可用的答案。

- `extract_sql_lineage(content)`：从任意 Python/Shell 脚本里启发式提取嵌入 SQL
  （heredoc / `spark.sql("…")` / `hive -e "…"` / `psql -c "…"` / 引号内 DML/DDL /
  纯 .sql 正文），逐段 sqlglot 解析，按 AST 出**方向正确**的 reads/writes。
- `route_predict(content, model_fn)`：混合路由——SQL 通道命中则以其为准，否则落模型。

启发式提取是**下界**（未必抓全每处嵌入 SQL），故系统指标是通道分工收益的保守估计；
限制显式披露，不夸大。纯函数（不依赖 torch），SQL 提取部分可无 GPU 单测。
"""
from __future__ import annotations

import re
import signal
import threading

import sqlglot
from sqlglot import exp

_PARSE_TIMEOUT_S = 3          # 单片段 sqlglot 解析上限（防个别输入令分词器病态回溯）
_parse_timeouts = 0          # 计数：被限时跳过的片段（诚实披露 → SQL 通道是下界）


class _Timeout(Exception):
    pass


def _safe_parse(sql: str):
    """带超时的 sqlglot.parse：仅主线程启用 SIGALRM；超时或异常 → 返回 []（跳过该片段）。"""
    global _parse_timeouts
    use_alarm = threading.current_thread() is threading.main_thread() and hasattr(signal, "SIGALRM")
    if use_alarm:
        def _h(signum, frame):
            raise _Timeout()
        old = signal.signal(signal.SIGALRM, _h)
        signal.setitimer(signal.ITIMER_REAL, _PARSE_TIMEOUT_S)
    try:
        return sqlglot.parse(sql, error_level="ignore")
    except _Timeout:
        _parse_timeouts += 1
        return []
    except Exception:  # noqa - 启发式片段可能非法
        return []
    finally:
        if use_alarm:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, old)

# SQL 语句起始关键字（大小写不敏感）——在自由文本里定位候选片段。含有界 SELECT…FROM
# 以捕获嵌在 `spark.sql("…")` 等里的只读查询。所有量词均有界，O(n)、无灾难性回溯。
_STMT_RE = re.compile(
    r"(?is)\b("
    r"insert\s+overwrite\s+table|insert\s+(?:into|overwrite\s+into)|"
    r"create\s+(?:or\s+replace\s+)?(?:temp(?:orary)?\s+)?(?:external\s+)?(?:table|view)|"
    r"merge\s+into|update\s+\w+\s+set\b|delete\s+from|replace\s+into|"   # UPDATE <表> SET，排除 MERGE 内裸 UPDATE SET
    r"copy\s+(?:into\s+\w|\w+\s+from\b)|"               # 收紧：避免误匹配 'copy of the License'
    r"load\s+data\s+(?:local\s+)?(?:inpath|infile)|"    # 收紧：避免误匹配 'load data first'
    r"select\b[^;'\"]{0,400}?\bfrom\b"
    r")")

# 嵌入 SQL 常以引号/括号闭合（`spark.sql("… FROM t")`）。片段截到首个闭合标记，去掉 `")` 等
# 尾巴噪声——否则 sqlglot 把 `t")` 当表名或解析失败，丢掉本可正确抽取的血缘。
_CLOSE_RE = re.compile(r"""["'`]\s*\)|["']\s*(?:\.(?:show|collect|count)|$)""", re.M)

# 模板/动态标记：含之的片段是动态构造名（约定 A 范围外），且会令 sqlglot 分词器病态回溯 → 跳过。
_TEMPLATE_RE = re.compile(r"\{\{|\{%|\$\{|\$CONDITIONS|<[?%]")

# heredoc 正文（`<<EOF … EOF`）——反向引用有界正文，避免大文件回溯。
_HEREDOC_RE = re.compile(r"<<[-~]?\s*['\"]?(\w+)['\"]?[^\n]*\n(.{0,4000}?)\n\s*\1\b", re.S)


# 052 执行上下文 sink：SQL 只有作为「被执行调用的实参」或喂给 SQL CLI 才计入自动采纳层。
# 排除 docstring/注释/文档里的示例 SQL（真实 ETL 脚本的头号 precision 杀手）。有界、O(n)。
_EXEC_SINK_RE = re.compile(
    r"(?is)("
    r"\.\s*sql\s*\(|"                                   # spark.sql( / sqlContext.sql(
    r"\.\s*execute(?:many|script)?\s*\(|"               # cursor.execute( / conn.executemany(
    r"\.\s*(?:query|run_query|run|read_sql|sql_query)\s*\(|"   # client.query( / hook.run(
    r"(?:read_sql|read_sql_query)\s*\(|"                # pandas.read_sql(
    r"\b(?:hive|beeline|impala-shell|spark-sql|presto|trino|sqlplus)\b[^\n]*?-[ef]\b|"  # CLI -e/-f
    r"\bbq\s+query\b|\bpsql\b[^\n]*?-c\b|\bmysql\b[^\n]*?-e\b|"
    r"\b(?:sql|query|_sql|stmt|statement|ddl|dml)\s*=\s*(?:f|r|rf|fr)?[\"']"  # sql = "..." 赋值
    r")")

# 行注释前缀：SQL 关键字若处于注释/Databricks MAGIC markdown 行 → 非执行、剔除。
_COMMENT_LINE_RE = re.compile(r"^\s*(?:#|--|//|\*|>>>|\"\"\"|''')")
_EXEC_WINDOW = 400   # 执行 sink 需出现在 SQL 关键字前 400 字符内（容多行三引号 SQL）


def _candidate_fragments(content: str, exec_gated: bool = False) -> list[str]:
    """exec_gated=True（052 Tier 1 自动采纳层）：仅保留处于执行 sink 内、且本行非注释的
    SQL 片段——排除 docstring/注释/文档示例 SQL。heredoc 天然是执行上下文（喂 CLI），保留。
    默认 False：保留 050 及既有调用者的全量启发式行为（零破坏）。"""
    frags: list[str] = []
    for m in _HEREDOC_RE.finditer(content):
        frags.append(m.group(2))
    # 关键字定位：从每个 SQL 起始词抓到下一个 ';'，但**无条件封顶 800 字符**——避免远处
    # 分号导致抓到数十 KB 巨块喂给 sqlglot（曾致 10GB 内存/回溯）。SQL 语句极少超 800 字符。
    for m in _STMT_RE.finditer(content):
        start = m.start()
        if exec_gated:
            line_start = content.rfind("\n", 0, start) + 1
            if _COMMENT_LINE_RE.match(content[line_start:start + 1]):
                continue                       # SQL 在注释/MAGIC/docstring 行 → 剔除
            if not _EXEC_SINK_RE.search(content[max(0, start - _EXEC_WINDOW):start]):
                continue                       # 前窗无执行 sink → 非执行 SQL，剔除
        semi = content.find(";", start)
        end = semi if semi != -1 else len(content)
        frag = content[start:min(end, start + 800)]
        cm = _CLOSE_RE.search(frag)          # 截到嵌入串闭合，去尾巴噪声
        frags.append(frag[:cm.start()] if cm else frag)
    return frags


_MAX_SQL_LEN = 2000  # 单片段喂 sqlglot 的长度上限（超出多为非 SQL 噪声，跳过防爆）


# 052 治理级名字消毒：血缘约定 A 里不算「持久表」的东西（strict/gated 模式剔除）。
_SYSTEM_SCHEMAS = {"information_schema", "pg_catalog", "sys", "sys_catalog",
                   "__tables__", "mysql", "performance_schema"}


def _clean_name(name: str, strict: bool) -> str | None:
    """规范表名；strict（Tier 1 自动采纳）下剔除解析噪声/系统表/shell 变量等非血缘 token。"""
    n = name.strip().strip('`"').lower()
    if not n:
        return None
    if not strict:
        return n
    if " " in n or "(" in n or n.startswith("$") or n.startswith("{"):
        return None                                  # 解析碎片 / shell 变量 / 模板
    if any(p in _SYSTEM_SCHEMAS for p in n.split(".")):
        return None                                  # information_schema / __tables__ 等系统元数据
    return n


def _stmt_lineage(sql: str, strict: bool = False) -> tuple[set[str], set[str]]:
    reads, writes = set(), set()
    for st in _safe_parse(sql):
        if st is None:
            continue
        # strict：解析失败回退成 Command 的碎片（GRANT/ALTER PARTITION 等非血缘 DDL 常落此）跳过。
        if strict and isinstance(st, exp.Command):
            continue
        # 目标表只取**顶层语句**的 target（INSERT/CREATE/MERGE/UPDATE/DELETE 的 .this）——
        # 不 find_all，避免 MERGE 的 `WHEN MATCHED THEN UPDATE SET` 等嵌套 mutation 被误抓成表。
        tgts = set()
        if isinstance(st, (exp.Insert, exp.Create, exp.Merge, exp.Update, exp.Delete)):
            if strict and isinstance(st, exp.Create) and (st.kind or "").upper() == "VIEW":
                pass                                 # CREATE VIEW 目标非持久表（约定 A 排除临时视图），不计写
            else:
                tt = st.this
                if isinstance(tt, exp.Schema):
                    tt = tt.this
                if isinstance(tt, exp.Table):
                    nm = _clean_name(tt.sql(), strict)
                    if nm:
                        tgts.add(nm)
        for t in st.find_all(exp.Table):
            name = _clean_name(t.sql(), strict)
            if name is None:
                continue
            (writes if name in tgts else reads).add(name)
    return reads - writes, writes


def extract_sql_lineage(content: str, exec_gated: bool = False) -> dict:
    """启发式提取脚本内所有 SQL 片段并合并血缘（方向由 AST 保证）。无 SQL 命中则 reads/writes 皆空。

    exec_gated=True（052）：仅计入执行上下文内的 SQL（Tier 1 自动采纳层，追求高 precision）。"""
    reads, writes = set(), set()
    for frag in _candidate_fragments(content, exec_gated=exec_gated):
        if len(frag) > _MAX_SQL_LEN or _TEMPLATE_RE.search(frag) or not _STMT_RE.search(frag):
            continue
        r, w = _stmt_lineage(frag, strict=exec_gated)
        reads |= r
        writes |= w
    reads -= writes
    return {"reads": [{"table": t} for t in sorted(reads)],
            "writes": [{"table": t} for t in sorted(writes)]}


def has_sql(content: str) -> bool:
    out = extract_sql_lineage(content)
    return bool(out["reads"] or out["writes"])


def route_predict(content: str, model_fn):
    """混合路由：SQL 通道命中 → 以 sqlglot 结果为准（方向可信）；否则残差落模型。"""
    sql_out = extract_sql_lineage(content)
    if sql_out["reads"] or sql_out["writes"]:
        return {**sql_out, "_channel": "sql"}
    m = model_fn()
    return {"reads": m.get("reads") or [], "writes": m.get("writes") or [], "_channel": "model"}
