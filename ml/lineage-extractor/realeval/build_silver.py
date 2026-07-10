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


def build_record(chash, content, task_type, m1, m2, synth_pool) -> dict | None:
    """构建单条银标（无有效表 → is_empty 空样本）。teacher 缺失/报错 → None（跳过）。"""
    if m1 is None or m2 is None or m1.get("error") or m2.get("error"):
        return None
    r1, r2 = _role_map(m1), _role_map(m2)
    t1, t2 = set(r1), set(r2)
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
        (writes if direction == "w" else reads).append({"table": t, "columns": None})
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


def build(pool_dir, labels_dir, gold_paths, synth_pool: set[str],
          empty_ratio: float = 0.20, seed: int = SEED,
          pair: tuple[str, str] = ("m1", "m2")) -> list[dict]:
    """pair：取交集的两 teacher 名（059 bulk 用 m_flash,m1 跨厂商；默认 m1,m2 兼容旧调用）。"""
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
                            m1.get(ch), m2.get(ch), synth_pool)
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
    ap.add_argument("--out", default="data/silver.jsonl")
    args = ap.parse_args(argv)

    synth = _load_synth_pool()
    pair = tuple(args.pair.split(","))
    recs = build(args.pool, args.labels, args.exclude_gold, synth, args.empty_ratio, pair=pair)
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
