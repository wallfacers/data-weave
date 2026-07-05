"""047 抗泄漏消融训练集变体构造器（041-R 方案 B）。

在**其它配方逐字不变**前提下，只改一个训练数据维度，产出两支消融变体：

- **B1 真实表名增广**：训练表名池从"合成生成名 400 ∪ HF"改为**真实名主导**
  （HF 收割上调 + 合成名仅保留 ~40 个供招牌指标可测），检验"命名分布过窄"
  是否即记忆泄漏根因。
- **B2 开放域弃权训练**：掺入 20% **空标签**弃权负样本（纯计算/日志、注释或打印内
  SQL、动态拼接名——均在约定 A 下应为空），教模型"没把握就输出空"。

每支落 `train.jsonl`（与 synth_pipeline 输出逐字同构，评测器零改动可读）+ `pool.json`
（该变体训练表名全集 + 其中合成生成子集，供 leak_analysis --train-pool 诚实统计泄漏）。

纯函数（`b1_pools` / `build_negative_row` / `variant_pool_json`）不触网络，供无 GPU 单测；
`main()` 负责 HF 收割等 I/O 后调用纯函数。固定 SEED → 同参数 byte-identical。

用法：
  PYTHONPATH=. python data/antileak.py --variant b1 --out data/out-b1
  PYTHONPATH=. python data/antileak.py --variant b2 --out data/out-b2
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

try:
    from data.synth_pipeline import (SEED, FALLBACK_COLUMNS, synth_table_names,
                                      harvest_hf_names, build_pools, render)
    from data.templates import TRAIN_TEMPLATES, HELDOUT_TEMPLATES, Ctx, Sample, Template
    from eval.metrics import tables as _tables
except ImportError:  # 直接 python data/antileak.py 运行
    from synth_pipeline import (SEED, FALLBACK_COLUMNS, synth_table_names,  # type: ignore
                                harvest_hf_names, build_pools, render)
    from templates import TRAIN_TEMPLATES, HELDOUT_TEMPLATES, Ctx, Sample, Template  # type: ignore
    from eval.metrics import tables as _tables  # type: ignore

B1_SYNTH_KEEP = 40      # B1 保留的合成生成名数（>0 才能与基线"逐字合成池"列对比）
B1_REAL_MIN = 2000      # B1 真实名去重后目标下限
B2_NEGATIVE_FRAC = 0.20 # B2 空标签负样本占训练集比例


# ── B1：真实名主导的表名池（纯函数，real_names 注入以便无网络单测）─────────

def b1_pools(rng: random.Random, real_names: list[str],
             synth_keep: int = B1_SYNTH_KEEP) -> tuple[list[str], list[str], list[str]]:
    """真实名主导 + 保留 synth_keep 个合成名。返回 (train_tables, heldout_tables, synth_subset_in_train)。

    切分沿用 build_pools 的 1/6 heldout 隔离（train/heldout 名池不相交，防内部泄漏）。
    synth_keep 个合成名保留在池中，使 leak_analysis 的"逐字合成池"对照列在 B1 上仍可测。
    """
    synth = synth_table_names(rng, synth_keep)
    tables = sorted(set(real_names) | set(synth))
    rng.shuffle(tables)
    cut = max(60, len(tables) // 6)
    heldout_tables, train_tables = tables[:cut], tables[cut:]
    synth_in_train = sorted(set(synth) & set(train_tables))
    return train_tables, heldout_tables, synth_in_train


# ── B2：开放域弃权负样本（三型，均 labels 空）────────────────────────────

_B2_TASK_ROTATION = ["PYTHON", "SHELL", "SCALA", "JAVA"]


def _neg_pure_compute(ctx: Ctx, task_type: str) -> str:
    """纯计算/日志脚本：无 SQL、无落表 → 应弃权（输出空）。"""
    body = ctx.distractors(task_type)
    extra = {
        "PYTHON": "values = [round(x * 1.05, 2) for x in range(10)]\nprint(sum(values))",
        "SHELL": "total=0\nfor i in $(seq 1 10); do total=$((total + i)); done\necho \"$total\"",
        "SCALA": "val values = (1 to 10).map(_ * 1.05)\nprintln(values.sum)",
        "JAVA": "double total = 0;\nfor (int i = 0; i < 10; i++) total += i * 1.05;\nSystem.out.println(total);",
    }[task_type]
    return body + "\n" + extra


def _neg_comment_sql(ctx: Ctx, task_type: str) -> str:
    """表名只出现在注释 / 被打印的字符串里 → 约定 A 判空，应弃权。"""
    t = ctx.table()
    cols = ctx.cols(2)
    sql = f"INSERT INTO {t} SELECT {cols[0]}, {cols[1]} FROM {t}"
    return {
        "PYTHON": f"# legacy migration (disabled): {sql}\nprint('would run:', {sql!r})\nlogger.info('skipped legacy step')",
        "SHELL": f"# {sql}\necho \"would run: {sql}\"\n: # no-op",
        "SCALA": f"// {sql}\nprintln(s\"would run: {sql}\")\nlogger.info(\"skipped\")",
        "JAVA": f"// {sql}\nSystem.out.println(\"would run: {sql}\");\nlog.info(\"skipped\");",
    }[task_type]


def _neg_dynamic_name(ctx: Ctx, task_type: str) -> str:
    """动态拼接表名（无字面名）→ 约定 A 范围外，应弃权。"""
    return {
        "PYTHON": ("schema = cfg['schema']\ntbl = f\"{schema}.{cfg['name']}_{biz_date}\"\n"
                   "df.write.mode('overwrite').saveAsTable(tbl)"),
        "SHELL": ("TBL=\"${SCHEMA}.${NAME}_${BIZ_DATE}\"\n"
                  "hive -e \"INSERT OVERWRITE TABLE ${TBL} SELECT * FROM staging\""),
        "SCALA": ("val tbl = s\"${schema}.${name}_${bizDate}\"\n"
                  "df.write.mode(\"overwrite\").saveAsTable(tbl)"),
        "JAVA": ("String tbl = schema + \".\" + name + \"_\" + bizDate;\n"
                 "df.write().mode(\"overwrite\").saveAsTable(tbl);"),
    }[task_type]


_B2_NEG_KINDS = [_neg_pure_compute, _neg_comment_sql, _neg_dynamic_name]


def build_negative_row(rng: random.Random, tables: list[str], columns: list[str], idx: int) -> dict:
    """造一条弃权负样本行（labels 空），行 schema 与 synth_pipeline.render 同构。"""
    task_type = _B2_TASK_ROTATION[idx % len(_B2_TASK_ROTATION)]
    kind = _B2_NEG_KINDS[idx % len(_B2_NEG_KINDS)]
    ctx = Ctx(rng, tables, columns)
    content = kind(ctx, task_type)
    return {
        "task_type": task_type,
        "content": content,
        "labels": {"reads": [], "writes": []},
        "meta": {"template_id": f"b2-abstain/{kind.__name__[1:]}",
                 "rule_covered": True,
                 "form_family": "b2-abstain",
                 "source_dataset": "synth-b2-abstain",
                 "split_group": "train"},
    }


# ── pool.json（诚实泄漏度量的锚）────────────────────────────────────────

def _names_in_rows(rows: list[dict]) -> set[str]:
    """训练行 labels 里出现的全部表名 = 模型被训练去输出的名字全集（含模板派生名）。"""
    s: set[str] = set()
    for r in rows:
        s |= set(_tables(r["labels"]["reads"])) | set(_tables(r["labels"]["writes"]))
    return s


def variant_pool_json(variant: str, train_names: set[str],
                      hf_degraded: bool = False) -> dict:
    """该变体训练表名全集（从生成行导出）+ 其中合成生成子集，键序确定（determinism）。"""
    synth_replay = set(synth_table_names(random.Random(SEED), 400))
    return {"variant": variant, "seed": SEED, "hf_degraded": hf_degraded,
            "train_table_names": sorted(train_names),
            "synth_generated_subset": sorted(train_names & synth_replay)}


# ── heldout 构造（镜像 synth_pipeline：60% 形态隔离 + 40% 训练模板@heldout 名池）──

def _build_heldout(rng: random.Random, heldout_tables: list[str], columns: list[str],
                   heldout_size: int) -> list[dict]:
    rows = []
    n_iso = int(heldout_size * 0.6)
    for i in range(n_iso):
        rows.append(render(rng, HELDOUT_TEMPLATES[i % len(HELDOUT_TEMPLATES)],
                           heldout_tables, columns, "heldout"))
    for i in range(heldout_size - n_iso):
        rows.append(render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)],
                           heldout_tables, columns, "heldout"))
    rng.shuffle(rows)
    return rows


# ── 变体装配 ────────────────────────────────────────────────────────────

def build_b1(rng: random.Random, real_names: list[str], columns: list[str],
             train_size: int, heldout_size: int, synth_keep: int,
             hf_degraded: bool) -> tuple[list[dict], list[dict], dict]:
    train_tables, heldout_tables, _synth = b1_pools(rng, real_names, synth_keep)
    train_rows = [render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)], train_tables, columns, "train")
                  for i in range(train_size)]
    rng.shuffle(train_rows)
    heldout_rows = _build_heldout(rng, heldout_tables, columns, heldout_size)
    for r in train_rows:
        r["meta"]["source_dataset"] = "synth-b1-realname+gretelai+b-mc2"
    pool = variant_pool_json("b1", _names_in_rows(train_rows), hf_degraded)
    return train_rows, heldout_rows, pool


def build_b2(rng: random.Random, train_tables: list[str], heldout_tables: list[str],
             columns: list[str], train_size: int, heldout_size: int,
             neg_frac: float) -> tuple[list[dict], list[dict], dict]:
    n_neg = int(round(train_size * neg_frac))
    n_pos = train_size - n_neg
    pos_rows = [render(rng, TRAIN_TEMPLATES[i % len(TRAIN_TEMPLATES)], train_tables, columns, "train")
                for i in range(n_pos)]
    neg_rows = [build_negative_row(rng, train_tables, columns, i) for i in range(n_neg)]
    train_rows = pos_rows + neg_rows
    rng.shuffle(train_rows)
    heldout_rows = _build_heldout(rng, heldout_tables, columns, heldout_size)
    # 训练表名全集从正样本行导出（负样本 labels 空，不贡献）
    pool = variant_pool_json("b2", _names_in_rows(train_rows))
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
    ap.add_argument("--variant", required=True, choices=["b1", "b2"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--train-size", type=int, default=10000)
    ap.add_argument("--heldout-size", type=int, default=600)
    ap.add_argument("--b1-real-min", type=int, default=B1_REAL_MIN)
    ap.add_argument("--b1-synth-keep", type=int, default=B1_SYNTH_KEEP)
    ap.add_argument("--b2-negative-frac", type=float, default=B2_NEGATIVE_FRAC)
    args = ap.parse_args()

    rng = random.Random(SEED)
    columns = list(FALLBACK_COLUMNS)

    if args.variant == "b1":
        hf_tables, hf_columns = harvest_hf_names(limit_rows=20000)
        hf_degraded = len(hf_tables) < args.b1_real_min
        if hf_degraded:
            print(f"[warn] HF 真实名仅 {len(hf_tables)} < {args.b1_real_min}；B1 退化为合成扩量（pool.json 标 hf_degraded）")
        columns = sorted(set(columns) | set(hf_columns))
        train_rows, heldout_rows, pool = build_b1(
            rng, hf_tables, columns, args.train_size, args.heldout_size,
            args.b1_synth_keep, hf_degraded)
    else:  # b2：正样本用基线池/列（消融纯净：与基线唯一差异 = 掺 20% 空标签负样本）
        train_tables, heldout_tables, b_cols = build_pools(rng)
        train_rows, heldout_rows, pool = build_b2(
            rng, train_tables, heldout_tables, b_cols,
            args.train_size, args.heldout_size, args.b2_negative_frac)

    _write(Path(args.out), train_rows, heldout_rows, pool)


if __name__ == "__main__":
    main()
