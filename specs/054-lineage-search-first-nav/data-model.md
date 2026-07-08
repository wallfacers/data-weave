# Phase 1 Data Model: 血缘探索器入口重构

只读投影富化 + 前端呈现模型。**无新建存储、无 schema 变更、无 PG/neo4j 写侧改动**。所有数据源归属数据已存在于 neo4j，仅新增投影与前端派生。

---

## 1. 后端 DTO 富化（向后兼容追加）

### 1.1 GraphNodeView.attrs 新增键（不改 record 结构）

`GraphNodeView(id, type, name, layer, granularity, parentId, attrs)` 结构不变；`attrs: Map<String,Object>` 追加：

| attrs 键 | 类型 | 语义 | 来源 |
|---|---|---|---|
| `datasourceId` | String | 节点所属数据源 dsKey | `:Table.datasourceId`；列节点继承所属表 |
| `datasourceName` | String | 数据源展示名 | `(:Datasource{id=datasourceId}).name` |

- **TABLE**：直接投影 `t.datasourceId` + join `:Datasource.name`。
- **COLUMN**：随所属表继承（投影时带出，或前端按 `parentId` 归属）。
- **DATASOURCE**：其自身即数据源，`datasourceId=自身 id`、`datasourceName=name`（可省，前端已知类型）。
- **METRIC**：无 datasource → 两键**缺省**（不写入）。

### 1.2 SearchCandidate 富化

现 `record SearchCandidate(String id, String type, String name, String layer, String datasource)`（`datasource` = Table 的 datasourceId）。

**改**：追加可空 `String datasourceName`（数据源展示名），用于候选项显示与同名跨库区分。`datasource`（id）保留不动，向后兼容。

### 1.3 FlowEdgeView

**不改**。跨源分类是前端呈现层派生（见 §3），不落后端边字段。

---

## 2. 投影 Cypher 追加（示意，非最终实现）

各节点投影处（`LineageQueryService` 的 `traverse`/`neighborhood`/`impact`/`pathsBetween`）在 Table 节点 RETURN 追加：

```cypher
// 表节点已有 RETURN t.id AS id, 'TABLE' AS type, t.qualifiedName AS name, t.layer AS layer, ... , <attrs> AS attrs
OPTIONAL MATCH (d:Datasource {id: t.datasourceId})
// 把 datasourceId / d.name 并入 attrs 表达式（nodeAttrsExpr/tableAttrsCypher 扩展）
```

- 隔离不变量保持：所有查询恒带 `WHERE t.tenantId=$tenantId AND t.projectId=$projectId`；`OPTIONAL MATCH :Datasource` 同租户/项目。
- `datasources()`/`columns()`/`expandColumns()` 既有查询按需带出 `datasourceId`（列继承）。

---

## 3. 前端呈现模型

### 3.1 GraphNodeView（TS）+ readNodeAttrs

`frontend/lib/lineage-api.ts`：`LineageNodeAttrs` / `readNodeAttrs()` 补 `datasourceId?: string`、`datasourceName?: string`。`SearchCandidate` 补 `datasourceName?: string`。

### 3.2 LineageNodeData（节点组件 data）

`nodes/lineage-node-types.ts` 的 `LineageNodeData` 追加：

| 字段 | 类型 | 用途 |
|---|---|---|
| `datasourceId?` | string | 徽标配色种子 + 跨源判定 |
| `datasourceName?` | string | 徽标显示名 |

列项 `LineageColumnItem` 已有稳定 `id`（列 id）——直接用作列级 Handle 的 `id`（无需新增字段）。

### 3.3 列级 Handle 模型（表/字段连线核心）

- 表节点渲染每个列行时，额外渲染两个 `@xyflow/react` `Handle`：
  - `type=target, position=Left, id=<columnId>`
  - `type=source, position=Right, id=<columnId>`
  - 垂直位对齐该列行（Handle 用绝对定位到行中线；行高 = 052 既有 `COL_ROW_H`）。
- 列级边（ReactFlow Edge）：`{ id: colEdgeKey, source: fromTableId, target: toTableId, sourceHandle: fromColId, targetHandle: toColId, data: FlowEdgeView }`。
- 表→表边：沿用现状（无 handle 指定，连节点级左右 Handle）。二者共存，样式区分（列级边更细/异色）。

### 3.4 跨源分类（派生，不落存储）

`lineage-layout.ts` 内派生，边渲染时计算：

```
crossSource(edge) =
  ds(from) && ds(to) && ds(from) !== ds(to)  → 'cross'
  ds(from) && ds(to) && ds(from) === ds(to)  → 'intra'
  否则（任一端未知/metric）                    → 'unknown'
```

样式（与 confidence 编码正交）：
- `cross` → 描边 `--color-warning`（+ 可选 marker）；
- `intra` → 沿用现状（confirmed 绿 / inferred 虚线 / default 边框色）；
- `unknown` → 中性弱化描边。
- confidence 的 dash（inferred）与 highlight（primary/animated）叠加不变。

### 3.5 lineage-graph reducer state 扩展

`LineageGraphState` 追加对列级边的保留（二选一，tasks 定）：
- 方案 a：`columnEdgesByTable: Record<tableId, FlowEdgeView[]>`，`expandColumns` 时存、`collapseColumns` 时删；
- 方案 b：并入既有 `edges` 并以 `granularity==='COLUMN'` 标记，`collapse` 时按来源表清理。

`toggleColumns`（view 层）**不再丢弃**列级边——保留以驱动 §3.3 连线。

### 3.6 Facet（P3 可选）

前端浏览分面模型（非持久化）：

| Facet | 分组键 | 数据来源 |
|---|---|---|
| `datasource` | datasourceId | 若交付 `/datasources/{id}/tables`；否则占位修正 |
| `layer` | layer（ODS/DWD/DWS/ADS） | 客户端按已知资产 layer 分组 |
| `recent` | — | 客户端本地记录（会话内锚定过的资产），**不含 ownership** |

`最近` 建议 sessionStorage/内存记录，键 = 资产 id + name + datasourceName。

---

## 4. Datasource 配色/缩写工具（新增）

`frontend/lib/workspace/lineage-datasource-style.ts`：
- `datasourceColor(datasourceId): string` —— 对 id 做确定性 hash → 从有限调色板取色（语义 token 或 chart-* 变量）。
- `datasourceAbbr(name): string` —— 取展示名缩写（如 `mysql-prod`→`My`）。
- **配色耗尽兜底**：调色板用尽时，不同 id 仍靠**徽标文本/缩写**保证可辨（FR-011/SC-003）。

---

## 5. 隔离与兼容性约束

- 所有新投影/新端点恒带 `tenantId/projectId` 过滤（沿用 `TenantContext`+`project()`）。
- 节点投影**仅追加 attrs 键 / SearchCandidate 仅追加可空字段** → 052/053 既有消费者不受影响（向后兼容）。
- 不改 `FlowEdgeView`、不改 neo4j 写侧、不改 PG、不 bump `schema_version`。
