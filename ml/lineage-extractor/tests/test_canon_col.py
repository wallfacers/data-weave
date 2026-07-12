"""067 canon_col / canon_cols 纯函数单测（contracts/canon_col.md 行为表）。"""
from eval.metrics import canon_col, canon_cols


def test_lowercase_and_strip():
    assert canon_col("Amount") == "amount"
    assert canon_col("  amount ") == "amount"


def test_strip_qualifier_prefix():
    assert canon_col("orders.amount") == "amount"        # 单点前缀
    assert canon_col("db.orders.amount") == "amount"     # 多级取尾段
    assert canon_col("o.amount") == "amount"             # 别名前缀


def test_wildcard_and_empty_are_abstain():
    assert canon_col("*") is None
    assert canon_col("orders.*") is None
    assert canon_col("") is None
    assert canon_col("   ") is None
    assert canon_col(None) is None


def test_canon_cols_tristate():
    assert canon_cols(None) is None                      # 弃权
    assert canon_cols([]) is None                        # 空→弃权
    assert canon_cols(["Amount", "user_id"]) == {"amount", "user_id"}
    assert canon_cols(["orders.amount"]) == {"amount"}   # 前缀归一


def test_canon_cols_wildcard_infection():
    # 含通配 → 整集弃权（不确定具体列）
    assert canon_cols(["amount", "*"]) is None
    assert canon_cols(["amount", "orders.*"]) is None
