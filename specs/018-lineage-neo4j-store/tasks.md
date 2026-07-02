---
description: "Task list for 018-lineage-neo4j-store"
---

# Tasks: 血缘图底座 —— neo4j 存储与写入链路

**Input**: Design documents from `specs/018-lineage-neo4j-store/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/lineage-store.md, quickstart.md

**Tests**: 本项目硬规则「新功能必须有测试 = 否则未完成」；测试用 **Testcontainers neo4j**。测试任务为强制项。

**Organization**: 按 user story 分阶段（Setup → Foundational → US1/US2/US3 同为 P1 → Polish）。每个 US 可独立测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: US1 / US2 / US3 / FOUND / SETUP / POLISH
- 路径均为仓库根相对的绝对路径

## Path Conventions

- 血缘逻辑：`backend/dataweave-master/src/main/java/com/dataweave/master/{domain,infrastructure,application}/lineage/`
- 测试：`backend/dataweave-master/src/test/java/com/dataweave/master/lineage/`
- Schema/seed：`backend/dataweave-api/src/main/resources/{schema.sql,data.sql}`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 引入 neo4j 依赖与运行/测试基础设施

- [X] T001 在 `docker-compose.yml` 增加 `neo4j` service（image `neo4j:5-community`，ports `7687:7687`/`7474:7474`，`NEO4J_AUTH=neo4j/dataweave`，volume `dataweave-neo4j`，healthcheck cypher-shell ping），与 PG/Redis/MinIO 同级；在 `volumes:` 段加 `dataweave-neo4j:`。
- [X] T002 [P] 在 `backend/dataweave-master/pom.xml` 加依赖 `org.neo4j.driver:neo4j-java-driver`（main）与 `org.testcontainers:neo4j` + `org.testcontainers:junit-jupiter`（test scope）；版本走 BOM/parent 管理，对齐已有 testcontainers 用法。
- [X] T003 [P] 在 `backend/dataweave-master/src/main/resources/application.yml`（或 api 模块配置）加 `lineage.neo4j.{uri,username,password}` 配置键，默认 `bolt://localhost:7687` / `neo4j` / `dataweave`（开发态；生产经环境变量覆盖）。

---

## Phase 2: Foundational（阻塞性前置 —— 所有 US 之前必须完成）

**Purpose**: 图模型契约 + neo4j driver 接入 + 约束/索引 + LineageStore 桩，是 US1/US2/US3 与 019/020 的共同地基。

**⚠️ CRITICAL**: 本阶段未完成，任何 user story 不能开工。

### 领域契约（domain/lineage，纯 record/接口，无框架依赖）

- [X] T004 [P] [FOUND] 建 `backend/.../master/domain/lineage/DatasourceCoord.java`（record + `dsKey()` 规范化合成键：有坐标→`tenantId|ip|port|database`、缺坐标→`tenantId|datasource:<fallbackName>`），按 contracts/lineage-store.md §1。
- [X] T005 [P] [FOUND] 建 `TableRef.java`、`IoEdge.java`、`ColumnEdge.java`、`MetricEdge.java` 及枚举 `Direction/Source/Confidence/Transform`，于 `backend/.../master/domain/lineage/`，签名严格对齐 contracts/lineage-store.md §1。
- [X] T006 [FOUND] 建 `backend/.../master/domain/lineage/LineageStore.java` 接口（`recordTaskIo` / `recordMetricLineage` / `recordSynced`），javadoc 写明 replace-per-task / 去重 / 韧性 / 隔离不变量（contracts §2）。依赖 T004、T005。

### neo4j 基础设施（infrastructure/lineage）

