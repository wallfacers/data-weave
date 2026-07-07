"""050 Tier 1：通道路由 SQL 抽取单测（纯函数，无 GPU）。

锁住：① 干净 SQL 方向由 AST 正确（这是通道分工的理论依据）；② 散文关键字不误匹配；
③ 模板/动态片段跳过；④ route_predict 混合逻辑。"""
from __future__ import annotations

from realeval.channel_router import (extract_sql_lineage, has_sql, route_predict)


def _names(out, key):
    return sorted(t["table"] for t in out[key])


# ── 干净 SQL：方向由 AST 保证（通道分工的立论依据）──────────────────────

def test_insert_select_direction():
    out = extract_sql_lineage("spark.sql(\"INSERT INTO mart.fact SELECT a FROM raw.orders\")")
    assert _names(out, "writes") == ["mart.fact"]
    assert _names(out, "reads") == ["raw.orders"]


def test_ctas_direction():
    out = extract_sql_lineage("CREATE TABLE dws.t AS SELECT * FROM dwd.s;")
    assert _names(out, "writes") == ["dws.t"] and _names(out, "reads") == ["dwd.s"]


def test_merge_direction():
    out = extract_sql_lineage("MERGE INTO tgt USING src ON a=b WHEN MATCHED THEN UPDATE SET x=1;")
    assert _names(out, "writes") == ["tgt"] and _names(out, "reads") == ["src"]


# ── 散文关键字不误匹配（曾致灾难性回溯 + 假抽取）────────────────────────

def test_prose_copy_and_load_not_matched():
    assert not has_sql("copy of the License at http://example")
    assert not has_sql("# load data first. Order list and details are linked")


def test_template_fragment_skipped():
    """Jinja/动态模板 SQL 跳过（约定 A 范围外 + 防 sqlglot 病态回溯）。"""
    tmpl = "spark.sql(\"SELECT {{ col }} FROM {{ conf.db }}.{{ table }} WHERE $CONDITIONS\")"
    assert not has_sql(tmpl)


def test_no_sql_returns_empty():
    out = extract_sql_lineage("import os\nx = [i*2 for i in range(10)]\nprint(sum(x))")
    assert out["reads"] == [] and out["writes"] == []


# ── route_predict 混合逻辑 ───────────────────────────────────────────────

def test_route_uses_sql_when_present():
    r = route_predict("INSERT INTO t SELECT * FROM s;", lambda: {"reads": [{"table": "x"}], "writes": []})
    assert r["_channel"] == "sql" and _names(r, "writes") == ["t"]


def test_route_falls_to_model_on_residual():
    called = {}
    def model():
        called["hit"] = True
        return {"reads": [{"table": "df_src"}], "writes": [{"table": "df_out"}]}
    r = route_predict("df = spark.read.table(x)\ndf.write.saveAsTable(y)", model)
    assert r["_channel"] == "model" and called.get("hit")
    assert _names(r, "reads") == ["df_src"]


# ── 052 exec-gated + strict 语义过滤（Tier 1 自动采纳层，治理级 precision）────

def test_gated_keeps_executed_sql():
    """在 spark.sql( 执行 sink 内的 SQL → gated 保留。"""
    out = extract_sql_lineage('spark.sql("INSERT INTO mart.fact SELECT a FROM raw.orders")',
                              exec_gated=True)
    assert _names(out, "writes") == ["mart.fact"] and _names(out, "reads") == ["raw.orders"]


def test_gated_drops_docstring_sql():
    """docstring/文档里的示例 SQL（无执行 sink）→ gated 剔除；ungated 仍抓（保 050 行为）。"""
    src = 'def f():\n    """Example: CREATE TABLE demo.t AS SELECT * FROM demo.s"""\n    return 1\n'
    assert extract_sql_lineage(src, exec_gated=True) == {"reads": [], "writes": []}
    assert extract_sql_lineage(src, exec_gated=False)["writes"]      # ungated 未变


def test_gated_drops_comment_line_sql():
    """注释行里的 SQL 关键字 → gated 剔除。"""
    src = 'x = 1\n# INSERT INTO audit.log SELECT * FROM events\nspark.range(1)\n'
    assert extract_sql_lineage(src, exec_gated=True) == {"reads": [], "writes": []}


def test_strict_command_fallback_skipped():
    """解析回退成 Command 的碎片（GRANT/非血缘 DDL）→ strict 跳过，不吐噪声表。"""
    # `GRANT CREATE TABLE ON SCHEMA ...` 片段化后以 CREATE TABLE 起头、解析回退 Command
    out = extract_sql_lineage('psql -c "GRANT CREATE TABLE ON SCHEMA db.public TO ROLE r"',
                              exec_gated=True)
    assert out == {"reads": [], "writes": []}


def test_known_limitation_cte_ref_leaks_on_fragmentation():
    """已知残差（诚实记录）：关键字锚定的片段化会丢掉 `WITH cte AS(...)` 前缀，
    使 `FROM cte` 的 CTE 引用被当读表泄漏——语句级 CTE 排除对此无能为力。
    这是 Tier 1 exec-gated precision ~0.68 的残余来源之一，非模型能力问题。"""
    sql = ('spark.sql("WITH cte AS (SELECT * FROM raw.src) '
           'INSERT INTO mart.fact SELECT * FROM cte")')
    out = extract_sql_lineage(sql, exec_gated=True)
    # 记录现状：cte 泄漏进 reads（若未来改为整串抽取修复，此断言应翻转）
    assert "cte" in _names(out, "reads")
    assert "raw.src" in _names(out, "reads")


def test_strict_excludes_system_tables():
    """information_schema 等系统元数据表 → strict 排除。"""
    out = extract_sql_lineage('cursor.execute("SELECT * FROM information_schema.tables")',
                              exec_gated=True)
    assert out == {"reads": [], "writes": []}


def test_strict_excludes_parse_noise():
    """含空格/`$`前缀的解析碎片/shell 变量 → strict 排除（不当表）。"""
    # $VAR 作为表名（shell 拼接）
    out = extract_sql_lineage('psql -c "SELECT * FROM $SCHEMA.$TABLE"', exec_gated=True)
    assert all("$" not in t for t in _names(out, "reads") + _names(out, "writes"))


def test_strict_excludes_create_view_target():
    """CREATE VIEW 目标非持久表 → strict 不计写（约定 A 排除临时视图）。"""
    out = extract_sql_lineage('spark.sql("CREATE VIEW tmp_v AS SELECT * FROM raw.s")',
                              exec_gated=True)
    assert "tmp_v" not in _names(out, "writes")
    assert "raw.s" in _names(out, "reads")


def test_ungated_default_unchanged_regression():
    """默认 ungated 行为对干净 SQL 不变（保 050 既有结果零破坏）。"""
    out = extract_sql_lineage("CREATE TABLE dws.t AS SELECT * FROM dwd.s;")
    assert _names(out, "writes") == ["dws.t"] and _names(out, "reads") == ["dwd.s"]
