# Contract: 指标市场端点（/api/marketplace/*）+ 指标定义源（/api/metrics）

> 既有端点（023 `MetricMarketplaceController` + `MetricsController`）。本特性消费,不改动。包络同 `ApiResponse<T>`。

## 读

### GET /api/marketplace/metrics — 搜索（认证分面/分页）
Query: `keyword?`, `certification?`(NONE|CERTIFIED), `page=1`, `size=20`, `projectId=1`
→ `data: ListingSearchResult { items: ListingSummary[], total, facets:{certification:{值:计数}}, truncated }`
- 本特性新增前端用法：传 `certification` 实现分面过滤；`page` 实现分页。

### GET /api/marketplace/metrics/{id} — 详情 + 血缘（降级安全）
→ `data: MarketplaceDetail { detail: ListingDetail, lineage: LineageEntryView }`

### GET /api/metrics — 指标定义池（上架选择器数据源）
→ `data: MetricCard[] { id, code, name, unit, versionNo, status, value }`（每 code 最新版本）

## 写（经闸门;三态分流）

### POST /api/marketplace/metrics?projectId=1 — 上架
Body: `{ metricId(必填), metricType?(默认ATOMIC), metricCode?, ownerId?, description?, freshnessInfo? }`
→ `data: GateResult`;幂等（已存在→更新复位 LISTED）;错误 `catalog.listing_invalid`(400 metricId 空)

### DELETE /api/marketplace/metrics/{id}?projectId=1 — 下架
→ `data: GateResult`（status→DELISTED）

### POST /api/marketplace/metrics/{id}/certify?projectId=1 — 认证（既有,L2 可能 PENDING_APPROVAL）
→ `data: GateResult`;DELISTED 不可认证→`catalog.not_certifiable`(409)

### POST /api/marketplace/metrics/{id}/reuse?projectId=1 — 复用（防环）
Body: `{ consumerType?(METRIC|TASK|ASSET,默认METRIC), consumerRef(必填) }`
→ `data: GateResult`;错误 `catalog.reuse_invalid`(400 ref 空) / `catalog.reuse_cycle`(成环→前端专门提示「会形成循环依赖」)

## 前端契约测试要点
- 上架 Dialog 从 `GET /api/metrics` 拉定义、提交 metricId+metricType。
- 复用 Dialog：consumerType 可选、`catalog.reuse_cycle` → 专门防环提示（区别于通用失败）。
- certification 分面过滤、分页 query 正确。
