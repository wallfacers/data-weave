# Contract: 血缘探索器读侧 API（054）

范围：**读侧投影富化**（既有端点向后兼容追加字段）+ **1 个可选新端点**（P3）。统一包络 `ApiResponse<T>`（后端 `code: int`，成功 `code=0` + `data`；降级/错误经 `errorCode`，前端按 `errorCode === "lineage.store_unavailable"` 走不可用态）。所有端点带可选 `projectId`（缺省取 `TenantContext.projectId()`，皆空 → `project.required`）。**不改任何端点的既有字段语义**。

---

## A. 既有端点·节点投影富化（向后兼容）

以下端点返回的 `GraphNodeView.attrs` 对 **TABLE / COLUMN** 节点**新增两键**（METRIC 不带、DATASOURCE 自身即源）：

- `attrs.datasourceId: string` —— 所属数据源 dsKey
- `attrs.datasourceName: string` —— 数据源展示名

受影响端点（字段追加，形状不变）：

| 端点 | 说明 |
|---|---|
| `GET /api/lineage/tables/{id}/neighborhood` | 双向邻域，节点带数据源 → 前端徽标 + 跨源判定 |
| `GET /api/lineage/tables/{id}/upstream` · `/downstream` | 同上 |
| `GET /api/lineage/columns/{id}/upstream` · `/downstream` | 列级，节点继承所属表数据源 |
| `GET /api/lineage/impact/{nodeId}` | 影响集节点带数据源 |
| `GET /api/lineage/paths?from=&to=` | 路径节点带数据源 |
| `GET /api/lineage/tables/{id}/columns/lineage` | 列 + 列级 `DERIVES_FROM` 边（**前端不再丢弃**，用于字段连线）；列节点带数据源 |

**契约断言**：
1. 对跨数据源 seed，`neighborhood` 返回的表节点 `attrs.datasourceId`/`datasourceName` 非空且与图库一致；同库节点共享同值。
2. `columns/lineage` 返回的 `edges` 中列级边 `from`/`to` 为**列 id**，且列节点 `parentId` 指向所属表。
3. METRIC 节点 attrs **不含** datasource 键。
4. 跨项目：他项目资产不出现在任一返回中。
5. 既有字段（id/type/name/layer/granularity/parentId 及原 attrs 键）**一字不改**。

---

## B. 既有端点·搜索候选富化

`GET /api/lineage/search?q=<keyword>&types=&offset=&limit=`（`q` required）

`SearchCandidate` **新增可空字段** `datasourceName: string`（既有 `datasource`=datasourceId 保留）：

```json
{
  "code": 0,
  "data": [
    { "id": "…", "type": "TABLE", "name": "dwd_user", "layer": "DWD",
      "datasource": "1|10.0.0.2|3306|hive_dw", "datasourceName": "hive-dw" }
  ]
}
```

**契约断言**：
1. 输入表名片段（如 `user`），返回跨多个数据源的候选，每项 `datasourceName` 可用于区分同名跨库表。
2. 严格项目隔离；`types` 过滤生效；结果按 name 排序 + 截断到 `limit`。
3. 无匹配 → `data: []`（非报错、非 500）。

---

## C. 新端点（P3 可选）：按数据源列表

`GET /api/lineage/datasources/{id}/tables?offset=&limit=`

- `data: List<GraphNodeView>`（该数据源下的 TABLE 节点，带 `attrs.datasourceId/datasourceName`）。
- Cypher：`MATCH (d:Datasource {id: $id, tenantId, projectId})-[:HAS_TABLE]->(t:Table) RETURN … SKIP $offset LIMIT $limit`。
- **契约断言**：① 只返回该数据源、该项目的表；② 修正 052 遗留占位（此前展开数据源错调 columns）；③ 不交付时前端分面「数据源」维度可整体从缺，US1 搜索主入口不受影响。

---

## D. 明确不做

- 不改 `FlowEdgeView`：跨源/库内/未知分类是**前端呈现层派生**，不落后端边字段。
- 不新增 neo4j 索引 / 不改写侧 / 不改 PG / 不 bump `schema_version`。
- 不改既有端点路径、既有查询参数、既有返回字段语义。
