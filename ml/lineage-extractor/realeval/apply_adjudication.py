"""041-R 加强实验 A：把人工证伪裁决落成最终 gold。

流程：既有 gold（48 条，第一轮已裁）原样保留；对 tolabel 里**净新**候选（content
不在既有 gold），先取 `adjudicate_aid.adjudicate` 的约定 A 自动提议，再套下方
`OVERRIDES`（我逐条读脚本原文证伪后的人工修正，key=source.path）。task_type 直接
沿用 tolabel 记录（= 预标时 `_infer_task_type`），保证预标/评测 prompt 一致。

OVERRIDES 是人工裁决的**唯一权威记录**（论文 provenance）。每条附一句依据。
未列入 OVERRIDES 的净新候选 = 自动提议已正确，直接采纳。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from realeval.adjudicate_aid import adjudicate

# path -> (reads, writes, 依据)。空列表 = 判 ∅。
OVERRIDES: dict[str, tuple[list[str], list[str], str]] = {
    "tools/snowflake2bq/bash/snowflake-bq.sh": ([], [], "表名仅出现在 echo Usage 帮助文本，非执行语句"),
    "variant/regen.py": ([], ["output"], "`t` 是 license 注释噪声；`output` 有 DROP TABLE + 新建表写入"),
    "scripts/bq_load.sh": ([], [], "information_schema.columns 为元数据目录，非业务血缘表"),
    "office-hours/oh_merge.sql": ([], ["emps", "fixes", "oracle_team"],
                                  "均 create table + insert into values（无 select from），仅写"),
    "app_jobs/app_jobs-126/vdn/vdn_log_2.sh": (
        ["vdn_avlb_minutely", "vdn_avlb_minutely_fully", "vdn_ip_repo_cn", "vdn_logs", "vdn_logs_fully"],
        ["vdn_avlb_daily", "vdn_avlb_minutely", "vdn_avlb_minutely_fully", "vdn_avlb_minutely_fully_gather",
         "vdn_avlb_minutely_fully_report", "vdn_avlb_minutely_report", "vdn_fluent_minutel_report",
         "vdn_group_by_err", "vdn_logs", "vdn_logs_fully"],
        "剔除 #hive -e 注释屏蔽的 vdn_node_repo/vdn_logs_other/vdn_logs_ifeng；其余 INSERT INTO TABLE 为写、FROM 为读"),
    "sync_views.py": ([], [], "system.information_schema.views 为元数据目录"),
    "llm-models/experimental/blip-2/Step 1: Generating descriptions.py": (
        ["concise_prompts", "fashion_description_concise"],
        ["concise_prompts", "fashion_description_concise"],
        "saveAsTable+SELECT 读写；captioning_prompts 是 python list、fashion_description 在 # 注释行，均剔"),
    "src/examples/sample_cube/create_sample_ssb_tables.sql": (
        [], ["customer", "dates", "lineorder", "part", "supplier"],
        "CREATE EXTERNAL TABLE + LOAD DATA INTO 为写；p_lineorder 是 create view（视图非物理表），剔"),
    "examples/spark-s3-to-hive/spark/s3tohive.py": ([], [], "saveAsTable(output_table) 中 output_table 是 argparse 变量非字面表名"),
    "ch15/ch_15_semistruct.sql": (
        ["pirate_json"], ["pirate_json", "pirate_weapons", "pirate_years_active"],
        "pirate_json CREATE+INSERT+SELECT 读写；pirate_weapons/years_active CREATE+MERGE 写；ship/weapon/bare pirate 是 JSON variant 字段非表"),
    "force_phot.py": ([], [], "table_in/table_out/filenames/fits_hdrtable_list 均 python 变量/函数参数，非表"),
    "examples/dbt-spark-example/spark/s3tohive.py": ([], [], "同上，output_table 是变量"),
    "cloudeon-stack/EDP-2.0.0/hive/service-common/bootstrap.sh": (
        [], ["test_table"], "CREATE TABLE + insert into values（无 select），仅写"),
    "sql/planstate_subplans.sql": ([], [], "pg_tracing 扩展测试夹具：pg_tracing_peek_spans 是函数、m 是 MERGE 演示别名"),
    "docker/orchestrator/snowflake/configure.sh": ([], [], "YADAMU_SYSTEM 是 database 名非表"),
    "compose_files/extra.sql": (
        ["identity_provider", "opendcs_user"],
        ["identity_provider", "opendcs_user", "opendcs_user_password", "tsdb_property",
         "user_identity_provider", "user_roles"],
        "insert/merge 目标为写；identity_provider/opendcs_user 在子查询 select 中为读；tsdb_property 的 merge source 是子查询故仅写"),
    "ci/scripts/e2e-source-mysql-offline-schema-change.sh": (
        ["test_db.t1", "risedev.t"], ["t1"],
        "CREATE TABLE t1 为写；CDC 绑定 FROM ... TABLE 'test_db.t1'/'risedev.t' 为源表读；information_schema/裸 t 剔"),
    "src/publisher/functions.sh": ([], [], "table_name 是 shell 局部变量非字面表名"),
    "install_with_logger.sql": (
        [], ["mailgun_settings"],
        "merge into mailgun_settings 为写；user_objects/user_queues/user_scheduler_jobs 是 Oracle 数据字典视图，剔"),
    "DE-101 Modules/Module07/DE - 101 Lab 7.5/examples/2_tables_and_views.py": (
        ["us_delay_flights_tbl"], ["managed_us_delay_flights_tbl", "us_delay_flights_tbl"],
        "CREATE TABLE/saveAsTable 写；us_origin_airport_*_global_tmp_view 是 GlobalTempView，剔"),
    "ci/000-prepare-database": ([], [], "CREATE DATABASE bulk_insert_test 是库名非表"),
    "providers/apache/spark/src/airflow/providers/apache/spark/hooks/spark_jdbc_script.py": (
        [], [], "default.default 是函数签名默认参数值，非执行语句表引用"),
    "scripts/ci/metrics.sh": (
        ["acs-san-stackroxci.ci_metrics.stackrox_tests__extended_view"],
        ["acs-san-stackroxci.ci_metrics.stackrox_central_metrics",
         "acs-san-stackroxci.ci_metrics.stackrox_image_prefetches",
         "acs-san-stackroxci.ci_metrics.stackrox_jobs",
         "acs-san-stackroxci.ci_metrics.stackrox_tests"],
        "bq 表名 var 落读/写；project:dataset.table 的 `:` 归一为 `.`（同物理表）"),
    "glue/etl_job.py": (
        ["contacts", "credits", "people"], [],
        "from_catalog(table_name=...) 字面读 Glue 目录源表；data_train/label_*/protected_* 是 DataFrame 变量非表"),
}


def _rows(tok_reads, tok_writes):
    return ([{"table": t, "columns": None} for t in tok_reads],
            [{"table": t, "columns": None} for t in tok_writes])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", default="realeval/gold/real.jsonl")
    ap.add_argument("--tolabel", default="realeval/tolabel")
    ap.add_argument("--out", default="realeval/gold/real.jsonl")
    args = ap.parse_args(argv)

    gold = [json.loads(l) for l in Path(args.gold).read_text(encoding="utf-8").splitlines() if l.strip()]
    gold_contents = {g["content"] for g in gold}

    idx = json.loads((Path(args.tolabel) / "_index.json").read_text(encoding="utf-8"))
    added, applied_overrides, unknown_overrides = [], 0, set(OVERRIDES)
    for e in idx:
        rec = json.loads((Path(args.tolabel) / e["filename"]).read_text(encoding="utf-8"))
        if rec["content"] in gold_contents:
            continue
        a = adjudicate(rec)
        path = a["path"]
        if path in OVERRIDES:
            r, w, _why = OVERRIDES[path]
            applied_overrides += 1
            unknown_overrides.discard(path)
        else:
            r, w = a["proposal"]["reads"], a["proposal"]["writes"]
        reads, writes = _rows(r, w)
        added.append({
            "content": rec["content"],
            "task_type": rec.get("task_type", "PYTHON"),
            "labels": {"reads": reads, "writes": writes},
            "meta": {
                "template_id": path,
                "form_family": "real",
                "source_dataset": "real",
                "rule_covered": True,
                "literal_density": a["density"],
                "needs_review": False,
            },
        })

    if unknown_overrides:
        print(f"[warn] {len(unknown_overrides)} 条 OVERRIDE 未匹配任何净新候选（路径可能过期）：{sorted(unknown_overrides)}")

    final = gold + added
    Path(args.out).write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in final) + "\n", encoding="utf-8")

    ne = sum(1 for r in final if r["labels"]["reads"] or r["labels"]["writes"])
    added_ne = sum(1 for r in added if r["labels"]["reads"] or r["labels"]["writes"])
    print(f"既有 gold {len(gold)}（非空 {sum(1 for g in gold if g['labels']['reads'] or g['labels']['writes'])}）"
          f" + 净新 {len(added)}（非空 {added_ne}，套用 override {applied_overrides}）")
    print(f"→ 最终 gold {len(final)}（非空 {ne} / ∅ {len(final) - ne}） 写入 {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
