# Tasks: 实时任务运维（Streaming Task Ops · 062）

**Input**: Design documents from `specs/062-streaming-task-ops/`（plan.md · spec.md · research.md · data-model.md · contracts/streaming-tasks-api.md · quickstart.md）

**Prerequisites**: 062 分支已 rebase 到 main（含 060 + **061**）；schema 基线 0.15.0。

**Tests**: 包含（plan.md 有 tests/ 段；CLAUDE.md 硬规则「新特性必须有测试，无测试=未完成」）。后端 JUnit5+AssertJ / WebTestClient；前端 vitest + 浏览器验证。

**061 边界更新（相对 research/plan）**：research/plan/contracts 通篇假设「061 未合 main、savepoint 返 503 占位」。**061 现已合入 main**（10 引擎真跑 PASS、Flink detached→JobID→reattach→cancel 经 T038 真跑核实）。因此 062 **直接实现真实 Flink savepoint 触发**（`/jobs/{jobId}/stop?targetDirectory`），单测以 mock Flink REST 覆盖；503 仅作引擎真拒绝/未配置时的合法回退，不再是「待 061」占位。真实引擎端到端取证复用 061 harness，列为收尾可选（TR）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1..US5（映射 spec.md 用户故事）
- 每个任务含精确文件路径

## Path Conventions

- backend 四模块：`backend/dataweave-{api,master,worker,alert}/src/{main,test}/java/com/dataweave/...`
- frontend：`frontend/components/...`、`frontend/lib/...`、`frontend/messages/...`
- schema 单一权威 DDL：`backend/dataweave-api/src/main/resources/schema.sql`

---

## Phase 1: Setup（共享基建）

- [ ] T001 确认 062 分支已 rebase 到 main（`git merge-base --is-ancestor 09ab59f HEAD` 为真）且基线全量构建绿：`cd backend && ./dev-install.sh -pl dataweave-api,dataweave-master,dataweave-worker -am`（零 error 方可继续）
- [ ] T002 新增配置键到 `backend/dataweave-master/src/main/resources/application.yml`（及 h2 profile）：`streaming.checkpoint.retention-count`（默认 3）、`streaming.checkpoint.ttl-hours`（默认 24），绑定到一个 `@ConfigurationProperties("streaming.checkpoint")` StreamingCheckpointProperties（master 模块 infrastructure/config）

**Checkpoint**: 基线可构建、配置就位。

---

## Phase 2: Foundational（阻塞所有 US 的前置）

**⚠️ 完成前任何 US 不得开工。**

- [ ] T003 `backend/dataweave-api/src/main/resources/schema.sql`：新增 `task_checkpoint` 表（字段/约束/索引严格按 data-model.md：id UUID PK、task_instance_id FK NOT NULL 级联删除、ordinal INT NOT NULL、checkpoint_path VARCHAR(1024) NOT NULL、external_ref VARCHAR(255)、status VARCHAR(32) NOT NULL、size_bytes BIGINT、completed_at TIMESTAMP、created_at TIMESTAMP DEFAULT now()；`idx_checkpoint_instance(task_instance_id, ordinal DESC)`、`idx_checkpoint_status(task_instance_id, status)`；DROP TABLE IF EXISTS 前置保证幂等，H2/PG 双兼容）
- [ ] T004 `schema.sql`：`task_instance` 新增 `long_running BOOLEAN NOT NULL DEFAULT FALSE` 快照列（紧邻既有 `task_type` 快照列，:585 附近）
- [ ] T005 `schema.sql`：`schema_version` 升 **0.15.0 → 0.16.0**（文件头注释 + 单行表 INSERT，描述「062 实时任务运维：task_checkpoint 表 + task_instance.long_running 快照列」）；三处一致（DB 行/文件头/项目版本）
- [ ] T006 实例创建时快照 `long_running`：定位实例落库路径（master 调度器/实例工厂，从 `task_def.long_running` 取值），把 `long_running` 写入 `task_instance` INSERT；无对应 task_def 时默认 FALSE
- [ ] T007 [P] `OpsService.rerunInstance` 原生 SQL UPDATE（OpsService.java:645-649 附近）加入 `long_running` 重置（值取自 task_def，随实例 rerun 保持与任务定义一致），防 rerun 后残留旧值（data-model.md「必改点」）
- [ ] T008 [P] Checkpoint 领域模型 + 仓储：`backend/dataweave-master/src/main/java/com/dataweave/master/domain/Checkpoint.java`（record，字段同 data-model.md）+ `.../infrastructure/CheckpointRepository.java`（JdbcTemplate：insert、`listByInstance(instanceId)` ordinal DESC、`findById`、`countByInstance`、`markExpired(ids)`；无业务重试/终态副作用）
- [ ] T009 [P] i18n 错误码骨架：`backend` `Messages` 资源加 `streaming.not_long_running` / `streaming.checkpoint.invalid` / `streaming.savepoint.unavailable` / `streaming.instance.not_resumable`（agent locale MessageFormat）；前端 `frontend/messages/zh-CN.json` + `en-US.json` 加 `streaming.*` UI 命名空间占位键（两 bundle 等键，CI 校验）

