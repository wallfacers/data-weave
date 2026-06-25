---
description: "Task list for 分布式 Cron 精确触发（移植 PowerJob 调度思想）"
---

# Tasks: 分布式 Cron 精确触发（移植 PowerJob 调度思想）

**Input**: Design documents from `specs/001-distributed-cron-trigger/`

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓

**Tests**: 包含测试任务 —— 本项目 CLAUDE.md「Testing」门禁要求 `no test = not done`,且 spec 每个用户故事都带 Independent Test + 验收场景。测试先写、先失败、再实现。

**Organization**: 按用户故事分阶段(US1/US2/US3/US4),对应 spec 优先级;>10k 分片作为 US2 的规模延伸(FR-016/SC-007)单列一阶段。改动集中在 `dataweave-master`,schema/config 在 `dataweave-api/resources`,测试在 `dataweave-api/src/test`。

**路径约定**:
- master 代码:`backend/dataweave-master/src/main/java/com/dataweave/master/`
- 资源/schema/config:`backend/dataweave-api/src/main/resources/`
- PG 迁移:`backend/dataweave-api/src/main/resources/db/migration/`(项目惯例)
- 测试:`backend/dataweave-api/src/test/java/com/dataweave/api/`

> ⚠️ 后端改 master 后必须 `cd backend && ./dev-install.sh -pl dataweave-master -am` 再 `spring-boot:run`,否则跑的是旧 jar。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 配置键与新组件骨架

