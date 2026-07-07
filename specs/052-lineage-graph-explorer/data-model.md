# Phase 1 Data Model: Lineage Graph Explorer

血缘图在 **Neo4j**（唯一存储），本特性不改图写侧模型，只**增查询/富化投影**。下列为查询契约涉及的 DTO 与前端视图状态。PG `schema.sql` 与 `schema_version` **不变**。

## Neo4j 图模型（既有，仅引用）

- **节点标签**: `Datasource(dsKey)` · `Table(tableKey; qualifiedName, layer, datasourceId)`（**无 name，用 `COALESCE(name,qualifiedName)`**）· `Column(columnKey; name, dataType, ordinal, tableKey)` · `Metric(metricKey; metricType, metricId, name)` · `Task(taskKey; taskDefId)` · `TaskRun(instanceId; bizDate)`。均带 `tenantId/projectId`。
- **关系**: 结构 `HAS_DATASOURCE/HAS_TABLE/HAS_COLUMN`；设计态 `READS/WRITES/READS_COL/WRITES_COL`；遍历 `FLOWS_TO`（表级）、`DERIVES_FROM`（列级；transform/confidence/source/taskDefId）；指标 `COMPUTED_FROM`；运行 `SYNCED{bizDate,rowCount,bytes}`。
- **约束/索引**: 6 唯一约束 + 5 复合 range 索引 `(tenantId,projectId)`（`Neo4jSchemaInitializer`）。**MVP 不新增索引**（CONTAINS 搜索）。

## DTO（后端 `dataweave-master/.../lineage/`）

### 新增 `SearchCandidate`
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 节点 id（供后续以其为锚点查询） |
| `type` | NodeType(`TABLE\|COLUMN\|METRIC`) | 消歧标注 |
| `name` | String | Table=qualifiedName / Column=name / Metric=name |
| `layer` | String? | Table 层（ODS/DWD/…），Column/Metric 为 null |
| `datasource` | String? | 消歧：Table=datasourceId / Column=tableKey / Metric=metricType |

约束：结果严格 `tenantId/projectId` 过滤（FR-022）；空关键字或无匹配 → `[]`（FR-011）；分页 `offset/limit`（clamp 默认 100 / 硬顶 2000）。

### 新增 `LineagePath`
| 字段 | 类型 | 说明 |
|---|---|---|
| `from` / `to` | GraphNodeView | 端点 |
| `nodes` | List\<GraphNodeView\> | 所有连接路径上的节点去重集 |
| `edges` | List\<FlowEdgeView\> | 路径上的边去重集（供高亮） |
| `pathExists` | boolean | 无路径时 false（FR-014 提示） |
| `truncated` | boolean | 达 `pathCap` 截断 |

约束：有界变长 `*1..depth`（clamp≤20）+ `pathCap`；Neo4j 默认关系不重复防环（FR-025）。

### 修改 `ImpactResult`
| 字段 | 变更 | 说明 |
|---|---|---|
| `root` | 不变 | 锚点 |
| `downstream` | 不变 | 当前页受影响节点 |
| `edges` | **填充**（原恒 `List.of()`） | 连接受影响节点的边，闭合于 `downstream` 集，经 `annotateCorrections`（REMOVED 剔除，FR-018） |
| `nodeCount` | 语义澄清 | = 当前页条数（`downstream.size()`） |
| `reachableTotal` | **新增 int** | 真实下游可达总数（独立 COUNT，FR-013） |
| `totalIsLowerBound` | **新增 boolean** | 达 `countCap` 时 true → 前端显示「≥N」 |
| `truncated`/`truncatedAt` | 不变 | 分页截断 |

### 不改结构，仅填充
- **`GraphNodeView.attrs`**（`Map<String,Object>`，开放）：Table 节点富化 `layer`、`producers`(String[])、`syncedRowsToday`(long)、`lastSyncDate`(String)。`owner`/`tag` MVP 不在图查询带出（详情面板按需从 PG catalog 补取或 follow-up）。
- **`LineageGraph.edges`**：`neighborhood` 双向查询填充（原 controller 丢边）。

### 查询过滤参数（新增可空 query params，作用于 upstream/downstream/impact/neighborhood）
`layers`(List) · `types`(List) · `confidences`(List) · `sources`(List) —— 服务端 Cypher `$x IS NULL OR ...`；边过滤在 `annotateCorrections` 之后。

## 前端视图状态（`lib/workspace/lineage-selection-store.ts` + view state）

### ViewState（可深链恢复，FR-021）
| 字段 | 类型 | 说明 |
|---|---|---|
| `anchorId` | string | 当前锚点节点 id |
| `direction` | `upstream\|downstream\|both` | 方向（默认 both） |
| `depth` | number(1..20) | 展开深度（默认 3） |
| `granularity` | `TABLE\|COLUMN` | 粒度 |
| `filters` | { layers?, types?, confidences?, sources? } | 过滤 |
| `expandedNodeIds` | Set\<string\> | 原地增量展开的节点（FR-005） |

深链编码为 URL query（`?open=lineage&anchor=&dir=&depth=&gran=`…），进入视图恢复。

### Selection（面板内容切换）
`selectedNode: GraphNodeView | null` · `selectedEdge: FlowEdgeView | null` · `panelTab: node|edge|impact`。选中驱动 `DetailPanelShell` `headerExtra` Tab 与 children。

### LineageNodeData（ReactFlow 自定义 node data，`nodes/lineage-node-types.ts`）
`id, type(DATASOURCE|TABLE|COLUMN|METRIC), name, layer?, freshness?, syncedRowsToday?, producers?, columns?(展开列), isAnchor, isImpacted, expanded` —— 与工作流 `CanvasNodeData` 分离。

## 状态/交互不变量

- **项目隔离**：所有查询与搜索结果 100% 限当前项目（FR-022）。
- **截断显式**：达 `MAX_NODES/countCap/pathCap` 必置 `truncated`/`totalIsLowerBound` 并前端提示（FR-024）。
- **人工修正边**：REMOVED 不出图、CONFIRMED 以对应样式（FR-018）；写操作仍走 `/corrections` 闸门（FR-026）。
- **边闭合**：impact/neighborhood/path 返回的边两端必在返回节点集内（无悬挂边）。
