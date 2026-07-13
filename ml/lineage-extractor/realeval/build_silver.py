"""T012: 银标构建——交集为主 + 分歧经字面门救回。

FR-004/005/006 · contracts/silver-label.schema。口径（clarify 定）：
- m1∩m2 一致的表直收（provenance=intersection）；
- 两 teacher 分歧的表名，仅当脚本内**字面出现**（约定 A 门）且方向可 AST 判定时救回
  （provenance=disagreement_rescued，dir_source=ast）；
- 所有入选过约定 A 门（`adjudicate_aid._reject_reason`：字面/动态/路径/tempview）；
- 方向 AST 优先，缺失取 teacher 共识，方向分歧且不可 AST 定 → 弃该边；
- **零合成名**（∩ 合成生成池 = ∅，防泄漏，构造性保证）；
- 空样本 ~20% 配比；训练∩测试金标 content-hash 去污染。
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from realeval.adjudicate_aid import _reject_reason
from realeval.dir_fix import sql_direction
from realeval.hashutil import content_hash
from realeval.teacher_label import load_pool

SEED = 20260707


def _role_map(rec: dict) -> dict[str, str]:
    """teacher 记录 → {规范化表名: 'r'|'w'}（写优先）。"""
    role: dict[str, str] = {}
    for it in rec.get("writes") or []:
        t = (it.get("table") if isinstance(it, dict) else it)
        if t:
            role[str(t).strip().lower()] = "w"
    for it in rec.get("reads") or []:
        t = (it.get("table") if isinstance(it, dict) else it)
        if t:
            k = str(t).strip().lower()
            role.setdefault(k, "r")
    return role


def _load_labels(path: Path) -> dict[str, dict]:
    out = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                out[r["chash"]] = r
    return out


def _gold_hashes(gold_paths) -> set[str]:
    hs = set()
    for p in gold_paths:
        p = Path(p)
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                c = r.get("content")
                if c:
                    hs.add(content_hash(c))
    return hs


def _col_map(rec: dict) -> dict:
    """067 单 teacher 记录 → {规范化表名: canon_cols 结果}（与 build_gold_b._col_map 同口径）。"""
    from eval.metrics import canon_cols
    cm: dict = {}
    for side in ("writes", "reads"):
        for it in rec.get(side) or []:
            if not isinstance(it, dict):
                continue
            t = it.get("table")
            if not t:
                continue
            k = str(t).strip().lower()
            cc = canon_cols(it.get("columns"))
            if k not in cm:
                cm[k] = cc
            elif cm[k] is None or cc is None:
                cm[k] = cm[k] if cc is None else cc
            else:
                cm[k] = cm[k] | cc
    return cm


def build_record(chash, content, task_type, m1, m2, synth_pool, keep_columns: bool = False) -> dict | None:
    """构建单条银标（无有效表 → is_empty 空样本）。teacher 缺失/报错 → None（跳过）。
    keep_columns=True（067）→ 列取 m1（pair[0]）；缺省列恒 None（表级银标零回归）。"""
    if m1 is None or m2 is None or m1.get("error") or m2.get("error"):
        return None
    r1, r2 = _role_map(m1), _role_map(m2)
    t1, t2 = set(r1), set(r2)
    cm1 = _col_map(m1) if keep_columns else {}
    role_ast = sql_direction(content)

    reads, writes = [], []
    edge_prov, edge_dir = [], []
    for t in sorted(t1 | t2):
        if _reject_reason(t, content):        # 约定 A：字面/动态/路径/tempview 门
            continue
        if t in synth_pool:                   # 零合成名（防泄漏，构造性）
            continue
        agree = t in t1 and t in t2
        if agree:
            if t in role_ast:
                direction, dsrc = role_ast[t], "ast"
            elif r1[t] == r2[t]:
                direction, dsrc = r1[t], "teacher"
            else:
                continue                       # 方向分歧且无 AST → 弃边
            prov = "intersection"
        else:
            if t not in role_ast:              # 分歧仅当 AST 可定方向才救回
                continue
            direction, dsrc = role_ast[t], "ast"
            prov = "disagreement_rescued"
        cols = (sorted(cm1[t]) if cm1.get(t) else None) if keep_columns else None
        (writes if direction == "w" else reads).append({"table": t, "columns": cols})
        edge_prov.append(prov)
        edge_dir.append(dsrc)

    is_empty = not reads and not writes
    return {
        "chash": chash,
        "content": content,
        "task_type": task_type,
        "reads": reads,
        "writes": writes,
        "is_empty": is_empty,
        "provenance": ("intersection" if all(p == "intersection" for p in edge_prov)
                       else "disagreement_rescued") if edge_prov else "empty",
        "dir_source": ("ast" if "ast" in edge_dir else "teacher") if edge_dir else "none",
    }


def build_record_consensus(chash, content, task_type, recs: list[dict | None],
                           min_agree: int = 2, synth_pool: set[str] | None = None,
                           keep_columns: bool = False) -> dict | None:
    """068：N-teacher **2-of-3 多数共识** 单条银标（区别 067 pair：无单 teacher AST 救回，
    守≥2 独立厂商=召回宽于 2-of-2 交集、精度仍由双厂商背书）。errored/缺失 teacher 弃权（不投票）。
    列（keep_columns）复用 build_gold_b._decide_cols：投票 teacher 中 ≥2 家给具体列取交集、否则 None。"""
    from realeval.build_gold_b import _decide_cols as _decide_cols_gold
    active = [r for r in recs if r is not None and not r.get("error")]
    if len(active) < min_agree:                    # 投票方不足以成共识 → 跳过
        return None
    roles = [_role_map(r) for r in active]
    cmaps = [_col_map(r) for r in active] if keep_columns else []
    role_ast = sql_direction(content)
    synth_pool = synth_pool or set()

    reads, writes = [], []
    for t in sorted({t for rm in roles for t in rm}):
        if _reject_reason(t, content):             # 约定 A：字面/动态/路径/tempview 门
            continue
        if t in synth_pool:                        # 零合成名（防泄漏）
            continue
        voter_idx = [i for i, rm in enumerate(roles) if t in rm]
        if len(voter_idx) < min_agree:             # 多数不足 → 不入
            continue
        if t in role_ast:
            direction = role_ast[t]
        else:
            dirs = {roles[i][t] for i in voter_idx}
            if len(dirs) == 1:
                direction = next(iter(dirs))
            else:
                continue                           # 方向分歧且无 AST → 弃边
        cols = _decide_cols_gold(t, voter_idx, cmaps) if keep_columns else None
        (writes if direction == "w" else reads).append({"table": t, "columns": cols})

    is_empty = not reads and not writes
    return {"chash": chash, "content": content, "task_type": task_type,
            "reads": reads, "writes": writes, "is_empty": is_empty,
            "provenance": f"consensus>={min_agree}/{len(recs)}",
            "dir_source": "ast_or_teacher"}


def build_consensus(pool_dir, labels_dir, teachers: list[str], gold_paths, synth_pool: set[str],
                    min_agree: int = 2, empty_ratio: float = 0.20, seed: int = SEED,
                    keep_columns: bool = False) -> list[dict]:
    """068：N-teacher 2-of-3 多数共识 silver 全量构建（表+列）。"""
    labels_dir = Path(labels_dir)
    by_teacher = {t: _load_labels(labels_dir / f"{t}.jsonl") for t in teachers}
    gold_hs = _gold_hashes(gold_paths)

    nonempty, empty = [], []
    for cand in load_pool(pool_dir):
        ch = cand["chash"]
        if ch in gold_hs:                          # 污染护栏：训练∩测试=∅
            continue
        recs = [by_teacher[t].get(ch) for t in teachers]
        rec = build_record_consensus(ch, cand["content"], cand["task_type"], recs,
                                      min_agree=min_agree, synth_pool=synth_pool,
                                      keep_columns=keep_columns)
        if rec is None:
            continue
        (empty if rec["is_empty"] else nonempty).append(rec)

    if nonempty:
        target_e = round(empty_ratio / (1 - empty_ratio) * len(nonempty))
        rng = random.Random(seed)
        rng.shuffle(empty)
        empty = empty[:target_e]
    out = nonempty + empty
    random.Random(seed + 1).shuffle(out)
    return out


def build(pool_dir, labels_dir, gold_paths, synth_pool: set[str],
          empty_ratio: float = 0.20, seed: int = SEED,
          pair: tuple[str, str] = ("m1", "m2"), keep_columns: bool = False) -> list[dict]:
    """pair：取交集的两 teacher 名（059 bulk 用 m_flash,m1 跨厂商；默认 m1,m2 兼容旧调用）。
    keep_columns=True（067）→ 表级 recipe 不变（双 teacher 交集，protects 门②），列取 pair[0]。"""
    labels_dir = Path(labels_dir)
    m1 = _load_labels(labels_dir / f"{pair[0]}.jsonl")
    m2 = _load_labels(labels_dir / f"{pair[1]}.jsonl")
    gold_hs = _gold_hashes(gold_paths)

    nonempty, empty = [], []
    for cand in load_pool(pool_dir):
        ch = cand["chash"]
        if ch in gold_hs:                      # 污染护栏：训练∩测试=∅
            continue
        rec = build_record(ch, cand["content"], cand["task_type"],
                            m1.get(ch), m2.get(ch), synth_pool, keep_columns=keep_columns)
        if rec is None:
            continue
        (empty if rec["is_empty"] else nonempty).append(rec)

    # 空样本 ~empty_ratio 配比：E/(N+E)=ratio → E=ratio/(1-ratio)*N，确定性下采样
    if nonempty:
        target_e = round(empty_ratio / (1 - empty_ratio) * len(nonempty))
        rng = random.Random(seed)
        rng.shuffle(empty)
        empty = empty[:target_e]
    out = nonempty + empty
    random.Random(seed + 1).shuffle(out)
    return out


def _load_synth_pool() -> set[str]:
    try:
        from realeval.leak_analysis import train_table_pool
        return {t.lower() for t in train_table_pool()}
    except Exception:
        return set()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", required=True)
    ap.add_argument("--labels", default="realeval/teacher_labels")
    ap.add_argument("--exclude-gold", nargs="*", default=[
        "realeval/gold/real.jsonl", "realeval/gold/real-jvm.jsonl",
        "realeval/gold/real-b.jsonl", "realeval/gold/real-c.jsonl"])
    ap.add_argument("--empty-ratio", type=float, default=0.20)
    ap.add_argument("--pair", default="m1,m2", help="取交集的两 teacher（059 bulk 用 m_flash,m1）")
    ap.add_argument("--teachers", default=None,
                    help="068：≥3 teacher 逗号列表，走 2-of-3 多数共识路径（覆盖 --pair）")
    ap.add_argument("--min-agree", type=int, default=2, help="068：多数共识最少一致 teacher 数")
    ap.add_argument("--keep-columns", action="store_true",
                    help="067：列取 pair[0]（表级 recipe 不变），缺省列恒 None")
    ap.add_argument("--out", default="data/silver.jsonl")
    args = ap.parse_args(argv)

    synth = _load_synth_pool()
    if args.teachers:                              # 068：多数共识路径
        teachers = [t for t in args.teachers.split(",") if t]
        recs = build_consensus(args.pool, args.labels, teachers, args.exclude_gold, synth,
                               min_agree=args.min_agree, empty_ratio=args.empty_ratio,
                               keep_columns=args.keep_columns)
        pair = teachers
    else:                                          # 059/067 兼容：pair 交集路径
        pair = tuple(args.pair.split(","))
        recs = build(args.pool, args.labels, args.exclude_gold, synth, args.empty_ratio,
                     pair=pair, keep_columns=args.keep_columns)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    ne = sum(1 for r in recs if not r["is_empty"])
    print(f"build_silver: pair={pair} total={len(recs)} nonempty={ne} empty={len(recs)-ne} "
          f"empty_ratio={(len(recs)-ne)/max(1,len(recs)):.3f} synth_pool={len(synth)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
