"""050 Tier 0：schema 规范化匹配单测（canon）——修正假象但不放水，且向后兼容。"""
from __future__ import annotations

from eval.metrics import canon_match, score_row


def _row(gr, gw, pr, pw, content):
    g = {"reads": [{"table": t} for t in gr], "writes": [{"table": t} for t in gw]}
    p = {"reads": [{"table": t} for t in pr], "writes": [{"table": t} for t in pw]}
    return g, p, content


# ── canon_match 关系 ──────────────────────────────────────────────────────

def test_canon_match_suffix_hits():
    assert canon_match("agg_events_iceberg", "gcp_primary_staging.agg_events_iceberg")
    assert canon_match("db.schema.t", "schema.t")          # 对称
    assert canon_match("orders", "orders")                 # 相等


def test_canon_match_conservative_no_false_merge():
    """粒度真错不放水：dataset 名 vs dataset.table 不命中。"""
    assert not canon_match("test_dataset", "test_dataset.test_table")
    assert not canon_match("orders", "customers")
    assert not canon_match("a.orders", "b.orders")         # 不同 schema 同叶，不强并（尾段序列不等）


# ── score_row(canon=True) 修正假象 ───────────────────────────────────────

def test_canon_fixes_schema_prefix_artifact():
    g, p, ct = _row(["agg_events_iceberg"], [], ["gcp_primary_staging.agg_events_iceberg"], [],
                    "insert into gcp_primary_staging.agg_events_iceberg select * from src")
    base = score_row(g, p, ct, canon=False)
    fixed = score_row(g, p, ct, canon=True)
    assert base["tp"] == 0 and base["fp"] == 1 and base["fn"] == 1   # 旧口径：双错
    assert fixed["tp"] == 1 and fixed["fp"] == 0 and fixed["fn"] == 0  # canon：命中


def test_canon_keeps_genuine_miss_honest():
    """粒度真错在 canon 下仍记 fp+fn，不被洗白。"""
    g, p, ct = _row([], ["test_dataset.test_table"], [], ["test_dataset"], "create table test_dataset.test_table")
    fixed = score_row(g, p, ct, canon=True)
    assert fixed["tp"] == 0 and fixed["fp"] == 1 and fixed["fn"] == 1


def test_canon_backward_compatible_default():
    """默认 canon=False 与历史逐字一致（同名精确匹配场景两口径同值）。"""
    g, p, ct = _row(["orders"], ["dwd.fact"], ["orders"], ["dwd.fact"],
                    "insert into dwd.fact select * from orders")
    assert score_row(g, p, ct, canon=False) == score_row(g, p, ct, canon=True)


def test_canon_direction_under_qualification():
    """方向在限定名差异下也应正确对齐。"""
    g, p, ct = _row(["raw.orders"], ["fact"], ["orders"], ["mart.fact"],
                    "insert into mart.fact select * from raw.orders")
    fixed = score_row(g, p, ct, canon=True)
    assert fixed["dir_total"] == 2 and fixed["dir_correct"] == 2   # 两表方向都对


def test_canon_no_double_count_one_to_one():
    """一对一对齐：两个 pred 都后缀命中同一 gold，只记 1 tp、另一个记 fp。"""
    g, p, ct = _row(["t"], [], ["a.t", "b.c.t"], [], "x")
    fixed = score_row(g, p, ct, canon=True)
    assert fixed["tp"] == 1 and fixed["fp"] == 1 and fixed["fn"] == 0
