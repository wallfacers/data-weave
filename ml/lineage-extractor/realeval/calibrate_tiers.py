"""063 T007：分级置信度校准常量冻结（gold C 嵌套 CV 去偏）。

research R1：无独立非泄漏带标集（测试集 A 已删 / pool-c-held⊇gold C / pool-c-train 模型
训练过泄漏）→ 退回 054 已验证的 **gold C 嵌套 CV 去偏**（clarify 原选项 B）：
  · 部署固化常量 = 全 gold C 点估计（`confidence_calibration.calibrate`）；
  · 报告精度 = 留一/k 折 held-out（`conf_calibration_cv.cross_validate`，级序+前沿切点留出）。

模型预测先过**语义 grounding**（与部署管线一致，059 ③），按行 idx 对齐（gold C 与 preds 同序）。
输出：`--emit-constants` 写 `tier_classify_constants.py`（冻结常量供 serving 只读），`--report` md。

用法: PYTHONPATH=. python3 realeval/calibrate_tiers.py \
        --gold realeval/gold/real-c-arbitrated.jsonl --model out/preds-c-run-059-runc.jsonl \
        --k 5 --thr 0.95 --report out/calibrate-tiers.md \
        --emit-constants realeval/tier_classify_constants.py
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.confidence_calibration import TIERS, TIER_LABEL, calibrate, best_frontier
from realeval.conf_calibration_cv import cross_validate, sweep_fit_threshold
from realeval.semantic_grounding import filter_pred_semantic


def _load_rows(path: str) -> list[dict]:
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _extract_pred(rec: dict) -> dict:
    """兼容两种预测布局：顶层 {reads,writes} 或嵌套 {pred:{reads,writes}}。"""
    src = rec.get("pred") if isinstance(rec.get("pred"), dict) else rec
    return {"reads": src.get("reads") or [], "writes": src.get("writes") or []}


def build_model_by_idx(gold_rows: list[dict], pred_rows: list[dict]) -> dict:
    """按行 idx 对齐（gold C 与 preds 同序），模型预测先过语义 grounding（部署管线一致）。"""
    if len(gold_rows) != len(pred_rows):
        raise SystemExit(f"gold({len(gold_rows)}) 与 preds({len(pred_rows)}) 行数不一致，无法按 idx 对齐")
    mbi = {}
    for i, (g, p) in enumerate(zip(gold_rows, pred_rows)):
        grounded = filter_pred_semantic(_extract_pred(p), g["content"])
        mbi[i] = {"reads": grounded["reads"], "writes": grounded["writes"]}
    return mbi


def calibrate_frozen(gold_rows: list[dict], model_by_idx: dict, k: int, thr: float) -> dict:
    """点估计（部署常量）+ CV 去偏（报告口径）+ fit_thr 扫描（部署曲线）。"""
    point = calibrate(gold_rows, model_by_idx)              # 全 gold C 点估计
    cv5 = cross_validate(gold_rows, model_by_idx, k, thr)   # k 折 held-out
    loo = cross_validate(gold_rows, model_by_idx, 0, thr)   # 留一 held-out
    sweep = sweep_fit_threshold(gold_rows, model_by_idx, k, [0.99, 0.97, 0.95, 0.90, 0.85])
    front = best_frontier(point, thr)                       # 点估计前沿（对照）
    return {"point": point, "cv5": cv5, "loo": loo, "sweep": sweep, "thr": thr,
            "point_frontier": front}


def frozen_constants(cal: dict) -> dict:
    """从点估计抽冻结常量：每级 precision + 校准序（部署用）；CV 前沿附注（报告用）。"""
    tier = cal["point"]["tier"]
    return {
        "precision": {t: tier[t]["precision"] for t in TIERS if tier[t]["n"] > 0},
        "n": {t: tier[t]["n"] for t in TIERS if tier[t]["n"] > 0},
        "calibrated_order": cal["point"]["calibrated_order"],
        "cv_modal_accept": cal["cv5"]["modal_accept"],
        "cv_pooled_precision": cal["cv5"]["pooled_precision"],
        "cv_pooled_recall": cal["cv5"]["pooled_recall"],
        "thr": cal["thr"],
    }


def emit_constants(fc: dict, cal: dict, path: str) -> None:
    """写死冻结常量模块（serving 只读；变更=重跑本脚本覆写）。

    治理诚实（R1/CV）：自动采纳集用 **CV 去偏扫描**（held-out precision≥阈的最大召回集），
    而非样本内乐观累计——SC-002 按构造成立。`autoaccept_tiers(thr)` 据此选级集。
    """
    order = fc["calibrated_order"]
    # CV 扫描表：(fit_thr, accept_tiers, held-out precision, held-out recall)，按 fit_thr 降序。
    sweep = sorted(cal["sweep"], key=lambda s: -s["fit_thr"])
    sweep_rows = [(s["fit_thr"], list(s["modal_accept"]), round(s["precision"], 4),
                   round(s["recall"], 4)) for s in sweep]
    lines = [
        '"""063 冻结校准常量——由 realeval/calibrate_tiers.py 在 gold C 上产出（勿手改）。',
        "",
        "research R1：gold C 嵌套 CV 去偏（无独立非泄漏带标集）。",
        "_FROZEN_PRECISION = 全 gold C 点估计每级 precision（部署 confidence，供复核层排序）；",
        "_CALIBRATED_ORDER = 点估计校准序；",
        "_CV_SWEEP = fit_thr 扫描 (fit_thr, 采纳级集, held-out precision, held-out recall)——",
        "  自动采纳集由此选（governance-honest：held-out precision≥治理阈的最大召回集）。",
        f"注（CV 诚实边界）：thr={fc['thr']} 下 held-out 仅 sql_qual 可守（n=7，折间抖 0.71-1.0）；",
        "  样本内累计乐观（sql_qual+model_bare=0.922）不泛化 → 自动层在任何 ≥0.90 阈下 recall~0.05，",
        "  召回回收价值全在复核层。CV 只校同分布偏置、不覆盖分布漂移。",
        '"""',
        "",
        "_FROZEN_PRECISION = {",
        *[f"    {t!r}: {fc['precision'][t]:.4f},  # n={fc['n'][t]}" for t in order],
        "}",
        "",
        f"_CALIBRATED_ORDER = {order!r}",
        "",
        "# (fit_thr, accept_tiers, heldout_precision, heldout_recall)",
        "_CV_SWEEP = [",
        *[f"    ({ft}, {acc!r}, {p}, {r})," for ft, acc, p, r in sweep_rows],
        "]",
        "",
        "",
        "def frozen_precision(tier: str) -> float:",
        '    """复核层排序用：该级点估计 precision（未定级→0.0，归复核）。"""',
        "    return _FROZEN_PRECISION.get(tier, 0.0)",
        "",
        "",
        "def autoaccept_tiers(thr: float) -> list[str]:",
        '    """治理诚实的自动采纳级集：CV 扫描里 held-out precision≥thr 的最大召回集。',
        "",
        "    thr<=0 → 全并集进自动（所有定级 tier）。无满足者 → 空（全进复核）。",
        '    """',
        "    if thr <= 0:",
        "        return list(_CALIBRATED_ORDER)",
        "    ok = [row for row in _CV_SWEEP if row[2] >= thr]",
        "    if not ok:",
        "        return []",
        "    best = max(ok, key=lambda row: row[3])  # 最大 held-out recall",
        "    return list(best[1])",
        "",
    ]
    Path(path).write_text("\n".join(lines), encoding="utf-8")


