# Contract: 资产目录端点（/api/catalog/*）

> 既有端点（023 已实装,`AssetCatalogController`）。本特性**消费**之,不新增/改动。统一包络 `ApiResponse<T>{code,message,errorCode,data}`;写返回 `data=GateResult`。鉴权 `Authorization: Bearer <token>`,租户由服务端身份解析。

## 读

### GET /api/catalog/assets — 搜索（分面/分页/质量）
Query（后端实际入参,`AssetDtos.SearchQuery`）: `keyword?`, `type?`, `owner?`, `tag?`, `sensitivity?`, `qualityMin?:number`, `page=1`, `size=20`, `projectId=1`
→ `data: SearchResult { items: AssetSummary[], total, facets: {sensitivity|status|owner|tag: {值:计数}}, truncated }`
- **无 `status` 入参**；基础查询恒含 `a.status <> 'RETIRED'`（`AssetSearchService` L58）→ 返回项只可能 ACTIVE/STALE。
- 本特性前端用法：传 `owner`/`tag`/`sensitivity` 实现分面真过滤；`status` facet **仅只读展示**（analyze F1）；`page` 实现分页。
- `qualityMin`：后端 v1 **no-op**（缺 022 评分卡表,`AssetSearchService` L84 不施加过滤）→ 前端透传 + 静态声明（analyze F2）。

### GET /api/catalog/assets/{id} — 详情
→ `data: AssetDetail`（PII 仅 owner/steward,否则 `catalog.forbidden_sensitivity`）

### GET /api/catalog/subscriptions — 当前用户订阅
→ `data: AssetSubscription[]`

## 写（经服务端闸门;前端按 GateResult.outcome 三态分流）

### POST /api/catalog/assets?projectId=1 — 创建
Body（资产字段直传）: `{ datasourceId, qualifiedName(必填), name?, description?, ownerId?, stewardId?, sensitivity?, tags?, glossaryTerms?, lineageTableRef?, schemaSnapshotJson? }`
→ `data: GateResult`;错误：`catalog.asset_invalid`(400 限定名空) / `catalog.duplicate_asset`(409 重复)

### PATCH /api/catalog/assets/{id}?projectId=1 — 编辑（部分更新）
Body（patch,仅含改动键）: `{ name?, description?, ownerId?, stewardId?, sensitivity?, tags?, glossaryTerms?, lineageTableRef?, schemaSnapshotJson?, status? }`
→ `data: GateResult`（改 schemaSnapshotJson 触发服务端漂移通知）

### DELETE /api/catalog/assets/{id}?projectId=1 — 下线
→ `data: GateResult`（status→RETIRED）

### POST /api/catalog/assets/{id}/reconcile?projectId=1 — 对账
→ `data: GateResult`;判据=lineageTableRef 非空→ACTIVE、空→STALE（RETIRED 不变）

### POST /api/catalog/subscriptions?projectId=1 — 订阅（既有）
Body: `{ targetType:"ASSET", targetId, changeFilter? }` → `GateResult`

### DELETE /api/catalog/subscriptions/{id}?projectId=1 — 退订
→ `data: GateResult`（属主校验在服务端）

## 前端契约测试要点
- API client 各方法发出的 method/path/query/body 与上表一致（vitest 断言 fetch 入参）。
- 三态：`outcome=EXECUTED/PENDING_APPROVAL/REJECTED` 与 `code≠0` 的分流正确（不把 PENDING 当成功）。
- 已知 errorCode 映射到专门文案。
