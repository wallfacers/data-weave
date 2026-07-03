"""041 合成数据管线（FR-014 可复现：固定 seed + 锁版本 requirements）。

三源：
  1. HF 公开数据集提供真实感表/列名池（gretelai/synthetic_text_to_sql Apache-2.0、
     b-mc2/sql-create-context cc-by-4.0）——只取名字，不取语句（labels 必须零噪声）。
  2. 数仓风格合成名池（ods_/dwd_/dws_/ads_ 前缀 + schema 限定）。
  3. templates.py 模板注入 → (content, labels) 对。

split 隔离：heldout = HELDOUT_TEMPLATES（形态隔离）+ 训练模板的未见名字组合；
训练/heldout 名池不相交（防名字记忆泄漏）。

用法：
  python data/synth_pipeline.py --out data/out --train-size 10000 --heldout-size 240
"""

from __future__ import annotations

import argparse
import json
import random
import re
from pathlib import Path

from templates import TRAIN_TEMPLATES, HELDOUT_TEMPLATES, Ctx, Template

SEED = 20260703

WAREHOUSE_SCHEMAS = ["ods", "dwd", "dws", "ads", "dw", "stg", "mart", "bi"]
WAREHOUSE_BASES = [
    "orders", "users", "payments", "events", "clicks", "sessions", "inventory",
    "shipments", "refunds", "products", "vendors", "campaigns", "exposure",
    "risk_score", "device_log", "coupon_use", "member_point", "cart_item",
]
WAREHOUSE_SUFFIXES = ["", "_di", "_df", "_daily", "_hourly", "_full", "_delta"]
FALLBACK_COLUMNS = [
    "id", "user_id", "order_id", "amount", "status", "created_at", "updated_at",
    "biz_date", "channel", "category", "price", "quantity", "score", "region",
    "device_id", "event_type", "duration_ms", "is_valid",
]

_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{2,40}$")


def harvest_hf_names(limit_rows: int = 4000) -> tuple[list[str], list[str]]:
    """从两个 HF 数据集的 CREATE TABLE 上下文收割表/列名（只取标识符）。"""
    tables: set[str] = set()
    columns: set[str] = set()
    create_re = re.compile(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([A-Za-z_][\w.]*)\s*\(([^)]*)\)",
                           re.IGNORECASE | re.DOTALL)
    try:
        from datasets import load_dataset
        specs = [("gretelai/synthetic_text_to_sql", "sql_context"),
                 ("b-mc2/sql-create-context", "context")]
        for name, field in specs:
            ds = load_dataset(name, split="train", streaming=True)
            for i, row in enumerate(ds):
                if i >= limit_rows:
                    break
                for m in create_re.finditer(row.get(field) or ""):
                    t = m.group(1).lower()
                    if _NAME_RE.match(t.replace(".", "_")):
                        tables.add(t)
                    for coldef in m.group(2).split(","):
                        col = coldef.strip().split()[0].lower() if coldef.strip() else ""
                        if _NAME_RE.match(col):
                            columns.add(col)
    except Exception as e:  # 网络/数据集不可用 → 纯合成名池兜底（管线仍可复现）
        print(f"[warn] HF harvest degraded ({e}); using synthetic pools only")
    return sorted(tables), sorted(columns)


def synth_table_names(rng: random.Random, n: int) -> list[str]:
    out = set()
    while len(out) < n:
        s = rng.choice(WAREHOUSE_SCHEMAS)
        b = rng.choice(WAREHOUSE_BASES)
        suf = rng.choice(WAREHOUSE_SUFFIXES)
        style = rng.random()
        if style < 0.5:
            out.add(f"{s}.{s}_{b}{suf}")
        elif style < 0.8:
            out.add(f"{s}.{b}{suf}")
        else:
            out.add(f"{s}_{b}{suf}")
    return sorted(out)


def build_pools(rng: random.Random) -> tuple[list[str], list[str], list[str]]:
    hf_tables, hf_columns = harvest_hf_names()
    tables = sorted(set(synth_table_names(rng, 400) + hf_tables))
    columns = sorted(set(FALLBACK_COLUMNS + hf_columns))
    rng.shuffle(tables)
    cut = max(60, len(tables) // 6)
    heldout_tables, train_tables = tables[:cut], tables[cut:]
    print(f"pools: train_tables={len(train_tables)} heldout_tables={len(heldout_tables)} columns={len(columns)}")
    return train_tables, heldout_tables, columns


def render(rng: random.Random, tpl: Template, tables: list[str], columns: list[str],
           split_group: str) -> dict:
    ctx = Ctx(rng, tables, columns)
    s = tpl.render(ctx)
    return {
        "task_type": tpl.task_type,
        "content": s.content,
        "labels": {"reads": s.reads, "writes": s.writes},
        "meta": {"template_id": tpl.template_id,
                 "rule_covered": tpl.rule_covered,
                 "source_dataset": "synth+gretelai/synthetic_text_to_sql+b-mc2/sql-create-context",
                 "split_group": split_group},
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/out")
    ap.add_argument("--train-size", type=int, default=10000)
    ap.add_argument("--heldout-size", type=int, default=240)
    args = ap.parse_args()

    rng = random.Random(SEED)
    train_tables, heldout_tables, columns = build_pools(rng)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    train_rows = [render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)], train_tables, columns, "train")
                  for i in range(args.train_size)]
    rng.shuffle(train_rows)

    # heldout：60% 形态隔离模板（rule_covered=False 子集）+ 40% 训练模板的 heldout 名池实例
    heldout_rows = []
    n_iso = int(args.heldout_size * 0.6)
    for i in range(n_iso):
        heldout_rows.append(render(rng, HELDOUT_TEMPLATES[i % len(HELDOUT_TEMPLATES)],
                                   heldout_tables, columns, "heldout"))
    for i in range(args.heldout_size - n_iso):
        heldout_rows.append(render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)],
                                   heldout_tables, columns, "heldout"))
    rng.shuffle(heldout_rows)

    for name, rows in [("train.jsonl", train_rows), ("heldout.jsonl", heldout_rows)]:
        p = out / name
        with p.open("w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"wrote {p} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