**Checkpoint**: 表 + 列 + 快照 + 仓储 + 配置 + i18n 就位；各 US 可并行开工。

---

## Phase 2A: 060 长驻链路缺口闭合（Option C 前置，**执行序在 Phase 3 之前**）

**⚠️ 背景（spec Clarifications 2026-07-11 代码核实）**：`task_def.long_running` 服务端不可创作、下发不传播 → 服务端永不产生 long_running 实例，面板恒空。Option C 并入最小「创作→下发」接线，使实时任务经产品链路真实可创建、可下发为 detached 长驻。**不做完整创作 UX**。

### 创作侧（授权 long_running 并持久化）

- [ ] T050 [P] `backend/dataweave-master/.../domain/TaskDef.java`：新增 `Boolean longRunning` 字段 + getter/setter（Spring Data JDBC 映射 `long_running` 列，additive；既有 save 站点 load-then-save 保值、新建默认 null≡false）
- [ ] T051 `backend/dataweave-master/.../filecontract/dto/TaskDoc.java`：record 新增 `Boolean longRunning` 字段（任务文件字段）；`TaskMapper`（toDoc 读 `task.getLongRunning()`、YAML serialize `putIfPresent("longRunning", ...)`、parse `optionalBool(raw,"longRunning",...)`、toEntity `task.setLongRunning(doc.longRunning())`）四处双向映射；`CURRENT_FORMAT_VERSION` 视需要（新增可选字段向后兼容，不必升）
- [ ] T052 `backend/dataweave-master/.../application/ProjectSyncService.java`：push 映射 insert 分支（:816 附近）+ update 分支（:841 附近）复制 `long_running`（`srv.setLongRunning(localTask.getLongRunning())`）；`compareTaskFields`（:453 变更检测）纳入 long_running 使改动可被 diff 识别

### 下发侧（服务端下发生效为 detached 长驻）

- [ ] T053 `backend/dataweave-master/.../application/TaskExecutionGateway.java`：`DispatchCommand` record 新增 `boolean longRunning` 字段（+javadoc）
- [ ] T054 `backend/dataweave-master/.../application/SchedulerKernel.java`：claim SELECT（:412 附近）加 `ti.long_running`（读快照列，免 JOIN）→ 映射到 row → `new DispatchCommand(...)`（:358）传 `r.longRunning`
- [ ] T055 `backend/dataweave-api/.../infrastructure/WorkerNodeExecGateway.java`（distributed WebClient body）+ `DistributedTaskExecutionGateway.java`：下发 body 加 `longRunning` key
- [ ] T056 `backend/dataweave-worker/.../interfaces/WorkerExecController.java`：读 `body.get("longRunning")` → 引擎任务用 `EngineSubmitRef` **9 参构造**传真 longRunning（:129/:160 两处，替换 7 参兼容构造）；`external_job_handle` 若 body 携带（reattach）一并透传
- [ ] T057 `backend/dataweave-api/.../infrastructure/InProcessTaskExecutionGateway.java`（all-in-one）：构造 ExecutionContext 时传播 `cmd.longRunning()`（与 distributed 语义一致）

### 缺口闭合验证

- [ ] T058 [P] 测试：`ProjectSyncServiceTest`/`TaskMapperTest` 覆盖 long_running push/pull 往返保真；`WorkerExecController` 引擎任务 longRunning 传播（9 参构造命中）；`SchedulerKernel` claim 读 long_running 并入 DispatchCommand。真跑核验：一个 `long_running=true` 的 FLINK 任务经服务端下发走 detached 分支、写 external_job_handle（复用 061 Flink harness 或 mock，起不来记 BLOCKED）

**Checkpoint**: 服务端可创建 long_running 任务并下发为 detached 长驻、写 external_job_handle → 面板有真实数据源。

---

