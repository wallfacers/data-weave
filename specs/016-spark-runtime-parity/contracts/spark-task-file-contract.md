# Contract: Spark 任务文件契约 + CLI run

## F1. Spark 任务 .task.yaml 结构

```yaml
name: 我的Spark任务
type: SPARK
sparkMode: pyspark        # pyspark | spark-sql | jar
datasource: spark-cluster # SPARK 类型数据源逻辑名（承载 master/SPARK_HOME/confs）
timeoutSeconds: 1800
# jar 形态额外字段：
# sparkMode: jar
# jarRef: <jar 资产标识>
# mainClass: com.example.Main
params: {}
```

脚本体独立文件：
- pyspark → `<task>.py`（PySpark 代码）
- spark-sql → `<task>.sql`（SQL，分号分隔多句）
- jar → 无脚本体（引用 jar 资产）

## F2. round-trip 保真

`TaskMapper.getScriptExtension` 对 SPARK 按 sparkMode 返回扩展名（pyspark→.py, spark-sql→.sql, jar→无）。push/pull round-trip 后 sparkMode + 脚本体扩展名 + jarRef/mainClass MUST 不丢、不漂移（参考 013 slug round-trip 严谨度）。

## F3. dw run 本地真跑（FR-015）

- `dw run <spark-task>`：CLI 按 type=SPARK + sparkMode 装配，经 `DW_WORKER_CP` 调起 `LocalRunMain`。
- 本地 SPARK 数据源（datasources.local.yaml）解析 master=local[*]、SPARK_HOME 等 → ds-json 传入。
- 装有 Spark：真提交 spark-submit，退出码忠实透传（退出码契约同 D 子特性：0 成功 / 6 任务执行失败 / 7 环境错）。
- **无 Spark**：SKIPPED——CLI 输出可辨识"已跳过（本地无 Spark 环境）"，**退出码不作失败处理**（不报 6），不阻塞开发流。

## F4. Skill 创作知识（FR-014）

`.claude/skills/weft-task-authoring/`：
- SKILL.md 补 Spark 段：三 sparkMode 何时用、SPARK 数据源怎么配、jar 资产怎么引用、SKIPPED 怎么读。
- file-contract.md 补 Spark 文件契约速查。
- examples/ 加最小可跑 `sample-spark.task.yaml` + `sample-spark.py`（pyspark 形态，print 一行 + 简单 DataFrame）。
- skill_lint 自省：Spark 示例引用的 dw 命令/flag 真实存在。
