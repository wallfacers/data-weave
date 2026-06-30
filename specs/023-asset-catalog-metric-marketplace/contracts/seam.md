# Contract: 023 跨模块/跨特性接缝

## 消费(入)——只读,不可达降级

| 接缝 | 现有服务/位置 | 023 用法 |
|---|---|---|
| neo4j 血缘 | `LineageQueryService.upstream/downstream(tenantId, projectId, tableId, depth, granularity)`、`metricLineage(tenantId, projectId, metricId)`(master/application);经 `Neo4jLineageGraphReader`(018) | `AssetLineageAssembler` 懒加载资产/指标血缘入口;捕获 `lineage.store_unavailable` → 降级隐藏入口(SC-002) |
| 指标定义 | `MetricService.findLatestByCode(code)` / `listLatest()`(master/application);`atomic_metrics`/`derived_metrics` | `metric_listing` 上架复用定义(不复制口径);详情展示 owner(`owner_id`)/version |
| 质量评分卡 | 份2 `quality_scorecard`(022 产出) | `AssetQualityBadgeAssembler` 取质量徽章;022 未落地/不可达 → 降级(桩 + 隐藏徽章) |
| 任务目录(并存) | `CatalogTreeService`(任务文件夹树,独立对象) | **不改**;仅前端导航整合,资产是另一维度 |
| 标签 | `tag`/`entity_tag`(`entity_type='ASSET'`) | 复用既有标签机制 |

**硬约束**:任一消费源不可达,目录主功能 100% 不受影响(SC-002);降级命中数经 `CatalogMetrics` 可观测。

## 产出(出)——ASSET_CHANGED 喂 021

- 021 已预留 `AlertSignal.Type.ASSET_CHANGED`(见 `specs/021-alert-engine/contracts/signal-seam.md`)。**`AlertSignal` 类在 `dataweave-master`**(021 拍板),023 在 master 内可直接 `applicationEventPublisher.publishEvent(new AlertSignal(ASSET_CHANGED, tenantId, datasetRef, ctx))`。
- **发射点**:`AssetCatalogService.updateAsset`(schema 对账不一致)、质量掉档检测(消费份2)、新鲜度违约检测 → publish;021 `AlertSignalListener` 据订阅者所配规则/通道分发。
- **change_filter**:订阅可选 schema/quality/freshness;变更类型与 filter 匹配才通知。退订后不通知。

## 写执行接缝(与 021 一致的 SPI)

- 021 在 master 的 `DefaultPlatformActionExecutor` 引入 `PlatformActionHandler` SPI(避免反向依赖)。023 在 master 内,可**直接加 case**(`ASSET_WRITE`/`METRIC_CERTIFY`/`ASSET_SUBSCRIBE`)或同样供 handler——**与 021 的 SPI 改造合并期对齐**,二选一保持 executor 单一风格(建议:023 也走 handler 风格,统一)。

## 闭环验证(合并期)

021→022→023 入 main 后,re-run:
1. 造 `ASSET_CHANGED` → 断言 021 据订阅通知(产出接缝)。
2. 血缘/质量源在线 → 资产详情含血缘入口 + 质量徽章;源下线 → 降级(消费接缝 + 降级)。
3. 指标上架/复用防环/认证 L2 审批闭环。
