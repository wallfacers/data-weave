"""059 ②-对比：让主流 teacher(qwen-max=m1 / deepseek-v4-pro=m3)在 gold C 上跑，
落盘成 dump_preds **同格式** 预测，供 rescore_arbitrated 用**同一校准 gold + grounding 尺子**
打分——与自托管 3B 完全同底对比。抓真实 token 用量算 ¥（预算门）。

用法: PYTHONPATH=. python3 realeval/eval_teachers_c.py --teacher m1 \
        --gold realeval/gold/real-c.jsonl --out out/preds-c-teacher-m1.jsonl
"""
from __future__ import annotations

import argparse
import json
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# 输入体量上限：gold C 有 116k-char 离群脚本；截断到 ~40k char(~10k tok)既给足上下文又封顶成本。
MAX_CHARS = 40000
# 计价(¥/1M token)：qwen-max 入 2.4 / 出 9.6；deepseek-v4-pro 入 3 / 出 6（与 arbitrate 一致）。
PRICE = {"m1": (2.4, 9.6), "m3": (3.0, 6.0), "m_flash": (0.5, 1.0)}


def _tabs(items):
    return [it for it in (items or [])
            if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip()]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", required=True, choices=["m1", "m3", "m_flash"])
    ap.add_argument("--gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]

    from llm.clients import load_clients
    cli = load_clients().get(args.teacher)
    if cli is None:
        raise SystemExit(f"teacher {args.teacher} 不可用（检查 .env）")

    results = {}
    usage = {"in": 0, "out": 0}
    lock = threading.Lock()

    def _do(i_r):
        i, r = i_r
        res = cli.extract(r.get("task_type"), (r["content"] or "")[:MAX_CHARS])
        rec = {"content": r["content"], "labels": r["labels"],
               "is_empty": bool(r.get("is_empty")), "task_type": r.get("task_type"),
               "pred": {"reads": _tabs(res.get("reads")), "writes": _tabs(res.get("writes")),
                        "_invalid": bool(res.get("_error"))}}
        with lock:
            results[i] = rec
            if res.get("_usage"):
                usage["in"] += res["_usage"].get("in", 0)
                usage["out"] += res["_usage"].get("out", 0)

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(_do, it) for it in enumerate(rows)]
        for k, f in enumerate(as_completed(futs)):
            f.result()
            if (k + 1) % 20 == 0:
                print(f"  {args.teacher} {k+1}/{len(rows)}", flush=True)

    out = Path(args.out); out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for i in range(len(rows)):
            f.write(json.dumps(results[i], ensure_ascii=False) + "\n")

    pin, pout = PRICE.get(args.teacher, (0, 0))
    cost = usage["in"] / 1e6 * pin + usage["out"] / 1e6 * pout
    inval = sum(1 for r in results.values() if r["pred"]["_invalid"])
    print(f"{args.teacher}: {len(rows)} rows → {out} | invalid {inval} | "
          f"token 入 {usage['in']} 出 {usage['out']} → 约 ¥{cost:.2f}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
