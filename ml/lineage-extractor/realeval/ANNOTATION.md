# 041-R 真实集标注规范

真实集（`realeval/pool/*.json`）用于对小模型抽取结果做人工标注/复核，标签为脚本的
表级血缘（reads/writes），格式与训练/评测集保持一致，便于复用同一套评测脚本。

## 标签格式

```json
{
  "reads": [{"table": "schema.table_name", "columns": ["col1", "col2"] | null}],
  "writes": [{"table": "schema.table_name", "columns": ["col1", "col2"] | null}]
}
```

- `table`：全限定表名，见「跨库/跨 schema」规则。
- `columns`：明确列出的列名列表；无法确定具体列（如 `SELECT *`）时为 `null`，
  不得猜测或补全列名。
- 一个脚本可能有多条 `reads`/`writes`，按脚本中出现顺序去重列出。

## 裁决规则（边界情况）

1. **动态拼接表名忽略**：表名由字符串拼接/变量插值构造（如
   `f"INSERT INTO {table_prefix}_{date}"`、Shell 变量 `${TABLE}`）且无法静态确定
   具体表名时，该条读写不计入标签，也不作为噪声干扰后续检测规则的评分。
2. **CTE / 临时表不计外部表**：`WITH cte AS (...)`、`CREATE TEMP TABLE` /
   `CREATE TEMPORARY TABLE` 定义的中间结果集，其内部引用不算作外部表 lineage；
   只标注真正落地到 CTE/临时表之外的物理表。若临时表本身后续被
   `INSERT INTO`/`SELECT INTO` 写入到持久表，则该持久表计入 `writes`。
3. **注释 / 打印中的 SQL 忽略**：出现在 `--`、`#`、`/* */` 注释块内，或作为
   字符串被 `print`/`log.info`/`logger.debug` 等调用打印但未真正执行的 SQL，
   不计入标签。只标注实际被执行引擎（cursor.execute / spark.sql / hive -e 等）
   运行的语句。
4. **多语句全计**：一个脚本内包含多条以 `;` 分隔或多次 `execute()` 调用的 SQL
   语句时，所有被执行语句的 reads/writes 都计入标签（去重后合并），不能只取
   第一条或最后一条。
5. **`SELECT *` 列为 null**：当读取语句使用 `SELECT *`（或等价的全列引用，如
   `df = spark.read.table(...)` 未指定列）时，对应条目的 `columns` 标注为
   `null`，禁止根据下游使用推断具体列名。
6. **跨库 / 跨 schema 全名保留**：涉及跨数据库、跨 schema 的引用（如
   `dbname.schema.table`、`schema.table`、Hive 的 `db.table`）一律保留完整限定
   名，不做归一化或简化为裸表名；同一物理表在不同语句中出现不同限定深度时，
   以脚本中出现的最长（信息量最全）的限定名为准。

## 标注流程

1. 从 `realeval/pool/*.json` 逐条读取候选（`content` 已脱敏，`source` 含
   `repo/path/commit/license` provenance）。
2. 标注者阅读脚本原文，按上述规则手工填写 `reads`/`writes`，存为同名
   `*.label.json`（与 pool 条目一一对应）。
3. 有疑义的边界情况（如规则未覆盖的新模式）先记录在标注备注里，标注规范
   随后据实更新，不擅自扩大/缩小既有规则的适用范围。
4. 标注完成的样本用于 `eval/` 下的真实集评测，与合成集分开统计，避免合成
   数据分布掩盖真实脚本上的表现差异。
