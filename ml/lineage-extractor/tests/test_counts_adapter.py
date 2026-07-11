"""065 T003：逐脚本 counts 适配层单测——exact_match 派生 / subset 附加 / 等长校验。"""
from __future__ import annotations

import pytest

from realeval.counts_adapter import filter_counts, per_script_counts


def _row(reads, writes, content, **kw):
    return dict(labels={"reads": [{"table": t} for t in reads],
                        "writes": [{"table": t} for t in writes]},
                content=content, **kw)


def test_exact_match_true_when_pred_equals_gold():
    rows = [_row(["ods_o"], ["dwd_o"], "insert into dwd_o select * from ods_o", type="sql")]
    preds = [{"reads": [{"table": "ods_o"}], "writes": [{"table": "dwd_o"}]}]
    c = per_script_counts(rows, preds)[0]
    assert c["exact_match"] is True and c["fp"] == 0 and c["fn"] == 0
    assert c["subset"] == "sql"


def test_exact_match_false_on_any_error():
    rows = [_row(["ods_o"], ["dwd_o"], "insert into dwd_o select * from ods_o", type="sql")]
    preds = [{"reads": [{"table": "wrong"}], "writes": [{"table": "dwd_o"}]}]
    c = per_script_counts(rows, preds)[0]
    assert c["exact_match"] is False


def test_subset_and_is_empty_attached():
    rows = [_row([], [], "import pandas", type="python", is_empty=True)]
    preds = [{"reads": [], "writes": []}]
    c = per_script_counts(rows, preds)[0]
    assert c["subset"] == "script" and c["is_empty"] is True


def test_length_mismatch_raises():
    with pytest.raises(ValueError):
        per_script_counts([_row(["a"], [], "x")], [])


def test_filter_counts_by_subset_and_nonempty():
    rows = [_row(["a"], [], "select a from a", type="sql", is_empty=False),
            _row([], [], "import x", type="python", is_empty=True)]
    preds = [{"reads": [{"table": "a"}], "writes": []}, {"reads": [], "writes": []}]
    counts = per_script_counts(rows, preds)
    assert len(filter_counts(counts, subset="sql")) == 1
    assert len(filter_counts(counts, nonempty=True)) == 1
