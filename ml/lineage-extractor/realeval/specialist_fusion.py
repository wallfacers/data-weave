"""068 US6：双专家 serving 融合——绕开 3B/LoRA 表↔列容量墙的推理期方案。

068 诊断出 3B/LoRA 存在表↔列 frontier（两次逃逸双证的容量墙）：单模型无法同时到
表 R≥0.75 且 列 F1≥0.85。本模块不训练，改在**推理期**把两个专家组合：

  · 表专家 run-tri-3b-col50：表 R 0.776（表集召回天花板），列 F1 0.578（列弱）；
  · 列专家 run-tri-3b        ：列 F1 0.932（列近满分），表 R 0.607（表召回低）。

融合逻辑（`fuse`，纯函数、无 GPU、可无依赖单测）：
  1. 表集（每 role 独立）：strategy='table' 取表专家；'union' 取两专家并集（冲召回，
     精度风险交给下游 grounding/分层剪）。
  2. 列嫁接：对表集里每个表，若**列专家**也预测了该表（精确或 canon 尾段匹配）→ 用列
     专家的列（富且准）；否则回退表专家的列；两者皆无 → null。

关键性质（对应单测）：strategy='table' 下表集逐字节 = 表专家 → 表级 counts 不受融合扰动
（门① 正交在评测侧由 eval.metrics 保证，融合只发生在打分前）；fuse(X, X) 幂等自反。

配套 `score_fusion` 复用 dump_and_score.score_preds 口径（canon=False 主口径，与 068
published 一致），对齐 preds↔gold 后离线出融合指标，无需重推理。
"""
from __future__ import annotations

from eval.metrics import canon_match

_MISSING = object()


def _role_colmap(items) -> dict:
    """[{table,columns}] → {表名(小写裸名): columns}。同表多现：具体列优先于弃权(None)。
    表身份用与 eval.metrics 同口径的 strip().lower()，列原样保留(交给下游 canon_cols 归一)。"""
    out: dict = {}
    for it in items or []:
        if not isinstance(it, dict):
            continue
        t = it.get("table")
        if not (isinstance(t, str) and t.strip()):
            continue
        key = t.strip().lower()
        cols = it.get("columns")
        if key not in out:
            out[key] = cols
        elif out[key] is None and cols is not None:   # 具体优先于弃权
            out[key] = cols
    return out


def _lookup_cols(colmap: dict, table: str):
    """在 colmap 里找 table 的列：先精确小写，再 canon 尾段匹配(限定名⟷短名)。
    命中返回列(可能 None)，未命中返回 _MISSING。"""
    if table in colmap:
        return colmap[table]
    for k, v in colmap.items():
        if canon_match(table, k):
            return v
    return _MISSING


def fuse(table_pred: dict, col_pred: dict, strategy: str = "table") -> dict:
    """双专家融合 → {reads:[{table,columns}], writes:[...]}（表名升序，确定性）。

    strategy='table'：表集=表专家（安全两全，表级指标 = 表专家）；
    strategy='union'：表集=表专家 ∪ 列专家（冲召回，可能破单专家天花板）。
    列一律从列专家嫁接（命中则），否则回退表专家，皆无 → null。
    """
    if strategy not in ("table", "union"):
        raise ValueError(f"unknown strategy: {strategy}")
    out: dict = {"reads": [], "writes": []}
    for role in ("reads", "writes"):
        tmap = _role_colmap(table_pred.get(role))
        cmap = _role_colmap(col_pred.get(role))
        if strategy == "table":
            keys = set(tmap)
        else:
            keys = set(tmap) | set(cmap)
        items = []
        for k in sorted(keys):
            cols = _lookup_cols(cmap, k)      # 列专家优先
            if cols is _MISSING:
                cols = tmap.get(k, None)      # 回退表专家（union 下列专家独有表 tmap 无 → None）
            items.append({"table": k, "columns": cols})
        out[role] = items
    return out


def fuse_pairs(gold_rows: list, table_preds: list, col_preds: list, strategy: str = "table") -> list:
    """对齐 gold↔双专家 preds（按行序），产出 dump_and_score.score_preds 口径的 pairs。

    gold_rows: real-c-tri.jsonl 行（含 labels/content）；table_preds/col_preds: out/preds-tri/*.jsonl
    行（{reads,writes}）。返回 [{content, gold, pred}]（pred=融合结果）。
    """
    pairs = []
    for g, t, c in zip(gold_rows, table_preds, col_preds):
        tp = {"reads": t.get("reads") or [], "writes": t.get("writes") or []}
        cp = {"reads": c.get("reads") or [], "writes": c.get("writes") or []}
        pairs.append({"content": g["content"], "gold": g["labels"],
                      "pred": fuse(tp, cp, strategy=strategy)})
    return pairs
