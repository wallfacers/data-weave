# Tasks: 调度器节点容错闭环（节点剔除·任务转移·不丢不失败）

**Input**: `specs/060-node-eviction-failover/`（plan.md · spec.md · research.md · data-model.md · contracts/ · quickstart.md）

**Tests**: 纳入（CLAUDE.md 硬规则「新功能必须有测试，无测试=未完成」）。

**Organization**: 按 User Story 分阶段，US1=切片A(MVP) / US2=切片B / US3=切片C。

---

## 收口状态（2026-07-10 定时收口，Claude 全权评审）

- **T001–T038 已实现并提交**（两个外部 agent，commit a53dfc9→543e434）。七条硬红线核验通过：`attempt` 纯栅栏零改动（`isCurrentDispatch` `COALESCE(attempt,0)<=cmd.attempt` 未回退）· `business_attempt` 双拆 · `SlotManager` 单点门 · `FleetService` 节点级即时回收(I1) · `HeartbeatReporter` 真 `runningInstanceIds` + self-fence 不变量断言 · `SUSPENDED` 态 · schema 0.15.0 三处一致。
- **T040 编译门禁**：master+worker+api `-am compile` 零错误 ✅
- **T042 回归**：144 项单测/集成测试真跑全绿（禁 build-cache）——SlotManager 8 / NodeHealthService 4 / LeaseReaper 3 / RetryService 3 / WorkerReportService 8 / StuckInstanceSweeper 5 / NodeRecoveryWake 2 / TimeoutSweeper 10 / FleetService 8 / InstanceStateMachine 8+3 / SchedulerKernel* 38 / WorkerExecService 10+4 / FlinkTaskExecutor 30。`isCurrentDispatch` 栅栏 + `attempt` 单调未回退，048/049/051 稳态不退化 ✅
- **T039 文档**：CLAUDE.md Knowledge Map 补 060 行 ✅
- **✅ Flink long_running reattach（T035/T036）已转真实现**（2026-07-10 追加）：去桩——`FlinkJobStatusFetcher`（REST GET /jobs/{jobId}，可注入测试）+ `pollUntilTerminal`（状态映射 FINISHED/FAILED/CANCELED，可中断，连续失败上限兜底）+ `executeReattach`（探测存在→轮询不重提交，不存在→按业务重试重提交）+ `writeHandle`（`CurrentExecution` ThreadLocal 绑定实例 id → `HttpExternalJobHandleWriter` POST `/api/cluster/instances/{id}/external-job-handle`（cluster-token 鉴权）→ `WorkerReportService.recordExternalJobHandle` 仅活跃态落库）。有界 Flink exit-code/stdout 保真不变。FlinkTaskExecutorTest 33 项绿（新增 detached+轮询+reattach+job消失重提+句柄回写）。⚠️ 真 Flink 集群端到端仍需有集群环境（单测用假 REST fetcher 驱动状态机，逻辑完备）。
- **✅ 全 4 模块（master/worker/alert/api）完整测试套件真跑全绿**（追加，禁 build-cache）。**做 T041 全量构建时发现并修复 3 处 060 遗留缺陷**（scoped 编译/测试漏掉）：① **alert 模块编译断裂**——060 给 `AlertSignal.Type` 加 `TASK_SUSPENDED`/`NODE_STARVATION` 但 `AlertEvaluator`/`AlertSignalListener` 穷举 switch 未更新 → `dataweave-api`（依赖 alert，master 镜像源）全量构建断裂（`-pl master,worker,api -am` 不含 alert 才漏）；② **api `LeaseReaperTest.reap_noRetryMax_staysFailed`** 断言旧 attempt 混用语义（infra→FAILED），与 060 硬不变量冲突 → 改断言 infra→WAITING；③ **api `SchedulerPreemptionTest`** seed 的 node-pre 无 `last_heartbeat` → 被 060 SlotManager 新门排除 → 补新鲜心跳。另修 `SchedulerKernelReadinessTest` `currentTimeMillis` 主键碰撞 flake + `NodeDetailEndpointTest` 预存 stale（3a0f8e8 seed ECHO→SHELL 未同步）。
- **⚠️ T041 证伪式真跑门禁（distributed 2 master+2 worker + 故障注入 SC-001~009）**：docker 环境真跑（进行中/需 docker distributed 起 PG+redis+neo4j+2master+2worker）。代码级栅栏不变量已守；单测/集成 4 模块全绿。

## Format: `[ID] [P?] [Story] Description with file path`

