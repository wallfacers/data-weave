# Contract: Lineage Query API（血缘查询接口）

Base: `/api/lineage` · 全部 `ApiResponse<T>{code,message,data}` 包裹 · 项目隔离经 `TenantContext`（JWT）或 `?projectId=` · 全部端点**只读，不经 PolicyEngine**（唯一写端点 `/corrections` 不在本契约变更范围）。

图例：🆕 新增 · ✏️ 修改 · ♻️ 复用不变。

## 端点清单

| # | Method | Path | 变更 | 关键 Params | Data |
|---|---|---|---|---|---|
| 1 | GET | `/search` | 🆕 | `q`(必), `types`?, `offset=0`, `limit=100`, `projectId`? | `List<SearchCandidate>` |
| 2 | GET | `/tables/{id}/upstream` | ✏️ | `depth`, `granularity`, +`layers`/`types`/`confidences`/`sources`? | `LineageGraph`（attrs 富化 + 过滤） |
| 3 | GET | `/tables/{id}/downstream` | ✏️ | 同上 | `LineageGraph` |
| 4 | GET | `/columns/{id}/upstream`·`/downstream` | ♻️→✏️ | `depth`, 过滤? | `LineageGraph`（启用于前端方向切换） |
| 5 | GET | `/tables/{id}/neighborhood` | ✏️ | `depth=2`, +`granularity`?, 过滤? | `LineageGraph`（**双向带边**，原丢边） |
| 6 | GET | `/impact/{nodeId}` | ✏️ | `depth`, `offset`, `limit`, 过滤? | `ImpactResult`（**edges 填充** + `reachableTotal` + `totalIsLowerBound`） |
| 7 | GET | `/paths` | 🆕 | `from`(必), `to`(必), `depth`? | `LineagePath` |
| 8 | GET | `/tables/{id}/columns/lineage` | 🆕 | `projectId`? | `LineageGraph`（T038：本表列 parentId=本表 ∪ 1 跳邻接列 parentId=其表 + 列到列 DERIVES_FROM 边；chevron 内联展开用） |
| — | GET | `/datasources`·`/tables/{id}/columns`·`/metrics/{id}/lineage`·`/sync-summary`·`/tasks/{id}/hints`·`/tasks/{id}/corrections` | ♻️ | 现有 | 不变 |

## 1. 🆕 GET /search

按名搜索数据资产（US2 / FR-008/009/011/022）。

**Request**: `GET /api/lineage/search?q=order_detail&types=TABLE,COLUMN&offset=0&limit=100`

**200**:
```json
{ "code":0, "message":"ok", "data":[
  { "id":"tbl:hive.sales.dwd_order_detail", "type":"TABLE", "name":"dwd_order_detail", "layer":"DWD", "datasource":"ds:hive_sales" }
]}
```
- `q` 空或无匹配 → `data:[]`（非报错，FR-011）。
- 结果仅当前项目（FR-022）；`limit` clamp 100 / 硬顶 2000。
- MVP `toLower CONTAINS` 中缀匹配（Table→qualifiedName，Column→name，Metric→name）。

## 2–4. ✏️ upstream / downstream（table & column）

节点 `attrs` 富化 + 服务端过滤（US1/US4/US5 / FR-003/007/019）。

**Request**: `GET /api/lineage/tables/{id}/downstream?depth=3&granularity=TABLE&layers=DWD,DWS&confidences=CONFIRMED`

**200 `LineageGraph`**:
```json
{ "code":0,"message":"ok","data":{
  "nodes":[ { "id":"...","type":"TABLE","name":"dwd_order_detail","layer":"DWD",
    "attrs":{ "layer":"DWD","producers":["t_order_detail"],"syncedRowsToday":1204882,"lastSyncDate":"2026-07-07" } } ],
  "edges":[ { "from":"...","to":"...","granularity":"TABLE","confidence":"CONFIRMED","source":"SQL_PARSED","transform":null,"taskDefId":"...","modelVersion":null } ],
  "granularity":"TABLE","depth":3,"truncated":false,"truncatedAt":null } }
```
- 过滤参数可空（`$x IS NULL OR ...`）；边过滤在人工裁决注解之后。
- 达 `MAX_NODES=2000` → `truncated=true`（FR-024）。

## 5. ✏️ GET /tables/{id}/neighborhood（双向带边）

US1 双向（FR-003/007）。原 `edges=List.of()` → 无向遍历返回真实边。

**Request**: `GET /api/lineage/tables/{id}/neighborhood?depth=2&granularity=TABLE`

**200**: `LineageGraph`，`nodes` = 上游∪下游∪锚点（distinct），`edges` = 连接它们的边（distinct，闭合于节点集，环去重 FR-025）。

## 6. ✏️ GET /impact/{nodeId}

影响分析返回边 + 真实可达总数（US3 / FR-012/013）。

**Request**: `GET /api/lineage/impact/{nodeId}?depth=10&offset=0&limit=100`

**200 `ImpactResult`**:
```json
{ "code":0,"message":"ok","data":{
  "root":{ "id":"...","type":"TABLE","name":"dwd_order_detail" },
  "downstream":[ /* 当前页节点 */ ],
  "edges":[ /* 连接受影响节点的边，闭合于 downstream，REMOVED 已剔除 */ ],
  "nodeCount":100,
  "reachableTotal":14,
  "totalIsLowerBound":false,
  "truncated":false, "truncatedAt":null } }
```
- `nodeCount` = 当前页条数；`reachableTotal` = 真实下游可达总数（独立 COUNT）。
- 达 `countCap` → `reachableTotal=countCap` 且 `totalIsLowerBound=true`（前端「≥N」，FR-013）。

## 7. 🆕 GET /paths

两节点间所有连接路径高亮（US3 / FR-014）。

**Request**: `GET /api/lineage/paths?from={A}&to={B}&depth=10`

**200 `LineagePath`**:
```json
{ "code":0,"message":"ok","data":{
  "from":{ "id":"A",... }, "to":{ "id":"B",... },
  "nodes":[ /* 所有 A→B 路径上的节点去重 */ ],
  "edges":[ /* 路径上的边去重，供高亮 */ ],
  "pathExists":true, "truncated":false } }
```
- 无路径 → `nodes:[],edges:[],pathExists:false`（FR-014 提示）。
- 有界 `*1..depth`（clamp≤20）+ `pathCap`；Neo4j 默认关系不重复防环（FR-025）。

## 错误 / 降级

| 场景 | 表现 |
|---|---|
| 血缘存储不可用 | `code="lineage.store_unavailable"` → 前端「血缘不可用」降级（FR-023） |
| 缺项目上下文 | `code="project.required"` |
| 跨项目访问 | 零泄漏：他项目 id 查询返回空 / 不含他项目资产（FR-022） |

## 契约测试要点

- **真 Neo4j IT**（仿 `LineageSeamE2EIT`，扩 `seed-lineage.cypher` 补 `:Task-[:WRITES]->:Table`）：search 命中中缀、impact 返回边且闭合、`reachableTotal` 与分页解耦、neighborhood 双向带边、paths 多路径去重 + 无路径 `pathExists=false`、attrs 富化字段正确。
- **h2 shape 契约**（仿 `LineageGraphEndpointTest`）：新端点参数解析、空态 `[]`、`project.required`。
- **隔离回归**：种子含 tenant2/project2，断言 search/impact 不返回他项目资产。
