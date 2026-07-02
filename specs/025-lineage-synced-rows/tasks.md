# Tasks: 运行态同步行数采集（recordSynced 接入）

**Input**: Design documents from `/specs/025-lineage-synced-rows/`（spec.md / plan.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: CLAUDE.md「新特性必须有测试」→ 单测 + testcontainers-neo4j 集成测任务。

**Organization**: 按 user story 组织（US1 P1 MVP / US2 P1 / US3 P2），每 story 独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 归属 user story
- 含精确文件路径（跨 worker / api / master 三模块）

---

## 关键架构决策（research.md 补强 + 实现期代码核实，不可违背）

1. **`StatementMetric` 放 `master.domain.lineage`（非 worker）**：模块依赖方向是 **`worker → master`**（worker pom 依赖 master；先例 `SqlTaskExecutor` 已 `import com.dataweave.master.domain.DriverJar / master.infrastructure.IsolatedDriverLoader`）。master 不依赖 worker → 类型只能放 master 让三模块都见。worker.ExecutionResult 引用 master.StatementMetric 与 DriverJar 同模式。
2. **`ExecutionResult` 加字段用 compat 构造器（零回归）**：末位加 `List<StatementMetric> statementMetrics`，新增 compat 构造器默认 `List.of()`——既有 **21 处构造点**（QualityProbe/Python/Spark/Shell/Sql/Echo/AbstractTaskExecutor/TaskExecutor 自身）零改动；仅 SqlTaskExecutor 成功路径填真实值。
3. **runtime `TableRef` coord 复用 `LineageEdgeAssembler.resolveCoord`**：reportFinished 注入 `LineageEdgeAssembler`，按 `ti.getTaskId()` 查 `task_def` 的目标 datasource_id → `resolveCoord(tenantId, projectId, dsId)`（与设计态同源 coord → runtime `:Table` 与设计态同 `tableKey` → SYNCED 挂到设计态表节点，契约意图）。缺 datasource 走 resolveCoord 的 null 降级（不阻断）。
4. **写表解析复用 `SqlTableExtractor.extract(sql).writes()`**（`Set<String>`），**不查 neo4j `:WRITES`**（runtime statement 是真相，避免与设计态漂移）。
5. **syncSummary 读侧不改**（`LineageQueryService:375` `MATCH (r:TaskRun)-[s:SYNCED]->(t:Table) ... RETURN SUM(s.rowCount)` by bizDate+tenant；runtime `:Table` 存在即聚合，coord 不阻断 SUM → SC-001 成立）。
6. **`recordSynced` 契约 additive 扩参**：加 `Long taskDefId`（**零现有调用**，安全），`:TaskRun` MERGE SET taskDefId（FR-004 建议留口子）。
7. **降级零阻断**：reportFinished 外层 try-catch；UPDATE/DELETE（updateCount>0 无写表）WARN 不静默丢；null/empty 跳过；neo4j 不可达吞。

---

## Phase 1: Setup

**Purpose**: baseline 留照，确认 recordSynced 零调用 + syncSummary 恒空起点

- [ ] T001 baseline：`cd backend && ./dev-install.sh` 绿 + 现状留照（确认 `recordSynced` 全仓零生产调用；跑一个写表 SQL 任务 → neo4j 无 `:SYNCED`、`GET /api/lineage/sync-summary` 当日 null/0）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 跨 story 的共享类型/字段，MUST 先于任何 US

- [x] T002 新增 `record StatementMetric(String sqlText, long updateCount)` 于 `master/domain/lineage/StatementMetric.java`（worker→master 可见）+ `ExecutionResult` 末位加 `List<StatementMetric> statementMetrics` 字段 + compat 构造器默认 `List.of()`（保留既有 7 参 compat + 新增 8 参(skipped) compat 各默认空 list + `skipped()` 工厂）—— `worker/domain/TaskExecutor.java:50-63`。编译验证 21 处既有构造点零改动。

**Checkpoint**: 类型 + 字段就绪（statementMetrics 全链路 worker→api→master 可见）。

---

## Phase 3: User Story 1 - SQL 任务 syncSummary 有真实数据 (Priority: P1) 🎯 MVP

**Goal**: SqlTaskExecutor 收 per-statement affected-rows → 双路径透传 → `reportFinished` 逐表 `recordSynced` → `:SYNCED` → syncSummary 当日真实 SUM（不再恒 null）。

**Independent Test**: 写单表 INSERT 的 SQL 任务成功 → neo4j `(:TaskRun{instanceId})-[:SYNCED{rowCount:<affected>,bizDate}]->(:Table)`，`GET /api/lineage/sync-summary` 当日返回该值；失败/非 SQL 零回归。

### Implementation