## Phase 3: User Story 1 - 实时任务独立运维视图（P1）🎯 MVP

**Goal**: OpsView 追加与「任务实例」并列、紧随其后的独立「实时任务」面板，只列 `long_running` 实例，带状态与已运行时长。

**Independent Test**: 运维进运维中心，在任务实例 tab 之后看到独立「实时任务」tab，仅含 long_running 实例，每行有状态 + 连续运行时长，视觉可区分批任务。

### Tests for US1

- [ ] T010 [P] [US1] `backend/dataweave-api/src/test/java/com/dataweave/api/interfaces/StreamingTasksWebTest.java`：WebTestClient 测 `GET /api/ops/streaming-tasks` — 只返 long_running=TRUE、分页、state/keyword 过滤、项目隔离（X-Project-Id）；先失败
- [ ] T011 [P] [US1] `frontend/components/workspace/views/ops/streaming-tasks-panel.test.tsx`：vitest — 渲染行含状态 badge + 运行时长；空态；先失败

### Implementation for US1

- [ ] T012 [P] [US1] DTO `backend/dataweave-api/.../interfaces/dto/StreamingTaskRow.java`（字段同 contracts：instanceId/taskDefId/taskName/state/longRunning/startedAt/durationSeconds/businessAttempt/lastCheckpoint{id,status,completedAt}/externalJobHandlePresent/workerOnline；datetime UTC ISO 带 Z）
- [ ] T013 [US1] `OpsService.listStreamingTasks(projectId, page, size, state, keyword)`：查 long_running 实例（server 分页），关联最近 SUCCESS 检查点（CheckpointRepository）+ workerOnline（复用 NodeHealth/Fleet 判据）+ durationSeconds（now − started_at）
- [ ] T014 [US1] `OpsController` 加 `GET /api/ops/streaming-tasks`（映射 listStreamingTasks，项目隔离 + Bearer，只读无门控）
- [ ] T015 [US1] `frontend/components/workspace/views/ops-view.tsx`：`TabId` 联合类型 + `TAB_ORDER` 追加 `streamingTasks`（紧随 periodic/任务实例后）+ 渲染分支（:64-77 附近）+ OpsTabBar 标签文案（next-intl `streaming.*`）
- [ ] T016 [US1] 新建 `frontend/components/workspace/views/ops/streaming-tasks-panel.tsx`（fork `periodic-instances-panel.tsx` 模式：DataTable + server fetcher 打 `/api/ops/streaming-tasks` + useRefreshSchedule + 状态 badge + 已运行时长列 + keyword/state 过滤；视觉与批实例区分）
- [ ] T017 [US1] i18n：`frontend/messages/{zh-CN,en-US}.json` 补 `streaming.*` UI 键（tab 名、列头、状态、空态），两 bundle 等键

**Checkpoint**: US1 独立可用 —— 运维能在独立面板集中看到所有实时任务及状态/时长。

---

## Phase 4: User Story 2 - 实时任务最新日志（P1）

**Goal**: 从实时任务行进入该实例「最新日志」，复用既有 SSE 近实时刷新，长期运行（7 天）不退化。

**Independent Test**: 打开一个运行中实时任务日志，数秒内刷新最新输出；断连恢复自动续接不重播。

### Tests for US2

- [ ] T018 [P] [US2] `streaming-tasks-panel.test.tsx` 追加：点击行「日志」动作打开日志视图并订阅 `/api/ops/instances/{id}/logs/stream`（mock EventSource 断言订阅 URL + Last-Event-ID 续传）；先失败

### Implementation for US2

- [ ] T019 [US2] 面板行接入「最新日志」入口：复用既有 `InstanceLogView` + `useEventSource`（drawer/tab 内打开该实例日志流），无需新端点
- [ ] T020 [US2] 长期连接不退化核查/加固：审查 `OpsController` logs/stream（:651-700）对长期 RUNNING long_running 的非运行态门控（:666-671）与 keep-alive，确保连续运行实例不被误闭流；如需，放宽门控使 RUNNING long_running 持续可订阅（不破坏批任务语义）
- [ ] T021 [US2] i18n：面板日志入口/状态提示键（两 bundle 等键）

**Checkpoint**: US1 + US2 可用 —— 集中视图 + 最新日志。

---

## Phase 5: User Story 3 - 优雅停止实时任务并保留进度（P1）

**Goal**: 对运行中实时任务发起「停止」→ 尽力保留检查点（savepoint）→ CAS STOPPED + 写 task_checkpoint；与「强制终止」（kill/CANCEL 无 savepoint）显式区分；savepoint 不可用时明确提示改用强制终止。

