"""050 可用性推进 · Tier 2.1：弃权课程加重（B3 硬负例变体）。

B2（047）证明「掺 20% 玩具型弃权负样本」只带来温和小涨、修不动真实集 58% 的空脚本
过度抽取。对 B2 在 139 条真实脚本上的 55 例过度抽取做归因，发现三大真因（非 B2 的
玩具型可覆盖）：

  1. **泄漏当假阳**：脚本陌生（license 头 / docstring / 非常规样板）时，模型回退去
     吐**合成训练名**（`ads.ads_payments_delta` 等）——记忆病以「在无血缘脚本上凭空
     捏造血缘」的形式发作。
  2. **变量名 / 路径 / 配置键当表**：bash `SNOWFLAKE_TABLE=...`、`schemas/x.json`
     路径、config 键被当字面表名抽出。
  3. **动态 / 日期后缀名**：`jobs_2023_10_13`… 模板名被物化。

B3 = 在 B2 正样本（同 TRAIN_TEMPLATES、同 synth 池）之上，把负样本占比 20%→45%，
且负样本改用**照上述真实失败分布造的 6 型**（B2 的 3 型 + 3 个硬型）。其余配方（SEED/
LoRA/epochs/max_len）逐字不变——与 B2 唯一差 = 负样本组成 + 占比，消融纯净。

pool 口径同 B2（正样本来自 synth 池），故 `leak_analysis --train-pool` 自有池列与
B2/基线同源可比。纯函数不触网络，供无 GPU 单测；`main()` 负责 build_pools 后调纯函数。

用法：PYTHONPATH=. python data/b3_hardneg.py --out data/out-b3
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

try:
    from data.synth_pipeline import SEED, FALLBACK_COLUMNS, build_pools, render
    from data.templates import TRAIN_TEMPLATES, Ctx
    from data.antileak import (_B2_NEG_KINDS, _build_heldout, _names_in_rows,
                               variant_pool_json)
except ImportError:  # 直接 python data/b3_hardneg.py 运行
    from synth_pipeline import SEED, FALLBACK_COLUMNS, build_pools, render  # type: ignore
    from templates import TRAIN_TEMPLATES, Ctx  # type: ignore
    from antileak import (_B2_NEG_KINDS, _build_heldout, _names_in_rows,  # type: ignore
                          variant_pool_json)

B3_NEGATIVE_FRAC = 0.45  # 弃权课程加重：负样本占训练集比例（B2 为 0.20）
_B3_TASK_ROTATION = ["PYTHON", "SHELL", "SCALA", "JAVA"]

_LICENSES = [
    ("# Licensed to the Apache Software Foundation (ASF) under one\n"
     "# or more contributor license agreements. See the NOTICE file\n"
     "# distributed with this work for additional information.\n"),
    ("# Copyright 2019 Example Corp. Licensed under the Apache License, Version 2.0\n"
     "# (the \"License\"); you may not use this file except in compliance with the License.\n"),
    ("# SPDX-License-Identifier: MIT\n# filename: job.py  Author: team-data\n"),
]


def _neg_license_boilerplate(ctx: Ctx, task_type: str) -> str:
    """陌生 license 头 + import/logging/argparse 样板，无任何落表操作 → 应弃权。

    直击真因 1：模型面对陌生样板时回退吐合成训练名（泄漏当假阳）。"""
    lic = _LICENSES[ctx.rng.randrange(len(_LICENSES))]
    body = {
        "PYTHON": ("import argparse, logging, os, sys\n"
                   "logging.basicConfig(level=logging.INFO)\n"
                   "def main():\n"
                   "    ap = argparse.ArgumentParser(description='batch runner')\n"
                   "    ap.add_argument('--verbose', action='store_true')\n"
                   "    args = ap.parse_args()\n"
                   "    logging.info('starting run with %s', args)\n"
                   "if __name__ == '__main__':\n    main()"),
        "SHELL": ("#!/usr/bin/env bash\nset -euo pipefail\n"
                  "usage() { echo \"usage: $0 [--verbose]\"; exit 1; }\n"
                  "[ \"$#\" -gt 2 ] && usage\n"
                  "echo \"env: ${ENVIRONMENT:-dev}\"\nlogger -t job \"started\""),
        "SCALA": ("import org.slf4j.LoggerFactory\n"
                  "object Job {\n  val log = LoggerFactory.getLogger(getClass)\n"
                  "  def main(args: Array[String]): Unit = {\n"
                  "    log.info(s\"args: ${args.mkString(\",\")}\")\n  }\n}"),
        "JAVA": ("import java.util.logging.Logger;\n"
                 "public class Job {\n  static final Logger log = Logger.getLogger(\"job\");\n"
                 "  public static void main(String[] a) {\n"
                 "    log.info(\"started \" + a.length);\n  }\n}"),
    }[task_type]
    return lic + body


def _neg_shell_config(ctx: Ctx, task_type: str) -> str:
    """连接串 / 变量赋值（含 table 字样的变量名）/ 文件路径 / --flag，无字面表读写 → 应弃权。

    直击真因 2：bash 变量名（如 SNOWFLAKE_TABLE=...）、schemas/x.json 路径被当表抽出。"""
    return {
        "SHELL": ("#!/bin/bash\nset -eu\n"
                  "SNOWFLAKE_TABLE=\"${SF_TABLE:-}\"\nHIVE_TABLE_NAME=\"${HTN:-}\"\n"
                  "JDBC=\"jdbc:hive2://${HIVESERVER:-localhost}:10000/${DB:-default}\"\n"
                  "SCHEMA_FILE=\"schemas/locations_metadata.json\"\n"
                  "echo \"connecting: $JDBC using $SCHEMA_FILE\"\n"
                  "beeline -u \"$JDBC\" -e \"!connect\""),
        "PYTHON": ("from configparser import ConfigParser\n"
                   "cfg = ConfigParser(); cfg.read('etl.ini')\n"
                   "SNOWFLAKE_TABLE = cfg.get('sf', 'table', fallback='')\n"
                   "conn = f\"jdbc:mysql://{cfg['db']['host']}/{cfg['db']['name']}\"\n"
                   "schema_path = 'schemas/ndt7schema.json'\n"
                   "print('using', conn, schema_path)"),
        "SCALA": ("val snowflakeTable = sys.env.getOrElse(\"SF_TABLE\", \"\")\n"
                  "val jdbc = s\"jdbc:postgresql://${sys.env(\"PGHOST\")}/${sys.env(\"PGDB\")}\"\n"
                  "val schemaFile = \"schemas/site_metadata.json\"\n"
                  "println(s\"cfg: $jdbc $schemaFile\")"),
        "JAVA": ("String snowflakeTable = System.getenv(\"SF_TABLE\");\n"
                 "String jdbc = \"jdbc:oracle:thin:@\" + System.getenv(\"ORA_HOST\");\n"
                 "String schemaFile = \"schemas/device_metadata.json\";\n"
                 "System.out.println(jdbc + \" \" + schemaFile);"),
    }[task_type]


def _neg_metadata_introspection(ctx: Ctx, task_type: str) -> str:
    """查 information_schema / SHOW TABLES / 系统目录做元数据自省 → 约定 A 范围外，应弃权。

    直击真因 2 延伸：#24/#26 抽出 information_schema.columns / system.information_schema.views。"""
    q = ctx.rng.choice([
        "SELECT table_name FROM information_schema.columns",
        "SHOW TABLES",
        "SELECT * FROM system.information_schema.views",
    ])
    return {
        "PYTHON": (f"cur.execute({q!r})\nfor row in cur.fetchall():\n"
                   "    print('exists:', row[0])"),
        "SHELL": (f"psql -c \"{q}\" | while read t; do echo \"found $t\"; done"),
        "SCALA": (f"spark.sql(\"{q}\").collect().foreach(r => println(r.getString(0)))"),
        "JAVA": (f"ResultSet rs = st.executeQuery(\"{q}\");\n"
                 "while (rs.next()) System.out.println(rs.getString(1));"),
    }[task_type]


