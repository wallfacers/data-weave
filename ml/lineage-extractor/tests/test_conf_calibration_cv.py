"""测试集 B（CV de-bias）纯函数单测（无 GPU）：折分/拟合采纳级集/留出评测/汇总。"""
from __future__ import annotations

from realeval.conf_calibration_cv import (
    _build_scripts,
    _eval_fold,
    _fit_accept_set,
    cross_validate,
)


def _row(content, reads, writes):
    return {"content": content,
            "labels": {"reads": [{"table": t} for t in reads],
                       "writes": [{"table": t} for t in writes]}}


def test_fit_accept_set_takes_high_precision_prefix():
    """一致层全对 + 模型裸名有幻觉 → thr=0.95 采纳级集含 agree、不含 model_bare。"""
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "mart.f"}, {"table": "junk"}]}}
    scripts = _build_scripts(rows, model)
    acc = _fit_accept_set(scripts, 0.95)
    assert "agree" in acc
    assert "model_bare" not in acc


def test_eval_fold_precision_recall():
    rows = [_row('spark.sql("INSERT INTO mart.f SELECT * FROM raw.s")', ["raw.s"], ["mart.f"])]
    model = {0: {"reads": [{"table": "raw.s"}], "writes": [{"table": "mart.f"}]}}
    scripts = _build_scripts(rows, model)
    e = _eval_fold(scripts, {"agree"})
    assert e["gold_tot"] == 2
    assert e["n_corr"] == e["n_acc"]           # 全对
    assert e["covered"] == 2


def test_cross_validate_shape_and_bounds():
    rows = [_row(f'spark.sql("INSERT INTO m.f{i} SELECT * FROM r.s{i}")', [f"r.s{i}"], [f"m.f{i}"])
            for i in range(10)]
    model = {i: {"reads": [{"table": f"r.s{i}"}], "writes": [{"table": f"m.f{i}"}]} for i in range(10)}
    cv = cross_validate(rows, model, k=5, thr=0.95)
    assert cv["k"] == 5 and cv["n_scripts"] == 10
    assert len(cv["per_fold"]) == 5
    assert 0.0 <= cv["pooled_precision"] <= 1.0
    assert 0.0 <= cv["pooled_recall"] <= 1.0


def test_loo_when_k_zero_uses_n_folds():
    rows = [_row(f'spark.sql("INSERT INTO m.f{i} SELECT * FROM r.s{i}")', [f"r.s{i}"], [f"m.f{i}"])
            for i in range(6)]
    model = {i: {"reads": [{"table": f"r.s{i}"}], "writes": [{"table": f"m.f{i}"}]} for i in range(6)}
    cv = cross_validate(rows, model, k=0, thr=0.95)
    assert cv["k"] == 6                          # 留一 = n 折
    assert len(cv["per_fold"]) == 6


def test_deterministic_reproducible():
    """同输入两次 CV 完全一致（折分 idx%k，无 RNG）。"""
    rows = [_row(f'spark.sql("INSERT INTO m.f{i} SELECT * FROM r.s{i}")', [f"r.s{i}"], [f"m.f{i}"])
            for i in range(8)]
    model = {i: {"reads": [{"table": f"r.s{i}"}], "writes": [{"table": f"m.f{i}"}]} for i in range(8)}
    a = cross_validate(rows, model, k=4, thr=0.95)
    b = cross_validate(rows, model, k=4, thr=0.95)
    assert a["pooled_precision"] == b["pooled_precision"]
    assert a["pooled_recall"] == b["pooled_recall"]
    assert a["modal_accept"] == b["modal_accept"]


def test_empty_gold_rows_excluded():
    rows = [_row('spark.sql("INSERT INTO m.f SELECT * FROM r.s")', ["r.s"], ["m.f"]),
            _row('x=1  # no sql', [], [])]
    model = {0: {"reads": [{"table": "r.s"}], "writes": [{"table": "m.f"}]}, 1: {"reads": [], "writes": []}}
    scripts = _build_scripts(rows, model)
    assert len(scripts) == 1                     # 空金标行剔除
