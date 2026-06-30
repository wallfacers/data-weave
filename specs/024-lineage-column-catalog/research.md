# Research: 声明驱动的列血缘 Catalog

**Feature**: 024-lineage-column-catalog | **Phase**: 0 | **Date**: 2026-06-30

针对 spec Assumptions「plan 期需落实的风险点」+ Technical Context 的代码现状调研。每条：Decision / Rationale / Alternatives。证据均为 `file_path:line`。

## R1. `.task.yaml` 声明解析入口与 formatVersion

**Decision**: 在 `TaskMapper.fromYaml`（`filecontract/mapping/TaskMapper.java:135`，SnakeYAML 经 `DeterministicYaml`）扩展解析两块可选 key（`schema`/`columnLineage`），沿用现有 `optionalMap`/`optionalStringList` 范式。**不 bump formatVersion**（保持 `formatVersion: 1`）。

**Rationale**: 全仓确认 `formatVersion` 只被 `intFrom`（`TaskMapper.java:137`）读成整数，**无任何版本分支/校验逻辑消费其值**；序列化侧恒写 `TaskDoc.CURRENT_FORMAT_VERSION=1`。新增可选键按前向兼容处理，老 yaml（无声明）继续正常解析，与 `datasource`/`targetDatasource` 缺失即 null 一致。

**Alternatives**: ① bump `formatVersion=2` 做声明语法门禁——否决，无版本消费机制，bump 无意义且增迁移面。② 用 Jackson 反序列化声明 DTO——否决，现有 yaml 解析全程 SnakeYAML 手动取键，引入 Jackson 反序列化破坏一致性。

## R2. 声明挂载方式（TaskDef 字段 vs 透传 map）

**Decision**: 仿 `datasource`/`targetDatasource` 的**解析期透传 map** 模式——`TaskMapper.fromYaml` 解析声明进 `TaskDoc` 新字段 → `ProjectMapper.deserialize`（`ProjectMapper.java:236`）挂到 `ProjectImport.Builder` 两个透传 map（taskId→declaredSchema / taskId→declaredColumnEdges）→ `ProjectSyncService.push` 落库时按 taskId 取出喂 `extractAndCrossCheck`/`recordTaskIo`。**TaskDef / `task_def` 表零改动。**

**Rationale**: 现有 `datasource`/`targetDatasource`（yaml 逻辑名 code）正是此路径——不进 TaskDef 的 Long id 字段，而是 `ProjectMapper` 挂 `taskDatasourceCode` map、push 时解析成 id（`ProjectSyncService.java:790-793`）。声明沿用此模式，零动表、零快照改动，与 FileContract 体系一致。

**限制（spec FR-003 已纳入）**: `createAndOnline`（`TaskService.java:407`，MCP 参数路径）**不经 FileContract**，拿不到 yaml 声明 → 声明血缘只在 **push（项目同步）路径**生效。与 Files-First（Constitution I）一致（声明是文件元数据），且 spec US1 本就是 push 框架。

**Alternatives**: ① 进 `params_json`——否决，污染用户 params 命名空间，且 SPARK 已占 `_sparkMode` 等下划线前缀。② 加 TaskDef 字段 + 改 `task_def` 表 + 改 `TaskDefVersion` 快照 + 改 row mapper——否决，改动最大、收益不抵成本。

## R3. catalog 租户隔离机制（lookupTable 签名）

**Decision**: **改 `ColumnLineageCatalog.lookupTable` 签名**加 `(long tenantId, long projectId)`：`lookupTable(long tenantId, long projectId, String qualifiedName)`。调用点（`TaskService:485`、`ProjectSyncService:858`）已有这俩值（push 真实 tenant；createAndOnline 暂为 MCP 固定 1L）。`Neo4jColumnLineageCatalog` Cypher 按 `tenantId+projectId+qualifiedName` 查；`EmptyColumnLineageCatalog` 与 `CalciteColumnLineage.analyze` 同步透传。

**Rationale**: master 模块 **pom 不依赖 dataweave-api**（方向 api→master），`TenantContext` 在 api 模块——master 直接 `TenantContext.current()` 会**循环依赖**；且 `application.yml` 是 reactive（WebFlux），`recordTaskIo`/`extract` 同步调用在 reactive 链切线程后 ThreadLocal 易丢。改签名是显式、无副作用、无跨模块的最干净方案。