def render(cal: dict) -> str:
    p, cv5, loo = cal["point"], cal["cv5"], cal["loo"]
    L = [
        "# 063 分级置信度校准（gold C 嵌套 CV 去偏）",
        "",
        "research R1：无独立非泄漏带标集（测试集 A 已删 / pool-c-held⊇gold C / pool-c-train 泄漏）",
        "→ gold C 嵌套 CV 去偏。**部署常量=全 gold C 点估计；报告精度=留一/k 折 held-out（无偏）**。",
        "",
        f"- 非空金标脚本 {p['n_nonempty']}，金标真表 {p['gold_total']}（canon）。治理阈 thr={cal['thr']}。",
        "",
        "## 逐级经验 precision（全 gold C 点估计 = 部署 confidence 常量）",
        "",
        "| 置信级 | 候选边数 | 点估计 precision |",
        "| --- | --- | --- |",
    ]
    for t in TIERS:
        s = p["tier"][t]
        pr = "—" if s["precision"] is None else f"{s['precision']:.3f}"
        L.append(f"| {TIER_LABEL[t]} | {s['n']} | {pr} |")
    L += [
        "",
        f"**部署校准序**：`{' > '.join(TIER_LABEL[t] for t in p['calibrated_order'])}`",
        "",
        "## CV held-out 前沿（诚实口径，SC-002）",
        "",
        "| 口径 | pooled precision | pooled recall | 采纳级集(众数) |",
        "| --- | --- | --- | --- |",
        f"| k=5 折 | **{cv5['pooled_precision']:.3f}** | {cv5['pooled_recall']:.3f} | "
        f"{' + '.join(TIER_LABEL.get(t,t) for t in cv5['modal_accept']) or '（空）'} |",
        f"| 留一(LOO) | {loo['pooled_precision']:.3f} | {loo['pooled_recall']:.3f} | "
        f"{' + '.join(TIER_LABEL.get(t,t) for t in loo['modal_accept']) or '（空）'} |",
        "",
        f"- 折间 precision 抖动 k=5：[{cv5['fold_precision_min']:.3f}, {cv5['fold_precision_max']:.3f}]"
        f"（样本小，如实披露）。",
        "",
        "## fit_thr 扫描（消除样本内乐观后的部署 precision↔recall 曲线）",
        "",
        "| fit_thr | held-out precision | held-out recall | 采纳级集(众数) |",
        "| --- | --- | --- | --- |",
    ]
    for s in cal["sweep"]:
        L.append(f"| {s['fit_thr']:.2f} | {s['precision']:.3f} | {s['recall']:.3f} | "
                 f"{' + '.join(TIER_LABEL.get(t,t) for t in s['modal_accept']) or '（空）'} |")
    L += [
        "",
        "> 诚实边界：CV 只校同分布（gold C 内）乐观偏置，**不覆盖分布漂移**；真·独立 fresh 集仍待凭据/预算。",
    ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--model", required=True)
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--thr", type=float, default=0.95)
    ap.add_argument("--report", default="out/calibrate-tiers.md")
    ap.add_argument("--emit-constants", default="realeval/tier_classify_constants.py")
    args = ap.parse_args(argv)

    gold = _load_rows(args.gold)
    preds = _load_rows(args.model)
    mbi = build_model_by_idx(gold, preds)
    cal = calibrate_frozen(gold, mbi, args.k, args.thr)
    fc = frozen_constants(cal)

    report = render(cal)
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    if args.emit_constants:
        emit_constants(fc, cal, args.emit_constants)
    print(report)
    print(f"[emit] {args.emit_constants}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
