# Phase 1 Data Model: 大数据开发任务类型补全（MVP）

本特性**不改 DDL**（`schema_version` 不变）。以下为概念实体与其在既有存储/上下文中的落点。

## 1. 任务类型目录（Task Type）

平台支持的任务类型代号（= 执行器 `TaskExecutor.type()`，`byType` 键）。`task_def.type VARCHAR(32)` 承载，无枚举约束。

| type | 状态 | 执行器 | 内容形态 | 绑定 | 子模式键（params_json） | 编辑器语言 |
|---|---|---|---|---|---|---|
| `SQL` | 已存在 | `SqlTaskExecutor` | SQL 脚本 | `datasource_id`（含 OLAP：STARROCKS/DORIS/CLICKHOUSE） | — | sql |
| `SHELL` | 已存在 | `ShellTaskExecutor` | shell 脚本 | 可选（DW_DS_* env） | — | bash |
| `PYTHON` | 已存在（入口未暴露） | `PythonTaskExecutor` | python 源码 | 可选（DW_DATASOURCE_CONFIG） | — | python |
| `SPARK` | 已存在（入口未暴露） | `SparkTaskExecutor` | pyspark/spark-sql/jar | 可选 SPARK 数据源 | `_sparkMode`,`_jarRef`,`_mainClass` | scala/python |
| `ECHO` | 已存在（测试用） | `EchoTaskExecutor` | 文本 | — | — | text |
| **`HIVE`** | **新（工作流A）** | `HiveTaskExecutor` | HQL | `datasource_id`（HIVE 类型，HiveServer2 JDBC） | — | sql |
| **`FLINK`** | **新（工作流C）** | `FlinkTaskExecutor` | Flink SQL / jar | 可选（props_json 集群 / FLINK_HOME） | `_flinkMode`(sql\|jar),`_jarRef`,`_mainClass` | sql |
| **`DATAX`** | **新（工作流B）** | `DataXTaskExecutor` | DataX job JSON | 可选 `datasource_id`+`target_datasource_id` | — | json |
| **`SEATUNNEL`** | **新（工作流B）** | `SeaTunnelTaskExecutor` | SeaTunnel 配置（HOCON/JSON） | 可选 source+sink | — | hocon/text |

**不变量**：type 大写匹配；未知 type → `NO_EXECUTOR` 可辨识失败（不静默当 SHELL）。

## 2. 数据源类型（Data Source Type）

存储：`datasource_types` 表（数据驱动，`data.sql` seed）。**OLAP 全部已就绪**，本轮不新增行（Flink 若走数据源型集群配置为可选增强）。

| code | category | driver | 状态 |
|---|---|---|---|
| `STARROCKS` | MPP | `com.mysql.cj.jdbc.Driver`（9030） | 已 seed，四同步点齐 |
| `DORIS` | MPP | `com.mysql.cj.jdbc.Driver`（9030） | 已 seed，四同步点齐 |
| `CLICKHOUSE` | MPP | `com.clickhouse.jdbc.ClickHouseDriver`（8123） | 已 seed，四同步点齐 |
| `HIVE` | MPP | `org.apache.hive.jdbc.HiveDriver`（10000） | 已 seed，`HiveTaskExecutor` 复用 |

**四处同步点**（新增任何 JDBC 数据源类型时须全改；OLAP 已满足）：`data.sql` seed · `DatasourceResolver.buildJdbcUrl` · `JdbcConnectionTester`(JDBC_TYPES/buildJdbcUrl/builtinFallbackDriver/validationQuery) · 前端 `datasource-type-config.ts`。

## 3. 执行上下文扩展（ExecutionContext）— 共享类型

新增单个可空字段（工作流 B 先落 main）：

```
ExecutionContext( ..., SparkSubmitRef spark /*不动*/, EngineSubmitRef engine /*新*/ )

record EngineSubmitRef(
    String kind,        // FLINK | DATAX | SEATUNNEL
    String engineHome,  // FLINK_HOME / DATAX_HOME / SEATUNNEL_HOME
    String mode,        // Flink: sql|jar；DataX/SeaTunnel: null
    String jarPath,     // Flink jar 形态
    String mainClass,   // Flink jar 形态 --class
    String configPath,  // 执行器写入的临时作业/配置文件路径（运行期填）
    Map<String,String> props  // 集群/引擎附加配置（jobmanager/parallelism…）
)
```

**兼容性**：保留现有全部 telescoping 构造器，新增「含 engine 的全参构造」；老调用点 `engine=null` 零改动。

## 4. 执行结果（ExecutionResult）— 复用不变

三态语义沿用 `TaskExecutor.ExecutionResult`：
- 成功：`success=true, skipped=false`
- 失败：`success=false, skipped=false`（作业错误 / 资产缺失 / 真实超时）→ `exitCode` 忠实透传
- 跳过：`skipped=true, success=false, exitCode=0`（引擎/驱动/可执行程序缺失）→ 非失败完成、不阻塞下游

所有新执行器继承 `AbstractTaskExecutor`，用 `ExecutionResult.skipped(reason)` 工厂；不新增状态机状态。

**日志内容规范（FR-011/011a/011b，SC-007）**：`stdout` 字段承载完整日志——起止 banner（`WorkerExecService` 提供）+ 逐行执行日志 + **SQL/HQL 结果集渲染**（表头+行，带行数上限截断）+ **引擎原生 stdout/stderr**（子进程逐行忠实透出）。凭据脱敏（FR-017）。

## 5. 文件契约（TaskDoc）round-trip

- 引擎子模式与配置入既有 `TaskDoc.params: Map<String,Object>`（`_flinkMode` 等）+ `datasource`/`targetDatasource` 字段 → 自动 round-trip。
- **不新增** `TaskDoc` 类型化字段（除非 tasks 阶段证明必要）。
- 契约不变量：push→pull 到干净目录语义等价（Constitution II）。

## 实体关系摘要

```
task_def(type, datasource_id, target_datasource_id, params_json)
   │  type 选 → TaskExecutor(byType)
   │  datasource_id → datasources → datasource_types(driver, default_port)
   │  target_datasource_id → datasources           （DataX/SeaTunnel 汇端）
   └→ DatasourceResolver.resolve() → ResolvedConnection{sql|shell|python|spark|engine}
        → gateway 建 ExecutionContext{datasource|shellEnvVars|pythonConfigPath|spark|engine}
          → executor.execute() → ExecutionResult(三态)
```
