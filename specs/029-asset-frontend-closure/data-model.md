# Data Model: 资产目录 / 指标市场前端收口（Phase 1）

> 本特性**不新增数据结构**（FR-013）。下列均为 023 既有实体,前端经既有端点读写。这里记录前端**表单字段契约**与状态语义,作为 Dialog 实现与测试的依据。镜像后端 `AssetDtos` / `catalog-api.ts` 既有 TS 类型。

## 实体 1：资产 Data Asset

既有 TS：`AssetSummary`（列表）/ `AssetDetail`（详情），见 `frontend/lib/catalog-api.ts`。

| 字段 | 类型 | 创建表单 | 编辑表单(PATCH) | 说明 |
|---|---|---|---|---|
| `datasourceId` | number | ✅ 必填（数据源 Select） | ✘（不可改） | 去重键之一 |
| `qualifiedName` | string | ✅ 必填 | ✘（不可改） | 去重键之一;空→`catalog.asset_invalid`,重复→`catalog.duplicate_asset` |
| `name` | string? | ✅ | ✅ | 展示名 |
| `description` | string? | ✅ | ✅ | |
| `ownerId` | number? | ✅ | ✅ | |
| `stewardId` | number? | ✅ | ✅ | |
| `sensitivity` | enum | ✅ Select | ✅ Select | PUBLIC/INTERNAL/CONFIDENTIAL/PII;默认 INTERNAL |
| `tags` | string[] | ✅ | ✅ | 标签输入 |
| `glossaryTerms` | string[] | ✅（可选） | ✅ | |
| `lineageTableRef` | string? | ✅（可选） | ✅ | **对账判据**：非空→ACTIVE、空→STALE（D8） |
| `schemaSnapshotJson` | string? | （可选,纯文本域） | ✅ | 改动会触发 schema 漂移通知 |
| `status` | enum | ✘（创建恒 ACTIVE） | （经下线/对账间接改） | ACTIVE/STALE/RETIRED |

**状态机**（服务端,前端只触发与呈现）：
- `create` → ACTIVE
- `retire` → RETIRED（详情「下线」+ 确认）
- `reconcile`：lineageTableRef 非空 → ACTIVE;空 → STALE（RETIRED 不变）
- `update`：可改元数据;改 schemaSnapshotJson 触发 ASSET_CHANGED(schema) 通知（服务端）

**端点**（既有,前端消费）：
- `GET /api/catalog/assets`（搜索,分面/分页/qualityMin）
- `GET /api/catalog/assets/{id}`（详情;PII 仅 owner/steward）
- `POST /api/catalog/assets`（创建,body=资产字段）
- `PATCH /api/catalog/assets/{id}`（编辑,body=patch,仅改动键）
- `DELETE /api/catalog/assets/{id}`（下线）
- `POST /api/catalog/assets/{id}/reconcile`（对账）

## 实体 2：指标上架 Metric Listing

既有 TS：`ListingSummary` / `ListingDetail` / `MarketplaceDetail`。

| 字段 | 类型 | 上架表单 | 说明 |
|---|---|---|---|
| `metricId` | number | ✅ 必填（指标定义 Select←`GET /api/metrics`） | 空→`catalog.listing_invalid` |
| `metricType` | string | ✅（默认 ATOMIC） | |
| `metricCode` | string? | （随选择带出） | 展示用 |
| `ownerId` | number? | （默认当前用户） | |
| `description` | string? | ✅ | |
| `freshnessInfo` | string? | ✅ | 新鲜度说明 |
| `certification` | enum | ✘（认证经 certify） | NONE/CERTIFIED |
| `status` | enum | ✘ | LISTED/DELISTED |
| `reuseCount` | number | ✘（只读展示） | |

**状态机**：
- `list`（上架）→ LISTED（幂等：已存在则更新复位 LISTED）
- `certify` → CERTIFIED（既有;DELISTED 不可认证→`catalog.not_certifiable`）
- `delist`（下架）→ DELISTED（详情「下架」+ 确认）

**端点**（既有）：
- `GET /api/marketplace/metrics`（搜索,certification 分面/分页）
- `GET /api/marketplace/metrics/{id}`（详情+血缘降级）
- `POST /api/marketplace/metrics`（上架）
- `DELETE /api/marketplace/metrics/{id}`（下架）
- `POST /api/marketplace/metrics/{id}/certify`（既有）
- `POST /api/marketplace/metrics/{id}/reuse`（复用,body=consumerType/consumerRef）

## 实体 3：订阅 Asset Subscription

既有 TS：`AssetSubscription`（后端 domain）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 退订用 |
| `targetType` | string | "ASSET" |
| `targetId` | number | 资产 id |
| `changeFilter` | string? | 如 "schema,quality,freshness" |
| `ownerId`/user | number | 属主（退订属主校验在服务端） |

**端点**：
- `GET /api/catalog/subscriptions`（当前用户订阅列表）
- `POST /api/catalog/subscriptions`（订阅,既有）
- `DELETE /api/catalog/subscriptions/{id}`（退订）

## 实体 4：复用引用 Metric Reuse Ref

- `consumerType`：METRIC / TASK / ASSET（前端复用 Dialog 可选,默认 METRIC）
- `consumerRef`：消费方标识（必填,空→`catalog.reuse_invalid`）
- 防环：仅 METRIC↔METRIC 参与 listing 空间成环校验;成环→`catalog.reuse_cycle`（前端专门提示）

## 公共：GateResult（写返回）

既有 TS `GateResult`：`{ outcome: EXECUTED|PENDING_APPROVAL|REJECTED, level?, message?, ... }`。
前端三态分流见 research.md D5/D6;`ApiResponse.errorCode` 承载业务错误码。
