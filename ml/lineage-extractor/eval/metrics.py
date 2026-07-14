"""041-R 分层指标纯函数（无 torch 依赖，可独立单测）。"""
from __future__ import annotations

GATE = {"precision": 0.85, "uncovered_recall": 0.60, "hallucination": 0.02, "direction_acc": 0.95}


def tables(items) -> set[str]:
    out = set()
    for it in items or []:
        if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip():
            out.add(it["table"].strip().lower())
    return out


def canon_col(name) -> str | None:
    """067 列名规范化。返回归一后列名，或 None=弃权信号（不参与集合运算）。
    小写/去空白/剥表限定前缀（取点分尾段）/`*` 通配 → None。"""
    if not isinstance(name, str):
        return None
    s = name.strip().lower()
    if not s or "*" in s:
        return None
    if "." in s:                      # orders.amount / db.t.col → 取尾段
        s = s.split(".")[-1].strip()
    if not s or "*" in s:
        return None
    return s


def canon_cols(cols) -> set[str] | None:
    """067 列集三态归一：None/[]→None（弃权）；任一元素通配/空 → None（传染，整表弃权）；
    否则 → 归一后非空列名集合。"""
    if not cols:                      # None 或 []
        return None
    out = set()
    for cc in cols:
        c = canon_col(cc)
        if c is None:                 # 通配/空 → 整表弃权
            return None
        out.add(c)
    return out or None


def _item_cols(items) -> dict:
    """067 [{table,columns}] → {规范化表名: canon_cols 结果}。同表多现：具体优先于弃权，
    双具体取并（保守）。表身份用与表级同口径的小写裸名（列匹配再各自 canon）。"""
    out: dict = {}
    for it in items or []:
        if not isinstance(it, dict):
            continue
        t = it.get("table")
        if not (isinstance(t, str) and t.strip()):
            continue
        key = t.strip().lower()
        cc = canon_cols(it.get("columns"))
        if key not in out:
            out[key] = cc
        elif out[key] is None or cc is None:
            out[key] = out[key] if cc is None else cc   # 具体优先于弃权
        else:
            out[key] = out[key] | cc                     # 双具体取并
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
             invalid=1 if pred.get("_invalid") or pred.get("_error") else 0,
             col_tp=0, col_fp=0, col_fn=0, col_halluc=0, col_pred_total=0, col_eval_tables=0)
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
    # ── 067 独立列打分块（门①：绝不触碰上方表级 tp/fp/fn/halluc/dir_*）──
    # 条件于表级命中：对每个 role 独立重算表对齐对（与表级同 canon 设置），再比对列集。
    for role in ("reads", "writes"):
        gmap = _item_cols(gold.get(role))
        pmap = _item_cols(pred.get(role))
        used: set[str] = set()
        for gt, gcols in gmap.items():
            p_match = None
            for pt in pmap:
                if pt in used:
                    continue
                if canon_match(gt, pt) if canon else gt == pt:
                    p_match = pt
                    break
            if p_match is None:          # 表未命中（非 TP）→ 不评列
                continue
            used.add(p_match)
            if gcols is None:            # gold 弃权 → 跳过（不评）
                continue
            c["col_eval_tables"] += 1
            pcols = pmap[p_match]
            if pcols is None:            # pred 弃权而 gold 有列 → 全漏（不算幻觉）
                c["col_fn"] += len(gcols)
                continue
            c["col_tp"] += len(gcols & pcols)
            c["col_fp"] += len(pcols - gcols)
            c["col_fn"] += len(gcols - pcols)
            for col in pcols:            # 列幻觉：pred 列字面不在脚本
                c["col_pred_total"] += 1
                if col not in cl:
                    c["col_halluc"] += 1
    return c


def aggregate(counts: list[dict]) -> dict:
    s = {k: sum(c[k] for c in counts) for k in
         ("tp", "fp", "fn", "halluc", "pred_total", "dir_total", "dir_correct", "invalid")}
    prec = s["tp"] / (s["tp"] + s["fp"]) if s["tp"] + s["fp"] else 1.0
    rec = s["tp"] / (s["tp"] + s["fn"]) if s["tp"] + s["fn"] else 1.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    out = dict(precision=prec, recall=rec, f1=f1,
               hallucination=s["halluc"] / s["pred_total"] if s["pred_total"] else 0.0,
               direction_acc=s["dir_correct"] / s["dir_total"] if s["dir_total"] else 1.0,
               invalid=s["invalid"])
    # 067 列级聚合（独立 key；缺 col_* 的旧 counts 视为 0，向后兼容）
    cs = {k: sum(c.get(k, 0) for c in counts) for k in
          ("col_tp", "col_fp", "col_fn", "col_halluc", "col_pred_total", "col_eval_tables")}
    cprec = cs["col_tp"] / (cs["col_tp"] + cs["col_fp"]) if cs["col_tp"] + cs["col_fp"] else 1.0
    crec = cs["col_tp"] / (cs["col_tp"] + cs["col_fn"]) if cs["col_tp"] + cs["col_fn"] else 1.0
    cf1 = 2 * cprec * crec / (cprec + crec) if cprec + crec else 0.0
    out.update(col_precision=cprec, col_recall=crec, col_f1=cf1,
               col_hallucination=cs["col_halluc"] / cs["col_pred_total"] if cs["col_pred_total"] else 0.0,
               col_eval_tables=cs["col_eval_tables"])
    return out


def direction_accuracy(counts: list[dict]) -> float:
    return aggregate(counts)["direction_acc"]
