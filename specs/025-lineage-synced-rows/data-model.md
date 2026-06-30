# Data Model: 运行态同步行数采集（recordSynced 接入）

**Feature**: 025-lineage-synced-rows | **Phase**: 1 | **Date**: 2026-06-30

【新增】/【既有-改】/【既有-不改】。依据 [research.md](./research.md)。

## 1. StatementMetric【新增】

```java
record StatementMetric(String sqlText, long updateCount)
```
- worker 收集的 per-statement 原始对；`updateCount≥0`（<0 不收）。
- 跨 worker→api 上报 DTO 的 list 元素；Jackson 序列化。

## 2. ExecutionResult【既有-改】

`TaskExecutor.java:50-63` record，加字段：

```java
record ExecutionResult(boolean success, int exitCode, String stdout, String stderr,
                       boolean truncated, boolean timedOut, String message, boolean skipped,
                       List<StatementMetric> statementMetrics)   // ← 新增
```
- SQL 执行器填；其他执行器（Spark/Python/Shell）传 `List.of()`。
- 现有各执行器 `doExecute` return 点同步加 `List.of()`。

## 3. 上报 DTO【既有-改】

| 结构 | 改动 |
|---|---|
| `TaskReportRequest`（api ClusterController 反序列化） | 加 `List<StatementMetric> statementMetrics`；`@JsonIgnoreProperties(ignoreUnknown=true)` 已在 → 新 worker+旧 master 向后兼容 |
| `WorkerExecController.ReportCallback.reportToMaster`（`:204` 手拼 JSON） | 改 Jackson `ObjectMapper` 序列化整 payload（含 statementMetrics 数组），或 `StatementMetric` + `escapeJson` |
| `InProcessTaskExecutionGateway.run`（all-in-one） | 从 `result.statementMetrics()` 透传 reportFinished（进程内直传对象，无序列化） |

## 4. WorkerReportService.reportFinished【既有-改】签名

```java
void reportFinished(long taskInstanceId, int exitCode, String tailLog,
                    List<StatementMetric> statementMetrics)   // ← 新增参数
```
- 两个调用点（`InProcessTaskExecutionGateway:160`、`ClusterController:78`）同步加参。
- 注入 `LineageStore` + `SqlTableExtractor`（或 `LineageEdgeAssembler`）。
- 流程：仅 SUCCESS → 对每个 statementMetric → `SqlTableExtractor` 解析写表 → 每个 writeTable `recordSynced(tenantId, projectId, instanceId, tableRef, updateCount, null, bizDate)` → 外层 try-catch。

## 5. recordSynced【既有-不改契约，建议小补】

`LineageStore.recordSynced(tenantId, projectId, instanceId, TableRef, Long rowCount, Long bytes, String bizDate)` + `Neo4jLineageStore:155-177`。
- 契约不改（本期接线）。
- 建议：`:TaskRun` MERGE 时 SET `taskDefId`（reportFinished 有 `ti.getTaskId()`）——零成本，供后续按任务查。`bytes` 传 null（JDBC 拿不到）。

## 6. neo4j 图模型【既有-不改】

| 节点/边 | 属性 | 本期 |
|---|---|---|
| `:TaskRun` | `instanceId, tenantId, projectId, bizDate, (taskDefId 建议+)` | recordSynced 建（首次有生产调用） |
| `:TaskRun` -[:SYNCED]-> `:Table` | `rowCount, bytes(null), bizDate` | recordSynced 建 |

注：`:Table` 经 `ensureTable` MERGE，与设计态 `:Table` 同节点（tableKey），故 runtime SYNCED 自然挂到设计态表。

## 7. 校验/降级规则

- `updateCount<0` → 跳过 statement。
- 解析不出写表（UPDATE/DELETE/Calcite 失败）→ 跳过；`updateCount>0` 时 WARN。
- `statementMetrics` null/empty（旧 worker）→ 跳过 recordSynced。
- 单 statement 多表 → 每表各 recordSynced，共享 updateCount（近似）。
- neo4j 不可达 → try-catch 吞，不阻断。
- 仅 SUCCESS 路径。
