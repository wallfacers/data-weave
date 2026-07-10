"""059：用最强 teacher(deepseek-v4-pro=m3)盲复标 gold C 的争议空脚本，翻掉假空标。

争议 = gold 标 is_empty 但 Run A 模型抽出了表。pro **独立**抽取(不看模型预测)，
只有 pro 也抽出**可定位**(叶名在脚本)的表时，才判定原 gold 假空 → 翻成非空(用 pro 的
grounded 标签)。pro 也空 → 保留空标(模型 FP 属实)。保守：不看模型预测、只信 grounded、
存 pro 思维链供审计。输出 arbitrated gold + 翻标报告 + token 用量。

用法: PYTHONPATH=. python3 realeval/arbitrate_gold.py \
        --gold realeval/gold/real-c.jsonl --preds out/preds-c-run-059-plain.jsonl \
        --out realeval/gold/real-c-arbitrated.jsonl --report out/arbitrate-gold.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.analyze_grounding import is_grounded


def _tabs(items):
    return [it for it in (items or [])
            if isinstance(it, dict) and isinstance(it.get("table"), str) and it["table"].strip()]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real-c.jsonl")
    ap.add_argument("--preds", default="out/preds-c-run-059-plain.jsonl")
    ap.add_argument("--out", default="realeval/gold/real-c-arbitrated.jsonl")
    ap.add_argument("--report", default="out/arbitrate-gold.md")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args(argv)

    gold = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    preds = [json.loads(l) for l in Path(args.preds).read_text(encoding="utf-8").splitlines() if l.strip()]
    assert len(gold) == len(preds), f"gold {len(gold)} != preds {len(preds)}（顺序须一致）"

    disputed = []  # (idx, row)
    for i, (g, p) in enumerate(zip(gold, preds)):
        if g.get("is_empty") and (_tabs(p["pred"].get("reads")) or _tabs(p["pred"].get("writes"))):
            disputed.append((i, g))

    from llm.clients import load_clients
    pro = load_clients().get("m3")
    if pro is None:
        raise SystemExit("pro(m3) 不可用（检查 .env）")

    import threading
    from concurrent.futures import ThreadPoolExecutor, as_completed
    results = {}
    usage = {"in": 0, "out": 0}
    lock = threading.Lock()

    def _do(item):
        idx, g = item
        res = pro.extract(g["task_type"], g["content"])
        cl = g["content"].lower()
        pr = [it for it in _tabs(res.get("reads")) if is_grounded(it["table"], cl)]
        pw = [it for it in _tabs(res.get("writes")) if is_grounded(it["table"], cl)]
        with lock:
            results[idx] = {"reads": pr, "writes": pw, "reasoning": res.get("_reasoning"),
                            "error": res.get("_error")}
            if res.get("_usage"):
                usage["in"] += res["_usage"].get("prompt_tokens", 0) or res["_usage"].get("input_tokens", 0)
                usage["out"] += res["_usage"].get("completion_tokens", 0) or res["_usage"].get("output_tokens", 0)

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(_do, it) for it in disputed]
        for k, f in enumerate(as_completed(futs)):
            f.result()
            if (k + 1) % 10 == 0:
                print(f"  arbitrated {k+1}/{len(disputed)}", flush=True)

    # 组装 arbitrated gold：pro 抽出 grounded 表 → 翻成非空。
    flips = []
    out_rows = [dict(g) for g in gold]
    for idx, g in disputed:
        r = results.get(idx, {})
        if r.get("error"):
            continue
        if r["reads"] or r["writes"]:
            out_rows[idx]["labels"] = {"reads": r["reads"], "writes": r["writes"]}
            out_rows[idx]["is_empty"] = False
            out_rows[idx]["arbitrated"] = True
            flips.append((idx, r))

    outp = Path(args.out); outp.parent.mkdir(parents=True, exist_ok=True)
    with outp.open("w", encoding="utf-8") as f:
        for row in out_rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    # 成本
    cost = usage["in"] / 1e6 * 3 + usage["out"] / 1e6 * 6
    L = [f"# 059 gold C pro 仲裁复标报告", "",
         f"- 争议空脚本（gold 空 + 模型抽出表）：{len(disputed)}",
         f"- pro 判定假空翻成非空（label noise 确认）：**{len(flips)}**",
         f"- pro 也判空（模型 FP 属实）：{len(disputed) - len(flips)}",
         f"- token：入 {usage['in']} 出 {usage['out']} → **约 ¥{cost:.2f}**", "",
         "## 翻标样本（原空→pro 抽出真血缘）", ""]
    for idx, r in flips[:20]:
        tabs = [it["table"] for it in r["reads"]] + [it["table"] for it in r["writes"]]
        snip = gold[idx]["content"][:90].replace("\n", " ")
        L.append(f"- #{idx} → {tabs}  |  {snip}")
    report = "\n".join(L) + "\n"
    rp = Path(args.report); rp.parent.mkdir(parents=True, exist_ok=True)
    rp.write_text(report, encoding="utf-8")
    print(report, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
