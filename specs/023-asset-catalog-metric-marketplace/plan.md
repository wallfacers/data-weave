# Implementation Plan: 资产目录 + 指标市场 —— 编目、搜索、订阅与复用

**Branch**: `023-asset-catalog-metric-marketplace` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/023-asset-catalog-metric-marketplace/spec.md`

## Summary

元数据发现底座:把**数据资产(表/数据集)**与**指标**变成可**编目、分面搜索、订阅、复用**的治理对象。资产/指标详情**消费**(不重造)018-020 neo4j 血缘(`LineageQueryService`)、份2 质量评分卡、现有 metrics 体系(`MetricService`);资产/指标变更经 `ASSET_CHANGED` 信号**喂份1 告警**(复用 021 `AlertSignal` 接缝);写操作过 `PolicyEngine` 闸门;全表 `tenant_id`/`project_id` 隔离 + 敏感度可见性;新增 4 表升 `schema_version`(MINOR,合并期与 021/022 对齐版本号)。**最难一份**(面最广、与血缘/目录纠缠最深),由仓主(Claude)亲自实现。

**技术路径**:落在 `dataweave-master`(与 lineage/metrics/catalog 同模块)。`data_asset` 以 `(datasource_id, qualified_name)` 编目层引用既有 `data_table`(不复制血缘表)。搜索用 LIKE + 分面(双方言兼容,有界分页;PG 全文检索列为未来优化)。血缘消费走 `LineageQueryService` 只读;指标复用 `MetricService` 定义上架;变更检测点 publish `ASSET_CHANGED`(Spring `ApplicationEvent`,master 不反依赖 alert)。前端把 Workspace `catalog` 占位视图替换为真实资产目录视图 + 新增指标市场视图,以 `lineage-view` 为复杂视图模板。

## Technical Context

**Language/Version**: Java 25(mvnd 或 export JDK25)

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7、WebFlux、Spring Data JDBC/JPA + JdbcTemplate、Jackson 3、neo4j-java-driver(经现有 `Neo4jConfig` @Bean,只读)、Micrometer

**Storage**: PostgreSQL(默认)/ H2(`profiles=h2`,DDL 双方言);neo4j(血缘只读,经 `Neo4jLineageGraphReader`,018 提供);本特性**不写** neo4j

**Testing**: JUnit 5 + AssertJ;WebTestClient(带 JWT,`JwtTestSupport`);血缘消费用集成测试(neo4j 不可达 → 优雅降级路径必测);H2 净库 `@TestPropertySource` 独立库名

**Target Platform**: Linux server(WSL2,长跑 `setsid`)

**Project Type**: web(后端 `dataweave-master` 填充 + 前端两视图)

**Performance Goals**: 分面搜索有界(MAX 上界 + 分页,参考 020 `MAX_NODES=2000`);资产详情聚合(元数据 + 血缘入口 + 质量徽章)p95 < 1s;血缘/质量源不可达时降级不阻塞

**Constraints**: 全表 `tenant_id`+`project_id` 隔离 + 敏感度可见性;消费方不可达优雅降级;`data_asset` 以 `(datasource_id, qualified_name)` 唯一去重;复用引用防环;写过闸门;`schema_version` 三处恒等;依赖方向守恒

**Scale/Scope**: 4 新表 + `dataweave-master` 资产/市场 service/domain/infra/interfaces + `/api/catalog/*` + `/api/marketplace/*` + 2 前端视图

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**:资产/指标编目是**治理元数据**(类 metrics/lineage),非 task/workflow 定义,不进 pull/push 文件契约——不冲突原则 I。✅
- **II. Server is Source of Truth**:全服务端治理,租户/项目隔离 + 敏感度,无双向同步。✅
- **III. Two-Legged Debugging**:不涉 CLI 本地 runtime。✅ 不适用。
- **IV. AI Lives in Local Agent**:不嵌服务端 AI 脑;编目/搜索/订阅规则驱动;agent 写经写闸门。✅
- **V. Reuse the Kernel**:**消费**而非重造——血缘走 `LineageQueryService`、指标走 `MetricService`、质量走份2 评分卡、变更通知走 021 `AlertSignal`、写闸门走 `GatedActionService`;不重写任何内核。✅

**结论**:无违规。设计后复检见末尾。

## Project Structure

### Documentation (this feature)

```text
specs/023-asset-catalog-metric-marketplace/
├── plan.md research.md data-model.md quickstart.md
├── contracts/
│   ├── asset-api.md       # /api/catalog/* + /api/marketplace/* 端点契约
│   └── seam.md            # 消费(血缘/质量/指标)+ 产出(ASSET_CHANGED→021)接缝
└── tasks.md               # /speckit-tasks 生成
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── domain/asset/
│   ├── DataAsset.java MetricListing.java MetricReuseRef.java AssetSubscription.java
│   ├── Sensitivity.java (PUBLIC/INTERNAL/CONFIDENTIAL/PII) AssetStatus.java (ACTIVE/STALE/RETIRED)
│   └── repository/ (Asset*Repository 接口)
├── application/asset/
│   ├── AssetCatalogService.java     # 编目 CRUD + 装配(元数据 + 血缘入口 + 质量徽章聚合)
│   ├── MetricListingService.java    # 上架/认证/复用(防环)
│   ├── AssetSearchService.java      # 分面搜索(LIKE + facet,有界分页)
│   ├── AssetSubscriptionService.java# 订阅/退订 + 变更检测 publish ASSET_CHANGED
│   ├── AssetLineageAssembler.java   # 消费 LineageQueryService(只读,降级)
│   ├── AssetQualityBadgeAssembler.java # 消费份2 quality_scorecard(降级)
│   └── CatalogMetrics.java          # Micrometer
├── infrastructure/asset/
│   ├── jdbc/ (Asset*RepositoryImpl)
│   └── AssetActionExecutor 接入(ASSET_WRITE/METRIC_CERTIFY/ASSET_SUBSCRIBE case)
└── (interfaces 在 dataweave-api)

backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/
├── AssetCatalogController.java      # /api/catalog/*
└── MetricMarketplaceController.java # /api/marketplace/*

backend/dataweave-api/src/main/resources/
├── schema.sql   # [改] +4 表,升 schema_version(MINOR,合并期对齐)
└── data.sql     # [改] +policy_rules seed(ASSET_WRITE/METRIC_CERTIFY/ASSET_SUBSCRIBE)

frontend/
├── components/workspace/views/asset-catalog-view.tsx     # [新] 替换 catalog 占位
├── components/workspace/views/metric-marketplace-view.tsx# [新] 指标市场
├── lib/workspace/registry.tsx + views.ts                 # [改] 接真实组件 + 新增 marketplace 视图类型
└── messages/{zh-CN,en-US}.json                            # [改] catalog/marketplace 命名空间(双语等集)
```

**Structure Decision**:落 `dataweave-master`(与 lineage/metrics/catalog 内聚),DDD 四层守方向。`data_asset` 是 `data_table` 之上的**编目/治理层**:引用 `(datasource_id, qualified_name)`,不复制血缘。血缘/质量/指标全部**只读消费现有服务**。变更通知用 Spring `ApplicationEvent` publish `ASSET_CHANGED`(master 不反依赖 alert)。前端复用既有 `catalog` 占位视图 + 新增 `marketplace` 视图。

## Complexity Tracking

> 无 Constitution 违规,本节空。

## 跨特性接缝(Cross-Feature)

- **消费(入)**:018-020 neo4j 血缘(`LineageQueryService`)、份2 `quality_scorecard`、现有 `MetricService` 指标定义。**任一源不可达 → 优雅降级**(隐藏徽章/血缘入口,目录主功能不受影响),这是硬验收(SC-002)。
- **产出(出)**:`ASSET_CHANGED` 信号 publish 给 021(021 已预留 `AlertSignal.Type.ASSET_CHANGED`)。
- **依赖与定序**:023 依赖 021(信号接缝)+ 022(质量评分卡)+ 018-020(血缘,已 main)。并行实现时各开 worktree;**合并入 main 顺序建议 021→022→023**,023 合并时 re-run 全部消费/产出接缝集成测试,确认 seam 闭合。质量徽章/ASSET_CHANGED 在 022/021 未落地前用接口桩 + 降级路径,保证 023 独立可编译可测(不闭环即未完成)。

## Post-Design Constitution Re-Check

设计后复检:4 表全 `tenant_id`/`project_id`;血缘/质量/指标全只读消费(零重造);`ASSET_CHANGED` 用 ApplicationEvent(不反依赖 alert);写闸门零旁路;搜索有界;敏感度可见性隔离。**仍全部通过。**