**Independent Test**: 对运行中实时任务发起「停止」→ 进入「已停止且留有可恢复检查点」；强制终止作为独立兜底仍可用；savepoint 失败给出明确提示。

### Tests for US3

- [ ] T022 [P] [US3] `backend/dataweave-master/src/test/java/com/dataweave/master/application/OpsServiceTest.java`：`stopWithSavepoint` — mock Flink REST 成功 → 写 SUCCESS 检查点 + CAS RUNNING→STOPPED；savepoint 失败 → `streaming.savepoint.unavailable`，实例不误置终态；滚动保留 N（写第 N+1 个后最早的被淘汰/EXPIRED）；先失败
- [ ] T023 [P] [US3] `StreamingTasksWebTest` 追加：`POST /api/ops/streaming-tasks/{id}/stop` → 202 accepted+checkpointId；非 RUNNING → 409；savepoint 不可用 → 503；先失败

### Implementation for US3

- [ ] T024 [US3] `backend/dataweave-worker/.../infrastructure/FlinkTaskExecutor.java`：新增 savepoint 触发方法（Flink REST `POST /jobs/{jobId}/stop` body `{targetDirectory}` → 返回 request-id → 轮询 `GET /jobs/{jobId}/savepoints/{triggerId}` 至 COMPLETED，拿 savepointPath；复用 061 已验证的 REST 客户端与 detached 句柄 `{jobId, restEndpoint}`），引擎未配置/拒绝 → 抛可识别异常（映射 503）
- [ ] T025 [US3] `CheckpointService`（master application）：`recordSuccess(instanceId, path, ...)` 写 task_checkpoint + 滚动保留 N（超出按 ordinal 淘汰/EXPIRED，读 StreamingCheckpointProperties）；并发 IN_PROGRESS 唯一性防护
- [ ] T026 [US3] `OpsService.stopWithSavepoint(instanceId)`：**异步**（发起 savepoint → 轮询 → 完成后 CAS `RUNNING→STOPPED` + CheckpointService.recordSuccess）；失败明确回错（US3 AC3）；`audit("STOP_WITH_SAVEPOINT")` L1 直执不经审批
- [ ] T027 [US3] `OpsController` 加 `POST /api/ops/streaming-tasks/{id}/stop`（202/409/503 契约按 streaming-tasks-api.md）；`killTask`（强制终止）语义不变，面板「强制终止」按钮复用既有 kill 端点（FR-006）
- [ ] T028 [US3] 前端 `frontend/lib/instance-actions.ts` 加 `stop`（保留进度）动作；面板 `streaming-tasks-panel.tsx` 增「停止（保留进度）」与「强制终止」两个显式区分的操作 + 后果提示 + 「停止中（保存检查点…）」进行态反馈
- [ ] T029 [US3] i18n：停止/强制终止/savepoint 不可用提示键（后端 BizException 码 + 前端 next-intl，两 bundle 等键）

**Checkpoint**: US1+US2+US3 可用 —— 集中视图 + 最新日志 + 优雅停止保留进度。

---

## Phase 6: User Story 4 - 从检查点续跑（恢复）实时任务（P2）

**Goal**: 从保留的多个检查点中选一个「恢复续跑」已停止/异常实例，从该点之后继续、不重做；无有效检查点则明确告知并仅提供全量重跑。

**Independent Test**: 停止（留检查点）→ 恢复续跑并选检查点 → 从该点后继续；无有效检查点 → 明确「无可用检查点」+ 仅全量重跑。

### Tests for US4

- [ ] T030 [P] [US4] `backend/dataweave-master/src/test/java/com/dataweave/master/application/InstanceStateMachineTest.java`：`casResumeFromCheckpoint` — `STOPPED/SUSPENDED→WAITING` 单赢（并发只一个成功）、不清 external_job_handle、060 不变量兼容（attempt 纯栅栏 / business_attempt 双拆不受影响）；先失败
- [ ] T031 [P] [US4] `OpsServiceTest` 追加：`resumeFromCheckpoint` 有效 ckpt → WAITING + 记录所选 ckpt；无效/过期 → `streaming.checkpoint.invalid`；实例状态非 {STOPPED,SUSPENDED} → `streaming.instance.not_resumable`；先失败
- [ ] T032 [P] [US4] `StreamingTasksWebTest` 追加：`GET /streaming-tasks/{id}/checkpoints`（ordinal DESC + resumable 标记）、`POST /streaming-tasks/{id}/resume`（200/404/409 契约）；先失败

