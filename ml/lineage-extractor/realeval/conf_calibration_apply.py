"""054 测试集 B（fresh-repo held-out）：把 gold A **冻结**的分级校准梯度原样应用到 B。

`conf_calibration_cv` 用 k 折 CV 校**同分布**拟合偏置；本脚本用真·新仓 gold B 检验
**分布漂移（domain shift）** 下前沿是否仍守治理阈——CV 覆盖不到的一环。

关键区别（真 held-out）：级序（`calibrated_order`）与采纳前沿**从 conf-calibration-A.json
冻结**，**不在 B 上重拟合**。B 只做「按 A 冻结级序累计采纳前 k 级」的评测。

三方对照输出：
  · A 样本内（conf-calibration-A.json 的治理前沿）
  · CV held-out（conf-calibration-cv.json，若在）
  · **B fresh-repo held-out（本脚本）** ← 唯一覆盖分布漂移的口径
若 B 治理前沿 precision 仍 ≥ 阈 → 前沿抗漂移，2.4× 提升非样本内/同分布假象；
若 B 掉到阈下 → 前沿对 A 分布过拟合，须按 B 下移 fit_thr 或退回一致层。

纯函数复用 confidence_calibration（无 torch，模型预测从 dump_model_preds jsonl 读）。
用法: PYTHONPATH=. python3 realeval/conf_calibration_apply.py \
        --gold realeval/gold/real-b.jsonl --model out/model-preds-B.jsonl \
        --frozen out/conf-calibration-A.json --report out/conf-calibration-b.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import tables
from realeval.channel_router import extract_sql_lineage
from realeval.confidence_calibration import (
    TIER_LABEL, _canonical_edges, _cumulative, best_frontier,
)


def _build_scripts(rows: list[dict], model_by_idx: dict) -> tuple[list[dict], int]:
    """逐条非空金标脚本 → {"gold": set, "edges": [(name,tier)]}，与 calibrate 同口径。"""
    scripts: list[dict] = []
    gold_total = 0
    for i, r in enumerate(rows):
        g = tables(r["labels"]["reads"]) | tables(r["labels"]["writes"])
        if not g:
            continue
        gold_total += len(g)
        sp = extract_sql_lineage(r["content"], exec_gated=True)
        s = tables(sp["reads"]) | tables(sp["writes"])
        mp = model_by_idx.get(i, {"reads": [], "writes": []})
        m = tables(mp["reads"]) | tables(mp["writes"])
        scripts.append({"gold": g, "edges": _canonical_edges(s, m)})
    return scripts, gold_total


def apply_frozen(rows: list[dict], model_by_idx: dict, frozen: dict, thr: float) -> dict:
    """把 A 冻结的 calibrated_order + 治理前沿应用到 B，量 held-out 操作点。"""
    scripts, gold_total = _build_scripts(rows, model_by_idx)
    n_ne = len(scripts)

    frozen_order = frozen["calibrated_order"]
    # B 上按**冻结级序**累计（级序不重排）——但 B 里可能缺某些级（无候选边），
    # _cumulative 对空级 precision 记 1.0/召回不增，安全。
    cum_b = _cumulative(scripts, frozen_order, gold_total, n_ne)

    # A 冻结的治理前沿采纳集（precision≥thr 下召回最高）。
    a_front = best_frontier(frozen, thr, key="cumulative_calibrated")
    a_base = frozen["cumulative_calibrated"][0]
    accept_set = set(a_front["accept_tiers"]) if a_front else set(a_base["accept_tiers"])

    # 在 B 上定位「同一采纳集」的操作点。
    b_at_front = next((c for c in cum_b if set(c["accept_tiers"]) == accept_set), None)
    b_base = cum_b[0] if cum_b else None
    return {
        "n_nonempty": n_ne,
        "gold_total": gold_total,
        "frozen_order": frozen_order,
        "frozen_accept_set": sorted(accept_set),
        "cumulative_frozen_on_b": cum_b,
        "b_frontier": b_at_front,
        "b_baseline_agree_only": b_base,
        "a_frontier": a_front,
        "a_baseline_agree_only": a_base,
        "thr": thr,
    }


def render(res: dict, cv: dict | None) -> str:
    bf, bb = res["b_frontier"], res["b_baseline_agree_only"]
    af, ab = res["a_frontier"], res["a_baseline_agree_only"]
    thr = res["thr"]
    order_lbl = " → ".join(TIER_LABEL.get(t, t) for t in res["frozen_order"])
    acc_lbl = " + ".join(TIER_LABEL.get(t, t) for t in res["frozen_accept_set"])
    L = [
        "# 054 测试集 B（fresh-repo held-out）：冻结校准前沿的分布漂移检验",
        "",
        f"- 新仓非空金标脚本 **{res['n_nonempty']}** 条，金标真表 **{res['gold_total']}** 个（canon 口径）。",
        f"- 级序 + 采纳前沿从 gold A **冻结**（不在 B 重拟合）：级序 `{order_lbl}`；",
        f"  治理采纳集（precision≥{thr:.2f}）= **{acc_lbl}**。",
        "",
        "## B 上按冻结级序累计采纳（真 held-out）",
        "",
        "| 采纳至 | 采纳边数 | precision | 召回 | 复核(候选/脚本) |",
        "| --- | --- | --- | --- | --- |",
    ]
    for c in res["cumulative_frozen_on_b"]:
        star = " ★前沿" if set(c["accept_tiers"]) == set(res["frozen_accept_set"]) else ""
        L.append(f"| {TIER_LABEL.get(c['upto'], c['upto'])}{star} | {c['n_accept']} | "
                 f"{c['precision']:.3f} | {c['recall']:.3f} | {c['review_per_script']:.2f} |")

    L += ["", "## 三方对照：治理前沿的 precision / recall", "",
          "| 口径 | precision | recall | 复核/脚本 |", "| --- | --- | --- | --- |"]
    if af:
        L.append(f"| A 样本内（前沿） | {af['precision']:.3f} | {af['recall']:.3f} | {af['review_per_script']:.2f} |")
    cv5 = (cv or {}).get("cv5") or {}
    if cv5.get("pooled_precision") is not None:
        L.append(f"| CV 5折 held-out（同分布 de-bias） | {cv5['pooled_precision']:.3f} | "
                 f"{cv5['pooled_recall']:.3f} | — |")
    if bf:
        L.append(f"| **B fresh-repo held-out（前沿）** | **{bf['precision']:.3f}** | **{bf['recall']:.3f}** | {bf['review_per_script']:.2f} |")
    if bb:
        L.append(f"| B 基线（仅一致层） | {bb['precision']:.3f} | {bb['recall']:.3f} | {bb['review_per_script']:.2f} |")

    L += ["", "## 结论", ""]
    if bf and bb:
        safe = bf["precision"] >= thr
        lift = (bf["recall"] / bb["recall"]) if bb["recall"] else float("inf")
        cv_p = cv5.get("pooled_precision")
        # 与 CV 去偏估计对齐判定：若 B precision ≈ CV（|Δ|≤0.02）→ 掉幅是样本内乐观去偏、
        # 非分布漂移崩塌（domain shift 未进一步降级）；否则才是对 A 分布过拟合。
        tracks_cv = cv_p is not None and abs(bf["precision"] - cv_p) <= 0.02
        if safe:
            verdict = "抗漂移、治理安全（precision 守阈）"
        elif tracks_cv:
            verdict = (f"稳健但需留余量——B precision {bf['precision']:.3f} ≈ CV 去偏 {cv_p:.3f}，"
                       f"0.95→{bf['precision']:.2f} 的差是**样本内乐观去偏**、非分布漂移崩塌；"
                       f"domain shift 未进一步降级")
        else:
            verdict = f"掉出阈下且低于 CV 去偏（{cv_p:.3f}）→ 对 A 分布过拟合"
        L += [
            f"- B 治理前沿 precision **{bf['precision']:.3f}**（{'≥' if safe else '<'} 阈 {thr:.2f}）→ {verdict}。",
            f"- 自动采纳召回 {bb['recall']:.3f}（仅一致层）→ **{bf['recall']:.3f}**（前沿），"
            f"**{lift:.1f}× 提升在 B 上{'复现' if lift >= 1.5 else '未复现'}**"
            f"{'；治理层面需按 CV 处方下移 fit_thr 留 ~2pt 余量，方在 fresh 分布守住 0.95' if not safe else ''}。",
        ]
    else:
        L.append("- B 无非空金标脚本或无前沿操作点，无法评测（检查 gold-b 与 model-preds 对齐）。")
    L += ["",
          "> **诚实边界**：gold B 为**多 teacher 一致性 auto-gold**（无人工裁决）——偏向三家都能",
          "> 找到的表，recall 口径乐观、precision 相对可信；是人工金标的弱下位替代。distill-3b 的",
          "> 真实训练银标不可复原，训练污染仅靠查询多样化 + 海量语料重合可忽略缓解，非 hash 精确排除。"]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-b.jsonl")
    ap.add_argument("--model", required=True, help="dump_model_preds 对 gold B 的逐行预测 jsonl")
    ap.add_argument("--frozen", default="out/conf-calibration-A.json",
                    help="gold A 冻结校准 json（calibrated_order + cumulative_calibrated）")
    ap.add_argument("--cv", default="out/conf-calibration-cv.json", help="CV de-bias json（可选，仅对照）")
    ap.add_argument("--report", default="out/conf-calibration-b.md")
    ap.add_argument("--thr", type=float, default=0.95)
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    model_by_idx = {}
    for l in Path(args.model).read_text(encoding="utf-8").splitlines():
        if l.strip():
            d = json.loads(l)
            model_by_idx[d["idx"]] = d
    frozen = json.loads(Path(args.frozen).read_text(encoding="utf-8"))
    cv = None
    if args.cv and Path(args.cv).exists():
        try:
            cv = json.loads(Path(args.cv).read_text(encoding="utf-8"))
        except Exception:
            cv = None

    res = apply_frozen(rows, model_by_idx, frozen, args.thr)
    report = render(res, cv)
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
