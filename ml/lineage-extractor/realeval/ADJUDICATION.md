# 041-R 真实集金标裁决草案（全量作业集 48 条，分歧 18 条）

> 生成：Task 14 step B 后。采集 66 候选 → 作业筛 48 → 交叉预标(M1=Qwen-DashScope / M2=Ali-Anthropic) → 18 条真分歧。
> 约定基准：**gold 必须与训练标签约定一致**，否则模型按训练输出却被 gold 判错 = 虚假失败。
> 训练标签约定（查 `data/templates.py` + `synth_pipeline.py`）：
> ① 表名 = **可执行语句里字面出现的 token**（schema 限定随字面）；
> ② 训练分布**从不含**文件路径(hdfs/s3/gs/csv)、动态名(`${}`/`{{}}`/f-string `{}`)、temp view/CTE 别名 → 均不标。
>
> **待用户拍板的 scope 判断（唯一，有论文后果）见文末 [16]。**

## 18 条分歧裁决（A=严格语句字面约定，与训练一致）

| # | 脚本 | 草案 gold reads | 草案 gold writes | 依据 | 谁对 |
|---|---|---|---|---|---|
| 00 | 02_ingest/ingest.sh | `dsongcp.flights_raw` | — | 两 `bq query 'SELECT … FROM dsongcp.flights_raw'` 皆读；无写 | M2 |
| 01 | etl.sh | — | — | `rag.db` = SQLite 文件非表 | M2 近 |
| 02 | sqoop-import.sh | — | — | 全 `{{jinja}}` 动态 | 都过度 |
| 03 | sqoop-import-hive.sh | `users_d` | — | `users_d` 字面读；写目标 `${hive_table}` 动态排除 | 折中 |
| 04 | SparkMySQLHiveETL.py | — | — | class 库，表名来自 params dict；M1 抽的是文档示例非字面执行 | M2 |
| 05 | dbsnp/toast.sh | — | — | 全 `hdfs://` 路径非表 | M2 |
| 06 | hiveTest.sh | — | — | `${HIVE_TABLE_NAME}` 动态 | M2 近 |
| 07 | runWithGiraph.sh | — | — | `${database}.${table}` 动态 | — |
| 08 | insert_table.py | — | — | `{database_name}.{table_name}` f-string 参数 + `temp_view` 临时视图 | M2 |
| 09 | PySpark_ETL/PS06-JOINS.py | `T1`,`T2`,`T3` | — | `spark.sql("SELECT * FROM T1/T2/T3")` 字面读；csv 路径排除；仅 `display()` 无写 | M1 近(去 csv) |
| 10 | examples/SpannerSpark.py | `spanner.people` | `people` | `SELECT FROM spanner.people`(catalog 限定)读；`.option(table=people)` 写回 | 判定见下 |
| 11 | perf-query.sh | — | — | `impala-shell -q "$1"` 运行时参数，无字面表 | M2 |
| 12 | utilities/setup_bq.sh | — | — | 表名全 `$var` 动态组合；`ndt7` 只是字符串变量值；`__TABLES__` 元表 | 都过度 |
| 13 | ops/dataset-copy.sh | — | — | `${}` 动态 | M2 |
| 14 | src/run_etl.py | — | — | csv 路径 + `src.{key}` 循环动态写；`src` 是 database 非表 | M2 |
| 15 | daily_append.sh | — | — | `${db_name}.${staging_table}` 动态 | 折中 |
| 16 | ETL/ODS/mysql2hive_spark.py | **见 scope 判断** | **见 scope 判断** | 表名是**配置字典字面串**，但读写语句全 f-string 动态组合 | ★用户拍板 |
| 17 | hack/pyspark-hive-example.py | — | `punch_test_db_01.table_01` | `INSERT INTO table_01 SELECT * FROM view_data`（view_data=temp view 排除）；写 table_01 | M1 |

## 关键统计（论文级）

- **18 条分歧中 ~12 条正确 gold = ∅**：分歧几乎全是 **M1(Qwen-DashScope) 过度抽取**动态名/文件路径/temp view，而正确行为是**弃权**。
- 真实集 gold 因此**稀疏**：主要考 **precision / 低幻觉**（正确弃权），少量字面子集（`T1/T2/T3`、`table_01`、`users_d`、`dsongcp.flights_raw`）考 **recall/方向**。
- 这与合成 heldout 互补：heldout 密集考抽取，真实集考"在参数化噪声里不乱抽"。

## [10] SpannerSpark 次要判定（catalog 去重）

