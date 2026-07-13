"""067 build_gold_b 列级一致裁决单测（contracts/build_gold_column_mode.md）。"""
from realeval.build_gold_b import decide_tables

# 用 AST 可锚定方向的 SQL，聚焦列裁决。
CONTENT = "INSERT INTO analytics.daily SELECT amount, user_id FROM raw.users"


def _rec(read_cols=None, write_cols=None):
    return {"reads": [{"table": "raw.users", "columns": read_cols}],
            "writes": [{"table": "analytics.daily", "columns": write_cols}]}


def _cols(labels, side, table):
    for e in labels[side]:
        if e["table"] == table:
            return e["columns"]
    return "MISSING"


def test_both_concrete_intersection():
    recs = [_rec(read_cols=["amount", "user_id"]), _rec(read_cols=["amount", "user_id"])]
    out = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    assert _cols(out, "reads", "raw.users") == ["amount", "user_id"]


def test_intersection_narrows():
    recs = [_rec(read_cols=["amount", "user_id"]), _rec(read_cols=["amount"])]
    out = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    assert _cols(out, "reads", "raw.users") == ["amount"]      # 交集


def test_one_abstains_yields_null():
    recs = [_rec(read_cols=["amount"]), _rec(read_cols=None)]
    out = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    assert _cols(out, "reads", "raw.users") is None            # 一方弃权 → null


def test_empty_intersection_yields_null():
    recs = [_rec(read_cols=["amount"]), _rec(read_cols=["user_id"])]
    out = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    assert _cols(out, "reads", "raw.users") is None            # 交集空 → null


def test_wildcard_abstains():
    recs = [_rec(read_cols=["amount", "*"]), _rec(read_cols=["amount"])]
    out = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    assert _cols(out, "reads", "raw.users") is None            # 含通配一方弃权 → 不足双方 → null


def test_columns_false_zero_regression():
    """columns=False（默认）→ 列恒 None，且表级裁决与开关无关（表身份/方向/入选不变）。"""
    recs = [_rec(read_cols=["amount", "user_id"]), _rec(read_cols=["amount"])]
    off = decide_tables(CONTENT, recs, min_agree=2)
    on = decide_tables(CONTENT, recs, min_agree=2, columns=True)
    # 列恒 None
    assert _cols(off, "reads", "raw.users") is None
    # 表结构一致（表名/方向/条数），只有 columns 值不同
    assert {e["table"] for e in off["reads"]} == {e["table"] for e in on["reads"]}
    assert {e["table"] for e in off["writes"]} == {e["table"] for e in on["writes"]}
    assert off["n_agree_edges"] == on["n_agree_edges"]