### Implementation for US4

- [ ] T033 [P] [US4] DTO `CheckpointDTO`（id/ordinal/status/checkpointPath/completedAt/sizeBytes/expired/resumable）+ `ResumeRequest`（checkpointId），`backend/dataweave-api/.../interfaces/dto/`
- [ ] T034 [US4] `InstanceStateMachine.casResumeFromCheckpoint(instanceId, from∈{STOPPED,SUSPENDED}→WAITING)`：乐观 CAS `WHERE state IN (...)`，不清 handle，记录所选 checkpointId（写 task_instance 引用列或 task_checkpoint 关联，按 data-model.md 定夺——选实例侧 `resume_checkpoint_id` 引用列，随 schema 0.16.0 一并加，若加列则回补 T004/T005）
- [ ] T035 [US4] `OpsService.listCheckpoints(instanceId)`（校验 long_running + 项目隔离，返回 CheckpointDTO[] 带 resumable=SUCCESS&&!expired）+ `OpsService.resumeFromCheckpoint(instanceId, checkpointId)`（校验 ckpt 有效 → casResumeFromCheckpoint → 唤醒调度；无有效 ckpt → 回错引导全量重跑 FR-008；`audit("RESUME_FROM_CHECKPOINT")` L1）
- [ ] T036 [US4] `OpsController` 加 `GET /api/ops/streaming-tasks/{id}/checkpoints` + `POST /api/ops/streaming-tasks/{id}/resume`（契约按 streaming-tasks-api.md）
- [ ] T037 [US4] 前端 `instance-actions.ts` 加 `resume`；面板加「恢复续跑」+ 检查点选择 UI（列出 checkpoints，禁用不可续跑项）；无有效检查点时禁用续跑并引导「全量重跑」（复用既有 rerun 端点）
- [ ] T038 [US4] i18n：续跑/检查点选择/无可用检查点/全量重跑引导键（两 bundle 等键）

**Checkpoint**: US1-US4 可用 —— 停止后可从检查点续跑（不丢不重）。

---

## Phase 7: User Story 5 - 健康监控与 SUSPENDED 一等化（P2）

**Goal**: 面板展示健康信号（进度/堆积延迟、最近检查点状态与时效、重启情况）；SUSPENDED 作为一等状态明确标识，并可直接从面板恢复（优先续跑、无有效 ckpt 降级全量重跑）或终止。

**Independent Test**: 面板展示健康信号；SUSPENDED 实例被明确标识而非误显运行中/失败，且可直接恢复或终止。

### Tests for US5

- [ ] T039 [P] [US5] `backend/dataweave-master/src/test/java/com/dataweave/master/application/SchedulerMetricsTest.java`（或既有 metrics 测试追加）：`scheduler.streaming.checkpoint.total`（按 status 标签）、`scheduler.streaming.recovering` gauge 注册并被 sampleGauges 刷新；先失败
- [ ] T040 [P] [US5] `streaming-tasks-panel.test.tsx` 追加：SUSPENDED 行以一等 badge 呈现；健康列（检查点时效/重启次数）渲染；SUSPENDED 行提供「恢复」「终止」动作；先失败

### Implementation for US5

- [ ] T041 [US5] `SchedulerMetrics`：新增 `scheduler.streaming.checkpoint.total`（按 status 分标签）+ `scheduler.streaming.recovering`（resumeFromCheckpoint 后 WAITING/DISPATCHED 实例数）gauge，注册进 sampleGauges 周期刷新（指标不可变约定：新增非 UPDATE）
- [ ] T042 [US5] `StreamingTaskRow`/`listStreamingTasks` 补健康信号：重启情况（business_attempt / infra_redispatch_count）、最近检查点状态+时效（age vs ttl）、workerOnline+state=RUNNING 表达「断连但引擎侧可能仍活」（状态漂移，Edge Case ⑥）；进度/堆积延迟首版覆盖「有则展示」（引擎返回则填，否则留空，spec 允许迭代）
- [ ] T043 [US5] 面板 SUSPENDED 一等化：`streaming-tasks-panel.tsx` SUSPENDED 显著 badge + 直接「恢复」（优先续跑，无有效 ckpt 降级 rerun 全量重跑）「终止」动作（复用 US4 resume + kill）；健康列（检查点时效/重启/断连漂移标识）
- [ ] T044 [US5] i18n：SUSPENDED/健康信号列头/断连漂移提示键（两 bundle 等键）

