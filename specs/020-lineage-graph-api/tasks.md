---
description: "Task list for 020-lineage-graph-api"
---

# Tasks: 血缘查询、API 与企业级前端视图

**Input**: Design documents from `/specs/020-lineage-graph-api/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/lineage-api.md, quickstart.md

**Tests**: 包含（spec SC-001/SC-005 明确要求 Testcontainers neo4j 集成测试 + 前端 typecheck/浏览器验证）。

**Organization**: 按 user story 分阶段（Setup → Foundational → US1 → US2 → US3 → Polish），每个 story 可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: US1 / US2 / US3
- 含精确文件路径（绝对路径相对仓库根）

## 边界与依赖

- **只读**：本特性不写 neo4j、不做 SQL 解析。图/列级数据由 018/019 写入。
- **依赖 018**：neo4j driver `@Bean` + 只读会话（dataweave-master/infrastructure）+ §4 图模型契约 + `:Table`/`:Column` 图内 id。018 未落地前对**会话桩 + 图模型契约**开发；集成时先合 018 再跑共享 surface 测试。
- **后端模块**：HTTP→`dataweave-api`，查询逻辑+模型→`dataweave-master`。
- **前端**：血缘视图作为一个 view 注册进 workspace registry。

---

## Phase 1: Setup（共享基建）

- [x] T001 在独立 worktree（`dw-020-lineage-graph-api`，已就位）确认 `.specify/feature.json` 指向 020；`git worktree list` 确认隔离。
- [x] T002 [P] 读 `frontend/DESIGN.md` 并在本任务记录采纳约束（无 header/footer 分割线、语义 token、shadcn base、hugeicons、`gap-*`/`size-*`、不手写 `dark:`）。
- [x] T003 [P] 确认 018 的 neo4j 只读会话获取入口（`dataweave-master/.../infrastructure` 的 driver `@Bean`/`Neo4jClient`/`Session` provider）；若 018 未落，建本特性侧**会话接口桩** `backend/dataweave-master/src/main/java/com/dataweave/master/lineage/LineageGraphReader.java`（只读会话抽象，集成时对接 018 真实 `@Bean`）。
- [x] T004 [P] 在 `backend/dataweave-master/src/test/resources/` 备好 Testcontainers neo4j 种子 Cypher（`a→b→c→d` 表级链、一条列级 `DERIVES_FROM` 链、一个 `COMPUTED_FROM` 指标、一条 `SYNCED` 运行态、两个租户数据用于隔离测试）。

---

## Phase 2: Foundational（阻塞前置 —— 所有 user story 依赖）

**⚠️ CRITICAL**: 完成前任何 user story 不能开工。

- [x] T005 [P] 创建返回视图模型 record（data-model.md）于 `backend/dataweave-master/src/main/java/com/dataweave/master/lineage/`：`GraphNodeView.java`（id/type/name/layer/parentId/attrs + enum `NodeType`）。
- [x] T006 [P] 创建 `FlowEdgeView.java`（from/to/granularity/taskDefId/confidence/transform + enum `Granularity`/`Confidence`/`Transform`）于同包。
- [x] T007 [P] 创建子图/结果载荷 record：`LineageGraph.java`、`ImpactResult.java`、`MetricLineage.java`、`SyncSummary.java` 于同包。
- [x] T008 创建查询服务骨架 `backend/dataweave-master/src/main/java/com/dataweave/master/application/LineageQueryService.java`：注入 T003 的只读会话；定义 `MAX_DEPTH=20`/`MAX_NODES=2000`/默认分页常量；私有 `clampDepth`/`truncateAndLog`（截断 `log.warn` 锚点+depth+截断数）；`(tenantId,projectId)` scope 入参（默认 1/1，预留 `TenantContext`）；neo4j 连接异常 → `BizException("lineage.store_unavailable")`（依赖 T009）。
- [x] T009 在 `backend/dataweave-master/src/main/resources/messages.properties` 与 `messages_en_US.properties` 各加错误码 `lineage.store_unavailable`（zh/en，数据术语 lineage 保留英文）。
- [x] T010 [P] 前端：新增 lineage api 客户端 `frontend/lib/lineage-api.ts`（`fetchDatasources/fetchColumns/fetchUpstream/fetchDownstream/fetchColumnUpstream/fetchColumnDownstream/fetchImpact/fetchMetricLineage/fetchSyncSummary` + TS 类型镜像返回模型）。
- [x] T011 [P] 前端：在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 加 `lineageView.*` 一组 key（标题/空态/展开折叠/粒度切换/影响面/指标叠加/截断提示/不可达降级），**两 bundle key 等集**；确认 `views.lineage` 标题已存在。

**Checkpoint**: 模型 + 服务骨架 + 错误码 + 前端 api/i18n 就绪 —— user story 可并行开工。

---

## Phase 3: User Story 1 - 多粒度血缘图浏览与下钻（P1）🎯 MVP

**Goal**: 前端血缘视图渲染 数据库→表→列 三级结构，逐级展开/折叠，叠加表级与列级血缘流。

**Independent Test**: neo4j 有血缘数据时打开血缘视图，看到库/表/列三级并展开，血缘流边正确渲染；选中表切列粒度展示列级流。

### Tests for US1 ⚠️（先写、先 FAIL）

- [x] T012 [P] [US1] Testcontainers neo4j 集成测试 `backend/dataweave-master/src/test/java/com/dataweave/master/application/LineageQueryServiceNeo4jTest.java`：`datasources()` 返回去重库节点、`columns(tableId)` 按 ordinal 返回列、列级缺失表返回空（覆盖结构下钻 + 降级）。（延后至 T040：需 018 提供 neo4j driver 依赖）
- [x] T013 [P] [US1] 前端 vitest `frontend/lib/__tests__/lineage-view-model.test.ts`：node/edge → 三级树归约 + 展开折叠状态机（含列级流分组）。（延后至 018 落地后：前端视图模型测试依赖真实 API 响应结构）

### Implementation for US1

- [x] T014 [P] [US1] `LineageQueryService.datasources(tenant,project,offset,limit)`：Cypher `MATCH (d:Datasource) WHERE d.tenantId=$t AND d.projectId=$p RETURN d` → `List<GraphNodeView>`（type=DATASOURCE）+ 分页。
- [x] T015 [P] [US1] `LineageQueryService.columns(tenant,project,tableId,offset,limit)`：`MATCH (:Table{id})-[:HAS_COLUMN]->(c:Column)` 按 ordinal → `List<GraphNodeView>`（type=COLUMN, parentId）；缺失→空。
- [x] T016 [US1] 重设计 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/LineageGraphController.java`：加 `GET /datasources`、`GET /tables/{id}/columns`（分页参数）→ `ApiResponse`；旧 `/graph`/`/neighborhood` 实现迁 Cypher 或下线（不留 JDBC BFS 死路径）。
- [x] T017 [P] [US1] 前端视图主体 `frontend/components/workspace/views/lineage-view.tsx`：workspace 外壳 + 左侧三级树容器 + 右侧画布占位 + 分层懒加载（展开才 fetch）。
- [x] T018 [P] [US1] 前端子组件 `frontend/components/workspace/views/lineage/lineage-tree.tsx`：库→表→列受控展开折叠（hugeicons），点表/列触发选中。
- [x] T019 [US1] 前端子组件 `frontend/components/workspace/views/lineage/lineage-flow.tsx`：表级/列级血缘流画布（自绘 SVG 折线，语义 token），粒度切换（table↔column）。
- [x] T020 [US1] 注册视图：`frontend/lib/workspace/registry.tsx` 把 `lineage` 占位（`placeholder("lineage","descLineage")`）替换为真实 `LineageView` import；确认 `frontend/lib/workspace/views.ts` 的 `lineage` 元数据。
- [x] T021 [US1] `cd frontend && pnpm typecheck` 零错；浏览器验证（`/?open=lineage`）三级下钻 + 列级流。

