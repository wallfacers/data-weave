"""052 分层信封纯函数单测（无 GPU）：Tier 1 gated/ungated、并集召回、一致层 precision。"""
from __future__ import annotations

from realeval.tiered_envelope import _tier1, _union, analyze


def _row(content, reads, writes):
    return {"content": content, "task_type": "PYTHON",
            "labels": {"reads": [{"table": t} for t in reads],
                       "writes": [{"table": t} for t in writes]}}


def test_tier1_gated_cleaner_than_ungated_on_doc_noise():
    """docstring 示例 SQL：ungated 抓成 FP，gated 剔除 → gated precision 不低于 ungated。"""
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"]),
            _row('"""example: CREATE TABLE demo.t AS SELECT * FROM demo.s"""\nx=1', [], [])]
    ne = [r for r in rows if r["labels"]["reads"] or r["labels"]["writes"]]
    g = _tier1(rows, ne, gated=True)
    u = _tier1(rows, ne, gated=False)
    assert g["full"]["precision"] >= u["full"]["precision"]
    assert g["full"]["hallucination"] <= u["full"]["hallucination"]


def test_union_recall_covers_both_channels():
    """SQL 通道抓 raw.s，模型抓 df_out（SQL 打空脚本）→ 并集召回覆盖两者。"""
    rows = [
        _row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"]),
        _row('df=spark.read.table("x")\ndf.write.saveAsTable("out.t")', [], ["out.t"]),
    ]
    model = {0: {"reads": [], "writes": []},
             1: {"reads": [], "writes": [{"table": "out.t"}]}}   # 模型救回 SQL 打空的第 2 条
    u = _union(rows, model)
    assert u["union_recall"] > 0.9          # raw.s+mart.f（SQL）+ out.t（模型）都被 surface


def test_agreement_layer_high_precision():
    """双通道都认的一致层：都对 → precision 1.0。"""
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "mart.f"}]}}
    u = _union(rows, model)
    assert u["agree_precision"] == 1.0
    assert u["n_agree"] >= 2                 # raw.s 与 mart.f 均被两通道认


def test_analyze_shape_with_and_without_model():
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    assert "union" not in analyze(rows)
    a = analyze(rows, {0: {"reads": [{"table": "raw.s"}], "writes": []}})
    assert "union" in a and 0.0 <= a["union"]["union_recall"] <= 1.0