- **[P]**: 可并行（不同文件、无未完成依赖）
- 路径以 `backend/` 为根（`m/` = `dataweave-master/src/main/java/com/dataweave/master`，`w/` = `dataweave-worker/src/main/java/com/dataweave/worker`）

---

## Phase 1: Setup（共享配置与常量）

- [ ] T001 [P] 在 `backend/dataweave-api/src/main/resources/application.yml` `scheduler:` 下新增配置键（含注释与保守默认）：`node.stabilization-window-ms:15000`、`node.quarantine-threshold:3`、`node.quarantine-backoff-ms:30000`、`infra-redispatch-max:10`、`stuck-wait-alert-ms:300000`、`worker.self-fence-grace-ms:20000`（对齐 data-model §4）
- [ ] T002 [P] 在 `m/domain/InstanceStates.java` 新增状态常量 `SUSPENDED` 与失效原因常量 `INFRA_SUSPENDED`（若原因码集中管理）；确认 `WAITING/DISPATCHED/RUNNING` 等既有常量不动

---

## Phase 2: Foundational（阻塞所有 US 的地基）

**⚠️ 完成前任何 US 不能开工**

- [ ] T003 在 `backend/dataweave-api/src/main/resources/schema.sql`：① `worker_nodes` 加 `incarnation_since TIMESTAMP`、`consecutive_infra_failures INTEGER DEFAULT 0`、`quarantined_until TIMESTAMP`；② `task_instance` 加 `business_attempt INTEGER DEFAULT 0`、`infra_redispatch_count INTEGER DEFAULT 0`、`external_job_handle VARCHAR(512)`；③ `task_def` 与 `task_def_version` 加 `long_running BOOLEAN DEFAULT FALSE`；④ `state` 注释补 `SUSPENDED`、`failure_reason` 注释补 `INFRA_SUSPENDED`（H2/PG 通用 DDL，均带默认值）
- [ ] T004 bump `schema_version`：新增 INSERT `('0.15.0', …, '060 节点容错闭环：worker_nodes 健康三列 + task_instance business_attempt/infra_redispatch_count/external_job_handle + task_def long_running + SUSPENDED 态')`，并同步文件头 `Schema Version` 注释（DB 行/文件头/项目版本三处一致）。**部署语义注记（修 analyze M1）**：新列默认 0，升级瞬间在飞实例的既有 `attempt` 不再兼作业务重试、`business_attempt` 从 0 起 → 这些在飞实例获全新业务重试预算；在 quickstart/PR 描述里显式声明此一次性语义跳变（无存量迁移，符合 constitution）
- [ ] T005 [P] `m/domain/WorkerNode.java` 加字段 `incarnationSince/consecutiveInfraFailures/quarantinedUntil` + getter/setter（对齐 `@Table("worker_nodes")` 列名映射）
- [ ] T006 [P] task_instance 领域/映射层（`m/domain` 对应实体 + `TaskMapper`/row mapper）加 `businessAttempt/infraRedispatchCount/externalJobHandle` 字段与读写；确认 `attempt` 语义注释更新为「纯下发纪元栅栏」
- [ ] T007 `m/application/InstanceStateMachine.java` 新增 CAS 助手（不改动 `casDispatch`/`isCurrentDispatch`/`attempt` 既有语义）：`casSuspend(id, from)`（→SUSPENDED）、`incrementBusinessAttempt(id)`、`incrementInfraRedispatch(id)`（原子 `SET x=x+1`）、`casRequeueInfra(id, from)`（回 WAITING 清 worker/租约，**不动 business_attempt**，`infra_redispatch_count+1`）

**Checkpoint**: 地基就绪，US1/US2/US3 可推进。

---

## Phase 3: User Story 1 - 坏节点剔除 + 任务转移（Priority: P1）🎯 MVP

**Goal**: 假/坏/未稳定节点不承接任务；已在坏节点的任务自动转移到健康节点；基础设施回收不烧业务重试、不判死；毒任务达上限挂起。

**Independent Test**: 注入抖动假节点 + 让某节点连续下发失败/重启，验证假节点 0 承接、任务转移成功且 `business_attempt` 不变、毒任务转 SUSPENDED（quickstart US1）。

### Tests for US1 ⚠️（先写、先红）

