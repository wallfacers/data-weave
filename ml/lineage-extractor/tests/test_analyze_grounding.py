"""059 grounding 过滤器纯函数单测：叶名定位 + 幻觉剔除 + FP 分解。"""
from realeval.analyze_grounding import (
    decompose_fp, filter_pred, is_dynamic, is_grounded, keep_table, leaf,
)


def test_is_dynamic_flags_placeholders_and_vars():
    assert is_dynamic("{}.{}")
    assert is_dynamic("$db_output.validcodes")
    assert is_dynamic("schema.%s")
    assert not is_dynamic("ods.orders")


def test_keep_table_requires_grounded_and_not_dynamic():
    content = "insert into orders select 1"
    assert keep_table("db.s.orders", content)
    assert not keep_table("phantom", content)          # 未定位
    assert not keep_table("{}.orders", content)        # 动态名（即便 orders 在脚本）


def test_filter_pred_drops_dynamic_names():
    out = filter_pred({"reads": [{"table": "$var.orders"}], "writes": []},
                      "insert into orders select 1")
    assert out["reads"] == []


def test_leaf_takes_last_dot_segment():
    assert leaf("db.schema.orders") == "orders"
    assert leaf("ORDERS") == "orders"
    assert leaf("") == ""


def test_is_grounded_qualified_name_matches_short_literal():
    # 脚本只写短名 orders，预测限定名 db.s.orders → 叶名 orders 在脚本里 → 保留。
    assert is_grounded("db.s.orders", "insert into orders select 1")
    # 凭空造名 phantom → 不在脚本 → 幻觉，剔除。
    assert not is_grounded("phantom_table", "insert into orders select 1")


def test_filter_pred_drops_only_hallucinated_tables():
    content = "INSERT INTO ods.orders SELECT * FROM raw_events"
    pred = {"reads": [{"table": "raw_events", "columns": None},
                      {"table": "ghost", "columns": None}],
            "writes": [{"table": "warehouse.ods.orders", "columns": None}]}
    out = filter_pred(pred, content)
    assert [it["table"] for it in out["reads"]] == ["raw_events"]        # ghost 剔除
    assert [it["table"] for it in out["writes"]] == ["warehouse.ods.orders"]  # 限定名保留


def test_filter_pred_preserves_invalid_flag():
    out = filter_pred({"reads": [], "writes": [], "_invalid": True}, "x")
    assert out.get("_invalid") is True


def test_decompose_fp_splits_killable_vs_groundable_wrong():
    rows = [
        # 空脚本，预测两张表：ghost(叶名不在脚本→可杀) / logged_tbl(叶名在脚本但非 gold→可定位但错)
        {"content": "print('logged_tbl only in a string')", "is_empty": True,
         "labels": {"reads": [], "writes": []},
         "pred": {"reads": [{"table": "ghost"}, {"table": "logged_tbl"}], "writes": []}},
    ]
    d = decompose_fp(rows)
    assert d["total_fp"] == 2
    assert d["empty_fp"] == 2
    assert d["killable"] == 1          # ghost
    assert d["groundable_wrong"] == 1  # logged_tbl