- [X] T007 [FOUND] 建 `backend/.../master/infrastructure/lineage/Neo4jConfig.java`：自建 `Driver` `@Bean`（`GraphDatabase.driver(uri, AuthTokens.basic(...))`，读 `lineage.neo4j.*`），对标 `dataweave-api/.../infrastructure/WebClientConfig.java`（SB4 无自动配置）。`@Bean` 析构 `close()`。依赖 T002、T003。
- [X] T008 [FOUND] 建 `backend/.../master/infrastructure/lineage/Neo4jSchemaInitializer.java`：`ApplicationRunner`/`@PostConstruct` 幂等执行 data-model.md §3 的 `CREATE CONSTRAINT ... IF NOT EXISTS`（dsKey/tableKey/columnKey/metricKey/taskKey/instanceId）+ `CREATE INDEX ... IF NOT EXISTS`（tenantId/projectId scope）。neo4j 不可达时记日志不阻断启动。依赖 T007。
- [X] T009 [FOUND] 建 `backend/.../master/infrastructure/lineage/Neo4jLineageStore.java` **接口桩**（`implements LineageStore`，方法体先空实现/记日志），让 application 层与 019/020 可编译并行；真实 Cypher 在 US1 填充。依赖 T006、T007。

### Foundational 测试 harness

- [X] T010 [P] [FOUND] 建 `backend/.../master/src/test/java/com/dataweave/master/lineage/Neo4jTestSupport.java`：Testcontainers `Neo4jContainer` + `@DynamicPropertySource` 注入 `lineage.neo4j.{uri,username,password}` 到被测 `Neo4jConfig`；提供每测清库工具（`MATCH (n) DETACH DELETE n`），沿用后端测试隔离不变量（`@DirtiesContext`、redis health off）。依赖 T002。

**Checkpoint**: 图模型契约 + driver Bean + 约束/索引 + Store 桩 + 测试 harness 就绪 —— US1/US2/US3 可并行开工；019/020 可对桩并行。

---

## Phase 3: User Story 1 - 建任务即把血缘落入 neo4j（Priority: P1）🎯 MVP

**Goal**: `createAndOnline` 把数据源→表→任务读写→表级流血缘写入 neo4j（replace-per-task 单事务），PG 不再写血缘表。

**Independent Test**: 建 `INSERT INTO dwd_order SELECT * FROM ods_order` 任务 → neo4j 出现 `:Table{ods_order,dwd_order}`、`(:Task)-[:READS]->ods_order`、`-[:WRITES]->dwd_order`、`(ods_order)-[:FLOWS_TO]->(dwd_order)`。

### Tests for US1（先写、确保 FAIL）⚠️

- [X] T011 [P] [US1] 在 `backend/.../master/lineage/Neo4jLineageStoreIT.java` 写 recordTaskIo 核心断言：单任务两表 → `:Table`×2 + `READS`/`WRITES` 边 + `FLOWS_TO` 派生边正确（用 Neo4jTestSupport 真容器）。依赖 T010。
- [X] T012 [P] [US1] 同 IT 增 replace-per-task 幂等用例：同任务 recordTaskIo 两次 → 边集合一致、无翻倍、无残留陈边（SC-003）；改写后再记录 → 旧边整体替换（spec US1 验收 #2）。
- [X] T013 [P] [US1] 同 IT 增 ColumnEdge 写入用例：传入构造的 `List<ColumnEdge>` → `:Column` 节点 + `HAS_COLUMN` + `DERIVES_FROM {taskDefId,transform}` 正确入图（FR-011，验证 019 接口形参可用）。

### Implementation for US1