**Checkpoint**: US1-US5 全部独立可用。

---

## Phase 8: Polish & Cross-Cutting

- [ ] T045 [P] i18n 键一致性：`frontend/messages/{zh-CN,en-US}.json` 两 bundle `streaming.*` 等键校验（`pnpm` i18n 校验脚本 / CI 门），每个 `t("streaming.x")` 可静态解析
- [ ] T046 [P] 全量构建 + 测试绿：`cd backend && ./mvnw -pl dataweave-master,dataweave-api,dataweave-worker -am test`（WSL2 setsid 脱离 + 单次秒回轮询；build-cache 关闭确认 Tests run:N>0）；`cd frontend && pnpm typecheck && pnpm test`
- [ ] T047 [P] `CLAUDE.md` Knowledge Map 加「062 实时任务运维」条目（面板/新表/新方法/状态迁移锚点）
- [ ] T048 schema_version 三处一致性核查（DB 行 / 文件头 / 项目版本 = 0.16.0）+ H2 与 PG 双 profile 起库 DDL 兼容验证
- [ ] T049 quickstart.md 走查验证（本地 dw run / 面板 / 停止 / 续跑 路径按 quickstart 步骤跑通，浏览器验证渲染）
- [ ] TR （可选收尾，真实引擎取证）复用 061 harness（`verification/061-task-types/` Flink compose）对 `stopWithSavepoint`/`resumeFromCheckpoint` 做真实 Flink savepoint→续跑端到端取证；WSL2 单机错峰起防 OOM；起不来记 BLOCKED 不冒充（沿用 061 三态硬门精神）

---

## Dependencies & Execution Order

### Phase 依赖

- **Setup (P1)** → **Foundational (P2)** 阻塞所有 US → **US1..US5 (P3-P7)** → **Polish (P8)**
- Foundational 完成后，US1/US2/US3/US4/US5 可并行（不同文件为主）；建议按优先级 P1(US1→US2→US3) → P2(US4→US5) 增量交付。

### User Story 依赖

- **US1**（视图）：仅依赖 Foundational；其他故事的操作挂载在 US1 面板上（US2-US5 的前端动作复用 US1 面板，但后端可独立测试）
- **US2**（日志）：依赖 Foundational；前端入口挂 US1 面板（后端复用既有 SSE，无新端点）
- **US3**（优雅停止）：依赖 Foundational（CheckpointService 写路径）；FlinkTaskExecutor savepoint 为 worker 侧独立改动
- **US4**（续跑）：依赖 Foundational + US3 产出的检查点（测试可用夹具检查点独立验证）；casResumeFromCheckpoint 为状态机独立改动
- **US5**（健康+SUSPENDED）：依赖 Foundational；SUSPENDED 恢复复用 US4 的 resume + 既有 rerun/kill

### 关键顺序约束

- 状态机/服务：Model(T008) → Repository(T008) → Service(T013/T025/T035) → Controller(T014/T027/T036)
- schema(T003-T005) 必须在任何后端服务任务前
- 若 T034 采用实例侧 `resume_checkpoint_id` 引用列，须回补 schema（T004/T005 同批），避免二次升版

### 并行机会

- Foundational 内 T007/T008/T009 [P] 可并行
- 各 US 的测试任务 [P] 可先并行落地（先失败）
- US1-US5 后端服务/前端面板改动多为不同文件，Foundational 完成后可多路并行
- Polish 内 T045/T046/T047 [P] 可并行

---

## Implementation Strategy

### MVP First（US1）

1. Phase 1 Setup → 2. Phase 2 Foundational（关键，阻塞全部）→ 3. Phase 3 US1 → **停下验证**：独立面板集中展示实时任务 → demo。

### Incremental Delivery

Setup+Foundational → US1（MVP：集中视图）→ US2（最新日志）→ US3（优雅停止保留进度）→ US4（检查点续跑）→ US5（健康+SUSPENDED 一等化）。每个故事独立加值、不破坏前序。

### Notes

- [P] = 不同文件、无未完成依赖
- 每个 US 独立可完成、可测试；测试先失败再实现（TDD）
- 每个任务/逻辑组后提交
- 后端每次编辑后 `./mvnw -q -pl <module> compile` 零 error；前端 `pnpm typecheck` 零 error
- 写操作全 L1 直执 + audit（不经 GatedActionService 审批，FR-011）；不破坏 060 的 kill/rerun 存量语义与七红线不变量
