# Phase 1 Data Model: 返回视图模型

**Feature**: 020-lineage-graph-api | **Date**: 2026-06-30

本特性**只读**，不定义持久化实体（图节点/关系是 018 的契约，见共享设计 §4）。本文件定义 `/api/lineage/*` 的**返回视图模型**——从 neo4j `Record` 投影、做租户裁剪后透给前端的稳定 DTO（Java `record`，Jackson 3 序列化）。所有模型携带 `type`/`granularity` 供前端分层渲染（FR-008）。

落地包：`backend/dataweave-master/src/main/java/com/dataweave/master/lineage/`。

---

## GraphNodeView · 统一节点视图

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 图内稳定 id（`:Table`/`:Column` 由应用层生成；`:Task`/`:Metric` 镜像 PG 主键）。 |
| `type` | enum `NodeType` | `DATASOURCE` / `TABLE` / `COLUMN` / `METRIC`。 |
| `name` | String | 展示名（datasource.name / table.qualifiedName / column.name / metric.name）。 |
| `layer` | String? | 分层标签（如表的 ODS/DWD/ADS；datasource 层为库名）。 |
| `parentId` | String? | 父节点 id（column→table、table→datasource），供前端三级树挂载。 |
| `attrs` | Map<String,Object>? | 类型相关附加（table: qualifiedName；column: dataType,ordinal；metric: metricType）。 |

**校验/不变量**：`id`+`type` 非空；`type` 与 `attrs` 内容一致；不透出 neo4j 内部 elementId/原始 Record。

---

## FlowEdgeView · 统一血缘流边

| 字段 | 类型 | 说明 |
|------|------|------|
| `from` | String | 源节点 id。 |
| `to` | String | 目标节点 id。 |
| `granularity` | enum `Granularity` | `TABLE`（`FLOWS_TO`）/ `COLUMN`（`DERIVES_FROM`）。 |
| `taskDefId` | Long? | 产生此边的任务（来自关系属性）。 |
| `confidence` | enum? | `CONFIRMED` / `UNVERIFIED` / `CONFLICT`（A×B 交叉校验，018/019 写入）。 |
| `transform` | enum? | 列级：`DIRECT` / `EXPRESSION` / `AGGREGATE`（仅 `COLUMN` 粒度）。 |

**不变量**：`from`/`to`/`granularity` 非空；`COLUMN` 粒度边的 `from`/`to` 必为 `COLUMN` 节点；`transform` 仅列级有意义。

---

## LineageGraph · 子图载荷（上下游/邻域通用）

| 字段 | 类型 | 说明 |
|------|------|------|
| `nodes` | List\<GraphNodeView\> | 子图节点（去重）。 |
| `edges` | List\<FlowEdgeView\> | 子图边。 |
| `granularity` | enum `Granularity` | 本次查询粒度（table/column）。 |
| `depth` | int | 实际生效深度（clamp 后）。 |
| `truncated` | boolean | 是否触上界截断。 |
| `truncatedAt` | Integer? | 截断处的节点数/深度（供前端提示），`truncated=false` 时 null。 |

---

## ImpactResult · 影响面

| 字段 | 类型 | 说明 |
|------|------|------|
| `root` | GraphNodeView | 起点节点。 |
| `downstream` | List\<GraphNodeView\> | 全下游可达集合（`[:FLOWS_TO|DERIVES_FROM*]`），分层分粒度，含 `type` 区分表/列。 |
| `edges` | List\<FlowEdgeView\> | 支撑可达的边集合（供高亮路径）。 |
| `nodeCount` | int | 可达节点数（截断前真实数若可得，否则=返回数）。 |
| `truncated` | boolean | 是否截断。 |
| `truncatedAt` | Integer? | 截断点。 |

---

## MetricLineage · 指标血缘

| 字段 | 类型 | 说明 |
|------|------|------|
| `metric` | GraphNodeView | 指标节点（`type=METRIC`，`attrs.metricType=ATOMIC|DERIVED`）。 |
| `sources` | List\<GraphNodeView\> | `COMPUTED_FROM` 指向的表/列（`type=TABLE|COLUMN`）。 |
| `edges` | List\<FlowEdgeView\> | metric→source 边（granularity 随 source 类型）。 |

---

## SyncSummary · 运行态聚合

| 字段 | 类型 | 说明 |
|------|------|------|
| `syncedRows` | Long? | 今日同步行数聚合（`:TaskRun-[:SYNCED]->:Table`）；**null = 无采集**，前端显示"估算中"（沿用现行为）。 |

---

## DatasourceList / ColumnList · 结构下钻

- `GET /datasources` → `List<GraphNodeView>`（`type=DATASOURCE`，按租户去重后的库节点）。
- `GET /tables/{id}/columns` → `List<GraphNodeView>`（`type=COLUMN`，`parentId=table id`，按 `ordinal` 排序）；支持分页。
- 列级数据缺失（019 未覆盖的降级表）→ 返回空列表，前端"仅表级"降级（Edge Case）。

---

## 枚举汇总

```text
NodeType    = DATASOURCE | TABLE | COLUMN | METRIC
Granularity = TABLE | COLUMN
Confidence  = CONFIRMED | UNVERIFIED | CONFLICT     // 来自 018/019 写入的关系属性
Transform   = DIRECT | EXPRESSION | AGGREGATE       // 列级专有
```

**租户/隔离不变量（贯穿全部模型）**：服务层在 Cypher `WHERE n.tenantId=$t AND n.projectId=$p` 过滤后投影；返回模型**不含** tenantId/projectId（前端无需，且避免越权暴露）。越权访问（请求 id 不在当前 scope）→ 空结果或拒绝，不跨租户泄漏。