- [ ] T001 [P] 在 `backend/dataweave-api/src/main/resources/application.yml` 的 `scheduler.*` 增加 `cron-scan-interval-ms`(默认 15000)与 `cron-lookahead-ms`(默认 30000)
- [ ] T002 [P] 新建 `TimingStrategy` 策略接口骨架 `backend/dataweave-master/src/main/java/com/dataweave/master/application/TimingStrategy.java`(方法 `next(wf, base, now)` + `supports(scheduleType)`,见 contracts/trigger-engine.md)
- [ ] T003 [P] 新建 `TriggerEngine` 接口骨架 `backend/dataweave-master/src/main/java/com/dataweave/master/application/TriggerEngine.java`(`scanAndArm(now)` + `refresh(workflowId)`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: schema 加列 + 实体/仓储映射,所有用户故事的共同前置

**⚠️ CRITICAL**: 本阶段完成前任何用户故事不能开工

- [ ] T004 在 `backend/dataweave-api/src/main/resources/schema.sql` 的 `workflow_def` 加列 `next_trigger_time TIMESTAMP NULL` + `schedule_interval_ms BIGINT NULL`,并新增索引 `idx_workflow_def_scan (deleted, schedule_type, status, next_trigger_time)`(H2,DDL 须 PG 双兼容)
- [ ] T005 [P] 新建 PG 迁移 `backend/dataweave-api/src/main/resources/db/migration/V__add-next-trigger-pg.sql`:`ALTER TABLE workflow_def ADD COLUMN next_trigger_time/schedule_interval_ms` + 创建同名索引
- [ ] T006 `WorkflowDef` 实体加 `nextTriggerTime` / `scheduleIntervalMs` 字段映射 `backend/dataweave-master/src/main/java/com/dataweave/master/domain/WorkflowDef.java`
- [ ] T007 `WorkflowDefRepository` 加扫描查询 `findScannable(scheduleTypes, status, nextTriggerTimeLe[, shardCount, shardIndex])` `backend/dataweave-master/src/main/java/com/dataweave/master/domain/WorkflowDefRepository.java`(分片参数本阶段先留可选,Batch B 启用)
- [ ] T045 **[G1 / FR-010]** 统一时间真相来源为 DB 服务端时间:新增 `now()` 取数封装(`SELECT CURRENT_TIMESTAMP`,JdbcTemplate,H2/PG 兼容),`TriggerEngine` 的扫描捞取与逾期判定以此为基准而非各 master `LocalDateTime.now()`,降低多 master 时钟漂移导致的延迟/补偿偏差 `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerClock.java`

**Checkpoint**: 编译通过(`./dev-install.sh -pl dataweave-master -am`)后,用户故事可开工

---

## Phase 3: User Story 1 - cron 工作流准点触发 (Priority: P1) 🎯 MVP

**Goal**: 60s 粗轮询 → 15s 扫 + 预读窗口 + 进程内精确触发,使 cron 时刻→实例创建延迟降到秒级

**Independent Test**: 配近未来 cron 工作流,测「cron 时刻→实例创建」延迟 p99 ≤ 2s(quickstart 验证 1)

### Tests for User Story 1 ⚠️（先写先失败）

- [ ] T008 [P] [US1] `CronTriggerLatencyTest`:近未来分钟级 cron 触发,断言 cron 时刻→`workflow_instance` 创建延迟在秒级目标内 `backend/dataweave-api/src/test/java/com/dataweave/api/CronTriggerLatencyTest.java`
- [ ] T009 [P] [US1] 预读窗口边界用例:触发点落在两次扫描之间不被漏到下周期(同测试类或 `CronWindowBoundaryTest`)
- [ ] T046 [P] [US1] **[C3 / SC-004]** 向后兼容回归:既有分钟级 cron 工作流(`next_trigger_time=NULL`)经首轮回填后仍在正确时刻触发、且只触发一次 `backend/dataweave-api/src/test/java/com/dataweave/api/ExistingCronCompatTest.java`
- [ ] T047 [P] [US1] **[C1 / FR-013]** 失效不触发:工作流在「已 arm、未到点」期间被置 OFFLINE / 删除 / 超出 `schedule_end`,到点时断言不产生实例 `backend/dataweave-api/src/test/java/com/dataweave/api/ArmedThenInvalidatedTest.java`
- [ ] T048 [P] [US1] **[C2 / FR-015]** 重叠并发:上一实例未完成时到达新触发点,断言仍建新实例、不阻塞/不排队 `backend/dataweave-api/src/test/java/com/dataweave/api/OverlappingTriggerTest.java`

### Implementation for User Story 1

- [ ] T010 [P] [US1] 实现 CRON 计时策略 `CronTimingStrategy`(`CronExpression.parse(cron).next(base)`)`backend/dataweave-master/src/main/java/com/dataweave/master/application/timing/CronTimingStrategy.java`
- [ ] T011 [US1] 实现 `DefaultTriggerEngine.scanAndArm`:扫 `next_trigger_time ≤ now + lookahead`,按 `due-now`(过期取 0)装入 `ScheduledExecutorService` 精确触发器;幂等去重同一 (workflowId, due) `backend/dataweave-master/src/main/java/com/dataweave/master/application/DefaultTriggerEngine.java`
- [ ] T012 [US1] 在 `DefaultTriggerEngine` 实现到点 `fire` 内部序列:生效期校验 → `cron_fire` 护栏 `save`(去重)→ `WorkflowTriggerService.trigger(wf,"CRON",bizDate,priority,locale)`(**签名不变**)→ 回填 `cron_fire` + `TimingStrategy.next` 重算并持久化 `next_trigger_time`(同文件,依赖 T011)
- [ ] T013 [US1] 重构 `CronScheduler.tick()`:`fixedRate` 60000→`${scheduler.cron-scan-interval-ms}`,委派 `triggerEngine.scanAndArm(now)`;`tryFire` 简化但保留护栏+下游(`backend/dataweave-master/src/main/java/com/dataweave/master/application/CronScheduler.java`)
- [ ] T014 [US1] 首轮 `next_trigger_time` 回填:NULL 时据 `last_fire_time`/`created_at` 计算并落库(在 `DefaultTriggerEngine.refresh`/scan 路径)
- [ ] T015 [US1] 接 `dw.cron.trigger.latency` Timer via `SchedulerMetrics`(记录 cron 时刻→实例创建延迟)

**Checkpoint**: US1 独立可用 —— 分钟级 cron 准点触发达标,现有工作流零改动兼容

---

## Phase 4: User Story 2 - 多 master 下仍恰好触发一次 (Priority: P1)

**Goal**: 引入预读+精确触发后,同一触发点在多 master 下仍恰触发一次(去重不变式不退化)

**Independent Test**: 多线程/多引擎对同一 (workflow, fireTime) 并发 fire,恰一条实例(quickstart 验证 3)

### Tests for User Story 2 ⚠️

- [ ] T016 [P] [US2] 扩展 `SchedulerConcurrencyTest.cronFireGuardrail_dedupsConcurrentInserts`,覆盖「时间轮 arm 后到点并发触发」恰一次 `backend/dataweave-api/src/test/java/com/dataweave/api/SchedulerConcurrencyTest.java`
- [ ] T017 [P] [US2] 多 `TriggerEngine` 实例(模拟多 master)对同一 (workflowId, due) 并发 `fire`,断言恰一条 `workflow_instance`、其余 `DataIntegrityViolationException` 安全放弃 `backend/dataweave-api/src/test/java/com/dataweave/api/MultiMasterDedupTest.java`

### Implementation for User Story 2

- [ ] T018 [US2] 加固 `DefaultTriggerEngine.fire` 的 `cron_fire` 唯一键去重:捕获 `DataIntegrityViolationException` 即安全放弃,不向上抛、不记错误级日志(`DefaultTriggerEngine.java`)
- [ ] T019 [US2] 验证「单 master 已 arm 但到点前退出」由其它 master 下一轮 `scanAndArm` 补 arm,不丢触发(逻辑确认 + 在 T017 测试中补一条用例)

**Checkpoint**: US1 + US2 都独立通过;多 master 零重复

---

## Phase 5: User Story 3 - 错过触发点的容错补偿 (Priority: P2)

**Goal**: master 宕机/扫描延迟导致逾期点,按可配 misfire 策略确定性处理(对齐 PowerJob)

**Independent Test**: 回拨基准制造逾期点,`fire_once` 补一次 + 推进基准;`skip` 仅推进(quickstart 验证 4)

### Tests for User Story 3 ⚠️

- [ ] T020 [P] [US3] `MisfireRecoveryTest`:逾期点在 `fire_once` 下补触发一次并推进基准、`misfire.count`+1;`skip` 下补 0 仅推进 `backend/dataweave-api/src/test/java/com/dataweave/api/MisfireRecoveryTest.java`
- [ ] T021 [P] [US3] master 重启后据 `next_trigger_time ≤ now` 在一个扫描周期内感知错过点(同测试类补用例)

### Implementation for User Story 3

- [ ] T022 [US3] misfire 归一逻辑:算得 `next ≤ now` → 立即触发(delay=0)+ 再 `next(now,…)` 推进到未来最近点(默认 `fire_once`);`skip` 仅推进基准不触发(`DefaultTriggerEngine.java` + `TimingStrategy` 配合)
- [ ] T023 [US3] 复用现有 `scheduler.cron-misfire`(fire_once/skip)配置;接 `dw.cron.misfire.count` Counter(tag policy)via `SchedulerMetrics`
- [ ] T024 [US3] 重启恢复:确认启动后首轮 `scanAndArm` 即覆盖 `next_trigger_time ≤ now` 的点,无需独立补偿轮(在 `DefaultTriggerEngine`/`CronScheduler` 启动路径验证)

**Checkpoint**: US1 + US2 + US3 独立通过;停机恢复无静默丢失、无补偿风暴

---

## Phase 6: User Story 4 - 秒级与多种周期表达式 (Priority: P1)

**Goal**: 秒级 cron + FIXED_RATE + FIXED_DELAY 计时策略(移植 PowerJob 多策略思想)

**Independent Test**: cron=`*/30 * * * * *` 连续 ≥60 周期间隔误差 ≤2s 无漂移;固定频率/延迟按各自语义(quickstart 验证 2)

### Tests for User Story 4 ⚠️

- [ ] T025 [P] [US4] `SecondLevelCronTest`:`*/30 * * * * *` ≥60 周期,相邻间隔误差 ≤2s 且无累计漂移 `backend/dataweave-api/src/test/java/com/dataweave/api/SecondLevelCronTest.java`
- [ ] T026 [P] [US4] `FixedRateDelayTest`:FIXED_RATE 按计划间隔触发、FIXED_DELAY 上次完成+interval `backend/dataweave-api/src/test/java/com/dataweave/api/FixedRateDelayTest.java`

### Implementation for User Story 4

- [ ] T027 [P] [US4] 确认 `CronTimingStrategy` 秒级生效(6 字段),补秒级单测;**不引入 cron-utils**(research D4)
- [ ] T028 [P] [US4] 实现 `FixedRateTimingStrategy`(`next = prevScheduledFire + scheduleIntervalMs`)`backend/dataweave-master/src/main/java/com/dataweave/master/application/timing/FixedRateTimingStrategy.java`
- [ ] T029 [P] [US4] 实现 `FixedDelayTimingStrategy`(`next = lastCompletion + scheduleIntervalMs`)`backend/dataweave-master/src/main/java/com/dataweave/master/application/timing/FixedDelayTimingStrategy.java`。**[U1 钉死]** `lastCompletion` 来源 = 重算 next 时查该工作流最近一条已完成 `workflow_instance` 的完成时刻(`WorkflowInstanceRepository` 查询,非实例完成事件回填);无历史完成则用工作流创建时刻为基准
- [ ] T030 [US4] `schedule_type` 扩展取值 `FIXED_RATE`/`FIXED_DELAY`,`TriggerEngine` 按 type 经 `supports()` 选策略并使用 `schedule_interval_ms`(`DefaultTriggerEngine.java`)
- [ ] T031 [US4] 工作流创建/编辑校验:`FIXED_*` 必填 `schedule_interval_ms`(interfaces 层),保持既有 CRON 工作流向后兼容(`backend/dataweave-master/.../interfaces` 或 api 校验处)

**Checkpoint**: 四个 P1/P2 故事(Batch A)全部独立通过 —— 准点 + 去重 + 容错 + 秒级/多策略

---

## Phase 7: Scale - >10k 工作流分片 (extends US2 · FR-016 / SC-007)

**Goal**: 新增 master 成员表 + 哈希分片,使每个 master 只预读自己分片;>10k 下延迟与去重不退化、单机负载随分片下降

**Independent Test**: ≥10k 工作流、≥3 模拟 master,零重复零漏 + 单 master 负责数≈总数/活 master(quickstart 验证 5)

### Tests ⚠️

- [ ] T032 [P] [US2] `ShardingScaleTest`:≥10k 工作流、≥3 模拟 master,全量触发点零重复零漏,`dw.cron.shard.workflows` ≈ 总数/活 master `backend/dataweave-api/src/test/java/com/dataweave/api/ShardingScaleTest.java`
- [ ] T033 [P] [US2] 重平衡用例:master 上/下线漂移期靠 `cron_fire` 兜底不丢不重(同测试类)

### Implementation

- [ ] T034 schema:新建 `master_nodes` 表(见 data-model E3)`backend/dataweave-api/src/main/resources/schema.sql`(H2)+ PG 迁移 `db/migration/V__add-master-nodes-pg.sql`
- [ ] T035 [P] `MasterNode` 实体 + `MasterNodeRepository` `backend/dataweave-master/src/main/java/com/dataweave/master/domain/`
- [ ] T036 `MasterRegistry`:`register/heartbeat/activeMasters/myShardIndex`(自注册 `host-pid`、心跳续约、超时剔除,照搬 worker_nodes 惯例)`backend/dataweave-master/src/main/java/com/dataweave/master/application/MasterRegistry.java`
- [ ] T037 [US2] `DefaultTriggerEngine.scanAndArm` 在 `cron-sharding-enabled=true` 时追加分片过滤 `MOD(id, activeCount) = myIndex`,关闭时全量预读(`DefaultTriggerEngine.java` + T007 仓储)
- [ ] T038 [US2] 接 `dw.cron.shard.workflows` Gauge via `SchedulerMetrics`(本 master 负责工作流数)
- [ ] T039 配置:`application.yml` 增加 `cron-sharding-enabled`(false)/`master-heartbeat-ms`(10000)/`master-offline-threshold-sec`(30)/`cron-fire-retention-days`(30)

**Checkpoint**: >10k 规模下满足 SC-007;关闭分片时行为退化为 Batch A(cron_fire 兜底),向后兼容

---

## Phase 8: Polish & Cross-Cutting

**Purpose**: 跨故事收尾

- [ ] T040 [P] `cron_fire` 归档清理:低频 `@Scheduled` 任务删除 `fired_at < now - retention-days`,与触发路径解耦(`backend/dataweave-master/.../application/CronFireReaper.java`)
- [ ] T041 [P] 接 `dw.cron.window.size` Gauge,并在 `contracts/config-and-metrics.md` 核对全部新增指标已暴露 `/actuator/prometheus`
- [ ] T042 全量回归:`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api test`(`SchedulerConcurrencyTest` 等必须全绿)
- [ ] T043 跑 `quickstart.md` 五项验证(准点 / 秒级 / 多 master 去重 / misfire / 分片)
- [ ] T044 [P] 更新 `CLAUDE.md` Knowledge Base「Scheduler kernel」行,补 `TriggerEngine`/`TimingStrategy`/`MasterRegistry` 与分片说明

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (P1)**:无依赖,立即可做(T001/T002/T003 全 [P])
- **Foundational (P2)**:依赖 Setup;**阻塞所有用户故事**(schema 加列 + 实体/仓储)
- **US1 (P3)**:依赖 Foundational —— MVP,交付准点
- **US2 (P4)**:依赖 US1(去重发生在 US1 的 fire 路径上),主要是测试 + 加固
- **US3 (P5)**:依赖 US1(misfire 归一在 TriggerEngine.fire);与 US2 独立
- **US4 (P6)**:依赖 Foundational + US1 的 TriggerEngine 选策略接缝;与 US2/US3 独立可并行
- **Scale (P7)**:依赖 US1/US2(扩展去重到规模);可在 Batch A 全绿后再做
- **Polish (P8)**:依赖所需故事完成

### User Story 完成顺序（建议）
US1 → US2 → (US3 ∥ US4) → Scale → Polish。Batch A = US1..US4 可独立交付;Scale 为规模化叠加,无破坏性依赖。

### Within Each Story
测试先写先失败 → 计时策略/模型 → TriggerEngine 实现 → CronScheduler 接线 → 指标。

### Parallel Opportunities
- Setup T001/T002/T003 并行
- Foundational T004 与 T005(PG 迁移)并行;T006/T007 在 T004 后
- US1 测试 T008/T009 并行;T010 与 T011 可并行起步(不同文件),T012 依赖 T011
- US4 的 T027/T028/T029 三个计时策略不同文件,完全并行
- Scale 的 T035(实体)与 T034(schema)并行

---

## Parallel Example: User Story 4

```bash
# 三个计时策略不同文件,并行实现:
Task: "实现 CronTimingStrategy 秒级单测 (T027)"
Task: "实现 FixedRateTimingStrategy.java (T028)"
Task: "实现 FixedDelayTimingStrategy.java (T029)"
```

---

## Implementation Strategy

### MVP First (US1)
1. Setup(T001-T003)→ 2. Foundational(T004-T007)→ 3. US1(T008-T015)→ **停下验证**:cron 准点 p99 ≤ 2s + 现有工作流零改动兼容 → 可演示。

### Incremental Delivery
Setup+Foundational → US1(MVP 准点)→ US2(多 master 去重)→ US3(容错)/US4(秒级,可并行)→ Scale(>10k)→ Polish。每个故事独立加值不破坏前序。

### 关键护栏(全程不可破)
- `WorkflowTriggerService.trigger()` 签名/语义不变;`SchedulerKernel` 零改动;死锁防御四不变量保持。
- `cron_fire` 唯一键是唯一去重真相,分片只决定「谁预读」不决定「谁能触发」。
- 每次改 master 后 `./dev-install.sh -pl dataweave-master -am` 再运行。

---

## Notes
- [P] = 不同文件、无未完成依赖,可并行
- [US#] 标签映射 spec 用户故事;Scale 阶段用 [US2] 因其是「多 master 恰一次」在 >10k 规模的延伸(SC-002 + SC-007)
- 测试先失败再实现;每个任务或逻辑组完成后提交
- 任一 Checkpoint 可停下独立验证
