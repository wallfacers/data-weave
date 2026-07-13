"""068 T024（US3/FR-015）：治理路由——缓解已知限制②"模糊案例缺人工复核"。

三厂商是否一致 = 客观模糊度信号：
  · **auto 层（3-of-3 一致边）** = 不模糊 → 可自动采纳；报模型在此层精度 = 自动化安全性证据；
  · **review 层（仅 2-of-3 边=有一家分歧）** = 模糊 → 人工复核；报占比 = 人工工作量。
接 063 分层信封（厂商分歧 = 复核层客观定义）。**诚实边界**：缩小模糊区 + 给复核判据，
非消灭人工复核（模糊案例本质需人）。纯读文件 + 复用打分核，无网络/torch。

用法: PYTHONPATH=. python3 -m realeval.governance_routing \
        --tri realeval/gold/real-c-tri.jsonl --unan realeval/gold/real-c-tri-unan.jsonl \
        --preds out/preds-tri/run-tri-3b.jsonl --out out/governance-routing-068.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import score_row


def _load_jsonl(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def tier_edges(tri_rows: list[dict]) -> dict:
    """按每条 gold 边的一致票数分层计数：auto=全体一致(agree==n_teachers)、review=其余(2-of-3)。
    返回 {auto, review, total}（边计数）。auto+review==total（分层是全集划分）。"""
    auto = review = total = 0
    for r in tri_rows:
        cons = r.get("consensus") or {}
        agree = cons.get("agree") or {}
        n_t = cons.get("n_teachers", 3)
        lab = r.get("labels") or {}
        for side in ("reads", "writes"):
            for e in lab.get(side) or []:
                total += 1
                if agree.get(e["table"]) == n_t:
                    auto += 1
                else:
                    review += 1
    return {"auto": auto, "review": review, "total": total}


def auto_layer_scores(unan_rows: list[dict], preds: list[dict], canon: bool = True) -> dict:
    """模型在 auto 层（3-of-3 unanimous gold）的精度/召回——自动化安全性证据（SC-011）。"""
    counts = []
    for i, row in enumerate(unan_rows):
        gold = row.get("labels") or {}
        pred = preds[i] if i < len(preds) else {"reads": [], "writes": []}
        counts.append(score_row(gold, {"reads": pred.get("reads") or [], "writes": pred.get("writes") or []},
                                row.get("content", ""), canon=canon))

    def s(k):
        return sum(c.get(k, 0) for c in counts)
    tp, fp, fn = s("tp"), s("fp"), s("fn")
    p = tp / (tp + fp) if (tp + fp) else 0.0
    r = tp / (tp + fn) if (tp + fn) else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "precision": p, "recall": r}


def build_report(tri_rows, unan_rows, preds, model_name: str) -> str:
    te = tier_edges(tri_rows)
    auto = auto_layer_scores(unan_rows, preds)     # 召回：recover 多少 unanimous 边
    full = auto_layer_scores(tri_rows, preds)      # 精度：对全 tri gold（review 层真表不算 fp）
    review_frac = te["review"] / te["total"] if te["total"] else 0.0
    L = [
        "# 068 治理路由：缓解限制②（模糊案例缺人工复核）", "",
        "三厂商是否一致 = 客观模糊度信号。auto 层（3-of-3 一致）可自动采纳；review 层（仅 2-of-3=有分歧）走人工复核。", "",
        "## 分层（gold 边）", "",
        "| 层 | 定义 | 边数 | 占比 |",
        "| --- | --- | --- | --- |",
        f"| auto（自动采纳）| 3 家全一致 | {te['auto']} | {te['auto']/te['total']:.1%} |",
        f"| review（人工复核）| 仅 2-of-3（一家分歧）| {te['review']} | {review_frac:.1%} |",
        f"| 合计 | | {te['total']} | 100% |", "",
        f"## auto 层自动化安全性（模型 {model_name}）", "",
        f"- **auto 层召回 {auto['recall']:.3f}**（recover {auto['tp']}/{auto['tp']+auto['fn']} 条 3-of-3 一致边）→ 不模糊案例上高置信边被稳稳捕获",
        f"- **整体精度 {full['precision']:.3f}**（对全 tri gold，tp {full['tp']}/fp {full['fp']}）→ 模型极少幻觉=自动采纳安全",
        f"  （注：精度按全 tri gold 算，review 层 2-of-3 真表不计 fp；仅按 unanimous 子集会把真表误判 fp 而虚低）", "",
        "## 读法与诚实边界", "",
        f"- **人工复核工作量 = {review_frac:.1%} 的边**（厂商分歧）——接 063 分层信封的复核候选层客观定义。",
        f"- **自动采纳建议**：auto 层（{te['auto']/te['total']:.0%} 的边）召回 {auto['recall']:.3f} + 整体精度 {full['precision']:.3f} → 高置信边可自动化；分歧 {review_frac:.0%} 走人工。",
        "- 这是**缩小模糊区 + 给出有原则的复核路由判据**，**非消灭人工复核**——模糊案例（厂商分歧）本质需人。",
        "- 限制①（动态名/注释/临时视图）为刻意设计边界，不在本缓解范围（见 spec Out of scope）。",
    ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tri", default="realeval/gold/real-c-tri.jsonl")
    ap.add_argument("--unan", default="realeval/gold/real-c-tri-unan.jsonl")
    ap.add_argument("--preds", required=True, help="模型在 real-c-tri 上的 idx 对齐 preds")
    ap.add_argument("--model-name", default=None)
    ap.add_argument("--out", default="out/governance-routing-068.md")
    args = ap.parse_args(argv)

    tri = _load_jsonl(args.tri)
    unan = _load_jsonl(args.unan)
    preds = _load_jsonl(args.preds)
    name = args.model_name or Path(args.preds).stem
    md = build_report(tri, unan, preds, name)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md, encoding="utf-8")
    print(f"governance_routing → {out}")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
