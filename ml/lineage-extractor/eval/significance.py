"""065 T005（W1）：统计诚实层——脚本级 bootstrap CI + 配对差值 + McNemar 精确检验。

只消费 `eval.metrics` 的逐脚本 counts，**不 import 也不修改打分逻辑**（守 FR-003）。全部
确定性（固定 seed → 逐位可复现，FR-001）。

指标口径与 `eval.metrics.aggregate` 逐字一致（`_agg_from_sums` 复刻其公式，并有单测钉住一致性）；
bootstrap 用 multinomial 权重对「脚本」重采样后向量化重聚合，避免十万次 Python 循环。

McNemar 用手写精确二项检验（`math.comb`）——精确、零额外依赖（不引 scipy）。这是对 research R6
「加 scipy」的修订：精确二项等价于 scipy.stats.binomtest 的双侧精确 p，但免一个重依赖，
利于无凭据可复现（FR-006/FR-008）。
"""
from __future__ import annotations

import math

import numpy as np

from eval.metrics import aggregate

# 与 metrics.aggregate 对齐的计数分量（不含 invalid，指标不用）
_KEYS = ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct")
_METRICS = ("precision", "recall", "f1", "hallucination", "direction_acc")


def _matrix(counts) -> np.ndarray:
    return np.array([[float(c[k]) for k in _KEYS] for c in counts], dtype=float)


def _agg_from_sums(s) -> dict:
    """从 7 维求和向量算指标——必须与 metrics.aggregate 逐字一致（test 钉住）。"""
    tp, fp, fn, halluc, pred_total, dir_total, dir_correct = s
    prec = tp / (tp + fp) if (tp + fp) else 1.0
    rec = tp / (tp + fn) if (tp + fn) else 1.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
    return dict(
        precision=prec, recall=rec, f1=f1,
        hallucination=halluc / pred_total if pred_total else 0.0,
        direction_acc=dir_correct / dir_total if dir_total else 1.0,
    )


def _boot_weights(n: int, n_resamples: int, seed: int) -> np.ndarray:
    """(n_resamples × n) multinomial 重采样权重，每行和为 n。"""
    rng = np.random.default_rng(seed)
    return rng.multinomial(n, np.full(n, 1.0 / n), size=n_resamples).astype(float)


def bootstrap_metric_ci(counts, metric, *, n_resamples: int = 10000,
                        seed: int = 20260712, alpha: float = 0.05) -> dict:
    """脚本级 percentile bootstrap 95% CI。契约见 contracts/significance-api.md。"""
    counts = list(counts)
    n = len(counts)
    point = aggregate(counts)[metric] if n else 0.0
    if n <= 1:
        return dict(metric=metric, point=point, lo95=point, hi95=point,
                    n_scripts=n, n_resamples=0, seed=seed, degenerate=True)
    M = _matrix(counts)                       # n × 7
    W = _boot_weights(n, n_resamples, seed)   # R × n
    S = W @ M                                  # R × 7（每次重采样的分量和）
    vals = np.array([_agg_from_sums(row)[metric] for row in S])
    lo, hi = np.quantile(vals, [alpha / 2.0, 1.0 - alpha / 2.0])
    return dict(metric=metric, point=float(point), lo95=float(lo), hi95=float(hi),
                n_scripts=n, n_resamples=n_resamples, seed=seed, degenerate=False)


def paired_bootstrap_diff(counts_a, counts_b, metric, *, n_resamples: int = 10000,
                          seed: int = 20260712, alpha: float = 0.05) -> dict:
    """配对 bootstrap 差值（a−b）95% CI；同一批脚本索引同步作用于 a、b。"""
    ca, cb = list(counts_a), list(counts_b)
    if len(ca) != len(cb):
        raise ValueError(f"配对检验要求等长同序：a={len(ca)} b={len(cb)}")
    n = len(ca)
    diff_point = aggregate(ca)[metric] - aggregate(cb)[metric] if n else 0.0
    if n <= 1:
        return dict(metric=metric, diff=diff_point, lo95=diff_point, hi95=diff_point,
                    significant=False, n_scripts=n, n_resamples=0, seed=seed, degenerate=True)
    MA, MB = _matrix(ca), _matrix(cb)
    W = _boot_weights(n, n_resamples, seed)   # 共享权重 = 配对
    SA, SB = W @ MA, W @ MB
    diffs = np.array([_agg_from_sums(a)[metric] - _agg_from_sums(b)[metric]
                      for a, b in zip(SA, SB)])
    lo, hi = np.quantile(diffs, [alpha / 2.0, 1.0 - alpha / 2.0])
    significant = not (lo <= 0.0 <= hi)
    return dict(metric=metric, diff=float(diff_point), lo95=float(lo), hi95=float(hi),
                significant=bool(significant), n_scripts=n, n_resamples=n_resamples,
                seed=seed, degenerate=False)


def mcnemar_exact(correct_a, correct_b, *, alpha: float = 0.05) -> dict:
    """逐脚本精确匹配对错的 McNemar 精确二项检验（双侧，p0=0.5）。

    b = a 对 b 错、c = a 错 b 对；并列（都对/都错）不计入。b+c==0 → p=1.0（无差异证据）。
    """
    a = list(correct_a)
    b_ = list(correct_b)
    if len(a) != len(b_):
        raise ValueError(f"配对检验要求等长同序：a={len(a)} b={len(b_)}")
    b = sum(1 for x, y in zip(a, b_) if x and not y)
    c = sum(1 for x, y in zip(a, b_) if (not x) and y)
    n = b + c
    if n == 0:
        p = 1.0
    else:
        k = min(b, c)
        tail = sum(math.comb(n, i) for i in range(0, k + 1)) * (0.5 ** n)
        p = min(1.0, 2.0 * tail)
    return dict(b=b, c=c, n_discordant=n, p_value=float(p),
                significant=bool(n > 0 and p < alpha))