- [x] T003 [US1] `SqlTaskExecutor` 成功路径收集 per-statement `(sqlText, updateCount≥0)` 进 `statementMetrics`（loop `:68-83` 内 `st.execute` 后：`!hasResultSet && updateCount>=0` 才收；SELECT/DDL 跳过）；成功 return `:86` 带真实 list（失败/skipped 走 compat 空 list）—— `worker/infrastructure/SqlTaskExecutor.java`
- [x] T004 [US1] `recordSynced` 签名加 `Long taskDefId`（additive，零现有调用安全）+ `Neo4jLineageStore:164` `:TaskRun` MERGE ON CREATE SET `r.taskDefId`；`WorkerReportService.reportFinished` 签名加 `List<StatementMetric> statementMetrics`，注入 `LineageEdgeAssembler` + `SqlTableExtractor`，SUCCESS CAS 后（仅成功 FR-006）逐 statement：`extract(sqlText).writes()` → 每 writeTable 构 `TableRef(resolveCoord(tenant,project,taskDef.datasource_id), name, layer)` → `recordSynced(tenantId, projectId, instanceId, tableRef, updateCount, null, bizDate, ti.getTaskId())`；bizDate/tenant/project 取自 ti —— `master/domain/lineage/LineageStore.java:45` + `master/infrastructure/lineage/Neo4jLineageStore.java:155-177` + `master/application/WorkerReportService.java:67-82`
- [x] T005 [P] [US1] all-in-one 路径：`InProcessTaskExecutionGateway` 3 个 reportFinished 调用点透传 statementMetrics（`:119` msg 路径 `List.of()`；`:157` skipped `List.of()`；`:160` 成功 `result.statementMetrics()`）—— `api/infrastructure/InProcessTaskExecutionGateway.java:119,157,160`
- [x] T006 [P] [US1] HTTP 路径 master 侧：`TaskReportRequest` 加 `List<StatementMetric> statementMetrics` + getter/setter（`@JsonIgnoreProperties(ignoreUnknown=true)` 已在 → 向后兼容）；`ClusterController:78` 透传 reportFinished —— `api/interfaces/dto/TaskReportRequest.java` + `api/interfaces/ClusterController.java:78`
- [x] T007 [US1] HTTP 路径 worker 侧：`WorkerExecService.ReportCallback.onFinished` 加 `List<StatementMetric>` 参数（`:53` 接口 + `:158/:160` 传 `result.statementMetrics()`）；`WorkerExecController.ReportCallback.onFinished:195` 接收 → `reportToMaster` 改 **Jackson `ObjectMapper`** 序列化整 payload（含 statementMetrics 数组，正确转义 SQL 文本 `"`/换行/反斜杠）—— `worker/application/WorkerExecService.java:48-56,158,160` + `worker/interfaces/WorkerExecController.java:182-237`

### Tests

- [x] T008 [P] [US1] 单测 `SqlTaskExecutor` 收 statementMetrics：多 statement 各 updateCount / SELECT(hasResultSet) 跳过 / DDL(updateCount<0) 跳过 / 无数据源 SKIPPED 空 list —— `worker/infrastructure/SqlTaskExecutorTest.java`（H2 in-memory 连接，既有 executor 测试模式）
- [x] T009 [US1] 单测 `WorkerReportService.reportFinished`：单表 INSERT → recordSynced 调 1 次（mock LineageStore）；多 statement 多表 → N 次；statementMetrics empty → 0 次；非 SUCCESS 不调 —— `master/application/WorkerReportServiceTest.java`
- [ ] T010 [US1] 集成测 testcontainers-neo4j：端到端 SQL 任务成功 → `(:TaskRun)-[:SYNCED{rowCount}]->(:Table)` + syncSummary 当日 SUM；失败任务不写 —— `master/infrastructure/lineage/`（既有 lineage IT）

**Checkpoint**: MVP 达成——syncSummary 不再恒 null（US1 独立可验）。

---

## Phase 4: User Story 2 - 降级绝不阻断主链路 (Priority: P1)

**Goal**: 任何采集/解析/写入失败干净降级（跳过+日志），0 阻断任务执行/回报；updateCount>0 无写表不静默丢。

**Independent Test**: 四种降级情形正确；neo4j 不可达任务仍 SUCCESS。

### Implementation

- [x] T011 [US2] reportFinished recordSynced 外层 try-catch（neo4j 不可达吞，任务仍 SUCCESS，FR-005）；`updateCount<0` 跳过 statement；statementMetrics null/empty 跳过 —— `master/application/WorkerReportService.java`（依赖 T004）
- [x] T012 [US2] UPDATE/DELETE WARN：解析不出写表（`parsed` 但 `writes` 空 / UPDATE·DELETE 未识别）+ `updateCount>0` → WARN 日志（显式降级，不静默丢）；MVP 仅 INSERT/MERGE 识别（`SqlTableExtractor` 现状，R5）—— `master/application/WorkerReportService.java`

