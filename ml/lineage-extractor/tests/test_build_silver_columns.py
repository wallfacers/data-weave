"""067 build_silver 列保留单测（contracts/build_silver_column_preserve.md）。列取 pair[0]=m1。"""
from realeval.build_silver import build_record

CONTENT = "INSERT INTO analytics.daily SELECT amount, user_id FROM raw.users"


def _teacher(read_cols=None, write_cols=None):
    return {"reads": [{"table": "raw.users", "columns": read_cols}],
            "writes": [{"table": "analytics.daily", "columns": write_cols}]}


def _cols(rec, side, table):
    for e in rec[side]:
        if e["table"] == table:
            return e["columns"]
    return "MISSING"


def test_keep_columns_false_zero_regression():
    m1 = _teacher(read_cols=["amount"])
    m2 = _teacher(read_cols=["amount"])
    rec = build_record("h", CONTENT, "SQL", m1, m2, set())   # keep_columns 默认 False
    assert _cols(rec, "reads", "raw.users") is None          # 列恒 None


def test_keep_columns_takes_m1():
    m1 = _teacher(read_cols=["amount", "user_id"])
    m2 = _teacher(read_cols=["amount"])                      # m2 不影响列（只取 m1）
    rec = build_record("h", CONTENT, "SQL", m1, m2, set(), keep_columns=True)
    assert _cols(rec, "reads", "raw.users") == ["amount", "user_id"]


def test_keep_columns_m1_abstain_yields_null():
    m1 = _teacher(read_cols=None)                            # m1 弃权
    m2 = _teacher(read_cols=["amount"])
    rec = build_record("h", CONTENT, "SQL", m1, m2, set(), keep_columns=True)
    assert _cols(rec, "reads", "raw.users") is None
