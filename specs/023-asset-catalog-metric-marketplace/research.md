# Phase 0 Research: 资产目录 + 指标市场

技术决策与依据。NEEDS CLARIFICATION 已解。集成点经测绘确认(file:line)。

## D1. data_asset 与既有 data_table 关系:编目层引用,不复制

- **Decision**:`data_asset` 是 `data_table`(schema.sql:845-860,`(datasource_id, qualified_name)` UNIQUE)之上的**编目/治理层**:以 `(datasource_id, qualified_name)` 唯一去重,逻辑引用 `data_table`,叠加 owner/steward/描述/术语/标签/敏感度/schema 快照/status。**不复制**血缘边(`task_table_io`/`task_run_table_io`),血缘按需经 `LineageQueryService` 取。
- **Rationale**:避免双写/漂移;`data_table` 已是表的注册真相,资产层只加治理元数据。
- **Alternatives**:`data_asset` FK 硬绑 `data_table.id`——否决(data_table 可能由血缘自动产生、生命周期不同,软引用 `(datasource_id,qualified_name)` 更稳);把治理字段直接加到 `data_table`——否决(混淆血缘注册与编目治理两种职责)。

## D2. 血缘消费:只读经 LineageQueryService,不可达降级

- **Decision**:`AssetLineageAssembler` 调用 `LineageQueryService.upstream/downstream(tenantId, projectId, tableId, depth, granularity)`(file:152-160)、`metricLineage(...)`(file:103+)取血缘入口数据。资产/指标详情**懒加载**血缘(详情展开才查),neo4j 不可达(`lineage.store_unavailable`)时**捕获并降级**:隐藏血缘入口,目录主功能照常(SC-002)。
- **Rationale**:020 已建多粒度只读查询 + 有界;023 纯消费,零重造(原则 V)。
- **Alternatives**:把血缘冗余进 data_asset——否决(漂移 + 双写);强依赖 neo4j(不可达即报错)——否决(违降级要求)。

## D3. 指标市场:复用 MetricService 定义上架,复用引用防环

- **Decision**:`metric_listing` 通过 `metric_ref`(`metric_type=ATOMIC|DERIVED` + metric code/id)复用 `MetricService.findLatestByCode`/`listLatest`(file:31-46)的指标定义,**不复制口径**。详情展示定义 + 血缘(经 D2)+ owner(`atomic_metrics.owner_id`)+ 新鲜度 + 认证状态。**复用**建 `metric_reuse_ref`(listing_id → consumer_ref)引用关系,**防环**:复用前做有向图可达性检查(A 复用 B 时若 B 已(间接)复用 A 则拒,错误码 `catalog.reuse_cycle`)。
- **Rationale**:指标定义不可变 + version 范式(metrics 已有),市场是其发现/复用包装层,不碰 metrics 写语义。
- **Alternatives**:市场复制指标定义——否决(口径分叉,正是要防的);不做防环——否决(spec FR-005 硬要求)。

## D4. 分面搜索:LIKE + facet(双方言,有界),PG 全文列未来优化

- **Decision**:`AssetSearchService` 用 `name/description/glossary LIKE CONCAT('%', ?, '%')`(双库兼容,镜像 `OpsService`/`TaskService` 现有用法 file:132-134)+ 分面(类型/owner/标签/敏感度/质量分/新鲜度)拼 `WHERE` 叠加 + 分面计数(GROUP BY)。**有界**:`MAX_RESULTS` 上界(参考 020 `MAX_NODES=2000`)+ 分页(1-based,见 [[unified-table-flow-B-learnings]]);超界 `log` 截断不静默。排序:质量分/相关度(LIKE 命中位置简单加权)。
- **Rationale**:双方言是硬约束(H2/PG 都要过),LIKE 零依赖即可满足 v1 分面发现;PG `tsvector`+GIN 全文检索 H2 无对应,留未来优化(spec Assumptions 已声明选型留 plan)。
- **Alternatives**:PG 全文检索——否决 v1(破 H2 双方言);引 Elasticsearch——否决(外部依赖,超 v1 范围)。

## D5. 订阅 + 变更检测 → ASSET_CHANGED 喂 021