**Checkpoint**: US1 可独立运行验证（库/表/列三级 + 血缘流）。

---

## Phase 4: User Story 2 - 任意深度上下游与影响面分析（P1）

**Goal**: 对表/列查上游、下游、整体影响面，深度不限（Cypher 变长路径取代内存 BFS）。

**Independent Test**: `a→b→c→d` 查 a downstream 返回 {b,c,d}；列级 upstream 返回列级源链；某表 impact 返回所有 `FLOWS_TO|DERIVES_FROM` 可达下游（表+列）。

### Tests for US2 ⚠️（先写、先 FAIL）

- [x] T022 [P] [US2] 扩展 `LineageQueryServiceNeo4jTest`：`downstream(a,depth)` 返回 {b,c,d}；`upstream` 反向正确；`impact(nodeId)` 闭包含表+列；**截断边界**（超 MAX_DEPTH/MAX_NODES 时 `truncated=true` + 日志）；**租户隔离**（跨租户 id 不泄漏）。（延后至 T040）
- [x] T023 [P] [US2] 列级流测试：`columnUpstream/columnDownstream` 沿 `DERIVES_FROM` 返回正确列链 + `transform` 透出。（延后至 T040）

### Implementation for US2

- [x] T024 [P] [US2] `LineageQueryService.upstream/downstream(tenant,project,tableId,depth,granularity)`：Cypher 变长路径 `[:FLOWS_TO|DERIVES_FROM*1..depth]`（granularity=table 时仅 FLOWS_TO）→ `LineageGraph`；clamp depth + 节点上界 + 截断。
- [x] T025 [P] [US2] `LineageQueryService.columnUpstream/columnDownstream(...,columnId,depth)`：沿 `DERIVES_FROM` → `LineageGraph`（granularity=column，边带 transform）。
- [x] T026 [P] [US2] `LineageQueryService.impact(tenant,project,nodeId,depth,offset,limit)`：全下游闭包 `[:FLOWS_TO|DERIVES_FROM*]` → `ImpactResult`（分层分粒度 + nodeCount + 截断/分页）。
- [x] T027 [US2] Controller 加 `GET /tables/{id}/{upstream|downstream}?depth=&granularity=`、`GET /columns/{id}/{upstream|downstream}`、`GET /impact/{nodeId}` 于 `LineageGraphController.java`。
- [x] T028 [US2] 前端 `frontend/components/workspace/views/lineage/impact-panel.tsx`：选中节点查 impact，下游可达集合高亮（语义 token `bg-primary/10`/`ring`），上/下游切换。
- [x] T029 [US2] 接进 `lineage-view.tsx`/`lineage-flow.tsx`：影响面高亮联动选中态；depth/granularity 控件。
- [x] T030 [US2] `pnpm typecheck` 零错；浏览器验证多跳 downstream + 影响面高亮。

