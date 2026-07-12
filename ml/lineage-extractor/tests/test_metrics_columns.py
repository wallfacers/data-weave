"""067 条件列打分 + 门① 正交隔离单测（contracts/metrics_column_scoring.md）。"""
import copy

from eval.metrics import score_row, aggregate

CONTENT = "INSERT INTO db.summary SELECT amount, user_id FROM db.orders"

# 多态 fixtures：具体列 / gold 弃权 / pred 弃权 / 空。
_FIXTURES = [
    # 完美：表命中 + 列命中
    (dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}],
          writes=[{"table": "db.summary", "columns": ["amount"]}]),
     dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}],
          writes=[{"table": "db.summary", "columns": ["amount"]}])),
    # gold 弃权（columns=None）
    (dict(reads=[{"table": "db.orders", "columns": None}], writes=[]),
     dict(reads=[{"table": "db.orders", "columns": ["amount"]}], writes=[])),
    # pred 弃权而 gold 有列
    (dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}], writes=[]),
     dict(reads=[{"table": "db.orders", "columns": None}], writes=[])),
    # 空
    (dict(reads=[], writes=[]), dict(reads=[], writes=[])),
    # 表不命中（列不应评）
    (dict(reads=[{"table": "db.orders", "columns": ["amount"]}], writes=[]),
     dict(reads=[{"table": "other.tbl", "columns": ["x"]}], writes=[])),
]


def _strip_columns(row):
    r = copy.deepcopy(row)
    for role in ("reads", "writes"):
        for it in r.get(role) or []:
            it["columns"] = None
    return r


def test_column_scoring_never_perturbs_table_counts():
    """门①：带列 vs 列全抹 None，表级 counts 逐字节相等。"""
    keys = ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct", "invalid")
    for gold, pred in _FIXTURES:
        for canon in (False, True):
            base = score_row(_strip_columns(gold), pred, CONTENT, canon=canon)
            withcol = score_row(gold, pred, CONTENT, canon=canon)
            for k in keys:
                assert base[k] == withcol[k], f"表级 {k} 被列打分扰动 (canon={canon})"


def test_column_tp_fp_fn_on_matched_table():
    gold = dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}], writes=[])
    pred = dict(reads=[{"table": "db.orders", "columns": ["amount", "ghost"]}], writes=[])
    c = score_row(gold, pred, CONTENT, canon=True)
    assert c["col_tp"] == 1          # amount 命中
    assert c["col_fp"] == 1          # ghost 多报
    assert c["col_fn"] == 1          # user_id 漏
    assert c["col_eval_tables"] == 1


def test_gold_abstain_skips_column_eval():
    gold = dict(reads=[{"table": "db.orders", "columns": None}], writes=[])
    pred = dict(reads=[{"table": "db.orders", "columns": ["amount"]}], writes=[])
    c = score_row(gold, pred, CONTENT, canon=True)
    assert c["col_eval_tables"] == 0
    assert c["col_tp"] == c["col_fp"] == c["col_fn"] == 0


def test_pred_abstain_counts_fn():
    gold = dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}], writes=[])
    pred = dict(reads=[{"table": "db.orders", "columns": None}], writes=[])
    c = score_row(gold, pred, CONTENT, canon=True)
    assert c["col_fn"] == 2          # 全漏
    assert c["col_tp"] == 0 and c["col_halluc"] == 0   # 弃权不算幻觉
    assert c["col_eval_tables"] == 1


def test_column_hallucination():
    gold = dict(reads=[{"table": "db.orders", "columns": ["amount"]}], writes=[])
    pred = dict(reads=[{"table": "db.orders", "columns": ["amount", "nonexistent_col"]}], writes=[])
    c = score_row(gold, pred, CONTENT, canon=True)
    assert c["col_halluc"] == 1      # nonexistent_col 不在脚本
    assert c["col_pred_total"] == 2


def test_columns_scored_per_role():
    # 同表名在 reads 与 writes 各自算列，互不串
    gold = dict(reads=[{"table": "t", "columns": ["a"]}],
                writes=[{"table": "t", "columns": ["b"]}])
    pred = dict(reads=[{"table": "t", "columns": ["a"]}],
                writes=[{"table": "t", "columns": ["b"]}])
    c = score_row(gold, pred, "a b t", canon=False)
    assert c["col_tp"] == 2 and c["col_fp"] == 0 and c["col_fn"] == 0
    assert c["col_eval_tables"] == 2


def test_aggregate_column_metrics():
    gold = dict(reads=[{"table": "db.orders", "columns": ["amount", "user_id"]}], writes=[])
    pred = dict(reads=[{"table": "db.orders", "columns": ["amount"]}], writes=[])
    agg = aggregate([score_row(gold, pred, CONTENT, canon=True)])
    assert agg["col_precision"] == 1.0            # amount 全对
    assert agg["col_recall"] == 0.5               # 2 中 1
    assert 0.66 < agg["col_f1"] < 0.67
    # 表级返回值仍在（零回归）
    assert "precision" in agg and "direction_acc" in agg


def test_aggregate_backward_compatible_without_col_keys():
    # 旧 counts（无 col_* key）→ aggregate 不炸，列指标取默认
    old = dict(tp=1, fp=0, fn=0, halluc=0, pred_total=1, dir_total=1, dir_correct=1, invalid=0)
    agg = aggregate([old])
    assert agg["col_eval_tables"] == 0 and agg["col_precision"] == 1.0