- [ ] T008 [P] [US1] `SlotManagerTest`：心跳不新鲜/纪元未过稳定窗/`quarantined_until>now` 三类各被排除，健康节点放行（`m/.../SlotManagerTest.java`）
- [ ] T009 [P] [US1] `NodeHealthServiceTest`：`recordInfraFailure` 原子自增；跨阈值置 `quarantined_until=GREATEST(...)`；两线程并发更新结果与串行等价；`clearOnSuccess` 复位
- [ ] T010 [P] [US1] `LeaseReaperTest`：infra 回收 `business_attempt` 不变、`infra_redispatch_count+1`、清 `worker_node_code`；`WORKER_RESTART` 走真 incarnation 比对；超 `infra-redispatch-max` → SUSPENDED
- [ ] T011 [P] [US1] `RetryServiceTest`：业务重试改比 `business_attempt<=retry_max`；PREEMPTED 不计次（回归）；infra 回收不经此路
- [ ] T012 [P] [US1] `WorkerReportServiceTest`：`reportFailed` 仅当 `started_at≠null` 才 `business_attempt+1`；耗尽判 FAILED

### Implementation for US1

- [ ] T013 [US1] 新增 `m/application/NodeHealthService.java`：`recordInfraFailure(nodeCode)`（原子自增 + 跨阈值 `quarantined_until=GREATEST(COALESCE(quarantined_until,now),now+backoff)`）、`clearOnSuccess(nodeCode)`（复位）、`markIncarnationChanged(nodeCode)`（置 `incarnation_since=now`）——全部纯原子/单调 SQL（FR-003/004/006）
- [ ] T014 [US1] `m/application/SlotManager.java`：`findByStatus("ONLINE")` 收口处追加三谓词（心跳新鲜 + `incarnation_since<=now-稳定窗` + 未隔离），`availableForNormal/Test()`、`snapshotOnline()` 同源（FR-001/002/005）
- [ ] T015 [US1] `m/application/FleetService.java`：`report()` 检测 incarnation 变化时——① 调 `NodeHealthService.markIncarnationChanged`（落 `incarnation_since`）；② **实装 `handleWorkerRestart`**（现为空壳）：CAS 回收该 `worker_node_code` 下全部 DISPATCHED/RUNNING 实例为 infra（`WORKER_RESTART`：`casRequeueInfra` + `incrementInfraRedispatch` + `recordInfraFailure`，超上限 `casSuspend`）；新注册节点也落 `incarnation_since=now`（FR-002/007；修 analyze I1 即时节点级回收）
- [ ] T016 [US1] `m/application/LeaseReaper.java`：`failWithRetry` 拆分——infra 类走 `casRequeueInfra` + `incrementInfraRedispatch` + `NodeHealthService.recordInfraFailure`，**不**经 `RetryService`；`infra_redispatch_count>上限` → `casSuspend` + 发告警；`reapLostInstances`（租约过期+节点 OFFLINE→`WORKER_LOST`）作为兜底，`reapRestartedInstances` 保留为兜底扫描（不再依赖 per-instance 纪元比对，主路径已由 T015 节点级即时回收承担）（FR-007/008/012/019）
- [ ] T017 [US1] `m/application/RetryService.java`：`shouldRetry` 改比 `business_attempt<=retry_max`（业务失败路径专用）；`WorkerReportService.reportFailed` 在 `started_at≠null` 时 `incrementBusinessAttempt` 后再判重试/终态（FR-009）
- [ ] T018 [US1] `m/application/SchedulerKernel.java`：下发失败（`DispatchException`）的 `casRequeue` 改为 infra 语义（`casRequeueInfra` + `incrementInfraRedispatch` + `recordInfraFailure`），不烧业务重试（FR-008/010）
- [ ] T019 [US1] worker 成功回报路径（`WorkerReportService.reportFinished`）调用 `NodeHealthService.clearOnSuccess(nodeCode)`（节点成功执行一次 → 解除熔断计数，FR-004）

**Checkpoint**: 假节点 0 承接、坏节点上任务转移不烧业务重试、毒任务挂起——MVP 可独立交付验证。

---

## Phase 4: User Story 2 - 无节点安全等待 + 恢复抽干（Priority: P2）

**Goal**: 无健康节点时任务安全等待（不判死不烧计数）；节点恢复主动唤醒抽干；长期卡住告警不判死。

**Independent Test**: 停全部 worker→任务停 WAITING 无 FAILED；恢复一个 worker→一个轮询周期内抽干、无残留（quickstart US2）。

### Tests for US2 ⚠️

