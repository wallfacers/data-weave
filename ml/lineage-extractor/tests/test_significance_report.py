"""065 T006（US1）：significance 报告编排 smoke——纯函数装配，无需 GPU/数据文件。"""
from __future__ import annotations

from realeval.significance_report import build_report


def _row(reads, writes, content, **kw):
    return dict(labels={"reads": [{"table": t} for t in reads],
                        "writes": [{"table": t} for t in writes]},
                content=content, is_empty=(not reads and not writes), **kw)


def test_build_report_assembles_ci_and_pairwise_sections():
    gold = [_row(["ods_o"], ["dwd_o"], "insert into dwd_o select * from ods_o", type="sql"),
            _row(["ods_u"], ["dwd_u"], "insert into dwd_u select * from ods_u", type="sql"),
            _row(["ods_p"], ["dwd_p"], "insert into dwd_p select * from ods_p", type="sql")]
    # 3B 全对；teacher 有一个漏
    p3b = [{"reads": [{"table": r["labels"]["reads"][0]["table"]}],
            "writes": [{"table": r["labels"]["writes"][0]["table"]}]} for r in gold]
    pte = [dict(p3b[0]), dict(p3b[1]), {"reads": [], "writes": []}]
    md = build_report(gold, {"model-3b": p3b, "teacher-m1": pte},
                      primary="model-3b", n_resamples=500)
    assert "统计诚实层" in md
    assert "95% 置信区间" in md
    assert "model-3b" in md and "teacher-m1" in md
    assert "McNemar" in md
    assert "诚实声明" in md


def test_build_report_survives_all_empty_gold():
    gold = [_row([], [], "-- noop", type="sql")]
    preds = [{"reads": [], "writes": []}]
    md = build_report(gold, {"model-3b": preds}, primary="model-3b", n_resamples=100)
    assert "统计诚实层" in md  # 不崩（非空子集为 0 也能出报告骨架）