`spanner.people`（Spark catalog 名限定）与 `.option("table","people")` 是**同一物理表**。两种记法：
- 去重成一张表 `people`（读+写自身）——更贴物理血缘；
- 或各记字面 token（`spanner.people` 读、`people` 写）。
建议前者（物理去重），与训练"一表一 token"一致。**低影响，默认物理去重。**

## [16] ★ 唯一需拍板：金标 scope = 语句字面 vs 配置流追踪

`mysql2hive_spark.py` 是**配置驱动 ETL**：`settings.TABLES` 列表里每条含字面
`{"db":"spider","table":"jobs_2023_10_13", 'hive_db':'ods_jobfree','hive_table':'ods_jobfree_db_spider_t_jobs'}`，
读写语句是 `spark.read…option('dbtable', table.get('table'))` 与
`spark.sql(f'select * from {table["hive_db"]}.{table["hive_table"]}')`——**无一条语句含字面表名**。

- **A 语句字面（与训练一致，推荐）**：无字面表 token → gold ∅。配置驱动 ETL **声明为评测范围外**并披露。→ 真实集更纯粹考"参数化下不乱抽"。小模型/约定天然对齐。
- **B 配置流追踪**：把配置字面名追进读写 → reads=`spider.jobs_2023_10_13…(去重后按 hive_table 落 1 写)`, writes=`ods_jobfree.ods_jobfree_db_spider_t_jobs, …_resume, …_clickjobs, …_starjobs`（M2 基本命中）。更难更全，但小模型未训练过配置流 → 很可能**输给 Qwen(M2)**，削弱"小模型追平大模型"主张；且需大得多的标注量。

**A 与 B 决定真实集究竟考什么、以及核心主张是否成立——这是你的论文取向。**

---

# 扩样轮（加强实验 A，2026-07-04）：66→175 候选，非空金标 18→59

采集 wide profile（原 10 查询 + 14 条扩展：MERGE INTO/CTAS/writeTo/to_sql/COPY INTO/LOAD DATA/
UNLOAD/beeline/bq load/mysql -e/snowsql 等）→ 175 候选 → 作业筛 139（弃 36 库/测试源码）。
净新 91 条经 `adjudicate_aid.py` 约定 A 自动提议 + 我逐条读原文证伪，产出 24 条人工修正
（`apply_adjudication.py:OVERRIDES`，每条附依据）。最终 gold **139 行 / 非空 59 / ∅ 80**。

## 约定 A 细化（扩样中新遇、已固化进裁决与自动过滤）

在原规则 1–6 之上，逐条证伪新识别出以下**非表**类别（均剔除，属"参数化/非血缘噪声"）：

1. **元数据/数据字典目录**：`information_schema.*`、`system.information_schema.*`、Oracle `user_objects`/
   `user_queues`/`user_scheduler_jobs`、`v$version`、`pg_*` 目录——是查目录不是业务血缘。
2. **注释屏蔽的语句**：`#hive -e "…"`、`-- …`、`/* */` 内的读写，即便含字面表名也不计
   （规则 3 的延伸；扩样中 `vdn_log_2.sh` 有多条 `#hive -e` 被屏蔽）。
3. **帮助/回显文本里的占位名**：`echo "Usage: … Snowflake_table …"`、`log "Create table foo"`
   仅作说明打印、非执行语句 → 不计。
4. **DataFrame/变量/函数参数当表**：`df.write.saveAsTable(output_table)` 中 `output_table` 是
   Python 变量（argparse dest）；`data_train, data_test = data.randomSplit(...)` 是 DataFrame；
   `def force_phot(table_in, …)` 是形参；函数签名默认值 `jdbc_table: str = "default.default"`——均非字面表。
5. **库/schema/database 名当表**：`CREATE DATABASE bulk_insert_test`、`dbname = YADAMU_SYSTEM` 是库名。
6. **视图**：`create view p_lineorder as`、`createOrReplaceGlobalTempView(...)` 是（临时或持久）视图，
   非物理表 → 不计（规则 2 延伸）。
7. **限定符归一**：BigQuery `project:dataset.table` 的 `:` 与 `project.dataset.table` 的 `.` 指同一物理表，
   归一为 `.` 去重（`metrics.sh`）。
8. **真源表 vs 临时视图辨析**：`glueContext.create_dynamic_frame.from_catalog(table_name="credits")`
   是**读 Glue 目录源表**（自动过滤曾误当临时视图）；须读原文区分。

## 关键统计（扩样后，论文级）

