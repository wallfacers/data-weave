"""065 T007（US2）：SQLLineage 基线单测——SQL 分列 / 脚本→空 / 永不抛。"""
from __future__ import annotations

from eval.baselines.sqllineage_baseline import predict


def _tables(items):
    return sorted(x["table"] for x in items)


def test_sql_insert_select_join_splits_source_target():
    row = {"content": "insert into dwd_o select a, b from ods_o "
                      "join ods_u on ods_o.id = ods_u.id"}
    out = predict(row)
    assert _tables(out["reads"]) == ["ods_o", "ods_u"]
    assert _tables(out["writes"]) == ["dwd_o"]
    assert all(x["columns"] is None for x in out["reads"] + out["writes"])


def test_python_script_yields_empty_structural_failure():
    row = {"content": "import pandas as pd\ndf = pd.read_csv('x')\ndf.to_sql('t', con)"}
    out = predict(row)
    assert out == {"reads": [], "writes": []}


def test_shell_script_yields_empty():
    row = {"content": "#!/bin/bash\nhive -e \"insert into a select * from b\" && echo done"}
    out = predict(row)
    # shell 外壳非合法 SQL → 空（结构性失效）
    assert out["writes"] == [] and out["reads"] == []


def test_never_raises_on_garbage():
    for content in ["", "???not sql???", "\x00\x01binary", "a" * 5000]:
        out = predict({"content": content})
        assert out == {"reads": [], "writes": []}


def test_write_table_not_double_counted_as_read():
    row = {"content": "insert into t select * from t"}
    out = predict(row)
    assert _tables(out["writes"]) == ["t"]
    assert "t" not in _tables(out["reads"])


# ── 067 US4：get_column_lineage 列抽取 ──

def _cols(items, table):
    for x in items:
        if x["table"] == table:
            return x["columns"]
    return "MISSING"


def test_with_columns_false_backward_compat():
    # 默认（无 with_columns）列恒 None——守既有契约零回归
    row = {"content": "insert into analytics.daily select user_id, amount from raw.orders"}
    out = predict(row)
    assert all(x["columns"] is None for x in out["reads"] + out["writes"])


def test_with_columns_extracts_source_and_target_columns():
    row = {"content": "insert into analytics.daily select user_id, amount from raw.orders"}
    out = predict(row, with_columns=True)
    assert _cols(out["reads"], "raw.orders") == ["amount", "user_id"]
    assert _cols(out["writes"], "analytics.daily") == ["amount", "user_id"]


def test_with_columns_non_sql_still_empty():
    row = {"content": "import pandas as pd\ndf.to_sql('t', con)"}
    assert predict(row, with_columns=True) == {"reads": [], "writes": []}


def test_with_columns_table_without_column_info_abstains():
    # 工具解析出表但给不出列 → columns=None（弃权，非空集），守三态列语义
    row = {"content": "select * from raw.orders"}
    out = predict(row, with_columns=True)
    assert all(x["columns"] is None for x in out["reads"] + out["writes"])
