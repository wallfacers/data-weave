---
description: "Task list for 大数据开发任务类型补全（MVP）"
---

# Tasks: 大数据开发任务类型补全（MVP）

**Input**: Design documents from `specs/059-bigdata-task-types/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 本特性 spec FR-018/FR-019 明确要求测试（"no test = not done"），故每个新执行器均含测试任务。

**Organization**: 按 user story 分组，对齐 plan 的 3-Agent 并行工作流（A=US1 / B=US2 / C=US3）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 / US2 / US3
- 每个任务含精确文件路径

## Agent ↔ Worktree ↔ Story 映射

| 工作流 | Agent | Story | Worktree | 关键约束 |
|---|---|---|---|---|
| A | Agent A | US1（OLAP+Hive，JDBC 路径） | `../dw-059-a` | **不依赖 Foundational**，可与 Foundational 并行 |
| B | Agent B | US2（DataX+SeaTunnel）+ **拥有 Foundational** | `../dw-059-b` | Foundational（`EngineSubmitRef` 等）**先落 main**，再做 US2 |
| C | Agent C | US3（Flink+入口暴露） | `../dw-059-c` | Flink 依赖 Foundational；消费 B 的 `EngineSubmitRef` |

**共享面协调（硬规则）**：`ExecutionContext.EngineSubmitRef`（B 先落 main，C 消费）；`frontend/messages/*.json` 的 `taskType*` 键全部集中在 US3（仅 Agent C 改，避免两方对撞）；`data.sql` MVP 无需改。碰撞即停并上报，禁静默覆盖。

---

## Phase 1: Setup（共享）

**Purpose**: 干净起点与隔离工作区

- [ ] T001 校验基线绿：`cd backend && ./dev-install.sh` 后 `./mvnw -q -pl dataweave-worker,dataweave-master,dataweave-api compile` 零错；`cd frontend && pnpm typecheck` 零错（记录基线，避免把既有问题算到本特性）
- [ ] T002 [P] 按 plan 协调规则创建三个隔离 worktree：`git worktree add ../dw-059-a -b 059-a-olap-hive`、`../dw-059-b -b 059-b-integration`、`../dw-059-c -b 059-c-flink-surface`（合并后 `git worktree remove`，禁提交 worktree 路径）
- [ ] T002a [P] 【G1，实现前裁决】评估 `task_def.content`/`task_def_version.content`（`schema.sql:322,353` `VARCHAR(4000)`）对 DataX/SeaTunnel/Flink 真实作业体的充分性：抽样 3–5 个典型作业测字节数；若普遍 >4000，则扩列为 `VARCHAR(1048576)` 或 `TEXT`（H2+PG 双跑验证）+ bump `schema_version`（同步文件头/DB 行版本）。裁决结论回写 plan Storage 行（FR-013a）

---

## Phase 2: Foundational（共享引擎管道 — 阻塞 US2 & US3；**US1 不依赖**）

**Purpose**: `EngineSubmitRef` 共享上下文 + 解析/下发/本地接线。由 **Agent B 先实现并合入 main**（契约先行），US3 基于其消费。

**⚠️ CRITICAL**: US2、US3 的执行器任务在本阶段合入 main 前不得开始；**US1 可立即并行**（Hive/OLAP 走 JDBC 路径，与引擎管道无关）。

- [ ] T003 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/domain/ExecutionContext.java` 新增 `EngineSubmitRef` record（`kind, engineHome, mode, jarPath, mainClass, configPath, props`）+ 新增可空字段 `engine`；保留全部现有 telescoping 构造器，新增「含 engine 全参构造」，老调用点 `engine=null` 零改动（data-model §3）
- [ ] T004 [P] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/DatasourceResolver.java` 的 `ResolvedConnection` 增 `engine(...)` 工厂 + `EngineClusterRef` record；`resolve()` 增 `FLINK/DATAX/SEATUNNEL` 分派（Flink 从绑定数据源 `props_json`；DataX/SeaTunnel 从环境 `*_HOME`），镜像现有 `buildSparkRef`
- [ ] T005 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/TaskExecutionGateway.java` 的 `DispatchCommand` 增引擎字段（engineMode/jarRef/mainClass 复用或新增），非引擎任务为 null
- [ ] T006 在 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/InProcessTaskExecutionGateway.java` 于 `buildSparkRef` 旁增 `buildEngineRef`，all-in-one 建 `ExecutionContext.engine`（仅 FLINK/DATAX/SEATUNNEL 类型）
- [ ] T007 在 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/DistributedTaskExecutionGateway.java` over-wire 序列化 engine ref 字段（镜像现有 Spark 序列化 :175-179）
- [ ] T008 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerKernel.java`（:317 附近）镜像 `_sparkMode/_jarRef/_mainClass` 提取，增 `_flinkMode` 等引擎子模式从 `params_json` 提取并带入 `DispatchCommand`
- [ ] T009 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/localrun/LocalRunArgs.java` 增 `--flink-mode`（复用现有 `--jar-path/--main-class`）；`LocalRunMain.buildContext` 合成 `EngineSubmitRef`（各执行器 selectExecutor 分支在各 US 任务内加）
- [ ] T010 编译门：`cd backend && ./dev-install.sh && ./mvnw -q -pl dataweave-worker,dataweave-master,dataweave-api compile` 零错，Foundational 合入 main

**Checkpoint**: 共享引擎管道就绪 → US2/US3 可开工；US1 全程不受此阶段阻塞

---

## Phase 3: User Story 1 - 面向数据仓库的 SQL 开发（OLAP + Hive）(Priority: P1) 🎯 MVP 【Agent A】

**Goal**: StarRocks/Doris/ClickHouse 分析 SQL + Hive HQL 可在平台创作、试跑、纳入调度；缺引擎 → SKIPPED 不阻塞。

**Independent Test**: 绑定 OLAP/Hive 数据源的 SQL/HIVE 任务经 `dw run` 与服务端试跑：有库真跑逐行日志+行数、无库/无驱动 SKIPPED；不依赖 US2/US3、不依赖 Foundational。

### Tests for User Story 1

- [ ] T011 [P] [US1] `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/HiveTaskExecutorTest.java`：HQL 多语句切分、`SET k=v;` 会话指令与分区写入不误报行数、未绑数据源/连接失败→SKIPPED、语句级错误→失败退出码透传
- [ ] T012 [P] [US1] 扩展 `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/SqlTaskExecutorTest.java`：ClickHouse 逐条 execute 无 false updateCount；StarRocks/Doris `getUpdateCount` 正确回填 `StatementMetric`（feature 025 recordSynced 复用）
- [ ] T012a [P] [US1] 【日志·FR-011a/SC-007】`SqlTaskExecutorTest` 增结果集渲染断言：`SHOW TABLES`/`SELECT` 语句日志含表头+数据行、超 `MAX_RESULT_ROWS` 追加「已截断」、DML 仍报「影响 N 行+耗时」、渲染不回显密码（H2 内存库可造 result set 无需外部依赖）

### Implementation for User Story 1

- [ ] T012b [US1] 【日志·FR-011a/SC-007，修订现状】改 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/SqlTaskExecutor.java`（87-90 行 `hasResultSet` 分支「本期不打印结果集」）：遍历 `ResultSet` 按「表头 + 数据行」`emitLine` 渲染，新增 `MAX_RESULT_ROWS`（如 200）+ 单元格长度截断，超限追加「已截断，仅显示前 N 行」；凭据脱敏（contracts C7.2/C7.4，research D10）。**注意**：此文件也被 T012/T014/HiveTaskExecutor 复用，Agent A 内串行改动避免自撞
- [ ] T013 [US1] 新建 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/HiveTaskExecutor.java`（`@Component`，`type()="HIVE"`）：经 HiveServer2 JDBC 建连（复用 `SqlTaskExecutor` 建连/驱动隔离/连接失败判定语义），HQL 多语句按序 execute、`SET`/分区不当行数，**查询类语句（`SHOW TABLES`/`DESCRIBE`/`SELECT`）复用 T012b 落地的结果集渲染助手（C7.2）→ 依赖 T012b 先完成（同属 Agent A，串行）**，缺连接→`ExecutionResult.skipped`，退出码/失败忠实透传（contracts C3）
- [ ] T014 [US1] 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/localrun/LocalRunMain.java` 的 `selectExecutor` 增 `case "HIVE"`（JDBC ds ref，构造同 SQL 分支用 `IsolatedDriverLoader`）；`buildContext` HIVE 走 `DataSourceRef`（非 engine ref）
- [ ] T015 [US1] 验证前端数据源创建 UI 已渲染 StarRocks/Doris/ClickHouse/Hive（数据驱动，`frontend/components/workspace/views/datasources-view.tsx` 走 `listDatasourceTypes()`）；如缺失才补 `frontend/lib/datasource-type-config.ts`（预期无需改）
- [ ] T016 [P] [US1] 血缘接入验证：HIVE 与 OLAP `SQL` 任务内容喂现有 `SqlTableExtractor`（Calcite）表/列血缘，与 `SQL` 同路径；补 IT/断言（`backend/dataweave-master` 血缘测试），方言不识别最小降级不产错血缘（FR-016）

**Checkpoint**: OLAP SQL + Hive 独立可用、可测（本地+服务端），构成 P1 MVP

---

## Phase 4: User Story 2 - 数据集成/同步任务（DataX + SeaTunnel）(Priority: P2) 【Agent B】

**Goal**: DataX job JSON 与 SeaTunnel 配置可创作、试跑；缺引擎 → SKIPPED。

**Independent Test**: 创建 DATAX/SEATUNNEL 任务，`dw run` 有引擎真跑逐行日志、无 `*_HOME` → SKIPPED；独立于 US1/US3。

**依赖**: Phase 2 Foundational（`EngineSubmitRef` 等）已合入 main。

### Tests for User Story 2

- [ ] T017 [P] [US2] `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/DataXTaskExecutorTest.java`：`buildCommand` 纯函数（`${DATAX_HOME}/bin/datax.py <job>`）、无 `DATAX_HOME`/`datax.py` 不存在→SKIPPED、job 文件缺失→失败、退出码透传、**超时 `destroyForcibly`+`timedOut` 断言（FR-013）**
- [ ] T018 [P] [US2] `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/SeaTunnelTaskExecutorTest.java`：`buildCommand`（`${SEATUNNEL_HOME}/bin/seatunnel.sh --config`）、无 `SEATUNNEL_HOME`→SKIPPED、退出码透传、**超时断言（FR-013）**

### Implementation for User Story 2

- [ ] T019 [P] [US2] 新建 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/DataXTaskExecutor.java`（`type()="DATAX"`）：job JSON 写临时文件→`datax.py` 子进程（`SparkTaskExecutor` 范式：逐行 onLine/超时 destroyForcibly/退出码透传），`static buildCommand`+`static skipReason`（contracts C4），缺环境→skipped，job 缺失→失败
- [ ] T020 [P] [US2] 新建 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/SeaTunnelTaskExecutor.java`（`type()="SEATUNNEL"`）：config 写临时文件→`seatunnel.sh --config` 子进程，同范式，缺 `SEATUNNEL_HOME`→skipped
- [ ] T021 [US2] 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/localrun/LocalRunMain.java` `selectExecutor` 增 `case "DATAX"`/`case "SEATUNNEL"`；`buildContext` 走 `EngineSubmitRef`（engineHome 从 env）
- [ ] T022 [US2] `cli/`：`dw run` 透传 `DATAX`/`SEATUNNEL` 类型（无子模式 flag）；更新 `cli/README.md` 支持类型列表

**Checkpoint**: DataX + SeaTunnel 独立可用、可测；US1+US2 各自独立

---

## Phase 5: User Story 3 - 计算+流式 & 创建入口全类型暴露（Flink + 暴露）(Priority: P2) 【Agent C】

**Goal**: Flink（SQL/jar）可运行；创建入口可选全部任务类型（≥8 种），编辑器语言高亮正确；Spark/Python 从入口直达。

**Independent Test**: 浏览器门验证创建对话框可选 SQL/SHELL/PYTHON/SPARK/HIVE/FLINK/DATAX/SEATUNNEL 且语言高亮正确；FLINK 任务有引擎真跑/无引擎 SKIPPED。

**依赖**: Phase 2 Foundational（消费 `EngineSubmitRef`）。

### Tests for User Story 3

- [ ] T023 [P] [US3] `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/FlinkTaskExecutorTest.java`：`buildCommand` sql 形态（`sql-client.sh -f`）与 jar 形态（`flink run -c <class> app.jar`）、无 `FLINK_HOME`→SKIPPED、jar 缺失→失败、退出码透传、**超时断言（FR-013）**
- [ ] T024 [P] [US3] `frontend` vitest：创建任务类型选择器渲染全 8 类型；`taskTypeToLang` 各类型返回正确语言

### Implementation for User Story 3

- [ ] T025 [P] [US3] 新建 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/FlinkTaskExecutor.java`（`type()="FLINK"`）：`mode` 取自 `EngineSubmitRef.mode`（sql|jar），sql→`sql-client.sh -f`、jar→`flink run [-c]`，`SparkTaskExecutor` 子进程范式，缺 `FLINK_HOME`→skipped、jar 缺失→失败，`static buildCommand`/`skipReason`
- [ ] T026 [US3] 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/localrun/LocalRunMain.java` `selectExecutor` 增 `case "FLINK"`；`buildContext` 消费 `--flink-mode`（T009 已加 flag）合成 `EngineSubmitRef`
- [ ] T027 [US3] `frontend/components/workspace/catalog-tree.tsx`（create-task，:1106-1122）：类型联合 `"SQL"|"SHELL"` 放宽为全类型，选项列表扩为 SQL/SHELL/PYTHON/SPARK/HIVE/FLINK/DATAX/SEATUNNEL（`DropdownSelect` 用 `render`）
- [ ] T028 [US3] `frontend/components/workspace/views/workflow-canvas-view.tsx`（:1194 起）：`taskType` state 类型与内建任务类型选择器同步扩为全类型
- [ ] T029 [US3] `frontend/components/workspace/task-config-panel.tsx`（:120 起）：任务类型选项扩为全类型
- [ ] T030 [P] [US3] `frontend/components/workspace/shared/params-table.tsx` `taskTypeToLang`：增 `HIVE:"sql"`、`FLINK:"sql"`、`DATAX:"json"`、`SEATUNNEL:"text"`（SPARK 已有）
- [ ] T031 [US3] i18n（**唯一改 messages 的任务**）：`frontend/messages/zh-CN.json` + `en-US.json` 增 `ops.nodeDetail.taskTypeSpark/taskTypeHive/taskTypeFlink/taskTypeDataX/taskTypeSeaTunnel` 与 `workflowCanvas.taskType*` 对应键；两 bundle key 集合一致（CI 校验）；数据术语保留英文
- [ ] T032 [US3] `cli/`：`dw run` 支持 `FLINK` + `--flink-mode`；更新 `cli/README.md`

**Checkpoint**: Flink 可用 + 创建入口全类型暴露；三 story 各自独立可用

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 保真、round-trip、闸门、跨工作流合并对齐（依赖三 story 完成）

- [ ] T033 [P] Round-trip 契约测试：push→pull `FLINK`(sql)/`DATAX`/`SEATUNNEL`/`HIVE` 任务到干净目录，断言 `type`/content/`params`（`_flinkMode`）/`datasource`·`targetDatasource` 无丢失（`backend/dataweave-master` filecontract 测试，Constitution II）
- [ ] T034 [P] LocalRun parity 测试：`HIVE/FLINK/DATAX/SEATUNNEL` 本地↔服务端 exitCode/stdout-stderr/超时/SKIPPED 逐项相等（扩 `LocalRunMainParityTest` 或新建，Constitution III C5）
- [ ] T035 验证两网关自动注册：确认 `WorkerExecService.byType` 与 `InProcessTaskExecutionGateway` 均收录 4 个新 `@Component` 执行器；无任何中央注册表被改（contracts C1.2）
- [ ] T035a 【C2·FR-015】验证写闸门+审计：push 一个 `FLINK`/`DATAX`/`SEATUNNEL`/`HIVE` 新类型任务定义，确认经 PolicyEngine 写闸门（`ActionRequest`→`GatedActionService`）并留 `agent_action` 审计轨迹，无旁路（Constitution V）
- [ ] T035b [P] 【C3·FR-017】验证凭据脱敏：断言数据源密码与 DataX/SeaTunnel job 内嵌凭据不明文出现在 ① 可导出任务定义（push/pull 文件）② 运行日志（onLine/tail/结果集渲染）；复用既有 datasource 密文机制
- [ ] T035c 【日志规范·FR-011/011b/SC-007】跨类型日志验证：`SPARK/FLINK/DATAX/SEATUNNEL/PYTHON/SHELL` 运行后日志含起止 banner + 引擎原生 stdout/stderr 逐行透出（不吞不改写）；`SQL/HIVE` 的 `SHOW TABLES` 结果集在日志可见；经 SSE 实时管道与 tail 均可见（复用 [[next-rewrite-proxy-buffers-sse]] 直连后端验证，别用裸 curl 骗测）
- [ ] T036 跨工作流合并对齐（**STOP-and-escalate 硬规则**）：合并 A/B/C worktree 前核对共享面（`ExecutionContext`、i18n bundle、`LocalRunMain`、`DatasourceResolver`）；`git diff`/`git log` 取证，碰撞即停上报，禁静默择一/覆盖他人改动
- [ ] T037 运行 `quickstart.md` 全 7 场景端到端验证：h2/CI 无外部引擎环境验 4 新类型 SKIPPED 闭环（不 fail 构建）；有引擎处真跑抽验；浏览器门验入口全类型（截图存 `tmp/`）
- [ ] T038 [P] 若采纳，更新 `CLAUDE.md` Knowledge Map「任务类型/执行器」行，标注新增 HIVE/FLINK/DATAX/SEATUNNEL 落点

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**：无依赖，立即开始
- **Foundational (P2)**：依赖 Setup；**阻塞 US2、US3**；**不阻塞 US1**
- **US1 (P3)**：仅依赖 Setup（JDBC 路径，独立于 Foundational）→ Agent A 可与 Foundational 并行
- **US2 (P4)**：依赖 Foundational 合入 main → Agent B（B 本人先做 Foundational）
- **US3 (P5)**：依赖 Foundational 合入 main → Agent C
- **Polish (P6)**：依赖 US1+US2+US3 完成

### User Story Dependencies

- **US1 (P1)**：无跨故事依赖；无 Foundational 依赖 → 最先可交付 MVP
- **US2 (P2)**：依赖 Foundational；不依赖 US1
- **US3 (P2)**：依赖 Foundational；消费 B 落的 `EngineSubmitRef`；不依赖 US1

### Within Each Story

- 测试与实现可并行编写；实现须使测试通过（FR-018/019）
- 执行器（`@Component`）自动注册，无需改分发注册表
- Story 完成即可独立试跑验证（`dw run` + 服务端 + 浏览器门）

### Parallel Opportunities

- Setup T002 [P]
- **Agent A（US1）全程与 Foundational 并行**（无依赖交集）
- Foundational 内 T004 [P]（master，不同文件）可与 T003（worker）并行；T005-T008 有序（gateway/scheduler 依赖 T003 类型）
- Foundational 合入后 US2 || US3
- 各 story 内 [P] 测试/执行器为不同文件可并行

---

## Parallel Example: 三 Agent 稳态

```bash
# 阶段一：Agent B 先落 Foundational（T003-T010）到 main，同时 Agent A 起 US1
Agent A: T011-T016 (US1: Hive 执行器 + OLAP 验证 + 血缘)      # 与 Foundational 并行
Agent B: T003-T010 (Foundational: EngineSubmitRef + 接线)     # 先落 main

# 阶段二：Foundational 合入后，B 做 US2、C 做 US3
Agent B: T017-T022 (US2: DataX + SeaTunnel)
Agent C: T023-T032 (US3: Flink + 入口全类型暴露 + i18n)

# 阶段三：主 Claude 收口
Polish: T033-T038 (round-trip / parity / 合并对齐 / quickstart / 注册验证)
```

---

## Implementation Strategy

### MVP First（US1 仅）

1. Phase 1 Setup → 2. Phase 3 US1（Agent A，无需等 Foundational）→ 3. **STOP & VALIDATE**：OLAP SQL + Hive 独立试跑 → 4. 可 demo（P1 MVP）。

### Incremental Delivery

1. Setup +（Agent A 起 US1 || Agent B 落 Foundational）
2. US1 完成 → 独立验证 → demo（MVP）
3. Foundational 合入 → US2（B）、US3（C）并行 → 各自独立验证 → demo
4. Polish 收口（保真/round-trip/合并对齐）

### Parallel Team Strategy（3 外部 Agent）

- Agent A：US1（OLAP+Hive），最先、无阻塞
- Agent B：Foundational（先）+ US2（DataX/SeaTunnel）
- Agent C：US3（Flink + 入口暴露），待 Foundational
- 各自 worktree；`EngineSubmitRef` 契约先行；i18n 仅 C 改；合并前 T036 对齐。

---

## Notes

- [P] = 不同文件、无未完成依赖
- 新执行器一律继承 `AbstractTaskExecutor`、`ExecutionResult.skipped()` 三态、`static buildCommand`/`skipReason` 可单测（contracts/task-executor.md）
- **原则不改 DDL**（`schema_version` 不变）；引擎子模式入 `params_json`/`TaskDoc.params` 自动 round-trip。**唯一例外见 T002a**：若 `task_def.content` 上限不足承载引擎作业体，则扩列 + bump `schema_version`（FR-013a / plan Storage 行）
- 后端长跑测试用 `setsid` 脱离（WSL2 硬规则）；`mvnd` 禁 cache 才算真编译
- 禁覆盖他人 worktree 改动；碰撞 STOP-and-escalate
