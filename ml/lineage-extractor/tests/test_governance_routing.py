"""068 T020（US3/FR-015）：治理路由分层契约——auto(3-of-3)/review(2-of-3) 划分正确、
auto∪review=全集、精度只在 auto 层算。见 contracts/metrics-orthogonality.md。"""
from __future__ import annotations

from realeval.governance_routing import tier_edges, auto_layer_scores


def _tri_row(reads_agree):
    # reads_agree: {table: agree_count}；n_teachers=3
    reads = [{"table": t, "columns": None} for t in reads_agree]
    return {"labels": {"reads": reads, "writes": []},
            "consensus": {"agree": dict(reads_agree), "n_teachers": 3}}


def test_tiering_counts_and_partition():
    rows = [_tri_row({"a": 3, "b": 2}), _tri_row({"c": 3})]
    te = tier_edges(rows)
    assert te["auto"] == 2 and te["review"] == 1 and te["total"] == 3
    # auto∪review 是全集划分
    assert te["auto"] + te["review"] == te["total"]


def test_auto_layer_precision_only_on_unanimous():
    # unanimous gold（只含 agree==3 边）：a,c；模型预测 a 对、c 漏、d 多
    unan = [{"labels": {"reads": [{"table": "a", "columns": None}], "writes": []}, "content": "select from a"},
            {"labels": {"reads": [{"table": "c", "columns": None}], "writes": []}, "content": "select from c"}]
    preds = [{"reads": [{"table": "a"}], "writes": []},
             {"reads": [{"table": "d"}], "writes": []}]
    sc = auto_layer_scores(unan, preds)
    assert sc["tp"] == 1 and sc["fn"] == 1 and sc["fp"] == 1
    assert abs(sc["precision"] - 0.5) < 1e-9 and abs(sc["recall"] - 0.5) < 1e-9


def test_empty_gold_no_crash():
    assert tier_edges([]) == {"auto": 0, "review": 0, "total": 0}
    assert auto_layer_scores([], [])["precision"] == 0.0