- **Decision**:`asset_subscription`(subscriber/target_type ASSET|METRIC/target_id/change_filter schema|quality|freshness)。变更检测:① schema 变(资产 schema_snapshot 与底层表对账不一致)② 质量掉档(份2 评分跨阈值)③ 新鲜度违约——检测点 `AssetSubscriptionService` / `AssetCatalogService.updateAsset` 处 `applicationEventPublisher.publishEvent(new AlertSignal(ASSET_CHANGED, tenantId, datasetRef, ...))`,021 `AlertSignalListener` 消费分发给订阅者通道。退订后不再通知。
- **Rationale**:复用 021 信号接缝(signal-seam.md 已预留 `ASSET_CHANGED`),不另造通知栈;master 经框架 `ApplicationEventPublisher` 不反依赖 alert。
- **接缝桩**:021/022 未落地时,`ASSET_CHANGED` publish 仍可发(021 落地即有消费方);质量掉档检测依赖份2,未落地时该 change_filter 分支用桩 + 降级,023 仍独立可测。
- **Alternatives**:023 自建订阅通知分发——否决(重复 021,违原则 V)。

## D6. 写闸门(资产/上架/认证/订阅写)

- **Decision**:agent 发起的写经 `ActionRequest(actionType=ASSET_WRITE|METRIC_CERTIFY|ASSET_SUBSCRIBE, targetType=ASSET|METRIC_LISTING, ...) → GatedActionService.submit(req, locale) → PolicyEngine`,`DefaultPlatformActionExecutor` 加对应 case(镜像 `PROJECT_PUSH` case file:94)。`policy_rules` seed:`ASSET_WRITE`=L1、`METRIC_CERTIFY`=L2(认证是可信背书,需审批)、`ASSET_SUBSCRIBE`=L1。`GateResult.Outcome` 三分流。UI admin CRUD 走普通鉴权 API + `agent_action` 审计。
- **Rationale**:原则 V 写闸门零旁路;认证赋予"可信"语义,影响下游消费决策,L2 审批。
- **Alternatives**:编目写绕闸门——否决(agent 也能写)。

## D7. 模块归属 + 隔离级别

- **Decision**:落 `dataweave-master`(与 lineage/metrics/catalog 内聚)。隔离级别对齐被引用对象:`data_asset`/`metric_listing`/`metric_reuse_ref` 为**项目级**(`tenant_id`+`project_id`,对齐 `data_table`/`atomic_metrics`);`asset_subscription` 为**租户级 + subscriber 用户**(订阅是用户行为,跨项目可订)。可见性:`tenant_id`/`project_id` 隔离 + 敏感度分级权限(未授权不可见、不可搜出,SC-006)。
- **Rationale**:资产/指标关联项目级对象,隔离级别须一致避免跨项目泄露。

## D8. 前端视图

- **Decision**:把 Workspace 既有 `catalog` 占位视图(`registry.tsx:78-80`,`VIEW_RENDER.catalog`)替换为真实 `AssetCatalogView`(左分面搜索 + 中列表 + 右详情面板:元数据/血缘入口/质量徽章/订阅);新增 `marketplace` 视图类型(`views.ts` `ViewType` + `VIEW_META` + `registry.tsx` 各加一行)→ `MetricMarketplaceView`。以 `lineage-view.tsx`(左树 + 右画布 + 异步加载 + `useTranslations`)为复杂视图模板。**确认** `catalog` 占位当前归属(若已被任务目录占用则改用新 `assets` 类型)——实现首步先核对,避免撞名。i18n `catalog`/`marketplace` 命名空间双 bundle 等集。
- **Rationale**:复用既有视图框架 + 注册范式;`lineage-view` 是最接近的复杂视图先例。
- **风险**:`catalog` 视图类型语义归属需实现首步核对(任务目录 vs 数据资产),不确定即新建 `assets` 类型,不抢占。

## D9. schema_version 升版(并行协调)

- **Decision**:4 新表 → MINOR 升。因 021/022/023 并行,**最终版本号在合并入 main 时按落地顺序定**(建议 021=0.1.0 / 022=0.2.0 / 023=0.3.0)。data-model 用占位 `0.3.0`,合并期对齐三处恒等。
- **Rationale**:CLAUDE.md schema 版本治理(017);并行特性各自升版会冲突,合并期统一裁定。

## D10. 可观测 CatalogMetrics

- **Decision**:`CatalogMetrics`(Micrometer,镜像 `SchedulerMetrics`):资产/指标计数、搜索 QPS/延迟、订阅数、血缘/质量降级命中数。经 `/actuator/prometheus` + `/api/ops/metrics`。
- **Rationale**:FR-011。降级命中数可观测 → 及时发现血缘/质量源不可达。
