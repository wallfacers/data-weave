"""063 T015：分层复核信封离线证明（gold C，三方对照）。

对每方预测（3b / deepseek / qwen），跑部署管线（语义 grounding → 分层）在 gold C 上量：
  · 模型独抽召回（基线）；
  · 自动层∪复核层召回（= 并集，SC-001 召回回收天花板）；
  · 自动层 precision / 召回（SC-002，配 calibrate_tiers 的 CV held-out 口径）；
  · 复核负载（候选/脚本，SC-003）。
报告 out/rescore-tiered.md。

用法: PYTHONPATH=. python3 realeval/rescore_tiered.py \
        --gold realeval/gold/real-c-arbitrated.jsonl \
        --preds 3b:out/preds-c-run-059-runc.jsonl \
        --preds deepseek:out/preds-c-teacher-deepseek-pro.jsonl \
        --preds qwen:out/preds-c-teacher-qwen-max.jsonl \
        --thr 0.95 --report out/rescore-tiered.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import tables, canon_match
from realeval.semantic_grounding import filter_pred_semantic
from realeval.tier_classify import classify_tiers
from realeval.tier_classify_constants import _CV_SWEEP, autoaccept_tiers


def _cv_at_thr(thr: float) -> tuple[float, float]:
    """治理阈 thr 下 CV 去偏采纳集的 held-out (precision, recall)——取扫描里达阈的最大召回行。"""
    ok = [row for row in _CV_SWEEP if row[2] >= thr]
    if not ok:
        return (1.0, 0.0)
    best = max(ok, key=lambda row: row[3])
    return (best[2], best[3])


def _load(path: str) -> list[dict]:
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def _pred(rec: dict) -> dict:
    src = rec.get("pred") if isinstance(rec.get("pred"), dict) else rec
    return {"reads": src.get("reads") or [], "writes": src.get("writes") or []}


def _cover(names: set[str], gold: set[str]) -> set[str]:
    return {g for g in gold if any(canon_match(n, g) for n in names)}


def score(gold_rows: list[dict], pred_rows: list[dict], thr: float) -> dict:
    gtot = m_cov = u_cov = a_cov = a_cand = a_corr = 0
    n_ne = 0
    review_cand = 0
    for g, p in zip(gold_rows, pred_rows):
        gold = tables(g["labels"]["reads"]) | tables(g["labels"]["writes"])
        if not gold:
            continue
        n_ne += 1
        gtot += len(gold)
        grounded = filter_pred_semantic(_pred(p), g["content"])
        m = tables(grounded["reads"]) | tables(grounded["writes"])
        t = classify_tiers(grounded, g["content"], thr)
        auto = {i["table"] for i in t["auto"]["reads"] + t["auto"]["writes"]}
        review = {i["table"] for i in t["review"]["reads"] + t["review"]["writes"]}
        union = auto | review
        m_cov += len(_cover(m, gold))
        u_cov += len(_cover(union, gold))
        a_cov += len(_cover(auto, gold))
        a_cand += len(auto)
        a_corr += sum(any(canon_match(x, gg) for gg in gold) for x in auto)
        review_cand += len(review)
    return {
        "n_nonempty": n_ne, "gold_total": gtot,
        "model_recall": m_cov / gtot if gtot else 0.0,
        "union_recall": u_cov / gtot if gtot else 0.0,       # SC-001 天花板
        "auto_recall": a_cov / gtot if gtot else 0.0,
        "auto_precision": a_corr / a_cand if a_cand else 1.0,  # 样本内（SC-002 配 CV held-out）
        "auto_n": a_cand,
        "review_per_script": review_cand / n_ne if n_ne else 0.0,  # SC-003
    }


def render(results: dict, thr: float) -> str:
    accept = autoaccept_tiers(thr)
    cv_p, cv_r = _cv_at_thr(thr)
    L = [
        "# 063 分层复核信封离线证明（gold C，三方对照）",
        "",
        f"治理阈 thr={thr} → CV 去偏采纳集 = `{accept or '（空）'}`。",
        f"自动层精度诚实口径 = CV held-out（thr={thr}）：precision {cv_p:.3f}、"
        f"recall {cv_r:.3f}（见 calibrate-tiers.md）。下表 auto_precision 为 gold C 样本内对照。",
        "",
        "| 方 | 模型独抽召回 | 自动∪复核召回 | 自动层 P（样本内） | 自动层召回 | 复核负载(候选/脚本) |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for label, r in results.items():
        L.append(
            f"| {label} | {r['model_recall']:.3f} | **{r['union_recall']:.3f}** | "
            f"{r['auto_precision']:.3f}（n={r['auto_n']}） | {r['auto_recall']:.3f} | "
            f"{r['review_per_script']:.2f} |")
    r3 = next(iter(results.values()))
    L += [
        "",
        "## 成功判据",
        "",
        f"- **SC-001 召回回收**：3B 自动∪复核召回 **{results.get('3b',r3)['union_recall']:.3f}** "
        f"vs 模型独抽 {results.get('3b',r3)['model_recall']:.3f}（复核层把并集召回 surface 给人工）。",
        f"- **SC-002 自动层精度**：CV held-out precision {cv_p:.3f}（治理诚实口径）；"
        f"样本内 {results.get('3b',r3)['auto_precision']:.3f}。**★诚实披露**：thr={thr} 下 CV 只 sql_qual "
        f"可守（n 小抖动大），自动层召回仅 {results.get('3b',r3)['auto_recall']:.3f}——召回回收价值全在复核层。",
        f"- **SC-003 复核负载**：3B {results.get('3b',r3)['review_per_script']:.2f} 候选/脚本。",
        "",
        "## fit_thr 部署曲线（CV held-out，calibrate-tiers.md）",
        "",
        "| fit_thr | held-out P | held-out R | 采纳级集 |",
        "| --- | --- | --- | --- |",
        *[f"| {ft} | {p:.3f} | {rc:.3f} | {acc} |" for ft, acc, p, rc in _CV_SWEEP],
        "",
        "> 诚实边界（R1）：无独立非泄漏带标集 → gold C 嵌套 CV 去偏；CV 只校同分布偏置、不覆盖分布漂移。",
    ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--preds", action="append", required=True, help="label:path，可重复")
    ap.add_argument("--thr", type=float, default=0.95)
    ap.add_argument("--report", default="out/rescore-tiered.md")
    args = ap.parse_args(argv)

    gold = _load(args.gold)
    results = {}
    for spec in args.preds:
        label, path = spec.split(":", 1)
        preds = _load(path)
        if len(preds) != len(gold):
            raise SystemExit(f"{label}: preds({len(preds)}) != gold({len(gold)})")
        results[label] = score(gold, preds, args.thr)

    report = render(results, args.thr)
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