- 净新 91 条：need_manual 63（提议非空/分歧/裸词）、auto-∅ 28。人工 24 条 override。
- 非空金标 59 / 139：含多引擎写（saveAsTable/INSERT OVERWRITE/COPY INTO/CREATE TABLE/MERGE/bq load）
  与多跳读写链（如 `vdn_log_2.sh` 10 写 5 读的 hive ETL）。
- 空金标 80 / 139：仍以"正确弃权"为主——动态名/路径/元数据/库名/变量/注释屏蔽，考 precision/低幻觉。
- **双模型共识错误谱扩大**：除首轮的属性/配置驱动/函数名/路径，新增元数据目录、注释屏蔽语句、
  DataFrame 变量、视图、`:`/`.` 不一致——进一步坐实"双模型一致 ≠ gold，共享过度抽取偏差"这一次要 finding。

---

# JVM 扩语言轮（加强实验 D，2026-07-05）：Scala/Java 真实集 141 → 非空金标 32

采集 `jvm` profile（10 条 Scala/Java 查询：spark.sql/saveAsTable/writeTo/insertInto/executeSql/
executeInsert）→ 141 候选（SCALA 74 / JAVA 67）→ 双模型预标 60 need_manual → 我逐条按约定 A
证伪，产出 `apply_adjudication_jvm.py:JVM_OVERRIDES`（34 条 path 键，同路径多版本文件给一致保守裁决）。
最终 `real-jvm.jsonl` **141 行 / 非空 32（SCALA 16 + JAVA 16）/ ∅ 109**。独立文件、不并入 py/sh 的
real.jsonl，以免污染已冻结的 n=139 数字。

## 约定 A 在 JVM 上新识别的**非表**类别（均剔）

在 py/sh 规则之上，JVM 逐条证伪新增：

1. **变量/常量/参数名当表**：`val tableName = "mc_test_table"` 后按变量传入读写（非可执行语句字面）；
   `String.format(..., TRIPLETABLE_NAME)` 的常量；`tablename = args(4)` 运行时参数；
   `dbutils.widgets.get("sourceTable")` widget 变量——均非字面串表。
2. **类/trait/包/model 名当表**：`class FilesRelation`→files、`class RawTableRelation`→raw、
   `trait HiveManageTable`→table、`package com.dahuatech.flink.demo`→demo、`model.ProductData`→productdata。
3. **jOOQ/ORM 生成常量**：`import static ...Tables.AUTHORITY_FILE / USER / ACCOUNT / BAZEL_EDGE`、
   `Sequences.OFC_DATA_ID_SEQ`——是生成的 Java 标识符常量、非字面串表（虽 1:1 映射物理表，但约定 A 只认字面串）。
4. **对象字段访问**：`vastTableMetaData.tableName`、`opts.table`——非字面表。
5. **CLI 帮助注释里的名字**：`--mongo.database source_db`。
6. **CREATE VIEW / CREATE DATABASE**：`create view P_LINEORDER`、`CREATE DATABASE Tableau_TDVT`——视图/库非物理表。
7. **临时视图**：`createTemporaryView("sourceTable")`、`global_temp.dept_global_view`——沿用 py/sh 规则。
8. **JDBC 连接串 / 落盘文件夹**：`jdbc:postgresql:dbserver`、`.csv("output")`/`.text("output_compressed")` 文件夹路径。

**保留为真表**：`spark.sql("... FROM x")`/`saveAsTable("x")`/`insertInto("x")`/`writeTo("x")` 的字面串、
Flink DDL `'table-name'='x'` 与 `CREATE TABLE x (...) WITH (...)` + `INSERT INTO x`、`.hiveDB("x").hbaseTable("x")`、
`dbtable`option `"schema.tablename"`、jOOQ `DSL.table("site_settings")` 的字面串参数。

## 关键统计（论文级）

- 60 need_manual 中约半数是**双模型共识过度抽取**（变量/类名/jOOQ 常量/参数）——JVM 上更严重（Scala/Java
  强类型 + ORM 生成代码让"看起来像表的标识符"更多）。auto-∅ 81 条基本是框架/库源码（Spark/Flink/Delta 源码，
  如 `AstBuilder.scala` density=37、`DeltaSQLConf.scala` density=28），正确判 ∅。
- 非空 32：含 Iceberg（demo.nyc.taxis*）、Flink DDL（print_sink/sink_table/redis_sink_demo）、
  Hudi（hudi_table_test）、Spark saveAsTable（sparkdatalake.*/department/employee）、TPC-H 生成（8 表）等。
- 局限：JVM 裁决基于预标 3 行语句片段（非逐文件全读），比 py/sh 轻——见 findings §披露 7。
