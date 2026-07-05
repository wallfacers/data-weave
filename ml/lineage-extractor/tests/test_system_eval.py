"""050 Tier 1：system_metrics 纯函数单测（注入假模型，无 GPU）。"""
from __future__ import annotations

from realeval.system_eval import system_metrics


def _row(content, gr, gw):
    return {"content": content,
            "labels": {"reads": [{"table": t} for t in gr], "writes": [{"table": t} for t in gw]},
            "meta": {}}


def test_routing_split_and_channel_override():
    rows = [
        _row("INSERT INTO mart.fact SELECT a FROM raw.orders;", ["raw.orders"], ["mart.fact"]),  # SQL 通道
        _row("df = spark.read.table('x')\ndf.write.saveAsTable('y')", ["x"], ["y"]),               # 残差→模型
    ]
    # 假模型：永远抽错（方向反）——用于验证 SQL 通道命中时以其为准、不被模型污染
    def bad_model(r):
        return {"reads": [{"table": "wrong"}], "writes": [{"table": "wrong"}]}
    res = system_metrics(rows, bad_model, canon=True)
    assert res["routed_sql"] == 1 and res["routed_model"] == 1
    # hybrid 在 SQL 行用 sqlglot 正确方向 → 至少比全用坏模型强
    assert res["hybrid"]["full"]["precision"] >= res["model"]["full"]["precision"]


def test_hybrid_equals_model_when_no_sql():
    rows = [_row("x = [i for i in range(3)]\nprint(x)", [], [])]
    def m(r):
        return {"reads": [], "writes": []}
    res = system_metrics(rows, m, canon=True)
    assert res["routed_sql"] == 0 and res["routed_model"] == 1


def test_sql_channel_fixes_direction_vs_wrong_model():
    """SQL 行上：坏模型方向全错，hybrid 用 AST 方向 → 方向准确率更高。"""
    rows = [_row("spark.sql('INSERT INTO t SELECT * FROM s')", ["s"], ["t"])]
    def flip(r):  # 把读写标反
        return {"reads": [{"table": "t"}], "writes": [{"table": "s"}]}
    res = system_metrics(rows, flip, canon=True)
    assert res["hybrid"]["nonempty"]["direction_acc"] > res["model"]["nonempty"]["direction_acc"]