# 6 型 = B2 的 3 型（纯计算 / 注释SQL / 动态名）+ 3 个照真实失败分布的硬型。
_B3_NEG_KINDS = list(_B2_NEG_KINDS) + [
    _neg_license_boilerplate, _neg_shell_config, _neg_metadata_introspection,
]


def build_negative_row_b3(rng: random.Random, tables: list[str], columns: list[str],
                          idx: int) -> dict:
    """造一条 B3 弃权负样本行（labels 空），schema 与 render / build_negative_row 同构。"""
    task_type = _B3_TASK_ROTATION[idx % len(_B3_TASK_ROTATION)]
    kind = _B3_NEG_KINDS[idx % len(_B3_NEG_KINDS)]
    ctx = Ctx(rng, tables, columns)
    content = kind(ctx, task_type)
    return {
        "task_type": task_type,
        "content": content,
        "labels": {"reads": [], "writes": []},
        "meta": {"template_id": f"b3-abstain/{kind.__name__[1:]}",
                 "rule_covered": True,
                 "form_family": "b3-abstain",
                 "source_dataset": "synth-b3-hardneg",
                 "split_group": "train"},
    }


def build_b3(rng: random.Random, train_tables: list[str], heldout_tables: list[str],
             columns: list[str], train_size: int, heldout_size: int,
             neg_frac: float) -> tuple[list[dict], list[dict], dict]:
    """B3 = B2 正样本 + 45% 硬负例。与 build_b2 唯一差 = 负样本组成（6 型）+ 占比。"""
    n_neg = int(round(train_size * neg_frac))
    n_pos = train_size - n_neg
    pos_rows = [render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)], train_tables, columns, "train")
                for i in range(n_pos)]
    neg_rows = [build_negative_row_b3(rng, train_tables, columns, i) for i in range(n_neg)]
    train_rows = pos_rows + neg_rows
    rng.shuffle(train_rows)
    heldout_rows = _build_heldout(rng, heldout_tables, columns, heldout_size)
    pool = variant_pool_json("b3", _names_in_rows(train_rows))
    return train_rows, heldout_rows, pool