- [ ] T020 [P] [US2] `StuckInstanceSweeperTest`：无节点等待 > 阈值发告警且不判终态；SUSPENDED 挂起发告警
- [ ] T021 [P] [US2] 恢复唤醒集成测试：节点由隔离/离线→可用时发 `WAKE_CHANNEL`，WAITING 实例被重新认领（`m/.../NodeRecoveryWakeTest.java`）

### Implementation for US2

- [ ] T022 [US2] `m/application/FleetService.java`：节点由不可用→可用边界（status 恢复 ONLINE / 纪元跨稳定窗）时 `eventBus.publish(WAKE_CHANNEL,"node-recovered")`（FR-014）
- [ ] T023 [US2] 新增 `m/application/StuckInstanceSweeper.java`（`@Scheduled`）：① 隔离到期（`quarantined_until<=now`）节点稳定后发唤醒；② WAITING 无候选且滞留 > `stuck-wait-alert-ms` → `AlertSignal(NODE_STARVATION)`；③ 巡检 SUSPENDED 实例发/续告警（FR-012/014/015）
- [ ] T024 [US2] 校验并保留「无候选→claim 分配 0→实例留 WAITING、零计数消耗」现状路径（`SchedulerKernel.claimAndMark` 无候选早退），补断言（FR-013）

**Checkpoint**: 无节点不判死、恢复即抽干、卡住有告警。

---

## Phase 5: User Story 3 - 长跑/流式/丢失区分 + 人工兜底（Priority: P3）

**Goal**: 真续约区分长跑与丢失；max-runtime 兜底卡死；worker 失联自我中止防分区双跑；Flink 流式 reattach 不重提交；人工停止/重跑重置双计数。

**Independent Test**: 长跑不误杀 / kill worker 自动重派 / 分区不双跑 / Flink reattach 集群 job 数=1 / kill+rerun 计数归零（quickstart US3）。

### Tests for US3 ⚠️

- [ ] T025 [P] [US3] `WorkerExecServiceTest`：running 集合 submit 加/finally 移除、`runningInstanceIds()` 快照；`abortAll` 只中止进程内、豁免 long_running
- [ ] T026 [P] [US3] 续约集成测试：worker 持续上报 running id → `renewLease` 命中 → 长跑不被回收；kill worker → 租约到期 WORKER_LOST 重派
- [ ] T027 [P] [US3] `TimeoutSweeperTest`：RUNNING 超 `timeout_sec` → FAILED(TIMEOUT)；`long_running` 豁免
- [ ] T028 [P] [US3] `OpsServiceTest`：`rerun` 复位 `attempt/business_attempt/infra_redispatch_count=0`；`kill` 对 long_running 触发集群取消

### Implementation for US3（进程内容错）

- [ ] T029 [US3] `w/application/WorkerExecService.java`：加 `Set<UUID> running`（`submit`/`executeSync` 入口 add、`finally` remove），暴露 `runningInstanceIds()`；加 `abortAll()`（销毁进程内在跑子进程，跳过 long_running）（FR-016/021）
- [ ] T030 [US3] `w/infrastructure/HeartbeatReporter.java`：`instanceIdsArray` 用 `execService.runningInstanceIds()` 真序列化（去硬编码 `"[]"`）；累计连续心跳失败时长 > `self-fence-grace-ms` → 调 `execService.abortAll()`；**加不变量校验/断言 `self-fence-grace-ms ≥ 租约续约窗`**（防分区误杀 vs 双跑窗，修 analyze C1）（FR-016/021）
- [ ] T031 [US3] 新增 `m/application/TimeoutSweeper.java`（`@Scheduled`）：RUNNING 且 `now-started_at>timeout_sec` 且 `long_running=false` → `casTaskTerminalFromActive(FAILED, "TIMEOUT")`（FR-020）
- [ ] T032 [US3] `m/application/OpsService.java`：`rerunInstance` 追加复位 `business_attempt=0, infra_redispatch_count=0`（并清 `external_job_handle`）；`killTask` 对 `long_running` 且句柄非空先取消集群作业（FR-027/028）

### Implementation for US3（外部长驻作业 Flink，⚠️ 依赖 059）

> **前置对账**：`FlinkTaskExecutor` 属 059「大数据任务类型」范围（活跃于 `dw-059-*` worktree/主线）。本子段开工前必须完成 T033 对账；若 059 未合入，T035~T037 先以契约 + 桩验证，真集成排 059 合入后。

