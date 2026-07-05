---
description: "Task list for 046-dispatch-parallelization"
---

# Tasks: dispatch 链路并行化优化

**Input**: Design documents from `specs/046-dispatch-parallelization/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.md, quickstart.md, design doc(`docs/superpowers/specs/2026-07-05-dispatch-parallelization-design.md`)
**Tests**: 单元测试 + 真跑压测(复用 045 cron-stress,backup 在 main `tmp/cron-stress/`)
**Organization**: 按 User Story(US1 dispatch 认领不节流 P1 → US2 找极限 P2 → US3 可靠 P3),Foundational 先建配置/WebClient 超时基础。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行(不同文件,无前置依赖)
- **[Story]**: 所属 user story
- 描述含确切文件路径

## Phase 1: Setup

- [ ] T001 确认 distributed 后端就绪:045 master 后端在跑(`curl localhost:8000/api/fleet` 看 worker-1/2 ONLINE);045 cron-stress 脚本拷到 `tmp/cron-stress/cron-stress.sh`(git-ignored)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 配置 + WebClient 超时 —— dispatch 解耦的地基(去屏障必须有超时,防慢 worker 挂 dispatchExecutor)。

- [X] T002 [P] 改 `backend/dataweave-api/src/main/resources/application.yml`:新增 `scheduler.dispatch-queue-capacity: 2000` / `dispatch-executor-threads: 64` / `dispatch-webclient-timeout-ms: 3000`
- [X] T003 [P] 改 `docker-compose.yml`:`dataweave-master` / `dataweave-master-2` env 追加 `SCHEDULER_DISPATCH_QUEUE_CAPACITY` / `SCHEDULER_DISPATCH_EXECUTOR_THREADS` / `SCHEDULER_DISPATCH_WEBCLIENT_TIMEOUT_MS`
- [X] T004 [P] 改 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/WebClientConfig.java`:WebClient builder 加 `responseTimeout(3s)` + `connectTimeout(2s)`(reactor-netty `HttpClient.create().responseTimeout(...)`;配套去屏障)

**Checkpoint**: 配置 + 超时就绪,可重构 SchedulerKernel。

---

## Phase 3: User Story 1 - dispatch 认领不节流 (Priority: P1) 🎯 MVP

**Goal**: 1000wf `*/2s` 物化 600 inst/s 下,dispatch_latency p99 < 5s,dispatch 吞吐 ≥600 inst/s

**Independent Test**: cron-stress 1000wf `*/2s`,核对 dispatch_latency / 吞吐 / queue.size

### Implementation for User Story 1

- [X] T005 [P] [US1] 改 `backend/dataweave-master/.../application/SchedulerKernel.java`:加 `DispatchCommand` record(instanceId/workflowId/taskId/content/paramsJson/attempt/lease/workerNodeCode/bizDate/timeoutSeconds)+ `dispatchQueue`(`LinkedBlockingQueue`,capacity 可配)+ `dispatchExecutor`(`ThreadPoolExecutor`,threads 可配,`RejectedExecutionHandler` 满则降级同步)+ Caffeine `cache` 字段((task_id,version_no)→ content/params)
- [X] T006 [US1] 改 `SchedulerKernel.java`:`runRound` → `claimRound` 拆 —— `running` 只护 claim 事务(SKIP LOCKED + CAS + assign),事务提交后逐个 `dispatchQueue.offer(cmd, 200ms)`(满则降级同步 dispatch + `markQueueFull`),释放 `running`(**不等 dispatch**)
- [X] T007 [US1] 改 `backend/dataweave-master/.../application/ParallelDispatcher.java`:`dispatchAll` → `dispatchAllAsync` —— 逐个 `dispatchQueue.offer` 立即返回(**去 `invokeAll` 屏障**),dispatchExecutor 消费调 `gateway.dispatch`;失败 `onFailure.casRequeue` 不变(依赖 T005/T006)
- [X] T008 [US1] 改 `SchedulerKernel.java`:新增 `TaskDefVersionBatchLoader`(本轮 `SELECT … WHERE (task_id, version_no) IN (...)` 批量预取 content/params)+ `assign()` 改用批量预取 + Caffeine cache + `crossCycleReady` 批量化(消除 N+1,250-350 次/轮 → 10-20 次/轮;依赖 T005)
- [X] T009 [US1] 改 `backend/dataweave-master/.../application/SchedulerMetrics.java`:新增 `dw.dispatch.queue.size`(gauge)/ `queue.full.count`(counter)/ `execute.latency`(timer);方法 `setDispatchQueueSize` / `markQueueFull` / `recordDispatchExecuteLatency`(依赖 T005/T006)
- [ ] T010 [P] [US1] 新增 `backend/dataweave-master/src/test/.../application/SchedulerKernelTest.java`:claimRound 不等 dispatch(offer 立即返回)/ dispatchQueue 满降级同步 + markQueueFull / assign 批量预取(N+1 消除,1 次 SELECT IN)/ WebClient 超时 casRequeue
- [X] T011 [P] [US1] 新增 `backend/dataweave-master/src/test/.../application/ParallelDispatcherTest.java`:`dispatchAllAsync` offer 队列立即返回(去 `invokeAll` 屏障,不阻塞调用线程)
- [X] T012 [US1] 真跑默认档压测:rebuild master(应用 T002-T009)→ `tmp/cron-stress/cron-stress.sh setup -n 1000 -c '*/2 * * * * *'` + `cron-watch -m 3` → 核对 SC-001(dispatch_latency p99 < 5s)/ SC-002(吞吐 ≥600 inst/s)/ SC-005(queue.size 稳态不涨)(依赖 T005-T011)

**Checkpoint**: US1 独立可验证 —— dispatch 认领节流解除。

