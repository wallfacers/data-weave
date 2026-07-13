"""054 fresh-repo 测试集 B：多 teacher 一致性 **auto-gold**（无人工裁决）。

凭据卡点解除后（GITHUB_TOKEN 采新仓 + m1/m2/m3 teacher 打标），用**多 teacher 一致**
把候选自动裁成 held-out gold，检验 #3 分级校准前沿在**真·domain shift** 下的泛化——
这是 `conf_calibration_cv.py`（CV de-bias，只校同分布偏置）覆盖不到的一环。

裁决口径（每条候选逐表）：
  · 表 t 入 gold ⟺ ① 过约定 A 字面门（`_reject_reason(t, content)` 为空：非动态/路径/
    tempview，且字面出现）② ≥K 个 teacher 含 t（默认 K=teacher 数，即**全体一致**）；
  · 方向：AST 优先（`sql_direction`，SQL target 锚定可信）；否则取**含 t 的 teacher 的共识
    方向**（全体一致 r/w）；二者皆无 → 弃该边（宁缺毋滥，避免污染 precision 口径）；
  · 保留自然分布（含空 gold 脚本，不下采样）——测试集须保真先验；
  · 与既有 gold（若磁盘尚存）content-hash 重合的候选剔除。

**诚实边界**（写入 FINDINGS）：一致性 gold 偏向「teacher 都能找到的表」，会漏掉三家都错过
的真表 → recall 口径乐观、precision 口径相对可信；是人工金标的**弱下位替代**，非等价。
distill-3b 的 161 条真实训练银标已 gitignored + 不在 HF，**无法按 hash 精确排除**训练污染，
仅靠「查询多样化 + 与 567 池精确重合对 GitHub 海量语料可忽略」缓解，如实披露。

纯逻辑（`decide_tables` 不触网/无 torch）→ 可离线单测。
用法: PYTHONPATH=. python3 realeval/build_gold_b.py \
        --pool realeval/pool-b --labels realeval/teacher_labels-b --out realeval/gold/real-b.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.adjudicate_aid import _reject_reason
from realeval.dir_fix import sql_direction
from realeval.hashutil import content_hash
from realeval.teacher_label import load_pool


def _role_map(rec: dict) -> dict[str, str]:
    """单 teacher 记录 → {规范化表名: 'r'|'w'}（写优先，与 build_silver 同口径）。"""
    role: dict[str, str] = {}
    for it in rec.get("writes") or []:
        t = it.get("table") if isinstance(it, dict) else it
        if t:
            role[str(t).strip().lower()] = "w"
    for it in rec.get("reads") or []:
        t = it.get("table") if isinstance(it, dict) else it
        if t:
            role.setdefault(str(t).strip().lower(), "r")
    return role


def _col_map(rec: dict) -> dict:
    """067 单 teacher 记录 → {规范化表名: canon_cols 结果}（弃权→None）。表两侧都扫，
    同表具体优先于弃权、双具体取并（与 metrics._item_cols 同口径）。"""
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


def _decide_cols(t: str, voter_idx: list[int], cmaps: list[dict]) -> list[str] | None:
    """067 列级一致裁决：投票 teacher 中给具体列集的 ≥2 方取交集；不足双方具体或交集空 → None。"""
    present = [cmaps[i][t] for i in voter_idx if cmaps[i].get(t) is not None]
    if len(present) < 2:
        return None                             # 不足双方具体列 → 弃权
    inter = set.intersection(*present)
    return sorted(inter) if inter else None     # 交集空 → 弃权


def decide_tables(content: str, teacher_recs: list[dict], min_agree: int,
                  columns: bool = False) -> dict:
    """核心裁决（纯函数）：多 teacher 角色图 + AST 方向 → gold labels。

    teacher_recs：本候选各 teacher 的 {reads,writes} 记录（已剔除 _error）。
    columns=False（默认）→ 列恒 None，行为与既有完全一致（表级 gold 零回归）；
    columns=True → 对每个入 gold 的表追加列级交集裁决（067）。
    返回 {"reads":[{table,columns}], "writes":[...], "n_agree_edges":int}。"""
    roles = [_role_map(r) for r in teacher_recs]
    cmaps = [_col_map(r) for r in teacher_recs] if columns else []
    role_ast = sql_direction(content)
    # 候选表全集 = 任一 teacher 提到的、且过约定 A 门的表。
    all_tables = sorted({t for rm in roles for t in rm})
    reads, writes = [], []
    agree: dict[str, int] = {}                   # 068：每条入 gold 边的一致票数（供 3-of-3 子集+治理路由）
    for t in all_tables:
        if _reject_reason(t, content):          # 约定 A：字面/动态/路径/tempview 门
            continue
        voter_idx = [i for i, rm in enumerate(roles) if t in rm]
        if len(voter_idx) < min_agree:          # 一致票数不足 → 不入 gold
            continue
        # 方向：AST 优先 → teacher 共识 → 弃边。
        if t in role_ast:
            direction = role_ast[t]
        else:
            dirs = {roles[i][t] for i in voter_idx}
            if len(dirs) == 1:
                direction = next(iter(dirs))
            else:
                continue                        # 方向分歧且无 AST → 弃边
        gold_cols = _decide_cols(t, voter_idx, cmaps) if columns else None
        (writes if direction == "w" else reads).append({"table": t, "columns": gold_cols})
        agree[t] = len(voter_idx)               # 068：记录该表一致票数
    return {"reads": reads, "writes": writes, "n_agree_edges": len(reads) + len(writes),
            "agree": agree}


def filter_unanimous(row: dict, n_teachers: int) -> dict:
    """068：把一行 gold 过滤成 3-of-3 一致高置信子集——仅保留 agree==n_teachers 的边。
    行结构不变（labels/consensus），仅 labels.reads/writes 被裁到全体一致边；行仍保留（可空）。"""
    agree = (row.get("consensus") or {}).get("agree") or {}
    lab = row.get("labels") or {}
    reads = [e for e in lab.get("reads") or [] if agree.get(e["table"]) == n_teachers]
    writes = [e for e in lab.get("writes") or [] if agree.get(e["table"]) == n_teachers]
    new = dict(row)
    new["labels"] = {"reads": reads, "writes": writes}
    new["is_empty"] = not (reads or writes)
    new["provenance"] = f"unanimous={n_teachers}/{n_teachers}"
    return new


def _load_labels(path: Path) -> dict[str, dict]:
    out: dict[str, dict] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                out[r["chash"]] = r
    return out


def _gold_hashes(paths) -> set[str]:
    hs: set[str] = set()
    for p in paths:
        p = Path(p)
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                c = json.loads(line).get("content")
                if c:
                    hs.add(content_hash(c))
    return hs


def build(pool_dir, labels_dir, teachers: list[str], exclude_gold, min_agree: int | None,
          columns: bool = False, error_abstain: bool = False) -> tuple[list[dict], dict]:
    labels_dir = Path(labels_dir)
    by_teacher = {t: _load_labels(labels_dir / f"{t}.jsonl") for t in teachers}
    exclude_hs = _gold_hashes(exclude_gold)
    ma = min_agree if min_agree is not None else len(teachers)

    rows: list[dict] = []
    stats = {"pool": 0, "excluded_gold": 0, "missing_labels": 0, "with_error": 0,
             "error_abstained": 0, "nonempty": 0, "empty": 0}
    for cand in load_pool(pool_dir):
        stats["pool"] += 1
        ch = cand["chash"]
        if ch in exclude_hs:
            stats["excluded_gold"] += 1
            continue
        recs = [by_teacher[t].get(ch) for t in teachers]
        if any(r is None for r in recs):
            stats["missing_labels"] += 1
            continue
        errored = [r for r in recs if r.get("error")]
        if errored:
            if error_abstain:
                # 068：errored teacher 弃权（视作空 reads/writes），行保留——单厂商错不杀 2-of-3 共识
                recs = [{"reads": [], "writes": []} if r.get("error") else r for r in recs]
                stats["error_abstained"] += len(errored)
            else:
                stats["with_error"] += 1
                continue
        labels = decide_tables(cand["content"], recs, ma, columns=columns)
        is_empty = labels["n_agree_edges"] == 0
        stats["empty" if is_empty else "nonempty"] += 1
        rows.append({
            "chash": ch,
            "task_type": cand["task_type"],
            "content": cand["content"],
            "labels": {"reads": labels["reads"], "writes": labels["writes"]},
            "is_empty": is_empty,
            "consensus": {"agree": labels["agree"], "n_teachers": len(teachers)},  # 068
            "provenance": f"agree>={ma}/{len(teachers)}",
        })
    return rows, stats


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", default="realeval/pool-b")
    ap.add_argument("--labels", default="realeval/teacher_labels-b")
    ap.add_argument("--teachers", default="m1,m2,m3")
    ap.add_argument("--min-agree", type=int, default=None,
                    help="入 gold 的最少一致 teacher 数；缺省=全体一致")
    ap.add_argument("--exclude-gold", nargs="*", default=[
        "realeval/gold/real.jsonl", "realeval/gold/real-jvm.jsonl"])
    ap.add_argument("--columns", action="store_true",
                    help="067：开列级一致裁决（双 teacher 交集），缺省列恒 None")
    ap.add_argument("--error-abstain", action="store_true",
                    help="068：errored teacher 弃权而非丢整行（单厂商错不杀 2-of-3 共识）")
    ap.add_argument("--out", default="realeval/gold/real-b.jsonl")
    ap.add_argument("--unanimous-out", default=None,
                    help="068：≥3 teacher 时附产 3-of-3 一致高置信子集路径（如 real-c-tri-unan.jsonl）")
    args = ap.parse_args(argv)

    teachers = [t for t in args.teachers.split(",") if t]
    rows, stats = build(args.pool, args.labels, teachers, args.exclude_gold, args.min_agree,
                        columns=args.columns, error_abstain=args.error_abstain)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"build_gold_b: teachers={teachers} min_agree={args.min_agree or len(teachers)} "
          f"→ {out}\n  {stats}")
    # 068：≥3 teacher 且 min_agree<全体 时，附产 3-of-3 一致高置信子集（unanimous）。
    if args.unanimous_out and len(teachers) >= 3:
        unan_rows = [filter_unanimous(r, len(teachers)) for r in rows]
        u_nonempty = sum(1 for r in unan_rows if not r["is_empty"])
        uo = Path(args.unanimous_out)
        with uo.open("w", encoding="utf-8") as f:
            for r in unan_rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"  + unanimous({len(teachers)}/{len(teachers)}) → {uo}  nonempty={u_nonempty}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
