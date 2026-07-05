"""041-R 分层指标纯函数（无 torch 依赖，可独立单测）。"""
from __future__ import annotations

GATE = {"precision": 0.85, "uncovered_recall": 0.60, "hallucination": 0.02, "direction_acc": 0.95}


def tables(items) -> set[str]:
    out = set()
    for it in items or []:
        if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip():
            out.add(it["table"].strip().lower())
    return out


def score_row(gold: dict, pred: dict, content: str) -> dict:
    gr, gw = tables(gold.get("reads")), tables(gold.get("writes"))
    pr, pw = tables(pred.get("reads")), tables(pred.get("writes"))
    cl = content.lower()
    c = dict(tp=0, fp=0, fn=0, halluc=0, pred_total=0, dir_total=0, dir_correct=0,
             invalid=1 if pred.get("_invalid") or pred.get("_error") else 0)
    for gold_set, got_set in [(gr, pr), (gw, pw)]:
        c["tp"] += len(gold_set & got_set)
        c["fp"] += len(got_set - gold_set)
        c["fn"] += len(gold_set - got_set)
        for t in got_set:
            c["pred_total"] += 1
            if t not in cl:
                c["halluc"] += 1
    # 方向准确率：对每个 gold 表，pred 给它的方向集合须与 gold 完全一致
    for t in gr | gw:
        c["dir_total"] += 1
        gold_roles = {role for role, s in (("r", gr), ("w", gw)) if t in s}
        pred_roles = {role for role, s in (("r", pr), ("w", pw)) if t in s}
        if gold_roles == pred_roles:
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
