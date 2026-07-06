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
