"""059：离线分析 dump_preds 落盘的预测——量化 literal-grounding 过滤器对 ALL-p 的提升上限。

grounding 过滤器（确定性，执行任务自带规则「表名须字面出现在脚本里」，非指标作弊）：
丢掉预测表中**叶名（点分末段）不在脚本文本里**的项，即消掉纯幻觉表名（凭空造名），
保留「限定名但可定位」（`db.s.orders` vs 脚本 `orders`）。

输出：raw vs grounded 的 ALL-p（canon=False/True 两口径）+ FP 分解（可杀幻觉 / 可定位但错）。

用法: PYTHONPATH=. python3 realeval/analyze_grounding.py --preds out/preds-c-run-059-plain.jsonl
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import aggregate, score_row, tables


def leaf(t: str) -> str:
    return (t or "").strip().lower().split(".")[-1]


def is_dynamic(t: str) -> bool:
    """任务规则：忽略动态构建的表名——含占位符/变量符（`{}` 格式串、`$var`、`%s`）。"""
    return any(ch in (t or "") for ch in "${}%")


def is_grounded(t: str, content_lower: str) -> bool:
    lf = leaf(t)
    return bool(lf) and lf in content_lower


def keep_table(t: str, content_lower: str) -> bool:
    """确定性保留判据（执行任务自带规则，非指标作弊）：叶名字面出现在脚本 且 非动态名。"""
    return is_grounded(t, content_lower) and not is_dynamic(t)


def filter_pred(pred: dict, content: str) -> dict:
    cl = content.lower()
    def keep(items):
        return [it for it in (items or [])
                if isinstance(it, dict) and keep_table(it.get("table", ""), cl)]
    out = {"reads": keep(pred.get("reads")), "writes": keep(pred.get("writes"))}
    if pred.get("_invalid"):
        out["_invalid"] = True
    return out


def metrics_over(rows, use_filter: bool, canon: bool):
    counts_all, counts_ne = [], []
    for r in rows:
        pred = filter_pred(r["pred"], r["content"]) if use_filter else r["pred"]
        c = score_row(r["labels"], pred, r["content"], canon=canon)
        counts_all.append(c)
        if not r["is_empty"]:
            counts_ne.append(c)
    return aggregate(counts_all), aggregate(counts_ne)


def decompose_fp(rows):
    """把假阳表分成：可杀幻觉（叶名不在脚本）/ 可定位但错（叶名在脚本、仍非 gold）。"""
    killable = groundable_wrong = total_fp = 0
    empty_fp = 0  # 空脚本上的 FP（ALL-p 主拖累）
    for r in rows:
        cl = r["content"].lower()
        gr, gw = tables(r["labels"].get("reads")), tables(r["labels"].get("writes"))
        gold = gr | gw
        for it in (r["pred"].get("reads") or []) + (r["pred"].get("writes") or []):
            if not (isinstance(it, dict) and it.get("table")):
                continue
            t = it["table"].strip().lower()
            if t in gold:
                continue
            total_fp += 1
            if r["is_empty"]:
                empty_fp += 1
            if is_grounded(t, cl):
                groundable_wrong += 1
            else:
                killable += 1
    return dict(total_fp=total_fp, killable=killable, groundable_wrong=groundable_wrong,
                empty_fp=empty_fp)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", required=True)
    ap.add_argument("--report", default=None)
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.preds).read_text(encoding="utf-8").splitlines() if l.strip()]
    ne = sum(1 for r in rows if not r["is_empty"])

    L = [f"# 059 grounding 过滤器分析 — {Path(args.preds).name}", "",
         f"- gold C: {len(rows)} 条（非空 {ne}）", "",
         "| 配置 | 口径 | ALL-p | ALL-r | ALL-dir | ALL-hall | 非空-p | 非空-r |",
         "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for canon in (False, True):
        for use_filter in (False, True):
            a, n = metrics_over(rows, use_filter, canon)
            tag = ("grounded" if use_filter else "raw")
            cc = ("canon" if canon else "exact")
            L.append(f"| {tag} | {cc} | {a['precision']:.4f} | {a['recall']:.4f} | "
                     f"{a['direction_acc']:.4f} | {a['hallucination']:.4f} | "
                     f"{n['precision']:.4f} | {n['recall']:.4f} |")
    d = decompose_fp(rows)
    L += ["", "## FP 分解（exact 口径）", "",
          f"- 总 FP 表数：{d['total_fp']}",
          f"- 其中空脚本上的 FP：{d['empty_fp']}（ALL-p 主拖累）",
          f"- 可杀幻觉（叶名不在脚本，grounding 过滤器直接消掉）：{d['killable']}",
          f"- 可定位但错（叶名在脚本、仍非 gold，过滤器杀不掉，需更强手段）：{d['groundable_wrong']}"]
    report = "\n".join(L) + "\n"
    print(report)
    if args.report:
        out = Path(args.report); out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
