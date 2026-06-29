# API Contract: `/api/lineage/*`（多粒度重设计）

**Feature**: 020-lineage-graph-api | **Date**: 2026-06-30
**Controller**: `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/LineageGraphController.java`
**返回包络**: 统一 `ApiResponse<T>`（`{ code, message, data }`，既有约定）。
**隔离**: 所有端点按 `(tenantId, projectId)` scope；当前注入点固定 1/1（与现状一致，预留 `TenantContext`）。
**降级**: neo4j 不可达 → `ApiResponse.err("lineage.store_unavailable")`（HTTP 由 `GlobalExceptionHandler` 决定，i18n 按 UI locale）。
**模型**: 见 [data-model.md](../data-model.md)（GraphNodeView / FlowEdgeView / LineageGraph / ImpactResult / MetricLineage / SyncSummary）。

---

## 1. `GET /api/lineage/datasources` — 数据源列表（库层）

- **用途**: 三级树第一层。返回当前租户去重后的 `:Datasource` 节点。
- **Query**: `offset?`（默认 0）、`limit?`（默认 100，≤MAX_NODES）。
- **200**: `ApiResponse<List<GraphNodeView>>`，每项 `type=DATASOURCE`。
- **错误**: `lineage.store_unavailable`。

## 2. `GET /api/lineage/tables/{id}/columns` — 表的列（结构下钻）

- **用途**: 三级树第三层（展开某表取其列）。`{id}` = 图内 table id。
- **Query**: `offset?`、`limit?`。
- **200**: `ApiResponse<List<GraphNodeView>>`，每项 `type=COLUMN`、`parentId={id}`，按 `ordinal` 排序。
- **降级**: 列级缺失（降级表）→ 空数组（前端"仅表级"）。
- **错误**: `lineage.store_unavailable`。

## 3. `GET /api/lineage/tables/{id}/upstream` — 表上游（变长路径）

- **用途**: 数据从哪来。Cypher `<-[:FLOWS_TO|DERIVES_FROM*1..depth]-`。
- **Query**:
  - `depth?`（默认全闭包，clamp 到 MAX_DEPTH=20）
  - `granularity?` = `table`（默认，仅 FLOWS_TO）| `column`（含 DERIVES_FROM）
- **200**: `ApiResponse<LineageGraph>`（nodes/edges/granularity/depth/truncated/truncatedAt）。
- **错误**: `lineage.store_unavailable`。

## 4. `GET /api/lineage/tables/{id}/downstream` — 表下游（变长路径）

- 同 3，方向 `-[...]->`。Acceptance：`a→b→c→d` 查 a 的 downstream 返回 {b,c,d}。

## 5. `GET /api/lineage/columns/{id}/upstream` — 列上游（列级流）

- **用途**: 列级溯源。`{id}` = 图内 column id；Cypher 沿 `DERIVES_FROM`。
- **Query**: `depth?`（同上界）。
- **200**: `ApiResponse<LineageGraph>`，`granularity=column`，节点 `type=COLUMN`，边带 `transform`。

## 6. `GET /api/lineage/columns/{id}/downstream` — 列下游（列级流）

- 同 5，方向反转。

## 7. `GET /api/lineage/impact/{nodeId}` — 影响面

- **用途**: 全下游可达集合（表+列），`[:FLOWS_TO|DERIVES_FROM*]` 闭包。`{nodeId}` 可为 table 或 column id。
- **Query**: `offset?`、`limit?`（节点分页）；`depth?`（默认 MAX_DEPTH）。
- **200**: `ApiResponse<ImpactResult>`（root/downstream 分层分粒度/edges/nodeCount/truncated/truncatedAt）。
- **截断**: 触上界 `truncated=true` + log.warn。

## 8. `GET /api/lineage/metrics/{id}/lineage` — 指标血缘

- **用途**: 指标由哪些表/列计算。`{id}` = metric id（镜像 PG）。Cypher `(:Metric)-[:COMPUTED_FROM]->(:Table|:Column)`。
- **200**: `ApiResponse<MetricLineage>`（metric/sources/edges）。

## 9. `GET /api/lineage/sync-summary` — 运行态聚合

- **用途**: 今日同步行数（`:TaskRun-[:SYNCED]->:Table` 聚合）。
- **200**: `ApiResponse<SyncSummary>`，`data.syncedRows` 可为 null（无采集→前端"估算中"）。

---

## 有界查询不变量（FR-004 / SC-004，适用 3–8）

| 闸 | 默认 | 上界 |
|----|------|------|
| `depth` | 全闭包 | `MAX_DEPTH=20`（clamp） |
| 节点数 | — | `MAX_NODES=2000`（`LIMIT`） |
| 分页 | offset=0,limit=100 | limit≤MAX_NODES |

触上界 → 返回 `truncated=true`/`truncatedAt`，并 `log.warn(锚点 id, depth, 截断数)`，**不静默丢**。

## 兼容性说明

- 现 `GET /api/lineage/graph`、`/tables/{id}/neighborhood`：**重设计期可保留或并入** datasources+下钻+impact 组合。本契约不强制删除旧端点，但前端新视图改用上述多粒度端点；旧端点的 BFS 实现迁到 Cypher（或下线，由 tasks 决定，不留 JDBC BFS 死路径）。
- `sync-summary` 行为保持（null 语义不变），仅底层改读 neo4j `:TaskRun-[:SYNCED]`。