---

## Phase 4: User Story 2 - 找极限 (Priority: P2)

**Goal**: 极限档定位 dispatch 链路饱和点

- [X] T013 [P] [US2] 改 `tmp/cron-stress/cron-stress.sh`:`cron-watch` 扩展抓 `dw.dispatch.queue.size` / `queue.full.count` / `execute.latency`(经 `/actuator/prometheus`,双 master :8000/:8200)
- [X] T014 [US2] 极限档压测:加密 cron(`* * * * * *` 每秒)或加 wf(1500-2000)→ `cron-watch -m 3` → 记录 SC-006(最大 dispatch 吞吐 + 最先饱和指标:dispatchExecutor / DB 连接 / worker slot / 聚合)(依赖 T012/T013)

**Checkpoint**: US2 独立可验证 —— dispatch 资源极限量化。

---

## Phase 5: User Story 3 - 可靠:不重复/不丢/无死锁 (Priority: P3)

**Goal**: 不丢 dispatch(崩溃 + shutdown 补偿)+ 不重复(CAS)+ 死锁 4 不变量保持

- [X] T015 [US3] 改 `SchedulerKernel.java`:`@PreDestroy` drain `dispatchQueue` —— 残余 DispatchCommand `casRequeue`(DISPATCHED→WAITING)防丢(shutdown 时;依赖 T005/T006)
- [ ] T016 [P] [US3] 扩展 `SchedulerKernelTest.java`:shutdown drain casRequeue 残余 / 崩溃后 casRequeue 回填 / 幂等无重复 dispatch(casDispatch CAS 保证)
- [ ] T017 [US3] 崩溃注入真跑:`setup -n 1000` + `docker kill dataweave-master-2 && docker start` → 等 30s → 核对 SC-003(dispatchExecutor 残余 casRequeue 回填 + 无重复 dispatch)(依赖 T015/T016)
- [ ] T018 [US3] 不变量核对(SC-004):代码审查死锁 4 不变量(SKIP LOCKED claim 保留单线程 / CAS 状态推进 / 锁顺序 task→workflow / 状态事务内 + dispatch 事务外)+ 压测无死锁/活锁

**Checkpoint**: US3 独立可验证 —— 可靠性护栏确立。

---

## Phase 6: Polish & Cross-Cutting

- [ ] T019 后端编译 + 测试:`cd backend && ./mvnw -pl dataweave-master -am clean test -Dmaven.build.cache.enabled=false`(零 fail;只认 `Tests run: N>0`,防 build-cache 假绿)
- [X] T020 SC-002 slot_util 复测(rebuild worker image,ShellTaskExecutor 真跑 sleep,验证 slot 真实容量 — 045 遗留)+ 把实测数字(dispatch_latency/吞吐/饱和点/崩溃补偿)与结论回写 `specs/046-dispatch-parallelization/research.md`(R9 实测段)

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (Phase 1)**:无依赖
- **Foundational (Phase 2)**:依赖 Setup;**阻塞所有 US**(配置/超时未就绪则无从重构)
- **US1 (Phase 3)**:依赖 Foundational;dispatch 解耦核心
- **US2 (Phase 4)**:依赖 US1(默认档基线先立,再跑极限档)
- **US3 (Phase 5)**:依赖 US1(shutdown drain / 崩溃补偿是 dispatchExecutor 的可靠性)
- **Polish (Phase 6)**:依赖 US 完成

### 关键任务依赖
- T006 依赖 T005(DispatchCommand/dispatchQueue 字段)
- T007 依赖 T005/T006(offer 队列需 claimRound 推送)
- T008 依赖 T005(Caffeine cache 字段)
- T009 依赖 T005/T006(绑 dispatchQueue.size)
- T012 依赖 T005-T011
- T014 依赖 T012/T013
- T015/T017 依赖 T015/T016

### Parallel Opportunities
- T002 ‖ T003 ‖ T004(Foundational 不同文件)
- T005 ‖ T010 ‖ T011(T005 字段定义 ‖ 测试,不同关注点;但 T010/T011 实际依赖 T005-T009 实现,可先写测试骨架)
- T013 ‖ T016(脚本 ‖ 测试,不同文件)

## Implementation Strategy

### MVP First (Setup + Foundational + US1)
1. Phase 1:T001 确认环境
2. Phase 2:T002-T004 配置 + WebClient 超时(可并行)
3. Phase 3:T005-T012 dispatch 解耦 + N+1 消除 + 去屏障 + 测试 + 默认档压测
4. **STOP and VALIDATE**:dispatch_latency p99 < 5s(MVP 交付)

### Incremental Delivery
5. US2:T013-T014 极限档找天花板
6. US3:T015-T018 shutdown drain + 崩溃补偿 + 不变量
7. Polish:T019-T020 编译测试 + slot_util 复测 + 结论回写

## Notes
- 后端 Java 代码改动集中在 master 模块 application 层 + api 模块 WebClientConfig;**无 schema 改动**(schema_version 保持 0.7.1)
- 设计源:`docs/superpowers/specs/2026-07-05-dispatch-parallelization-design.md`(brainstorming design doc)
- 复用 045 cron-stress 脚本 + cron-watch(从 main `tmp/cron-stress/` 拷入);新断言:dispatch queue/降级/execute.latency/幂等无重复 dispatch
- 死锁 4 不变量是硬约束(代码审查 + 长跑核对);dispatch fire-and-forget 后可靠性靠 casRequeue + shutdown drain
- 杠杆 7(聚合异步化)defer,实测 computeAndUpdate 是否变瓶颈后开新 feature
- 每个 Checkpoint 可独立停下验证;commit 节奏:Foundational 后 / 每个 US 后
