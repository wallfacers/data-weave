"""063 冻结校准常量——由 realeval/calibrate_tiers.py 在 gold C 上产出（勿手改）。

research R1：gold C 嵌套 CV 去偏（无独立非泄漏带标集）。
_FROZEN_PRECISION = 全 gold C 点估计每级 precision（部署 confidence，供复核层排序）；
_CALIBRATED_ORDER = 点估计校准序；
_CV_SWEEP = fit_thr 扫描 (fit_thr, 采纳级集, held-out precision, held-out recall)——
  自动采纳集由此选（governance-honest：held-out precision≥治理阈的最大召回集）。
注（CV 诚实边界）：thr=0.95 下 held-out 仅 sql_qual 可守（n=7，折间抖 0.71-1.0）；
  样本内累计乐观（sql_qual+model_bare=0.922）不泛化 → 自动层在任何 ≥0.90 阈下 recall~0.05，
  召回回收价值全在复核层。CV 只校同分布偏置、不覆盖分布漂移。
"""

_FROZEN_PRECISION = {
    'sql_qual': 1.0000,  # n=7
    'model_bare': 0.9091,  # n=44
    'agree': 0.8696,  # n=23
    'model_qual': 0.8148,  # n=54
    'sql_bare': 0.1818,  # n=11
}

_CALIBRATED_ORDER = ['sql_qual', 'model_bare', 'agree', 'model_qual', 'sql_bare']

# (fit_thr, accept_tiers, heldout_precision, heldout_recall)
_CV_SWEEP = [
    (0.99, ['sql_qual'], 1.0, 0.0473),
    (0.97, ['sql_qual'], 1.0, 0.0473),
    (0.95, ['sql_qual', 'model_bare'], 0.7857, 0.1486),
    (0.9, ['sql_qual', 'model_bare', 'agree'], 0.8125, 0.3514),
    (0.85, ['sql_qual', 'model_bare', 'agree', 'model_qual'], 0.8699, 0.723),
]


def frozen_precision(tier: str) -> float:
    """复核层排序用：该级点估计 precision（未定级→0.0，归复核）。"""
    return _FROZEN_PRECISION.get(tier, 0.0)


def autoaccept_tiers(thr: float) -> list[str]:
    """治理诚实的自动采纳级集：CV 扫描里 held-out precision≥thr 的最大召回集。

    thr<=0 → 全并集进自动（所有定级 tier）。无满足者 → 空（全进复核）。
    """
    if thr <= 0:
        return list(_CALIBRATED_ORDER)
    ok = [row for row in _CV_SWEEP if row[2] >= thr]
    if not ok:
        return []
    best = max(ok, key=lambda row: row[3])  # 最大 held-out recall
    return list(best[1])
