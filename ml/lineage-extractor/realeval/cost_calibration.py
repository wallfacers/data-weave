"""059 预算门：小批校准 → 从真实 usage token 外推全量成本。

每次 teacher 调用回传 `_usage={in,out}`（clients.py 抓 response.usage）。本脚本对
小批候选逐条打标、按 teacher 累计真实 token → 按单价表算「每脚本成本」→ 外推：
  · bulk teachers（flash + qwen-max）× 全量 total（默认 2900）
  · reasoning teacher（pro）× 干净子集 total（默认 1500）
最后把区间收成确定数字，摆预算给用户，点头再放全量。

单价 = 元/百万 token（缓存未命中）。DeepSeek 为用户提供的实价；qwen 为估值（DashScope
实价待确认，脚本照样打印真实 token 量，用户可自行代入）。

用法: PYTHONPATH=. python3 realeval/cost_calibration.py --pool realeval/pool-cal \
        --n 30 --bulk m_flash,m1 --reasoning m3
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

from realeval.teacher_label import load_pool

# 单价（元/M token，缓存未命中）。source: DeepSeek 官方(用户提供) / qwen 估值。
PRICES = {
    "deepseek-v4-flash": {"in": 1.0, "out": 2.0, "src": "官方"},
    "deepseek-v4-pro":   {"in": 3.0, "out": 6.0, "src": "官方"},
    "qwen-max":          {"in": 2.4, "out": 9.6, "src": "估值"},
    "qwen3.7-max":       {"in": 2.4, "out": 9.6, "src": "估值"},
}


def _price_for(model: str) -> dict:
    return PRICES.get(model, {"in": 0.0, "out": 0.0, "src": "未知(0)"})


def calibrate(pool_dir: str, n: int, teacher_names: list[str], max_chars: int = 24000) -> dict:
    """对 pool 前 n 条候选，用 teacher_names 里每个 teacher 打标，累计真实 usage。
    max_chars：跳过巨型离群脚本（对齐全量 24000 上限策略），避免高估。"""
    from llm.clients import load_clients
    all_clients = load_clients()
    clients = {k: all_clients[k] for k in teacher_names if k in all_clients}
    missing = [k for k in teacher_names if k not in all_clients]
    if missing:
        print(f"[warn] teachers 不可用(检查 .env): {missing}")
    if not clients:
        raise SystemExit("no teachers available")

    per = {k: {"model": c._model, "n": 0, "in": 0, "out": 0, "err": 0,
               "max_out": 0, "reasoning_chars": 0}
           for k, c in clients.items()}
    eligible = (c for c in load_pool(pool_dir) if len(c["content"]) <= max_chars)
    cands = list(itertools.islice(eligible, n))
    for cand in cands:
        for name, cli in clients.items():
            res = cli.extract(cand["task_type"], cand["content"])
            p = per[name]
            p["n"] += 1
            if res.get("_error"):
                p["err"] += 1
            u = res.get("_usage")
            if u:
                p["in"] += u["in"]
                p["out"] += u["out"]
                p["max_out"] = max(p["max_out"], u["out"])
            if res.get("_reasoning"):
                p["reasoning_chars"] += len(res["_reasoning"])
    return {"n_scripts": len(cands), "per_teacher": per}


def project(per: dict, teacher: str, total: int) -> dict:
    """把某 teacher 的每脚本均值 token × total → 成本（元）。"""
    p = per[teacher]
    n = max(1, p["n"])
    avg_in, avg_out = p["in"] / n, p["out"] / n
    pr = _price_for(p["model"])
    cost_in = avg_in * total / 1e6 * pr["in"]
    cost_out = avg_out * total / 1e6 * pr["out"]
    return {
        "teacher": teacher, "model": p["model"], "price_src": pr["src"],
        "total": total, "avg_in": avg_in, "avg_out": avg_out,
        "max_out": p["max_out"], "err": p["err"], "n": p["n"],
        "reasoning_chars_avg": p["reasoning_chars"] / n,
        "proj_in_tok": avg_in * total, "proj_out_tok": avg_out * total,
        "cost_in": cost_in, "cost_out": cost_out, "cost": cost_in + cost_out,
    }


def render(cal: dict, bulk: list[str], reasoning: list[str],
           full_total: int, reason_total: int) -> str:
    per = cal["per_teacher"]
    L = [f"# 059 成本校准（{cal['n_scripts']} 条真实脚本实测 token 外推）", ""]
    rows = []
    for t in bulk:
        if t in per:
            rows.append(project(per, t, full_total))
    for t in reasoning:
        if t in per:
            rows.append(project(per, t, reason_total))

    L += ["| teacher | 模型 | 价源 | 实测均值 in/out | 峰值 out | 外推条数 | 输入¥ | 输出¥ | 小计¥ |",
          "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        L.append(f"| {r['teacher']} | {r['model']} | {r['price_src']} | "
                 f"{r['avg_in']:.0f}/{r['avg_out']:.0f} | {r['max_out']} | {r['total']} | "
                 f"{r['cost_in']:.1f} | {r['cost_out']:.1f} | **{r['cost']:.1f}** |")
    total = sum(r["cost"] for r in rows)
    est = any(r["price_src"] == "估值" for r in rows)
    L += ["", f"**总预算 ≈ ¥{total:.1f}（约 ${total/7:.1f}）**"
          + ("　（含 qwen 估值，真实 token 已实测；DashScope 实价代入即精确）" if est else ""), ""]
    # 错误率提示（no_json 等）
    errs = [(t, per[t]["err"], per[t]["n"]) for t in per if per[t]["err"]]
    if errs:
        L.append("> ⚠️ 打标错误（no_json/call 失败）：" +
                 "、".join(f"{t} {e}/{n}" for t, e, n in errs) +
                 "——放全量前需先修（多半是 max_tokens 或思维链吃光 JSON）。")
    reason_present = [t for t in reasoning if t in per and per[t]["reasoning_chars"]]
    if reason_present:
        for t in reason_present:
            L.append(f"> 推理老师 {t} 平均思维链 {per[t]['reasoning_chars']/max(1,per[t]['n']):.0f} 字符/条（供蒸馏）。")
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", required=True)
    ap.add_argument("--n", type=int, default=30)
    ap.add_argument("--bulk", default="m_flash,m1", help="bulk 投票 teacher（外推到 --full-total）")
    ap.add_argument("--reasoning", default="m3", help="推理老师（外推到 --reason-total）")
    ap.add_argument("--full-total", type=int, default=2900)
    ap.add_argument("--reason-total", type=int, default=1500)
    ap.add_argument("--report", default="out/cost-calibration-059.md")
    args = ap.parse_args(argv)

    bulk = [t for t in args.bulk.split(",") if t]
    reasoning = [t for t in args.reasoning.split(",") if t]
    cal = calibrate(args.pool, args.n, bulk + reasoning)
    report = render(cal, bulk, reasoning, args.full_total, args.reason_total)
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(
        json.dumps({"cal": cal, "bulk": bulk, "reasoning": reasoning,
                    "full_total": args.full_total, "reason_total": args.reason_total},
                   ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