def _write(out: Path, train_rows: list[dict], heldout_rows: list[dict], pool: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for name, rows in [("train.jsonl", train_rows), ("heldout.jsonl", heldout_rows)]:
        with (out / name).open("w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"wrote {out / name} ({len(rows)} rows)")
    (out / "pool.json").write_text(json.dumps(pool, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {out / 'pool.json'} (train_table_names={len(pool['train_table_names'])}, "
          f"synth_subset={len(pool['synth_generated_subset'])})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--train-size", type=int, default=10000)
    ap.add_argument("--heldout-size", type=int, default=600)
    ap.add_argument("--negative-frac", type=float, default=B3_NEGATIVE_FRAC)
    args = ap.parse_args()

    rng = random.Random(SEED)
    columns = list(FALLBACK_COLUMNS)
    train_tables, heldout_tables, cols = build_pools(rng)
    if cols:
        columns = cols
    train_rows, heldout_rows, pool = build_b3(
        rng, train_tables, heldout_tables, columns,
        args.train_size, args.heldout_size, args.negative_frac)
    neg = sum(1 for r in train_rows if r["meta"]["form_family"] == "b3-abstain")
    print(f"B3: train={len(train_rows)} 负样本={neg} ({neg/len(train_rows):.3f})")
    _write(Path(args.out), train_rows, heldout_rows, pool)


if __name__ == "__main__":
    main()