- [X] T014 [US1] 实现 `Neo4jLineageStore.recordTaskIo`（替换 T009 桩）：单 `session.executeWrite` 事务内 —— ① `MATCH (:Task{taskKey})-[r:READS|WRITES|READS_COL|WRITES_COL]->() DELETE r` + 删本 taskDefId 的 `FLOWS_TO`/`DERIVES_FROM`；② `MERGE` `:Datasource`(dsKey 去重)/`:Table`(tableKey)/`:Column`(columnKey)/`:Task`(taskKey) 节点；③ `CREATE` `READS`/`WRITES`(含 source/confidence/version) + 派生 `FLOWS_TO {taskDefId}`（READ 表×WRITE 表）+ ColumnEdge 的 `DERIVES_FROM`。`backend/.../infrastructure/lineage/Neo4jLineageStore.java`。依赖 T006、T007。
- [X] T015 [US1] 改造 `backend/.../master/application/TaskService.java` 的 `recordLineage`/`buildEdges`：保留 A×B 交叉校验逻辑，产出 `List<IoEdge>`（替代 `LineageGraphService.EdgeInput`），调用 `lineageStore.recordTaskIo(...)`（注入 `LineageStore` 替代 `LineageGraphService` 写路径）；保留 try-catch 不阻断（FR-007）。`tenantId/projectId` 从现 `1L,1L` 占位沿用（与现状一致，租户化随上游）。依赖 T014。
- [X] T016 [US1] 实现 `:Datasource` 去重解析：在 store 写入前，由 `DatasourceCoord` → 查 `datasources`/`datasource_types`（host/port/database/default_port）拼 `dsKey`，缺坐标走 `fallbackName`；`:Table` 的 `datasourceId` 绑定去重后的 `:Datasource`。可置于 store 内部辅助方法或 application 装配。依赖 T014。
- [X] T017 [US1] 实现韧性：`Neo4jConfig` driver 设短连接/获取超时；store 写入异常向调用方抛后由 T015 try-catch 吞并记可诊断日志（taskDefId+原因）。验证 neo4j 不可达建任务仍成功（手动或 IT 关容器场景）。依赖 T014、T015。

**Checkpoint**: US1 独立可测 —— `createAndOnline` 血缘真入图、replace 幂等、列级形参可写、韧性不阻断。

---

## Phase 4: User Story 2 - CLI push 也建血缘（补齐缺口）（Priority: P1）

**Goal**: `dw push` 同步任务定义时，每个新增/修改任务触发 `recordTaskIo`，使 CLI 创作的任务血缘完整（补 push 路径不落血缘的已知缺口）。

**Independent Test**: 对含 SQL 任务的项目 `dw push` → neo4j 出现该任务血缘，语义与 `createAndOnline` 一致。

### Tests for US2（先写、确保 FAIL）⚠️

- [ ] T018 [P] [US2] 建 `backend/.../master/lineage/PushLineageIT.java`：构造含 SQL 任务的 push（复用 `ProjectSyncService.push` 路径 + Neo4jTestSupport），断言新增任务血缘入图、语义与 createAndOnline 一致（spec US2 验收 #1）。依赖 T010、T014。
- [ ] T019 [P] [US2] 同 IT 增「push 修改已有任务 → 血缘按 replace-per-task 更新」用例（spec US2 验收 #2）。

### Implementation for US2

- [x] T020 [US2] 改造 `backend/.../master/application/ProjectSyncService.java` 的 `push`：对本次 push 落库的每个新增/修改任务，解析其 content（复用 `SqlTableExtractor` + 与 TaskService 同款 A×B/IoEdge 装配，宜抽公共 helper 避免重复）→ 调用 `lineageStore.recordTaskIo(...)`；try-catch 包裹不阻断 push 主链路（FR-002 缺口补齐 + FR-007）。注入 `LineageStore`。依赖 T014、T015。
- [X] T021 [US2] 抽取 TaskService 与 ProjectSyncService 共用的「content → IoEdge 装配（含 A×B 交叉校验）」为一个可复用方法/小服务（如 `LineageEdgeAssembler`），消除两处重复并保证语义一致。`backend/.../master/application/lineage/`。依赖 T015、T020。

**Checkpoint**: US1 + US2 均独立可测 —— createAndOnline 与 push 两条创作路径血缘语义一致。

---

## Phase 5: User Story 3 - 同一物理库去重为单一节点（Priority: P1）

**Goal**: 同 `(ip,port,database)` 无论被多少任务/凭据引用，图中只有一个 `:Datasource`，其下表唯一。

**Independent Test**: 两个连接配置（同 ip/port/database、不同 username）各建引用同表的任务 → `:Datasource` 唯一、目标表唯一。

### Tests for US3（先写、确保 FAIL）⚠️

- [x] T022 [P] [US3] 在 `Neo4jLineageStoreIT.java` 增去重用例：两次 recordTaskIo 用同 `(tenantId,ip,port,database)` 不同 username 的 `DatasourceCoord` → `:Datasource` 节点数 = 1、目标 `:Table` 唯一（SC-002 / 验收 #1）。依赖 T010、T014。
- [x] T023 [P] [US3] 同 IT 增「同 ip/port 不同 database → 两个不同 `:Datasource`」用例（验收 #2）；并增「缺连接坐标 → 降级身份 `datasource:<name>` 仍唯一不重复」用例（Edge Case）。

