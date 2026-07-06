"""T010: 双 teacher 批量打标——逐候选调 m1+m2，content-hash 键缓存 + --resume 续跑。

FR-003 / data-model 实体 2。中断/限速可 --resume 继续，重跑不重复烧配额（Edge Case）。
`_error` 样本照写（带 error 字段），由 build_silver 剔除、不污染银标。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.hashutil import content_hash


def infer_task_type(source: str | None) -> str:
    s = str(source or "").lower()
    if ".scala" in s:
        return "SCALA"
    if ".java" in s:
        return "JAVA"
    if ".sql" in s:
        return "SQL"
    if ".sh" in s or "bash" in s:
        return "SHELL"
    return "PYTHON"


def load_pool(pool_dir: str | Path):
    """遍历候选池 *.json（{content, source, meta}）→ 生成 {chash, content, task_type}。"""
    for f in sorted(Path(pool_dir).glob("*.json")):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        content = d.get("content")
        if not content:
            continue
        yield {
            "chash": content_hash(content),
            "content": content,
            "task_type": infer_task_type(d.get("source")),
        }


def _load_cache(path: Path) -> dict:
    seen = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                seen[r["chash"]] = r
    return seen


def run_labeling(pool_dir, out_dir, clients: dict, resume: bool = True) -> dict:
    """对 pool 每条候选用 clients 里每个 teacher 打标，写 {teacher}.jsonl（append）。返回计数。"""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    caches = {name: _load_cache(out_dir / f"{name}.jsonl") for name in clients}
    handles = {name: (out_dir / f"{name}.jsonl").open("a", encoding="utf-8") for name in clients}
    stats = {"new": 0, "skipped": 0}
    try:
        for cand in load_pool(pool_dir):
            ch = cand["chash"]
            for name, cli in clients.items():
                if resume and ch in caches[name]:
                    stats["skipped"] += 1
                    continue
                res = cli.extract(cand["task_type"], cand["content"])
                rec = {
                    "chash": ch,
                    "teacher": name,
                    "task_type": cand["task_type"],
                    "reads": res.get("reads") or [],
                    "writes": res.get("writes") or [],
                    "error": res.get("_error"),
                }
                handles[name].write(json.dumps(rec, ensure_ascii=False) + "\n")
                handles[name].flush()
                caches[name][ch] = rec
                stats["new"] += 1
    finally:
        for h in handles.values():
            h.close()
    return stats


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", required=True)
    ap.add_argument("--out", default="realeval/teacher_labels")
    ap.add_argument("--teachers", default="m1,m2")
    ap.add_argument("--no-resume", action="store_true")
    args = ap.parse_args(argv)

    from llm.clients import load_clients
    want = set(args.teachers.split(","))
    clients = {k: v for k, v in load_clients().items() if k in want}
    if not clients:
        raise SystemExit("no teachers available (检查 .env 凭据)")
    stats = run_labeling(args.pool, args.out, clients, resume=not args.no_resume)
    print(f"teacher_label: teachers={list(clients)} new={stats['new']} skipped={stats['skipped']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
