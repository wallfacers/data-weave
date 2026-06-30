# Contract: recordSynced 调用（per-table）

**Feature**: 025-lineage-synced-rows

## 接口（既有，本期不改契约）

```java
// LineageStore（domain/lineage）
void recordSynced(long tenantId, long projectId, String instanceId,
                  TableRef table, Long rowCount, Long bytes, String bizDate);
```

## 调用语义（本期接线）

- **触发点**：`WorkerReportService.reportFinished`，**仅 SUCCESS**。
- 对 worker 上报的每个 `StatementMetric(sqlText, updateCount)`：
  1. `SqlTableExtractor` 解析该 `sqlText` 的写表（MVP 仅识别 INSERT/MERGE）。
  2. 每个 writeTable 调一次 `recordSynced`，`rowCount = updateCount`（单 statement 多表**共享** updateCount，JDBC 无 per-table 分解，近似）。
  3. `bytes = null`（JDBC 不提供）。
  4. `bizDate / tenantId / projectId` 取自 `TaskInstance`（reportFinished 持有 `ti`）。
- 建议：`:TaskRun` MERGE 时 SET `taskDefId`（`ti.getTaskId()`），零成本给后续"按任务查同步行数"留口子。

## 降级

- `updateCount < 0`（SELECT/DDL）→ 跳过该 statement。
- 解析不出写表（UPDATE/DELETE / Calcite 失败）→ 跳过；`updateCount > 0` 时 **WARN**（不静默丢）。
- `statementMetrics` null/empty（旧 worker）→ 跳过 recordSynced。
- neo4j 不可达 → try-catch，**不阻断**主链路（任务仍 SUCCESS）。

## neo4j 落点（既有实现，`Neo4jLineageStore:155-177`）

```cypher
MERGE (r:TaskRun {instanceId:$iid}) ON CREATE SET r.tenantId=$t, r.projectId=$p, r.bizDate=$bd [, r.taskDefId=$td]
... ensureTable(tx, table) ...
MERGE (r)-[:SYNCED {rowCount:$rc, bytes:$b, bizDate:$bd}]->(t:Table {tableKey:$tk})
```

注：`:Table` 经 `ensureTable` MERGE 与设计态 `:Table` 同节点（tableKey），故 runtime SYNCED 自然挂到设计态表节点。