### Implementation for US3

- [X] T024 [US3] 完善 `DatasourceCoord.dsKey()` 规范化（ip 小写 trim、port 缺省补 `default_port`、database 小写 trim、凭据不进键、缺坐标 fallbackName），并在 `Neo4jLineageStore` 的 `:Datasource` `MERGE` 用 `dsKey` 做匹配键（依赖 datasource_key `IS UNIQUE` 约束 T008 保证并发去重）。`backend/.../domain/lineage/DatasourceCoord.java` + `Neo4jLineageStore.java`。依赖 T004、T014、T016。

**Checkpoint**: 三个 P1 user story 全部独立可测通过。

---

## Phase 6: 指标血缘迁图 + schema 收口 + greenfield 种子（跨 US 收尾，FR-008/009/010）

**Purpose**: 完成 neo4j 完全替换 PG 血缘四表的剩余收口。这些是「换底座」闭环的必做项（不闭环 = 未完成）。

### 指标血缘迁图（FR-008）

- [x] T025 [P] [POLISH] 在 `Neo4jLineageStoreIT.java` 增 `recordMetricLineage` 用例：`:Metric`(metricKey)-[:COMPUTED_FROM]->`:Table` 正确入图，身份镜像 `(tenantId, metricType, id)`。依赖 T010。
- [ ] T026 [POLISH] 实现 `Neo4jLineageStore.recordMetricLineage`（MERGE :Metric + COMPUTED_FROM）；改造 `backend/.../master/application/LineageService.java` 指标血缘写入改走图（替代 `MetricLineageRepository` PG 写）。依赖 T014。

### schema 收口（FR-009，017 接触点 —— 删表必清悬空引用）

- [x] T027 [POLISH] 清理 PG 血缘 domain/repository：删/改 `backend/.../master/domain/{DataTable,DataTableRepository,TaskTableIo,TaskTableIoRepository,MetricLineage,MetricLineageRepository}.java` 及 `LineageGraphService.java`/`LineageService.java` 中对其的 JdbcTemplate 读写，改走 `LineageStore`/留待 020 查询；保证全模块编译通过、无悬空 JDBC 引用。`cd backend && ./mvnw -q -pl dataweave-master -am compile` 零错误。依赖 T026。
- [x] T028 [POLISH] 在 `backend/dataweave-api/src/main/resources/schema.sql` 删 `data_table`/`task_table_io`/`task_run_table_io`/`metric_lineage` 的 DROP+CREATE TABLE+CREATE INDEX 三段（域 F + metric_lineage）；并**递增 `schema_version`**（库内单行 INSERT 的 version + 文件头 `-- Schema Version:` 注释，较 `0.0.1` 升版，三处恒等）。依赖 T027。
- [x] T029 [POLISH] 在 `backend/dataweave-api/src/main/resources/data.sql` 删对应血缘 seed（域 F 的 data_table/task_table_io/task_run_table_io seed + metric_lineage seed + 相关 `ALTER ... RESTART`）。依赖 T028。
- [ ] T030 [P] [POLISH] 建 `backend/.../master/lineage/SchemaVersionConsistencyTest.java`：断言 `schema_version` 库内单行 version 与 schema.sql 文件头注释与项目版本三处恒等，且较 `0.0.1` 已递增（SC-005）。依赖 T028。

### greenfield 种子（FR-010）

- [x] T031 [POLISH] 建 `backend/.../master/infrastructure/lineage/Neo4jLineageSeeder.java`：`ApplicationRunner` 幂等经 `LineageStore` 路径播种 data-model.md §5 数据集（1 库/5 表/3 任务/7 io 边/1 指标，tenant=1 project=1）；neo4j 不可达时记日志不阻断启动。依赖 T014、T026。
- [x] T032 [P] [POLISH] 在 IT 增种子幂等用例：重复触发 seeder → 节点/边不翻倍（去重地基活体冒烟）。依赖 T031、T010。