**Alternatives**: ① `TenantContext.current()` ThreadLocal——否决（循环依赖 + reactive 线程风险）。② qualifiedName 编码 `tenantId|projectId|table`——否决，污染逻辑表名语义（`TableRef` 本是纯逻辑名）。

## R4. recordTaskIo 扩展与 ensureColumn

**Decision**: `recordTaskIo` 加一个声明 schema 入参（如 `List<TableSchema> declaredSchemas`）；事务内（`session.executeWrite` 块）**先**遍历声明 schema → `ensureTable`+`ensureColumn(tx, tableKey, colName, dataType, ordinal, tenantId, projectId)`（**参数已就绪**，当前调用点 `Neo4jLineageStore.java:110-111` 传 `null,null` 待改）seed `:Column`，**再**写 edges 落 `:DERIVES_FROM{confidence}`。declared edges 组装成 `ColumnEdge`（confidence=DECLARED）并入 `columnEdges` 入参；cross-check 对账激活 `extractAndCrossCheck`（`SqlColumnLineageExtractor.java:67`）→ `ColumnLineageCrossCheck.crossValidate`。

**生产调用点仅 2 处**（改造成本可控）：`TaskService.recordLineage`（`TaskService.java:494`，tenant 硬编码 1L）+ `ProjectSyncService.push` 5c-lineage（`ProjectSyncService.java:866`，真实 tenant）。启动 seed（`Neo4jLineageSeeder`）是第 3 处但非业务路径。**`publish()` 不调 recordTaskIo**（只写版本快照 + 改 status）。

**Alternatives**: ① 不扩 recordTaskIo，另开 `seedColumnSchema(...)` 在 extract 前独立调——可行但多一调用点；优先复用 recordTaskIo 单事务（schema seed + edge 落库原子化，且 FR-009 排序要求 seed 早于 extract，需在调用点把 seed 段前置）。② 重写 ensureColumn——不必要，参数已就绪。

## R5. catalog bean 装配（profile / H2 不崩）

**Decision**: 用 **`@ConditionalOnProperty(name="lineage.column-catalog.type", havingValue="neo4j"|"empty", matchIfMissing=true)`**：`Neo4jColumnLineageCatalog` `havingValue="neo4j"`；`EmptyColumnLineageCatalog` `havingValue="empty", matchIfMissing=true`。H2/默认走 Empty（零风险），neo4j 环境显式 `lineage.column-catalog.type=neo4j`。**不用裸 `@Primary`**。`Neo4jColumnLineageCatalog.lookupTable` 内部仍 try-catch 吞 neo4j 异常返回 `Optional.empty()`（守"绝不抛"契约）。

**Rationale**: 全仓**零 `@Profile`**，conditional 统一用 `@ConditionalOnProperty`（`eventbus.type`/`logbus.type`/`scheduler.mode` 既有范式）。裸 `@Primary` 会让 Empty 与 Neo4j 两 Bean 在 H2 下同时注册、且 H2 查询必失败（neo4j 不可达）——虽 lookupTable try-catch 能降级，但 conditional 让 H2 默认根本不装 Neo4j 版，更干净、与既有范式一致。

**Alternatives**: ① 裸 `@Primary` + lookupTable try-catch 降级——可行但不如 conditional 干净，且 H2 全应用 `@SpringBootTest` 会注入 Neo4j 版走降级路径（需额外测）。② `@Profile("neo4j")`——否决，仓库无 `@Profile` 范式，neo4j bean 现状靠运行时降级而非装配期排除。

## 落地决策摘要

| 项 | 决策 |
|---|---|
| formatVersion | 不 bump，保持 1（无版本消费逻辑） |
| 声明挂载 | 解析期透传 map（仿 datasource），TaskDef/表零改动；仅 push 路径生效 |
| tenant 隔离 | 改 `lookupTable` 签名加 `(tenantId, projectId)`；不用 TenantContext |
| recordTaskIo 扩展 | 加 declaredSchemas 入参；事务内先 ensureColumn(真实 type/ordinal) 再落边；2 个生产调用点 |
| catalog bean | `@ConditionalOnProperty(lineage.column-catalog.type)`；不用 @Primary；H2 默认 Empty |

## Phase 0 → 1 衔接

research 无残留 NEEDS CLARIFICATION，Technical Context 全绿。Phase 1 据 R1–R5 出 data-model.md（实体 + `lookupTable` 签名变更 + Confidence 加 DECLARED）、contracts/（`.task.yaml` 声明契约 + catalog 签名契约）、quickstart.md（testcontainers 验证场景）。
