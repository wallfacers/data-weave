"""059 扩语料采集器：the-stack-dedup 流式 → ETL 习语筛 → 候选池（绕开 GitHub 限速）。

GitHub code search 受 30/min 搜索限速 + license 门刷量，~4-10/min，到 1500 需数小时且易被
截断。the-stack-dedup 是宽松许可 parquet 代码集（已按 license 构建），流式拉取几分钟到上千。

复用 `collect.py` 纯函数：sanitize（脱敏）/ is_redistributable（license 双检）/ dedup_key
（跨仓去重）/ literal_density（字面表名密度）/ looks_like_etl_job（作业 vs 库粗筛）。
**ETL 习语门**：只留含 INSERT/saveAsTable/COPY/MERGE/spark.sql/... 的脚本（含参数化写法），
teacher 再定实际血缘——避免随机代码稀释语料。落盘格式与 collect.py 完全一致（可直接喂
teacher_label / build_silver / build_gold_b）。

用法: HF_TOKEN=... PYTHONPATH=. python3 realeval/collect_stack.py \
        --langs python,shell,sql --target 3000 --out realeval/pool-c
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

from realeval.collect import (
    dedup_key, is_redistributable, literal_density, looks_like_etl_job, sanitize,
)

# the-stack 语言目录 → task_type（与 prelabel/infer_task_type 对齐）。
LANG_DIR = {"python": "PYTHON", "shell": "SHELL", "sql": "SQL",
            "scala": "SCALA", "java": "JAVA"}

# ETL 习语门：命中即视为血缘相关脚本（含字面与参数化写法）。teacher 再裁实际读写。
_ETL_IDIOM = re.compile(
    r"INSERT\s+INTO|INSERT\s+OVERWRITE|saveAsTable|insertInto|\.writeTo\b|copy_into|"
    r"COPY\s+INTO|MERGE\s+INTO|CREATE\s+TABLE|LOAD\s+DATA|\.to_sql\b|spark\.sql|"
    r"tableEnv\.executeSql|hive\s+-e|beeline|bq\s+query|bq\s+load|\bUNLOAD\b|snowsql|psql",
    re.IGNORECASE)


def is_etl_script(content: str) -> bool:
    """ETL 相关粗筛：命中习语门 或 有字面可抽表名。"""
    return bool(_ETL_IDIOM.search(content)) or literal_density(content) > 0


def _licenses_ok(rec: dict) -> bool:
    """the-stack 记录 license 列（list）双检——任一命中宽松白名单即可。"""
    lics = rec.get("max_stars_repo_licenses") or rec.get("licenses") or []
    if isinstance(lics, str):
        lics = [lics]
    return any(is_redistributable(l) for l in lics) if lics else False


def collect_stack(langs, target: int, out_dir: Path, max_chars: int, token: str) -> int:
    """流式遍历各语言，ETL 筛 + 脱敏 + 去重落盘。target 为**总**目标（跨语言均分）。"""
    from datasets import load_dataset

    out_dir.mkdir(parents=True, exist_ok=True)
    # 已落盘文件名即 dedup_key（含 GitHub pool-c 的 162 条）→ 直接按文件名去重，不重复落盘。
    seen = {p.stem for p in out_dir.glob("*.json")}
    per_lang = max(1, target // max(1, len(langs)))
    written = 0

    for lang in langs:
        task_type = LANG_DIR.get(lang, "PYTHON")
        got = 0
        try:
            ds = load_dataset("bigcode/the-stack-dedup", data_dir=f"data/{lang}",
                              split="train", streaming=True, token=token)
        except Exception as e:
            print(f"[warn] lang={lang} load 失败: {repr(e)[:160]}", file=sys.stderr)
            continue
        for rec in ds:
            if got >= per_lang:
                break
            content = rec.get("content") or ""
            if not content or len(content) > max_chars:
                continue
            if not _licenses_ok(rec):
                continue
            if not is_etl_script(content):
                continue
            if not looks_like_etl_job(content, rec.get("max_stars_repo_path") or ""):
                continue
            clean = sanitize(content)
            key = dedup_key(clean)
            if key in seen:
                continue
            seen.add(key)
            lics = rec.get("max_stars_repo_licenses") or []
            record = {
                "content": clean,
                "source": {
                    "repo": rec.get("max_stars_repo_name"),
                    "path": rec.get("max_stars_repo_path"),
                    "license": (lics[0] if lics else None),
                    "query": f"the-stack:{lang}",
                    "task_type": task_type,
                },
                "meta": {
                    "literal_density": literal_density(clean),
                    "looks_like_job": True,
                },
            }
            (out_dir / f"{key}.json").write_text(
                json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            written += 1
            got += 1
            if written % 100 == 0:
                print(f"  written={written} (lang={lang} {got}/{per_lang})", flush=True)
        print(f"[lang {lang}] +{got}")
    return written


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", default="python,shell,sql")
    ap.add_argument("--target", type=int, default=3000, help="总目标条数（跨语言均分）")
    ap.add_argument("--max-chars", type=int, default=24000)
    ap.add_argument("--out", default="realeval/pool-c")
    args = ap.parse_args(argv)

    token = os.environ.get("HF_TOKEN")
    if not token:
        print("HF_TOKEN 未设置：the-stack-dedup 为 gated，需已接受条款的 HF token。", file=sys.stderr)
        return 1
    langs = [l.strip() for l in args.langs.split(",") if l.strip()]
    n = collect_stack(langs, args.target, Path(args.out), args.max_chars, token)
    print(f"collect_stack: 写入 {n} 条 → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