---

## Phase 7: Polish & Cross-Cutting

- [x] T033 [P] [POLISH] 实现 `Neo4jLineageStore.recordSynced`（`:TaskRun`-[:SYNCED]->`:Table`，迁 task_run_table_io 运行态）+ IT 用例；运行态采集接入点留待后续埋点（本期至少能写）。依赖 T014。
- [ ] T034 [POLISH] 按 quickstart.md §3/§4 跑端到端手动验证（本地 neo4j：建任务/push 入图、去重、replace 幂等、停 neo4j 不阻断），记录结果。依赖 全部实现任务。
- [ ] T035 [P] [POLISH] 全套血缘 IT setsid 脱离跑通（WSL2 硬规则）：`Neo4jLineageStoreIT,PushLineageIT,SchemaVersionConsistencyTest` 全绿（SC-005）；`./mvnw -q -pl dataweave-master -am compile` + `dataweave-api` 编译零错误（删表后无悬空引用）。依赖 全部任务。

---

## Dependencies & Execution Order

### Phase 依赖

- **Setup (P1)**: 无依赖，先行。
- **Foundational (P2)**: 依赖 Setup —— 阻塞所有 user story。
- **US1/US2/US3 (P3-5)**: 均依赖 Foundational；三者同为 P1，US1 先落 `recordTaskIo` 实现（T014）后 US2/US3 复用之。
- **Phase 6 收口**: 依赖 US1（recordTaskIo 实现）；指标/schema/种子收尾。
- **Phase 7 Polish**: 依赖前述实现。

### User Story 依赖

- **US1 (P1)**: Foundational 后即可 —— MVP 核心，落 `recordTaskIo`。
- **US2 (P1)**: 依赖 US1 的 `recordTaskIo`（T014）；push 路径复用同接口。
- **US3 (P1)**: 依赖 US1 的 store 写入（T014/T016）；去重是 store 内 `:Datasource` MERGE 行为。

### Within each story

- 测试先写并 FAIL（T011-T013 / T018-T019 / T022-T023）→ 再实现。
- 契约（domain record/接口）→ store 实现 → 触发点接入。

### Parallel Opportunities

- Setup：T002、T003 [P]。
- Foundational：T004、T005 [P]（不同 record 文件）；T010 [P]（测试 harness）。
- 各 US 内测试任务 [P]（同/不同 IT 文件的独立用例可并行编写）。
- Phase 6：T025、T030、T032 [P]；Phase 7：T033、T035 [P]。
- **跨 feature**：Foundational 完成（契约 + 桩 T009）后，019/020 可对桩并行开工。

---

## Implementation Strategy

### MVP First（US1 only）

1. Phase 1 Setup → 2. Phase 2 Foundational（契约+driver+约束+桩+harness）→ 3. Phase 3 US1（recordTaskIo 真实现 + TaskService 接入 + 去重 + 韧性）→ **STOP & VALIDATE**：建任务血缘入图、replace 幂等、不阻断 → 可演示。

### Incremental Delivery

1. Setup + Foundational → 地基就绪（019/020 可并行）。
2. US1 → createAndOnline 入图（MVP）。
3. US2 → push 补血缘。
4. US3 → 数据源去重。
5. Phase 6 → 指标迁图 + schema 删表升版本 + 种子（换底座闭环）。
6. Phase 7 → 运行态/端到端/全绿。

---

## Notes

- [P] = 不同文件、无依赖。
- 测试用 Testcontainers neo4j 真容器；跑长测试 setsid 脱离 + 单次秒回轮询（WSL2 硬规则）。
- 删 PG 血缘表必须连带清 domain/repository 悬空引用 + 升 schema_version（三处恒等）—— 否则不闭环 = 未完成。
- `LineageStore`/`ColumnEdge` 契约一旦落地（Foundational）即冻结，供 019/020 并行依赖；不在本 feature 改契约。
- 列映射的产生（SQL 列解析）不在本 feature（019）；查询/API/前端不在本 feature（020）。

**任务总数**: 35（T001–T035）
