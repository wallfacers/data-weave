# API Contracts: 资产目录

**Created**: 2026-07-05 | **Feature**: 043-asset-catalog-polish

本特性为纯前端改造，**不新增 API 端点**。以下记录本特性消费的既有 REST 端点（已在 `lib/catalog-api.ts` 中封装）。

所有端点前缀：`/api/catalog`

## 读取端点（本特性使用）

### GET /api/catalog/assets

搜索资产列表，支持分面聚合与分页。

**Query Parameters**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `keyword` | `string` | ❌ | 搜索关键词（名称/限定名/描述） |
| `sensitivity` | `string` | ❌ | 敏感度过滤（逗号分隔多值） |
| `owner` | `string` | ❌ | 负责人过滤 |
| `tag` | `string` | ❌ | 标签过滤 |
| `qualityMin` | `number` | ❌ | 质量分下限（后端 v1 no-op，被动透传） |
| `page` | `number` | ❌ | 页码（默认 1） |
| `size` | `number` | ❌ | 页大小（默认 20，HARD_CAP 100） |

**Response** (`ApiResponse<SearchResult>`):
```json
{
  "code": 0,
  "data": {
    "items": [{ "id": 1, "qualifiedName": "ads_gmv", "name": "GMV 汇总表", "sensitivity": "CONFIDENTIAL", "status": "ACTIVE", "ownerId": "1", "stewardId": "2", "tags": ["财务", "GMV"], "lastUpdatedAt": "2026-06-10T00:00:00Z", "datasourceId": 1 }],
    "total": 4,
    "truncated": false,
    "facets": {
      "sensitivity": { "PUBLIC": 0, "INTERNAL": 2, "CONFIDENTIAL": 1, "PII": 1 },
      "owner": { "#1": 4 },
      "tag": {},
      "status": { "ACTIVE": 3, "STALE": 1 }
    }
  }
}
```

### GET /api/catalog/assets/{id}

获取单个资产详情。

**Response** (`ApiResponse<AssetDetail>`): 返回完整资产信息（含 description、lineageTableRef 等扩展字段）。

### GET /api/catalog/assets/{id}/lineage

获取资产血缘信息。（懒加载，降级安全）

### GET /api/catalog/assets/{id}/quality

获取资产质量徽章。（懒加载，降级安全）

### GET /api/catalog/subscriptions

获取当前用户所有订阅。

### POST /api/catalog/subscriptions

订阅资产。`body: { assetId: number }`

### DELETE /api/catalog/subscriptions/{subId}

退订资产。

## 写操作端点（本特性使用，经闸门）

所有写操作经 `resolveGate` + `gateToast` 三态分流。详见 [data-model.md](../data-model.md) 和 `lib/gate-outcome.ts`。

### POST /api/catalog/assets

创建资产。`body: AssetCreatePayload`

### PATCH /api/catalog/assets/{id}

编辑资产。`body: Partial<AssetCreatePayload>`（PATCH-diff，仅含变更字段）

### POST /api/catalog/assets/{id}/retire

下线资产。

### POST /api/catalog/assets/{id}/reconcile

对账资产（按血缘锚点校验状态）。

## 零新增端点

本特性不新增或修改任何 API 端点、契约、或请求/响应格式。