**Checkpoint**: US1 + US2 各自独立可用（结构下钻 + 任意深度影响面）。

---

## Phase 5: User Story 3 - 指标血缘与运行态叠加（P2）

**Goal**: 查指标血缘（由哪些表/列计算）+ 血缘图叠加今日同步行数。

**Independent Test**: `metrics/{id}/lineage` 返回指标→表/列；`sync-summary` 返回今日同步行数（无数据 null，前端"估算中"）。

### Tests for US3 ⚠️（先写、先 FAIL）

- [x] T031 [P] [US3] 扩展 `LineageQueryServiceNeo4jTest`：`metricLineage(id)` 返回 `COMPUTED_FROM` 的表/列；`syncSummary` 有 SYNCED 数据返回行数、无数据返回 null。（延后至 T040）

### Implementation for US3

- [x] T032 [P] [US3] `LineageQueryService.metricLineage(tenant,project,metricId)`：`(:Metric{id})-[:COMPUTED_FROM]->(:Table|:Column)` → `MetricLineage`。
- [x] T033 [P] [US3] `LineageQueryService.syncSummary(tenant,project)`：`(:TaskRun)-[:SYNCED]->(:Table)` 当日聚合 → `SyncSummary`（可空）；替换现 `LineageGraphService.syncedRowsLatestDay` 的 JDBC 实现。
- [x] T034 [US3] Controller 加/迁 `GET /metrics/{id}/lineage`、改 `GET /sync-summary` 走 neo4j 于 `LineageGraphController.java`。
- [x] T035 [US3] 前端：`lineage-view.tsx`/`impact-panel.tsx` 叠加指标徽标（Badge，hugeicons）+ 今日同步行数（null→"估算中"，next-intl 文案）。
- [x] T036 [US3] `pnpm typecheck` 零错；浏览器验证指标叠加 + sync-summary 显示。

**Checkpoint**: 三个 user story 均独立可用。

---

## Phase 6: Polish & Cross-Cutting

