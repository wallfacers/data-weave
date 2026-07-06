"""041-R 分层指标纯函数（无 torch 依赖，可独立单测）。"""
from __future__ import annotations

GATE = {"precision": 0.85, "uncovered_recall": 0.60, "hallucination": 0.02, "direction_acc": 0.95}


def tables(items) -> set[str]:
    out = set()
    for it in items or []:
        if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip():
            out.add(it["table"].strip().lower())
    return out


def canon_match(a: str, b: str) -> bool:
    """050 Tier 0：schema 限定的规范化匹配。两名相等，或**短名是长名的点分尾段序列**
    （`t`⟷`db.s.t`、`s.t`⟷`db.s.t` 命中），即视为同表——修正评测把「模型解析出完整
    限定名 vs 金标用短名」错判为 fp+fn 的假象。**保守**：`test_dataset`⟷`test_dataset.test_table`
    不命中（粒度真错，保持诚实，不放水）。"""
    if a == b:
        return True
    pa, pb = a.split("."), b.split(".")
    short, long = (pa, pb) if len(pa) <= len(pb) else (pb, pa)
    return len(short) < len(long) and long[-len(short):] == short


def _align(gold_set: set[str], got_set: set[str]) -> tuple[int, set[str], set[str]]:
    """canon 下的贪心一对一对齐：返回 (tp, 命中的 gold, 命中的 pred)。集合小，贪心即最优够用。"""
    g_hit, p_hit = set(), set()
    for g in sorted(gold_set):
        for p in sorted(got_set):
            if p in p_hit:
                continue
            if canon_match(g, p):
                g_hit.add(g)
                p_hit.add(p)
                break
    return len(g_hit), g_hit, p_hit


def score_row(gold: dict, pred: dict, content: str, canon: bool = False) -> dict:
    gr, gw = tables(gold.get("reads")), tables(gold.get("writes"))
    pr, pw = tables(pred.get("reads")), tables(pred.get("writes"))
    cl = content.lower()
    c = dict(tp=0, fp=0, fn=0, halluc=0, pred_total=0, dir_total=0, dir_correct=0,
             invalid=1 if pred.get("_invalid") or pred.get("_error") else 0)
    for gold_set, got_set in [(gr, pr), (gw, pw)]:
        if canon:
            tp, g_hit, p_hit = _align(gold_set, got_set)
            c["tp"] += tp
            c["fp"] += len(got_set) - len(p_hit)
            c["fn"] += len(gold_set) - len(g_hit)
        else:
            c["tp"] += len(gold_set & got_set)
            c["fp"] += len(got_set - gold_set)
            c["fn"] += len(gold_set - got_set)
        for t in got_set:
            c["pred_total"] += 1
            if t not in cl:
                c["halluc"] += 1
    # 方向准确率：对每个 gold 表，pred 给它的方向集合须与 gold 完全一致
    def _roles(t: str, rset: set[str], wset: set[str]) -> set[str]:
        if canon:
            return {role for role, s in (("r", rset), ("w", wset))
                    if any(canon_match(t, p) for p in s)}
        return {role for role, s in (("r", rset), ("w", wset)) if t in s}
    for t in gr | gw:
        c["dir_total"] += 1
        if _roles(t, gr, gw) == _roles(t, pr, pw):
            c["dir_correct"] += 1
    return c


def aggregate(counts: list[dict]) -> dict:
    s = {k: sum(c[k] for c in counts) for k in
         ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct", "invalid")}
    prec = s["tp"] / (s["tp"] + s["fp"]) if s["tp"] + s["fp"] else 1.0
    rec = s["tp"] / (s["tp"] + s["fn"]) if s["tp"] + s["fn"] else 1.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return dict(precision=prec, recall=rec, f1=f1,
                hallucination=s["halluc"] / s["pred_total"] if s["pred_total"] else 0.0,
                direction_acc=s["dir_correct"] / s["dir_total"] if s["dir_total"] else 1.0,
                invalid=s["invalid"])


def direction_accuracy(counts: list[dict]) -> float:
    return aggregate(counts)["direction_acc"]
