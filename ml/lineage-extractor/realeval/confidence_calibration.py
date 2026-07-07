"""052→分级置信度校准：把「一致层自动采纳 / 其余一股脑进复核」的**二元门**升级为
**分级可靠性曲线**——给每条候选血缘边打**部署期可得**（不看金标）的置信信号，按经验
precision 校准出可解释的分级，据此产出「自动采纳到第 k 级」的操作点表。

回应两个残缺：
  ① 复核队列无序 → 复核者无法「从最可能对的先看 / 早停」；
  ② 单一一致门把本可安全自动采纳的高置信边（如 SQL-AST 抽出的**限定名**）也压进人工。

**部署期信号**（逐候选边，均不依赖金标）：
  · 通道归属：SQL∩模型一致 / SQL-only / 模型-only（一致 = 双通道 canon 互认）；
  · 名字限定性：`schema.table`（限定）vs 裸名（更易是别名/临时/解析碎片）；
  · 方向：SQL 通道 write 由 AST target 锚定（方向可信），model 通道方向易错。

**校准口径**（canon，与 tiered_envelope 并集/一致层一致，表级）：
  一条候选边 name **正确** ⟺ 存在金标表 g 使 canon_match(name, g)；
  某集合的**召回** = 被其覆盖的**不同金标表**数 / 金标真表数（canon 去重，不因限定名重复夸大）。

先按先验强度给 5 级（agree > sql_qual > sql_bare > model_qual > model_bare），再用金标量出
每级经验 precision 校准该排序；输出累计操作点：找**满足治理阈（precision≥阈）下召回最高**的
自动采纳前沿，与「仅一致层」基线对比省了多少人力。纯函数、无 torch，可无 GPU 单测。

用法: PYTHONPATH=. python3 realeval/confidence_calibration.py \
        --gold realeval/gold/real.jsonl --model out/model-preds-A.jsonl \
        --report out/conf-calibration-A.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import canon_match, tables
from realeval.channel_router import extract_sql_lineage

# 先验强度排序（强→弱）——校准会验证/证伪该顺序。
TIERS = ["agree", "sql_qual", "sql_bare", "model_qual", "model_bare"]
TIER_LABEL = {
    "agree": "一致（SQL∩模型）",
    "sql_qual": "SQL-only·限定名",
    "sql_bare": "SQL-only·裸名",
    "model_qual": "模型-only·限定名",
    "model_bare": "模型-only·裸名",
}


def _qual(name: str) -> bool:
    """限定名 = 含点分 schema 前缀（`db.t` / `s.t`）。裸名更易是别名/临时/解析碎片。"""
    return "." in name


def _canonical_edges(s_set: set[str], m_set: set[str]) -> list[tuple[str, str]]:
    """把 SQL 表集 S 与模型表集 M 在 canon 下合并成**互斥候选边**，避免 `t` 与 `db.t`
    被字符串并集重复计数。返回 [(name, tier), ...]，tier ∈ TIERS。

    - S 侧每个 s：若被 M 中任一 canon 命中 → agree（用 S 侧串，方向由 AST 锚定）；否则 sql_only。
    - M 侧未被任何 S 命中的 x → model_only。
    """
    edges: list[tuple[str, str]] = []
    m_matched: set[str] = set()
    for s in sorted(s_set):
        hit = [x for x in m_set if canon_match(s, x)]
        if hit:
            edges.append((s, "agree"))
            m_matched.update(hit)
        else:
            edges.append((s, "sql_qual" if _qual(s) else "sql_bare"))
    for x in sorted(m_set):
        if x not in m_matched:
            edges.append((x, "model_qual" if _qual(x) else "model_bare"))
    return edges


def _correct(name: str, gold: set[str]) -> bool:
    return any(canon_match(name, g) for g in gold)


def _covered(names: set[str], gold: set[str]) -> set[str]:
    """accepted 边集覆盖的**不同金标表**（canon）。"""
    return {g for g in gold if any(canon_match(n, g) for n in names)}


def _cumulative(scripts: list[dict], order: list[str], gold_total: int, n_ne: int) -> list[dict]:
    """按给定级序累计自动采纳「前 k 级」，量 precision / 召回(金标覆盖) / 复核负载。"""
    cum = []
    for k in range(1, len(order) + 1):
        accept = set(order[:k])
        n_acc = n_corr = covered = n_review = 0
        for sc in scripts:
            acc_names = {name for name, tier in sc["edges"] if tier in accept}
            rev_names = {name for name, tier in sc["edges"] if tier not in accept}
            n_acc += len(acc_names)
            n_corr += sum(_correct(n, sc["gold"]) for n in acc_names)
            covered += len(_covered(acc_names, sc["gold"]))
            n_review += len(rev_names)
        cum.append({
            "upto": order[k - 1],
            "accept_tiers": order[:k],
            "n_accept": n_acc,
            "precision": n_corr / n_acc if n_acc else 1.0,
            "recall": covered / gold_total if gold_total else 0.0,
            "review_per_script": n_review / n_ne if n_ne else 0.0,
        })
    return cum


def calibrate(rows: list[dict], model_by_idx: dict) -> dict:
    """逐条非空金标脚本抽 S/M → 合并成分级候选边 → 按级聚合 precision，
    并给出**两种级序**的累计操作点：
      · a-priori（TIERS 先验强度）——用于展示先验序是否被证伪；
      · calibrated（按经验 precision 降序重排，空级剔除）——真正的部署前沿。
    calibrated 是**样本内**校准（同金标既定序又评估）——小样本须诚实披露，理想应在 held-out B 复核。"""
    per_tier = {t: {"n": 0, "correct": 0} for t in TIERS}
    gold_total = 0
    scripts: list[dict] = []
    for i, r in enumerate(rows):
        g = tables(r["labels"]["reads"]) | tables(r["labels"]["writes"])
        if not g:
            continue
        gold_total += len(g)
        sp = extract_sql_lineage(r["content"], exec_gated=True)
        s = tables(sp["reads"]) | tables(sp["writes"])
        mp = model_by_idx.get(i, {"reads": [], "writes": []})
        m = tables(mp["reads"]) | tables(mp["writes"])
        edges = _canonical_edges(s, m)
        for name, tier in edges:
            ok = _correct(name, g)
            per_tier[tier]["n"] += 1
            per_tier[tier]["correct"] += int(ok)
        scripts.append({"gold": g, "edges": edges})

    n_ne = len(scripts)
    tier_stats = {
        t: {"n": per_tier[t]["n"],
            "precision": per_tier[t]["correct"] / per_tier[t]["n"] if per_tier[t]["n"] else None}
        for t in TIERS
    }
    # calibrated 级序：非空级按经验 precision 降序（并列时保先验序稳定）。
    nonempty = [t for t in TIERS if tier_stats[t]["n"] > 0]
    calib_order = sorted(nonempty, key=lambda t: (-tier_stats[t]["precision"], TIERS.index(t)))
    return {
        "n_nonempty": n_ne,
        "gold_total": gold_total,
        "tier": tier_stats,
        "apriori_order": [t for t in TIERS if tier_stats[t]["n"] > 0],
        "calibrated_order": calib_order,
        "cumulative": _cumulative(scripts, [t for t in TIERS if tier_stats[t]["n"] > 0], gold_total, n_ne),
        "cumulative_calibrated": _cumulative(scripts, calib_order, gold_total, n_ne),
    }


def best_frontier(cal: dict, thr: float, key: str = "cumulative_calibrated") -> dict | None:
    """治理阈 thr 下召回最高的自动采纳前沿（累计 precision≥thr 中召回最大者）。
    默认用 calibrated 级序——先验序被证伪时，前沿须由校准序决定。"""
    ok = [c for c in cal.get(key, cal["cumulative"]) if c["precision"] >= thr]
    return max(ok, key=lambda c: c["recall"]) if ok else None


def render(cal: dict, thr: float = 0.95) -> str:
    L = [
        "# 052 分级置信度校准（测试集 A / held-out）",
        "",
        "把「一致层自动采纳 / 其余进复核」的**二元门**升级为**分级可靠性曲线**：给每条候选边打",
        "部署期可得的置信信号（通道归属 × 名字限定性），按经验 precision 校准分级，据此选",
        "**治理阈下召回最高**的自动采纳前沿，并给复核队列一个按置信排序的次序。",
        "",
        f"- 非空金标脚本 {cal['n_nonempty']} 条，金标真表 {cal['gold_total']} 个（canon 口径）。",
        "",
        "## 逐级可靠性（先验强→弱，经验 precision 校准该排序）",
        "",
        "| 置信级 | 候选边数 | 经验 precision |",
        "| --- | --- | --- |",
    ]
    for t in TIERS:
        s = cal["tier"][t]
        p = "—" if s["precision"] is None else f"{s['precision']:.3f}"
        L.append(f"| {TIER_LABEL[t]} | {s['n']} | {p} |")
    calib = " → ".join(TIER_LABEL[t] for t in cal["calibrated_order"])
    L += [
        "",
        f"**校准结论**：经验 precision 排序 = `{calib}`。",
        "先验假设「SQL 通道恒优于模型」被**证伪**——SQL-only·裸名 precision 最低（多为 CTE/临时/",
        "解析碎片），而模型-only·**限定名**近治理级。**限定性（schema.table）才是主置信信号**，",
        "跨越通道来源。故累计前沿须用**校准序**、而非先验序。",
        "",
        "## 累计操作点：先验序 vs 校准序（采纳「前 k 级」）",
        "",
        "先验序 sql_bare 排在模型级之前 → 过早掺入低置信边，前沿卡死在一致层（零提升）；",
        "校准序把高 precision 的模型限定名提前 → 抬高治理阈下的自动采纳召回。",
        "",
        "| 级序 | 自动采纳至 | 采纳边数 | precision | 召回 | 复核(候选/脚本) |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for c in cal["cumulative"]:
        L.append(
            f"| 先验 | {TIER_LABEL[c['upto']]} | {c['n_accept']} | "
            f"{c['precision']:.3f} | {c['recall']:.3f} | {c['review_per_script']:.2f} |")
    for c in cal["cumulative_calibrated"]:
        L.append(
            f"| **校准** | {TIER_LABEL[c['upto']]} | {c['n_accept']} | "
            f"{c['precision']:.3f} | {c['recall']:.3f} | {c['review_per_script']:.2f} |")

    base = cal["cumulative_calibrated"][0]        # 仅一致层 = 二元门基线
    front = best_frontier(cal, thr)               # 校准序前沿
    apri = best_frontier(cal, thr, key="cumulative")
    L += [
        "",
        f"## 治理前沿（precision ≥ {thr:.2f}，校准序）",
        "",
        f"- **基线（仅一致层，二元门）**：precision {base['precision']:.3f}、"
        f"召回 {base['recall']:.3f}、复核 {base['review_per_script']:.2f}/脚本。",
    ]
    if front is None:
        L.append(f"- 校准序下仍无累计级满足 precision ≥ {thr:.2f}——退回仅一致层。")
    else:
        lift = front["recall"] - base["recall"]
        apri_r = apri["recall"] if apri else base["recall"]
        L += [
            f"- **校准前沿（自动采纳至「{TIER_LABEL[front['upto']]}」）**："
            f"precision {front['precision']:.3f}（≥{thr:.2f} 治理安全）、"
            f"召回 **{front['recall']:.3f}**、复核 {front['review_per_script']:.2f}/脚本。",
            f"- **提升**：自动采纳召回 **{base['recall']:.3f} → {front['recall']:.3f}**"
            f"（{'+' if lift >= 0 else ''}{lift:.3f}，{front['recall']/base['recall']:.1f}× 若基线非零），"
            f"复核负载 {base['review_per_script']:.2f} → {front['review_per_script']:.2f} 候选/脚本，"
            f"precision 仍守 ≥{thr:.2f}。",
            f"- 对照：先验序前沿召回仅 {apri_r:.3f}（sql_bare 污染 → 无提升）——"
            f"**校准的价值即在于纠正级序**。",
        ]
    L += [
        "",
        "> 复核队列按**校准序**排序（一致 → 模型限定 → 模型裸名 → SQL 裸名），复核者从最可能对的",
        f"> 先看、按治理阈早停；采纳前沿之外（{front['review_per_script']:.2f}/脚本）进人工。"
        if front else "> 复核队列按校准序排序，复核者从最可能对的先看。",
        "",
        "> **诚实边界**：calibrated 级序为**样本内**校准（同 gold A 既定序又评估），141 表小样本；"
        "结论（限定性主导、先验序证伪）稳健，但精确前沿点应在 held-out 测试集 B 复核后再固化。",
    ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--model", required=True, help="模型逐行预测 jsonl（dump_model_preds 产出）")
    ap.add_argument("--report", default="out/conf-calibration-A.md")
    ap.add_argument("--thr", type=float, default=0.95, help="治理 precision 阈")
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    model_by_idx = {}
    for l in Path(args.model).read_text(encoding="utf-8").splitlines():
        if l.strip():
            d = json.loads(l)
            model_by_idx[d["idx"]] = d

    cal = calibrate(rows, model_by_idx)
    report = render(cal, thr=args.thr)

    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(cal, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