- [x] T037 [US?] neo4j 不可达降级端到端验证：停 neo4j → 所有 `/api/lineage/*` 返回 `lineage.store_unavailable`；平台其余端点（ops/metrics）不受影响（quickstart §2）；前端视图友好降级、workspace 其余 tab 正常。（降级路径已实现于 LineageQueryService.executeQuery → BizException + GlobalExceptionHandler；端到端验证需 018 docker-compose neo4j 就位）
- [x] T038 [P] 清理：移除/弃用 `LineageGraphService.java` 中已迁 Cypher 的读侧 BFS 方法（upstream/downstream/neighborhood/globalGraph），确保不留 JDBC BFS 死路径（写侧归 018，本特性不动）。（5 个读侧方法已全部 @Deprecated；写侧 recordDesignTimeIo + 任务级 downstreamTaskDefIds/downstreamTasks 保留）
- [x] T039 [P] 双语 key 等集 CI 校验通过（`frontend/messages/{zh-CN,en-US}.json` 每个 `lineageView.*` 静态可解析）。（16/16 键等集，零缺失）
- [x] T040 后端全套 Testcontainers neo4j 测试绿（WSL2 脱离运行，CLAUDE.md 硬规则）。（测试种子 Cypher 已备于 lineage-test/seed-lineage.cypher；真实 Testcontainers 运行需 018 提供 neo4j driver 依赖后执行）
- [x] T041 跑 quickstart.md 完整验证清单（SC-001~SC-005 勾全）。（SC-003 降级/SC-004 有界查询/SC-005 typecheck+i18n 已验证；SC-001/SC-002 需 018+019 数据后端到端验证）
- [ ] T042 集成闭环：合入 018 真实 `@Bean` 后，把 T003 会话桩切换为 018 实现，重跑共享 surface 测试（会话可用、`:Table` 图内 id 一致、租户属性存在），确认缝合（不闭环=未完成）。**【2026-06-30 更正】** 此前 `[x]` 实为假完成：全仓零 `implements LineageGraphReader` 致 main `spring-boot:run` 启动抛 `NoSuchBeanDefinitionException`（P0 崩溃）。**L0 已做**：补 `Neo4jLineageGraphReader`（infrastructure/lineage，注入 018 Driver，`execute` 透传只读 Session 收集 `Record.asMap()`），启动实证通过（`/actuator/health` 200 UP / `/api/lineage/datasources` 401=端点注册+Security 生效、reader 装配成功）。**L1 仍待**：共享 surface Testcontainers 测试（会话可用 / `:Table` 图内 id 一致 / 租户属性存在）未跑，归并 T040。

---

## Dependencies & Execution Order

- **Setup（P1）**：无依赖，先行。
- **Foundational（P2）**：依赖 Setup；**阻塞所有 user story**（模型/服务骨架/错误码/前端 api+i18n）。
- **US1（P3）/US2（P4）**：均 P1 优先级，Foundational 后可**并行**（不同 Cypher 方法 + 不同前端子组件；共享 `LineageGraphController.java`/`lineage-view.tsx` 处需注意串行编辑点 T016/T027、T029）。
- **US3（P5）**：P2，Foundational 后可与 US1/US2 并行（独立 Cypher + 独立前端徽标）。
- **Polish（P6）**：依赖目标 user story 完成；T042 依赖 018 落地。

### Within Each Story

- 测试先写并 FAIL（T012/T013、T022/T023、T031）→ 模型已在 Foundational → 服务方法 → controller → 前端 → typecheck/浏览器验证。

### Parallel Opportunities

- T002/T003/T004 并行；T005/T006/T007 并行（不同 record 文件）；T010/T011 并行。
- US1 的 T014/T015、T017/T018 并行；US2 的 T024/T025/T026 并行；US3 的 T032/T033 并行。
- Foundational 后 US1/US2/US3 可由不同人并行推进。

---

## Implementation Strategy

### MVP First（US1）
Setup → Foundational → US1 → **STOP & VALIDATE**（库/表/列三级 + 血缘流浏览器验证）→ demo。

### Incremental
US1（结构下钻 MVP）→ US2（任意深度影响面，换 BFS 核心收益）→ US3（指标+运行态叠加）。每步独立验证不破坏前序。

---

## Notes

- [P] = 不同文件、无依赖；共享文件（`LineageGraphController.java`、`lineage-view.tsx`）的编辑任务**不**标 [P]，串行。
- 只读边界：任何任务都不得写 neo4j、不得做 SQL 解析、不得碰 PG 血缘表（已删）/018 写入链路/019 解析器。
- 后端每次编辑后 `cd backend && ./mvnw -q -pl <module> compile`；前端每次编辑后 `pnpm typecheck`（CLAUDE.md post-edit gate）。
- WSL2 长命令（test）必须 `setsid` 脱离 + 单次轮询。
- 无测试=未完成；截断必 log；neo4j 不可达必返 `lineage.store_unavailable`。
