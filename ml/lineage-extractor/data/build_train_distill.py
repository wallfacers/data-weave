"""T014: 银标 → SFT 训练格式（sft_qlora.to_messages 期望 {task_type, content, labels:{reads,writes}}）。

FR-009。纯格式转换：silver.jsonl 顶层 reads/writes → labels 嵌套；task_type/content 透传。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def to_train_row(silver: dict) -> dict:
    return {
        "task_type": silver["task_type"],
        "content": silver["content"],
        "labels": {"reads": silver.get("reads") or [], "writes": silver.get("writes") or []},
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--silver", default="data/silver.jsonl")
    ap.add_argument("--out", default="data/out/train-distill.jsonl")
    args = ap.parse_args(argv)
    rows = [json.loads(l) for l in Path(args.silver).read_text(encoding="utf-8").splitlines() if l.strip()]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(to_train_row(r), ensure_ascii=False) + "\n")
    print(f"build_train_distill: {len(rows)} rows → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