### Tests

- [ ] T013 [P] [US2] 单测降级四情形：neo4j 异常 try-catch 不抛 / updateCount<0 跳过 / null-empty 跳过 / UPDATE-DELETE WARN —— `master/application/WorkerReportServiceTest.java`
- [ ] T014 [US2] 兼容测：旧 worker（statementMetrics 缺失 null）+ 新 master 跳过不崩；新 worker + 旧 master `@JsonIgnoreProperties` 忽略不崩 —— `api/.../ClusterReportTest.java` 或单测

**Checkpoint**: 生产可用底线——降级零阻断（US2 独立可验）。

---

## Phase 5: User Story 3 - 多写表近似归属 (Priority: P2)

**Goal**: 单 statement 写多表（INSERT ALL 等），每表共享该 statement updateCount（JDBC 无 per-table 分解，近似正确）。

**Independent Test**: 一条 statement 写 tableA/tableB → 各 `:SYNCED{rowCount:<shared>}`。

- [ ] T015 [US3] 多写表归属：`SqlTableExtractor.writes()` 返回多表时，每表各 recordSynced 共享该 statement updateCount（T004 循环天然支持，本 task 验证 + 文档化 1:N 近似规则）—— `master/application/WorkerReportService.java`（依赖 T004）
- [ ] T016 [US3] 集成测：INSERT ALL / 单 statement 多表 → 每表 `:SYNCED` 共享 updateCount；多 statement → syncSummary SUM —— 既有 lineage IT

**Checkpoint**: 多表归属完整（US3 独立可验）。

---

## Phase 6: Polish & Cross-Cutting

- [x] T017 [P] 零回归：非 SQL 任务（Spark/Python/Shell/Echo/QualityProbe）statementMetrics 空、不写 `:SYNCED`；失败/skipped 不写 —— 既有测试 + 编译（21 构造点 compat 验证）
- [ ] T018 [P] 文档：`CLAUDE.md`「Table lineage」导航补 recordSynced 接入（运行态采集打通）+ `docs/architecture.md` 运行态采集段 —— `CLAUDE.md` + `docs/architecture.md`
- [ ] T019 跑 `quickstart.md` 端到端验证（场景1 单表 / 场景2 多表 / 降级 / 兼容 / H2 降级）
- [ ] T020 cross-feature 检查：与 024（定义态列 catalog）边界（recordSynced vs recordTaskIo 代码路径独立不冲突）、不改 syncSummary 读侧、H2 profile 启动不崩、合并序 021→022→023→024→**025**

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (T001)**: 无依赖
- **Foundational (T002)**: BLOCKS 全 US（statementMetrics 类型跨模块可见性）
- **US1 (T003–T010)**: T003 采集 → T004 接线（依赖 T002）→ {T005 ∥ T006} → T007（依赖 T004 签名）；测试 T008 ∥ T009，T010 依赖 T003–T007
- **US2 (T011–T014)**: T011/T012 依赖 T004；测试 T013 ∥ T014
- **US3 (T015–T016)**: 依赖 T004（循环已支持，验证为主）
- **Polish (T017–T020)**: 依赖目标 US 完成

### User Story Dependencies
- **US1 (P1)**: Foundational 后可开始，不依赖其他 story —— **MVP**
- **US2 (P1)**: 依赖 US1 的 reportFinished 接线（T004）；叠加降级层
- **US3 (P2)**: 依赖 US1 的 recordSynced 循环（T004）；验证多表归属

### Parallel Opportunities
- T005 ∥ T006（不同模块，均依赖 T004 签名）
- T008 ∥ T009（不同模块测试）
- T013 ∥ T014
- T017 ∥ T018

---

## Implementation Strategy

### MVP First (US1)
T001 → T002 → T003 → T004 → T005/T006 → T007 → T008/T009/T010。
**STOP 验证**：写表 SQL 任务成功 → syncSummary 当日返回真实 SUM（不再 null）；失败/非 SQL 零回归。

### Incremental Delivery
- +US1 → syncSummary 可用（MVP）
- +US2 → 降级零阻断（生产可用底线）
- +US3 → 多表归属完整

---

## Notes
- `[P]` = 不同文件、无依赖可并行
- 每 task 后编译 `cd backend && ./mvnw -q -pl <changed-module> -am compile`；WSL 长跑用 setsid 脱离（见 CLAUDE.md）
- 实现期开 `dw-025-lineage-synced-rows` worktree（与 021/022/023/024 惯例一致）
- 改 `reportFinished` / `ExecutionResult` / `ReportCallback` 签名后，同步既有调用点 + 测试（`ClusterReportTest`、`WorkerExecServiceTest`、`WorkerExecServiceDispatchTest`、InProcessTaskExecutionGateway 相关）
