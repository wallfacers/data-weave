"""065 T004（US1）：统计诚实层单测——确定性 / CI 含点 / 口径一致 / 配对 / McNemar。"""
from __future__ import annotations

import random

from eval.metrics import aggregate
from eval.significance import (
    _agg_from_sums,
    bootstrap_metric_ci,
    mcnemar_exact,
    paired_bootstrap_diff,
)


def _c(tp, fp, fn, *, pred_total=None, dir_total=1, dir_correct=1, halluc=0):
    # invalid=0：真实 score_row 总会带此键（metrics.aggregate 对它求和）
    return dict(tp=tp, fp=fp, fn=fn, halluc=halluc,
                pred_total=pred_total if pred_total is not None else tp + fp,
                dir_total=dir_total, dir_correct=dir_correct, invalid=0)


def test_agg_from_sums_matches_metrics_aggregate():
    """bootstrap 内部口径必须与 metrics.aggregate 逐字一致（防漂移）。"""
    rng = random.Random(0)
    counts = [_c(rng.randint(0, 5), rng.randint(0, 4), rng.randint(0, 4),
                 dir_total=rng.randint(1, 3), dir_correct=rng.randint(0, 1)) for _ in range(20)]
    s = [sum(c[k] for c in counts) for k in
         ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct")]
    a_ref = aggregate(counts)
    a_boot = _agg_from_sums(s)
    for m in ("precision", "recall", "f1", "hallucination", "direction_acc"):
        assert abs(a_ref[m] - a_boot[m]) < 1e-12, m


def test_bootstrap_deterministic_same_seed():
    counts = [_c(3, 1, 1) for _ in range(30)]
    a = bootstrap_metric_ci(counts, "precision", n_resamples=2000, seed=42)
    b = bootstrap_metric_ci(counts, "precision", n_resamples=2000, seed=42)
    assert a == b  # 逐位一致（FR-001）


def test_bootstrap_ci_contains_point_and_ordered():
    counts = [_c(4, 1, 1) for _ in range(50)]
    r = bootstrap_metric_ci(counts, "recall", n_resamples=3000, seed=7)
    assert r["lo95"] <= r["point"] <= r["hi95"]
    assert not r["degenerate"]


def test_bootstrap_degenerate_on_tiny_sample():
    assert bootstrap_metric_ci([], "precision")["degenerate"] is True
    r1 = bootstrap_metric_ci([_c(1, 0, 0)], "precision")
    assert r1["degenerate"] is True and r1["lo95"] == r1["hi95"] == r1["point"]


def test_paired_diff_detects_clear_superiority():
    # A 全对（无 fp/fn），B 全错召回 → A recall 显著高于 B
    a = [_c(1, 0, 0) for _ in range(40)]
    b = [_c(0, 0, 1) for _ in range(40)]
    r = paired_bootstrap_diff(a, b, "recall", n_resamples=3000, seed=1)
    assert r["diff"] > 0.5 and r["significant"] is True
    assert not (r["lo95"] <= 0.0 <= r["hi95"])


def test_paired_diff_not_significant_when_identical():
    a = [_c(2, 1, 1) for _ in range(40)]
    r = paired_bootstrap_diff(a, list(a), "precision", n_resamples=3000, seed=1)
    assert abs(r["diff"]) < 1e-9 and r["significant"] is False


def test_mcnemar_significant_when_all_discordant_one_way():
    ca = [True] * 12 + [True] * 8   # a 全对
    cb = [False] * 12 + [True] * 8  # 前 12 个 b 错 → b=12,c=0
    r = mcnemar_exact(ca, cb)
    assert r["b"] == 12 and r["c"] == 0 and r["significant"] is True and r["p_value"] < 0.05


def test_mcnemar_not_significant_when_balanced():
    ca = [True] * 5 + [False] * 5
    cb = [False] * 5 + [True] * 5   # b=5,c=5 对称
    r = mcnemar_exact(ca, cb)
    assert r["b"] == 5 and r["c"] == 5 and r["p_value"] == 1.0 and r["significant"] is False


def test_mcnemar_no_discordance_gives_p_one():
    r = mcnemar_exact([True, False, True], [True, False, True])
    assert r["n_discordant"] == 0 and r["p_value"] == 1.0 and r["significant"] is False