- [ ] T033 [US3] Cross-feature 对账：核对 059 已落的 `FlinkTaskExecutor` 提交契约与 `EngineSubmitRef`，确认 `long_running`/detached/JobID 解析改动不与 059 冲突；记录依赖与合入顺序到本任务备注
- [ ] T034 [US3] 任务定义类别：`task_def.long_running` 的读写贯通（物化到 `task_instance`）；Flink 流式模板置 `long_running=true`（有界 Flink SQL 仍 false）（FR-022）
- [ ] T035 [US3] `w/infrastructure/FlinkTaskExecutor.java`：`long_running` 分支 → `flink run -d`（detached）提交、从 stdout 解析 JobID、写回 `task_instance.external_job_handle`（JobID+REST 端点）、轮询 REST job status 驱动状态/续约、**不套** `DEFAULT_TIMEOUT_SECONDS`（FR-023/025/026）
- [ ] T036 [US3] Flink reattach：实例 `external_job_handle≠null` 到达 worker → reattach 模式（按 JobID 轮询、不 `flink run`）；REST 探测确认 job 不存在/FAILED 才按业务重试重提交（FR-024）
- [ ] T037 [P] [US3] `FlinkReattachTest`：detached 提交解析 JobID → 落句柄；reattach 路径不 `flink run`；worker 故障后集群 job 实例数=1；**有界 Flink（long_running=false）exit-code/stdout 语义不变**（constitution III 保真）

**Checkpoint**: 长跑不误杀、丢失自动兜底、分区不双跑、Flink reattach 不重复、人工兜底闭环。

---

## Phase 6: Polish & Cross-Cutting

- [ ] T038 [P] `m/application/SchedulerMetrics.java`：新增 gauge/counter——隔离节点数、SUSPENDED 实例数、infra 重派速率、无节点等待时长（不 UPDATE 既有 metric，遵「指标不可变」）
- [ ] T039 [P] 更新 `CLAUDE.md` Knowledge Map + `docs/architecture.md`：节点容错闭环入口（SlotManager 门 / NodeHealthService / LeaseReaper 分类 / attempt vs business_attempt / SUSPENDED / Flink reattach）
- [ ] T040 编译门禁：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-worker,dataweave-api -am compile` 零错误
- [ ] T041 **证伪式真跑门禁**（CLAUDE.md 调度验证硬规则）：distributed 起 2 master+2 worker，every-minute cron 端到端，确认 `started_at−created_at≈0`、root attempt=1、零 `跳过下发/中止执行` straggler；再按 quickstart 注入假节点/停 worker/kill/分区跑 US1~US3 验收，确认 SC-001~009
- [ ] T042 回归：`SchedulerKernelTest`/`InstanceStateMachineTest`/`LeaseReaperTest` 全绿；确认 `isCurrentDispatch` 栅栏 + `attempt` 单调未回退；048/049/051 认领稳态不退化

---

## Dependencies & Execution Order

- **Phase 1 Setup** → **Phase 2 Foundational**（T003 schema 阻塞一切）→ 各 US。
- **US1(P1)** 仅依赖 Foundational，独立可交付=MVP。
- **US2(P2)** 依赖 Foundational；与 US1 大部并行（触碰 FleetService/新 Sweeper，与 US1 的 SlotManager/LeaseReaper 文件多不重叠，注意 FleetService T015↔T022 同文件需串行）。
- **US3(P3)** 依赖 Foundational；进程内子段（T029~T032）可与 US1/US2 并行；**Flink 子段（T033~T037）依赖 059**，排最后。
- **Polish** 依赖对应 US 完成。

## Parallel Opportunities

- Foundational：T005/T006 [P]（不同文件）。
- US1 测试 T008~T012 全 [P]；实现中 T013(NodeHealthService 新文件) 可与 T014(SlotManager) 并行，T016/T017/T018 因触 LeaseReaper/RetryService/SchedulerKernel 不同文件可并行，但都依赖 T007/T013。
- US3：T029(worker)/T031(TimeoutSweeper 新文件)/T034(task_def) 互不干扰可 [P]。

## Implementation Strategy

- **MVP = US1**（切片 A）：Foundational + Phase 3，交付即消除"假节点承接 + infra 烧业务重试 + 毒任务无限循环"三大痛点，可独立上线验证。
- 增量：US2 补"无节点安全 + 恢复抽干"，US3 补"长跑/流式/丢失 + 人工兜底"。
- **Flink reattach 是唯一外部依赖点**（059），已隔离到 US3 末段，不阻塞 MVP 与 US2。
