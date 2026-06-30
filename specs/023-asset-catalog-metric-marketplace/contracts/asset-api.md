# Contract: /api/catalog/* + /api/marketplace/* REST 端点

约定:WebFlux;`200 + {code, data, message}`;`Authorization: Bearer`;`TenantContext` 隔离(缺身份 `catalog.tenant_required`);敏感度可见性过滤(未授权资产不返回、不计入分面)。

## 资产目录 /api/catalog

| 方法 | 路径 | 说明 | 写闸门 |
|---|---|---|---|
| GET | `/api/catalog/assets` | **分面搜索**:`?keyword=&type=&owner=&tag=&sensitivity=&qualityMin=&page=&size=` 关键词命中 name/description/glossary,分面叠加,有界分页 + 分面计数 | — |
| GET | `/api/catalog/assets/{id}` | 资产详情(元数据;血缘入口/质量徽章**懒加载**,见下) | — |
| POST | `/api/catalog/assets` | 编目资产(去重) | agent:`ASSET_WRITE`(L1) |
| PATCH | `/api/catalog/assets/{id}` | 改资产(PATCH null=清空/缺字段=不改) | `ASSET_WRITE` |
| DELETE | `/api/catalog/assets/{id}` | 下线(RETIRED) | `ASSET_WRITE` |
| GET | `/api/catalog/assets/{id}/lineage` | 资产血缘(代理 `LineageQueryService.upstream/downstream`);neo4j 不可达 → `code=catalog.lineage_degraded` + data=null,前端降级隐藏入口 | — |
| GET | `/api/catalog/assets/{id}/quality` | 质量徽章(消费份2 `quality_scorecard`);不可达 → 降级 | — |
| GET/POST/DELETE | `/api/catalog/subscriptions[/{id}]` | 订阅/退订 | `ASSET_SUBSCRIBE`(L1) |

## 指标市场 /api/marketplace

| 方法 | 路径 | 说明 | 写闸门 |
|---|---|---|---|
| GET | `/api/marketplace/metrics` | 分面搜索上架指标(同分面机制) | — |
| GET | `/api/marketplace/metrics/{id}` | 指标详情:定义(复用 `MetricService`)+ 血缘(`metricLineage`,降级)+ owner + 新鲜度 + 认证 | — |
| POST | `/api/marketplace/metrics` | 上架(复用现有 metric 定义) | `ASSET_WRITE` |
| POST | `/api/marketplace/metrics/{id}/certify` | 认证(可信徽章) | `METRIC_CERTIFY`(L2 → 可能 PENDING_APPROVAL) |
| DELETE | `/api/marketplace/metrics/{id}` | 下架(DELISTED) | `ASSET_WRITE` |
| POST | `/api/marketplace/metrics/{id}/reuse` | 复用(建引用,**防环**) | `ASSET_WRITE` |

`certify` / 写返回须含 `outcome`(EXECUTED/PENDING_APPROVAL/REJECTED),前端按 outcome 分流(不能只看 code===0)。

## 分面搜索响应结构

```
{ items: [...有界分页...], total, facets: { owner: {..count}, tag: {..count}, sensitivity: {..count} }, truncated: bool }
```
`truncated=true` 时后端已 `log` 截断(超 MAX 上界)。

## 错误码(catalog.<semantic>,稳定不复用)

`catalog.tenant_required`、`catalog.asset_not_found`、`catalog.duplicate_asset`(违唯一约束)、`catalog.metric_listing_not_found`、`catalog.reuse_cycle`(复用成环)、`catalog.lineage_degraded`(血缘源不可达,降级信号非硬错)、`catalog.quality_degraded`、`catalog.not_certifiable`、`catalog.forbidden_sensitivity`(敏感度无权)。

> 注:`*_degraded` 是**降级提示**(data 可空、前端友好处理),不是阻断错误——区别于 `*_not_found` 等硬错。
