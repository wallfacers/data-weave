"""052「高召回 + 人工复核残差」置信度分层信封分析。

把验收从「3B 全自动追平 m2」重构为**可部署的分层策略**——回应「错误血缘有后果」的治理约束:

- **Tier 1（自动采纳，治理安全）** = SQL-AST 通道（channel_router.extract_sql_lineage）。
  方向由 sqlglot AST 保证、只吐字面 SQL 里解析出的表 → precision 高、可直接入库。
- **Tier 2（人工复核残差）** = SQL 通道空白的脚本（动态名/shell 编排/框架调用）上，
  模型给出的**候选**。永不自动入库,只进复核队列 → 目标是高召回(帮人不漏),而非 precision。

本脚本先量化 **Tier 1 的 CPU-only 信封**(无需 GPU/模型):
  · Tier 1 全集 precision / 幻觉率、非空子集 recall / 方向;
  · SQL 通道覆盖率(命中脚本数);
  · **模型territory** = 非空金标里 SQL 通道打空的脚本 → Tier 2 复核的召回天花板与复核负载基数。

可选 --model 传入模型预测 jsonl(逐行 {content_hash|idx, reads, writes})算 Tier 2 残差收益。

用法: PYTHONPATH=. python realeval/tiered_envelope.py \
        --gold realeval/gold/real.jsonl --report out/tiered-envelope-A.md
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval.metrics import _align, aggregate, canon_match, score_row, tables
from realeval.channel_router import extract_sql_lineage


def _canon_tp(gold: set[str], got: set[str]) -> int:
    """canon 口径下 got 命中 gold 的表数（限定名⟷短名视为同表）。"""
    return _align(gold, got)[0]


def _nonempty(row: dict) -> bool:
    return bool(tables(row["labels"]["reads"]) or tables(row["labels"]["writes"]))


def _empty_pred(pred: dict) -> bool:
    return not (tables(pred["reads"]) or tables(pred["writes"]))


def _tier1(rows, ne, gated: bool, canon: bool = True) -> dict:
    preds = [extract_sql_lineage(r["content"], exec_gated=gated) for r in rows]
    full_counts = [score_row(r["labels"], p, r["content"], canon=canon) for r, p in zip(rows, preds)]
    ne_preds = [extract_sql_lineage(r["content"], exec_gated=gated) for r in ne]
    ne_counts = [score_row(r["labels"], p, r["content"], canon=canon) for r, p in zip(ne, ne_preds)]
    n_fire = sum(not _empty_pred(p) for p in preds)
    # 模型 territory：非空金标 ∧ SQL 通道打空 → Tier 2 复核领地
    territory = [r for r, p in zip(ne, ne_preds) if _empty_pred(p)]
    territory_true = sum(
        len(tables(r["labels"]["reads"]) | tables(r["labels"]["writes"])) for r in territory)
    return {
        "n_fire": n_fire,
        "full": aggregate(full_counts),
        "nonempty": aggregate(ne_counts),
        "n_territory": len(territory),
        "territory_true_tables": territory_true,
    }


def _union(rows: list[dict], model_by_idx: dict) -> dict:
    """并集高召回 + 通道一致层（052「高召回+人工复核」核心）。

    对每条脚本：SQL-gated 通道表集 S、模型表集 M。
      · 并集 U = S∪M → 复核者看到的全部候选（recall 天花板 = 高召回）；
      · 一致层 A = S∩M（canon）→ 双通道都认，最高置信 → 可自动采纳/快速批；
    量化 union recall、一致层 precision/recall、复核负载（候选/脚本）。"""
    ur_tp = ur_fn = 0                      # union recall
    ap_tp = ap_fp = ar_fn = 0             # agreement precision/recall
    n_cand = 0                            # 复核候选总数（并集大小）
    n_agree = 0
    n_ne = 0
    for i, r in enumerate(rows):           # i 对齐完整 gold 的行号（model_by_idx 的键）
        g = tables(r["labels"]["reads"]) | tables(r["labels"]["writes"])
        if not g:                          # 并集口径只在非空金标上评
            continue
        n_ne += 1
        s = set()
        sp = extract_sql_lineage(r["content"], exec_gated=True)
        s |= tables(sp["reads"]) | tables(sp["writes"])
        mp = model_by_idx.get(i, {"reads": [], "writes": []})
        m = tables(mp["reads"]) | tables(mp["writes"])
        u = s | m
        agree = {t for t in s if any(canon_match(t, x) for x in m)}
        n_cand += len(u)
        n_agree += len(agree)
        # union recall（canon）
        tp = _canon_tp(g, u)
        ur_tp += tp
        ur_fn += len(g) - tp
        # agreement precision/recall（canon）
        atp = _canon_tp(g, agree)
        ap_tp += atp
        ap_fp += len(agree) - _align(agree, g)[0]   # 一致层里不在 gold 的
        ar_fn += len(g) - atp
    return {
        "union_recall": ur_tp / (ur_tp + ur_fn) if ur_tp + ur_fn else 1.0,
        "agree_precision": ap_tp / (ap_tp + ap_fp) if ap_tp + ap_fp else 1.0,
        "agree_recall": ap_tp / (ap_tp + ar_fn) if ap_tp + ar_fn else 1.0,
        "cand_per_script": n_cand / n_ne if n_ne else 0.0,
        "n_agree": n_agree,
        "n_cand": n_cand,
    }


def analyze(rows: list[dict], model_by_idx: dict | None = None) -> dict:
    ne = [r for r in rows if _nonempty(r)]
    ne_true_tables = sum(
        len(tables(r["labels"]["reads"]) | tables(r["labels"]["writes"])) for r in ne)
    out = {
        "n_total": len(rows),
        "n_nonempty": len(ne),
        "ne_true_tables": ne_true_tables,
        "ungated": _tier1(rows, ne, gated=False),
        "gated": _tier1(rows, ne, gated=True),
    }
    if model_by_idx is not None:
        out["union"] = _union(rows, model_by_idx)   # 传完整 rows：i 对齐 model_by_idx 键
    return out


def _tier1_rows(tag: str, t: dict) -> list[str]:
    f, n = t["full"], t["nonempty"]
    return [
        f"| {tag} · 全集 | **{f['precision']:.3f}** | {f['hallucination']:.3f} | — | — |",
        f"| {tag} · 非空 | {n['precision']:.3f} | {n['hallucination']:.3f} | **{n['recall']:.3f}** | {n['direction_acc']:.3f} |",
    ]


def render(a: dict) -> str:
    ung, gat = a["ungated"], a["gated"]
    cap_g = (a["ne_true_tables"] - gat["territory_true_tables"]) / a["ne_true_tables"] if a["ne_true_tables"] else 0.0
    L = [
        "# 052 置信度分层信封（测试集 A / held-out）",
        "",
        "把「3B 全自动追平 m2」重构为**可部署分层**：Tier 1 自动采纳（治理安全），Tier 2 残差进人工复核。",
        "回应「错误血缘有后果」——**只自动入库高 precision 的 Tier 1，不确定的进复核队列**。",
        "",
        f"- 样本：{a['n_total']} 条（非空金标 {a['n_nonempty']}，真表 {a['ne_true_tables']} 个）。",
        "",
        "## Tier 1 — 自动采纳层（SQL-AST 通道，纯 CPU、确定性、方向 AST 保证）",
        "",
        "对比 **ungated**（050 全量启发式）vs **exec-gated**（052：仅执行 sink 内 SQL，剔除 docstring/注释/文档示例）：",
        "",
        "| 口径 | precision | 幻觉率 | recall | 方向 |",
        "| --- | --- | --- | --- | --- |",
        *_tier1_rows("ungated", ung),
        *_tier1_rows("exec-gated", gat),
        "",
        f"- ungated 开火 {ung['n_fire']} 条 → precision {ung['full']['precision']:.3f}（被文档/注释 SQL 污染）。",
        f"- exec-gated 开火 {gat['n_fire']} 条 → precision **{gat['full']['precision']:.3f}**（自动入库安全性由此决定）。",
        f"- exec-gated 自动采纳覆盖非空真表约 **{cap_g:.1%}**（territory 外）。",
        "",
        "## Tier 2 — 人工复核残差（Tier 1 打空的脚本，exec-gated 口径）",
        "",
        f"- 复核领地脚本数：**{gat['n_territory']}** / 非空 {a['n_nonempty']}（{gat['n_territory']/a['n_nonempty']:.1%}）。",
        f"- 领地内真表：**{gat['territory_true_tables']}** 个 = Tier 2 复核召回天花板（模型 surface 候选、人拍板）。",
        f"- 复核负载基数：约每 {a['n_total']} 脚本 {gat['n_territory']} 条进队列（{gat['n_territory']/a['n_total']:.1%}）。",
        "",
        "> Tier 2 模型不自动入库 → 无需 precision 达标；目标高召回帮复核者不漏。",
    ]
    if "union" in a:
        u = a["union"]
        L += [
            "",
            "## 「高召回 + 人工复核」部署信封（SQL-gated ∪ 蒸馏模型，非空子集）",
            "",
            "复核者看到**并集候选**（高召回不漏）；双通道**一致层**最高置信可自动采纳/快速批。",
            "",
            "| 指标 | 值 | 含义 |",
            "| --- | --- | --- |",
            f"| **并集召回** | **{u['union_recall']:.3f}** | 真血缘被 surface 给复核者的比例（高召回目标） |",
            f"| 一致层 precision | {u['agree_precision']:.3f} | 双通道都认的表的正确率（自动采纳安全性） |",
            f"| 一致层 recall | {u['agree_recall']:.3f} | 一致层覆盖的真表比例（自动采纳省多少人力） |",
            f"| 复核负载 | {u['cand_per_script']:.2f} 候选/脚本 | 复核者每脚本需过目的候选数 |",
            f"| 一致/候选 | {u['n_agree']}/{u['n_cand']} | 一致层占全部候选比例 |",
            "",
            "> 一致层高 precision → 可自动采纳省人力；其余候选（并集−一致）进复核队列。",
            "> 并集召回是复核者的召回天花板：越高越不漏真血缘。",
        ]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--report", default="out/tiered-envelope-A.md")
    ap.add_argument("--model", help="模型逐行预测 jsonl（dump_model_preds 产出）→ 加并集/一致层")
    args = ap.parse_args(argv)

    rows = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    model_by_idx = None
    if args.model:
        model_by_idx = {}
        for l in Path(args.model).read_text(encoding="utf-8").splitlines():
            if l.strip():
                d = json.loads(l)
                model_by_idx[d["idx"]] = d
    a = analyze(rows, model_by_idx)
    report = render(a)

    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps(a, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
