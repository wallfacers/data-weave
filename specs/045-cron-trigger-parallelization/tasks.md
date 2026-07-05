---
description: "Task list for 045-cron-trigger-parallelization"
---

# Tasks: cron 触发并发吞吐优化

**Input**: Design documents from `specs/045-cron-trigger-parallelization/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.md, quickstart.md, design doc(`docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md`)
**Tests**: 单元测试 + 真跑压测(复用 044 cron-stress)
**Organization**: 按 User Story(US1 并发吞吐 P1 → US2 找极限 P2 → US3 可靠 P3),Foundational 阶段先建 schema/配置/批量化基础。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行(不同文件,无前置依赖)
- **[Story]**: 所属 user story
- 描述含确切文件路径

## Phase 1: Setup

- [X] T001 确认 distributed 后端就绪:`curl localhost:8000/api/health` 与 `:8200/api/health` 均 200;`curl localhost:8000/api/fleet` 看到 worker-1/2 ONLINE

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: schema + 配置 + WorkflowTriggerService 批量化 —— 触发引擎重构的地基。

- [X] T002 [P] 改 `backend/dataweave-api/src/main/resources/schema.sql`:`cron_fire` 加 `status VARCHAR(16) DEFAULT 'PENDING' NOT NULL` 列 + 索引 `(instance_id, created_at)`;bump `schema_version`(header 注释 + single-row 表)
- [X] T003 [P] 改 `backend/dataweave-api/src/main/resources/schema.sql`:`workflow_instance` 加部分唯一约束 `UNIQUE (workflow_id, scheduled_fire_time) WHERE scheduled_fire_time IS NOT NULL`(PG 语法;H2 兼容性核对,H2 用 `ALTER TABLE ... ADD CONSTRAINT ...` + 触发的兼容写法)
- [X] T004 [P] 改 `backend/dataweave-api/src/main/resources/application.yml`:新增 `scheduler.cron-trigger-timer-threads: 8` / `cron-fire-worker-threads: 32` / `cron-fire-queue-capacity: 4000` / `cron-reconcile-interval-ms: 10000` / `cron-reconcile-grace-ms: 30000` / `cron-reconcile-timeout-ms: 180000`;`spring.datasource.hikari.maximum-pool-size: 40`
- [X] T005 [P] 改 `docker-compose.yml`:postgres 加 `command: postgres -c max_connections=200`;`dataweave-master` / `dataweave-master-2` env 追加 `SCHEDULER_CRON_TRIGGER_TIMER_THREADS` / `SCHEDULER_CRON_FIRE_WORKER_THREADS` / `SCHEDULER_CRON_FIRE_QUEUE_CAPACITY` / `SCHEDULER_CRON_RECONCILE_*` / `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`
- [X] T006 [P] 改 `backend/dataweave-master/.../application/WorkflowTriggerService.java`:`trigger` 主方法(11 参版本)加 `@Transactional`;taskInstance 循环 `save` → 收集 List + `saveAll`;`wake()` 移到事务提交后(`TransactionSynchronizationManager` AFTER_COMMIT 或抽取 `@TransactionalEventListener`)
- [X] T007 [P] 改 `backend/dataweave-master/.../domain/CronFire.java`:加 `status` 字段;`CronFireRepository.java` 加查询 `findByInstanceIdIsNullAndCreatedAtBefore(LocalDateTime threshold, Pageable)`

**Checkpoint**: schema/配置/批量化就绪,可重构触发引擎。

---

## Phase 3: User Story 1 - 并发触发不节流 (Priority: P1) 🎯 MVP

**Goal**: 50wf 同触发点吞吐 ≥30 inst/s,slot_util > 0.5

**Independent Test**: `setup -n 50` + `cron-watch`,核对吞吐/延迟/幂等

### Implementation for User Story 1

- [X] T008 [US1] 改 `backend/dataweave-master/.../application/DefaultTriggerEngine.java`:timer 池 `newScheduledThreadPool(2)` → 可配 `cron-trigger-timer-threads`;新增 `fireExecutor`(固定线程池 `cron-fire-worker-threads`)+ `fireQueue`(`LinkedBlockingQueue`,容量 `cron-fire-queue-capacity`)。`fire()` 拆:
  - `fireArm(wfId, due)`:校验 ONLINE/生效期/misfire + `INSERT cron_fire (status=PENDING)` + `fireQueue.offer(task, 200ms)`;超时 → fallback 同步 `fireExecute` + `metrics.markQueueFull`
  - `fireExecute(FireTask)`:应用层幂等查(SELECT workflow_instance 同 workflow_id+scheduled_fire_time)→ `triggerService.trigger` → 回填 `cron_fire(instance_id, status=FIRED, fired_at)` + `advanceNext` + metrics(依赖 T002/T006/T007)
- [X] T009 [US1] 改 `backend/dataweave-master/.../application/SchedulerMetrics.java`:新增 `dw.cron.fire.queue.size`(gauge,绑 fireQueue.size)/ `queue.full.count`(counter)/ `fire.execute.latency`(timer)/ `fire.arm.latency`(timer);方法 `markQueueFull()` / `recordFireExecuteLatency(d)` / `recordFireArmLatency(d)`(依赖 T008)
- [X] T010 [P] [US1] 新增 `backend/dataweave-master/src/test/.../application/DefaultTriggerEngineTest.java`:fireArm 不阻塞 timer / fireExecute 异步物化 / 满队列降级同步 / cron_fire status 转换 PENDING→FIRED / 幂等快查跳过
- [X] T011 [P] [US1] 新增 `backend/dataweave-master/src/test/.../application/WorkflowTriggerServiceTest.java`:`@Transactional` 原子性(物化中段异常回滚,无半成品)/ `saveAll` 批量正确性 / wake 在事务提交后触发
- [X] T012 [US1] 真跑默认档压测:重建 master(应用 T004/T005)→ `tmp/cron-stress/cron-stress.sh setup -n 50 -c '*/10 * * * * *'` + `cron-watch -m 2` → 核对 SC-001(吞吐 ≥30 inst/s)/ SC-002(slot_util>0.5)/ SC-003(幂等无重复)(依赖 T008/T009/T010/T011)

**Checkpoint**: US1 独立可验证 —— 触发层节流解除,吞吐 ≥10x。

---

## Phase 4: User Story 2 - 找极限 (Priority: P2)

**Goal**: 极限档压测定位饱和点

- [X] T013 [P] [US2] 改 `tmp/cron-stress/cron-stress.sh`:`cron-watch` 扩展抓 `dw.cron.fire.queue.size` / `queue.full.count` / `reconcile.replayed|skipped|dead`(经 `/actuator/prometheus`,双 master :8000/:8200)
- [X] T014 [US2] 极限档压测:master×2 env 切 worker=64 / HikariCP=64 / queue=8000 + postgres max_connections=200 → `setup -n 200` + `cron-watch -m 3` → 记录 SC-006(最大吞吐 + 最先饱和指标:CPU/DB 连接/锁/worker 池)(依赖 T012/T013)

**Checkpoint**: US2 独立可验证 —— 资源极限量化。

---

## Phase 5: User Story 3 - 可靠:不丢/不重/无死锁 (Priority: P3)

**Goal**: 崩溃 ≤30s 补偿,无重复,4 不变量保持

- [X] T015 [US3] 新增 `backend/dataweave-master/.../application/CronFireReconciler.java`:`@Scheduled(fixedRateString="${scheduler.cron-reconcile-interval-ms:10000}")`;扫 `cron_fire instance_id IS NULL && created_at < now-grace`(默认 30s,LIMIT batch)→ 应用层幂等查 → 已有回填 status=FIRED / 无则 `triggerService.trigger` → 回填;超 timeout(180s)→ `status=DEAD` + `log.error`;metrics.recordReconcile(replayed/skipped/dead)(依赖 T007/T008)
- [X] T016 [P] [US3] 新增 `backend/dataweave-master/src/test/.../application/CronFireReconcilerTest.java`:幂等跳过(已有 instance)/ 正常补偿(无 instance → 创建)/ DEAD 标记(超 timeout)/ 多 master 并发撞键安全(DB 唯一约束)
- [X] T017 [US3] 崩溃注入真跑:`setup -n 50` + `docker kill dataweave-master-2 && docker start` → 等 30s → 核对 SC-004(cron_fire instance_id NULL 最终回填 + `reconcile.replayed>0`)+ SC-003(无重复)(依赖 T015/T016)
- [X] T018 [US3] 不变量核对(SC-005):`SELECT workflow_id, scheduled_fire_time, count(*) FROM workflow_instance WHERE scheduled_fire_time IS NOT NULL GROUP BY 1,2 HAVING count(*)>1` 应空;`status='DEAD'` 应 0(无故障注入);双 master `dispatch_count_total` 均衡;代码审查 4 不变量保持

**Checkpoint**: US3 独立可验证 —— 可靠性护栏确立。

---

## Phase 6: Polish & Cross-Cutting

- [ ] T019 [P] 后端编译 + 测试:`cd backend && ./mvnw -pl dataweave-master -am clean test -Dmaven.build.cache.enabled=false`(零 fail;只认 `Tests run: N>0`,防 build-cache 假绿)—— **mvn test 已 detach 跑,等 `tmp/build.exit`**
- [X] T020 把实测数字(默认档吞吐/延迟 + 极限档饱和点 + 崩溃补偿延迟 + 撞键率)与结论回写 `specs/045-cron-trigger-parallelization/research.md`(R7 实测段)

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (Phase 1)**:无依赖
- **Foundational (Phase 2)**:依赖 Setup;**阻塞所有 US**(schema/配置/批量化未就绪则无从重构)
- **US1 (Phase 3)**:依赖 Foundational;触发引擎重构核心
- **US2 (Phase 4)**:依赖 US1(默认档基线先立,再跑极限档)
- **US3 (Phase 5)**:依赖 US1(reconciler 是触发引擎的补偿器)
- **Polish (Phase 6)**:依赖 US 完成

### 关键任务依赖
- T008 依赖 T002(cron_fire status)+ T006(trigger 批量化)+ T007(CronFire entity)
- T009 依赖 T008
- T012 依赖 T008/T009/T010/T011
- T014 依赖 T012/T013
- T015 依赖 T007/T008
- T017 依赖 T015/T016

### Parallel Opportunities
- T002 ‖ T003 ‖ T004 ‖ T005 ‖ T006 ‖ T007(Foundational 不同文件)
- T010 ‖ T011(US1 测试,不同 service)
- T013 ‖ T016(脚本 ‖ 测试,不同文件)

## Implementation Strategy

### MVP First (Setup + Foundational + US1)
1. Phase 1:T001 确认环境
2. Phase 2:T002-T007 schema/配置/批量化(可并行)
3. Phase 3:T008-T012 触发引擎重构 + 测试 + 默认档压测
4. **STOP and VALIDATE**:吞吐 ≥10x(MVP 交付)

### Incremental Delivery
5. US2:T013-T014 极限档找天花板
6. US3:T015-T018 reconciler + 崩溃补偿 + 不变量
7. Polish:T019-T020 编译测试 + 结论回写

## Notes
- 后端 Java 代码改动集中在 master 模块 application 层;`schema_version` 必 bump
- 设计源:`docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md`(brainstorming design doc)
- 复用 044 cron-stress 脚本 + cron-watch;新断言:队列/降级/reconcile/幂等
- 每个 Checkpoint 可独立停下验证;commit 节奏:Foundational 后 / 每个 US 后
