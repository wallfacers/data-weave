"""052 分级置信度校准纯函数单测（无 GPU）：候选边分级、逐级 precision、累计操作点、治理前沿。"""
from __future__ import annotations

from realeval.confidence_calibration import (
    TIERS,
    _canonical_edges,
    best_frontier,
    calibrate,
)


def _row(content, reads, writes):
    return {"content": content,
            "labels": {"reads": [{"table": t} for t in reads],
                       "writes": [{"table": t} for t in writes]}}


def test_canonical_edges_tiers_and_canon_dedup():
    """S={db.t, raw.s}、M={t, other}：db.t 与 M 的 t canon 互认 → agree（不重复计 t）；
    raw.s 无模型 → sql_qual；other 仅模型裸名 → model_bare。"""
    edges = _canonical_edges({"db.t", "raw.s"}, {"t", "other"})
    d = dict(edges)
    assert d["db.t"] == "agree"          # db.t ⟷ t canon 互认
    assert d["raw.s"] == "sql_qual"      # SQL-only 限定名
    assert d["other"] == "model_bare"    # 模型-only 裸名
    assert "t" not in d                  # canon 去重：t 已被 db.t 吸收，不另立 model 边


def test_sql_bare_vs_qual_split():
    edges = dict(_canonical_edges({"bare", "sch.q"}, set()))
    assert edges["bare"] == "sql_bare"
    assert edges["sch.q"] == "sql_qual"


def test_tier_precision_monotone_on_clean_case():
    """一致层全对、模型-only 裸名幻觉 → agree precision 1.0 > model_bare precision 0.0。"""
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    # 模型：认对 raw.s/mart.f（→一致），另幻觉一个裸名 ghost（→model_bare，错）。
    model = {0: {"reads": [{"table": "raw.s"}, {"table": "ghost"}],
                 "writes": [{"table": "mart.f"}]}}
    cal = calibrate(rows, model)
    assert cal["tier"]["agree"]["precision"] == 1.0
    assert cal["tier"]["model_bare"]["n"] == 1
    assert cal["tier"]["model_bare"]["precision"] == 0.0


def test_cumulative_calibrated_precision_monotone_nonincreasing():
    """校准序（按经验 precision 降序）下，累计采纳更多级 → precision 单调不增。"""
    rows = [
        _row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"]),
        _row('df=x\ndf.write.saveAsTable("out.t")', [], ["out.t"]),
    ]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "mart.f"}, {"table": "junk"}]},
             1: {"reads": [], "writes": [{"table": "out.t"}]}}
    cal = calibrate(rows, model)
    ps = [c["precision"] for c in cal["cumulative_calibrated"]]
    assert all(ps[i] >= ps[i + 1] - 1e-9 for i in range(len(ps) - 1))
    assert len(cal["cumulative"]) == len(cal["apriori_order"])


def test_calibrated_order_sorts_by_empirical_precision():
    """model_qual 全对(1.0) 应排在 model_bare(有幻觉) 之前——校准序由经验 precision 决定。"""
    rows = [
        _row('df=x\ndf.write.saveAsTable("sch.good")', [], ["sch.good"]),   # 模型限定名·对
        _row('df=y\ndf.write.saveAsTable("real")', [], ["real"]),           # 模型裸名·对
    ]
    model = {0: {"reads": [], "writes": [{"table": "sch.good"}]},
             1: {"reads": [], "writes": [{"table": "real"}, {"table": "ghost"}]}}  # ghost 裸名幻觉
    cal = calibrate(rows, model)
    assert cal["tier"]["model_qual"]["precision"] == 1.0
    assert cal["tier"]["model_bare"]["precision"] == 0.5   # real 对 / ghost 错
    assert cal["calibrated_order"].index("model_qual") < cal["calibrated_order"].index("model_bare")


def test_cumulative_recall_monotone_nondecreasing():
    """采纳更多级 → 召回单调不减（覆盖只会增加）。"""
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "extra.w"}]}}
    cal = calibrate(rows, model)
    rs = [c["recall"] for c in cal["cumulative_calibrated"]]
    assert all(rs[i] <= rs[i + 1] + 1e-9 for i in range(len(rs) - 1))


def test_best_frontier_picks_highest_recall_within_threshold():
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "mart.f"}]}}
    cal = calibrate(rows, model)
    front = best_frontier(cal, 0.95)
    assert front is not None
    assert front["precision"] >= 0.95
    # 全对语料：前沿召回应达满（agree 已覆盖全部金标）
    assert front["recall"] == 1.0
