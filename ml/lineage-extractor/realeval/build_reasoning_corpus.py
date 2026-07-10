"""059 推理蒸馏语料：pro 思维链 + 拒绝采样。

punch #1（最大杠杆）——让小模型学 deepseek-v4-pro「先思考再答」。plain SFT 只教最终
答案，推理能力传不过去；这里把 pro 的思维链轨迹当训练目标的一部分。

**拒绝采样（质量护栏）**：只留 pro 自答与 bulk 银标（flash∩qwen 交集）**一致**的样本——
三重背书（flash、qwen、pro 都同意），且保证「推理结论 == 训练目标」，不会出现思维链推向
A、目标却是 B 的自相矛盾语料。默认严格：pro 的 {reads,writes} 角色图与银标完全相等。

输入：
  · silver.jsonl（bulk 非空银标，含 chash/content/task_type/reads/writes）
  · teacher_labels/m3.jsonl（pro 对同一 train 池的逐条记录，含 reasoning 字段）
输出：reasoning-corpus.jsonl，每行 {task_type, content, reasoning, labels:{reads,writes}}。

纯逻辑（match/role 无触网无 torch）→ 可离线单测。
用法: PYTHONPATH=. python3 realeval/build_reasoning_corpus.py \
        --silver data/silver.jsonl --pro realeval/teacher_labels/m3.jsonl \
        --out data/reasoning-corpus.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.build_gold_b import _role_map


def _labels_role(row: dict) -> dict[str, str]:
    """银标行（顶层 reads/writes）→ {表: 'r'|'w'} 角色图。"""
    return _role_map({"reads": row.get("reads") or [], "writes": row.get("writes") or []})


def matches(silver_row: dict, pro_rec: dict, mode: str = "strict") -> bool:
    """pro 自答是否与银标一致（拒绝采样判据）。
    - strict：角色图完全相等（表集 + 方向都一致）。
    - superset：银标每张表 pro 都以相同方向命中（pro 可多，不可漏/错向）。"""
    sr = _labels_role(silver_row)
    pr = _role_map(pro_rec)
    if mode == "strict":
        return sr == pr
    return all(t in pr and pr[t] == d for t, d in sr.items())


def _load_jsonl(path: Path) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            out[r["chash"]] = r
    return out


def build(silver_path, pro_path, mode: str = "strict") -> tuple[list[dict], dict]:
    silver = _load_jsonl(Path(silver_path))
    pro = _load_jsonl(Path(pro_path))
    rows: list[dict] = []
    stats = {"silver_nonempty": 0, "pro_missing": 0, "pro_error": 0,
             "no_reasoning": 0, "mismatch": 0, "kept": 0}
    for ch, s in silver.items():
        if s.get("is_empty") or (not s.get("reads") and not s.get("writes")):
            continue  # 推理语料只要非空样本（空样本无需教推理）
        stats["silver_nonempty"] += 1
        p = pro.get(ch)
        if p is None:
            stats["pro_missing"] += 1
            continue
        if p.get("error"):
            stats["pro_error"] += 1
            continue
        if not p.get("reasoning"):
            stats["no_reasoning"] += 1
            continue
        if not matches(s, p, mode):
            stats["mismatch"] += 1
            continue
        rows.append({
            "task_type": s["task_type"],
            "content": s["content"],
            "reasoning": p["reasoning"],
            "labels": {"reads": s.get("reads") or [], "writes": s.get("writes") or []},
        })
        stats["kept"] += 1
    return rows, stats


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--silver", default="data/silver.jsonl")
    ap.add_argument("--pro", default="realeval/teacher_labels/m3.jsonl")
    ap.add_argument("--mode", choices=["strict", "superset"], default="strict")
    ap.add_argument("--out", default="data/reasoning-corpus.jsonl")
    args = ap.parse_args(argv)

    rows, stats = build(args.silver, args.pro, args.mode)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    keep_rate = stats["kept"] / max(1, stats["silver_nonempty"])
    print(f"build_reasoning_corpus: mode={args.mode} kept={stats['kept']} "
          f"keep_rate={keep_rate:.3f} → {out}\n  {stats}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
