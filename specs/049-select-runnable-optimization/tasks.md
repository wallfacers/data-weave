# Tasks: selectRunnable 优化

**Input**: Design documents from `/specs/049-select-runnable-optimization/`(spec.md / plan.md / research.md / data-model.md / contracts/api.md / quickstart.md)

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓(EXPLAIN 三轮隔离背书)

**Tests**: TDD — 测试先写(失败)再实现(调度正确性 + 性能双关)。

**Organization**: 按 user story。US1(P1)= selectRunnable 重构 + batchUpstreamReady = MVP;US2(P2)= 真机 R11;US3(P3)= 不变量。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件,无依赖)
- **[Story]**: US1/US2/US3
- 含精确文件路径

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 确认基线 + 工具

- [ ] T001 确认基线(本 worktree HEAD 含 main b6723a4 = 046+048)+ 核对无新依赖/无 schema 变更(纯查询优化 + Java 批量)
- [ ] T002 [P] 复核 cron-stress 工具可复用(`tmp/cron-stress/cron-stress.sh` 1000wf `*/2s`)+ EXPLAIN 命令(quickstart §1)

**Checkpoint**: 基线就位

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 配置 + selectRunnable 重构基础(US1/US2/US3 都依赖)

- [ ] T003 `application.yml` 加 `scheduler.claim-candidate-size: 200`(可配)+ `SchedulerKernel` 加 `claimCandidateSize` 字段(`@Value`)in `backend/dataweave-master/src/main/resources/application.yml` + `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerKernel.java`

**Checkpoint**: 候选 LIMIT 可配

---

## Phase 3: User Story 1 - 高负载认领查询稳定快 (Priority: P1) 🎯 MVP

**Goal**: selectRunnable 不随 WAITING 堆积退化(Index Scan 0.43ms vs 048 R10 244ms-1.6s),round_duration < 30ms 稳定

**Independent Test**: EXPLAIN 回归(Index Scan 无 Seq Scan/Sort)+ WAITING 堆积下 round_duration 稳定

### Tests for User Story 1（先写,失败）

- [ ] T004 [US1] `batchUpstreamReady` 单测:批量查 edge + pred state 组装 readyIds,覆盖 ① 强依赖 pred SUCCESS 就绪 ② 强依赖 pred 非 SUCCESS 未就绪 ③ 弱依赖 pred SUCCESS|FAILED 就绪 ④ 弱依赖 pred STOPPED 未就绪(中止非跑完)⑤ 无 edge 直通(单节点 wf)⑥ 多上游全就绪 ⑦ 多上游部分未就绪 ⑧ 多节点混合 in `backend/dataweave-master/src/test/java/com/dataweave/master/application/SchedulerKernelBatchUpstreamTest.java`

### Implementation for User Story 1

- [ ] T005 [US1] `selectRunnable` 重构 NORMAL 分支:去 NOT EXISTS 上游门 + `run_mode='NORMAL'`(替 `IN ('NORMAL','BACKFILL')`)+ LIMIT `claimCandidateSize`(200)+ 保留标量子查询(PK 快)+ 保留 workflow_instance state 门 in `SchedulerKernel.java`
- [ ] T006 [US1] `selectRunnable` 加 BACKFILL 分支(`run_mode='BACKFILL'` Index Scan,LIMIT `claimBatchSize` 50):selectRunnable 加 runMode 参数(TEST/NORMAL/BACKFILL),各等值用 Index Scan in `SchedulerKernel.java`
- [ ] T007 [US1] 实现 `batchUpstreamReady(List<Row> candidates) → Set<UUID readyIds>`:① 批量查 `workflow_edge WHERE deleted=0 AND to_node_id IN (...)`(行构造器 IN)② 批量查 pred `task_instance WHERE state IN ('SUCCESS','FAILED') AND (workflow_instance_id, workflow_node_id) IN (...)` ③ Java filter(强 SUCCESS / 弱 SUCCESS|FAILED / 无 edge 直通;语义同现状 NOT EXISTS `:290-298`)in `SchedulerKernel.java`
- [ ] T008 [US1] `claimAndMark` 串联:`normalCandidates = selectRunnable(NORMAL, claimCandidateSize)` → `readyIds = batchUpstreamReady ∩ batchCrossCycleReady` → filter + sort priority + take `claimBatchSize` → assign;BACKFILL 分支 `selectRunnable(BACKFILL)` → assign in `SchedulerKernel.java`
- [ ] T009 [US1] EXPLAIN 回归:`docker exec dataweave-postgres psql -c "EXPLAIN ANALYZE ..."` 核 selectRunnable NORMAL 走 Index Scan(无 Seq Scan/Sort),执行 < 5ms(research R1 复测)

**Checkpoint**: US1 EXPLAIN Index Scan + 单测绿 + round_duration 稳定低位

---

## Phase 4: User Story 2 - 认领跟上触发层物化 (Priority: P2)

**Goal**: 1000wf `*/2s` 全负载下 WAITING 不堆积、claim ≥ 600 inst/s

**Independent Test**: cron-stress 1000wf `*/2s` 跑 3min,WAITING 稳态不涨 + claim ≥ 600 inst/s

- [ ] T010 [US2] distributed 双 master 真机 R11 复测:① rebuild master image(mvn clean package + compose build)② cron-stress 1000wf `*/2s` ③ 抓 `scheduler_round_duration_seconds` / WAITING / `dw.dispatch.queue.size` ④ 核 SC-001 round < 30ms 稳定(不随堆积退化,048 R10=0.3-1.6s)⑤ 核 SC-002 WAITING 不堆积(048 R10=53 万)+ claim ≥ 600 inst/s ⑥ 核 046+048 不退化(queue.size ≤18 / full=0)复用 `tmp/cron-stress/`
- [ ] T011 [US2] 饱和判定:加压到认领吞吐不再提升,记录饱和认领吞吐(inst/s)+ 最先饱和资源

**Checkpoint**: R11 达成 SC-001/002 + 知饱和点

---

## Phase 5: User Story 3 - 认领不重复、不丢、无死锁 (Priority: P3)

**Goal**: selectRunnable 优化 + 上游门 Java 化后,4 不变量与恰好一次仍保持

**Independent Test**: 崩溃注入(认领事务提交后下发前杀 master-2)→ 重启核对无重复/无丢/无死锁

- [ ] T012 [US3] idempotency 单测:同一实例并发可见恰好认领一次(casDispatchBatch WHERE state='WAITING' CAS,048 已有,回归核)in `SchedulerKernelBatchUpstreamTest.java`
- [ ] T013 [US3] 崩溃注入不变量核对:`docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2`,核对 ① 无重复 dispatch ② 无丢失(认领事务提交未下发实例重派)③ actuator/日志无 deadlock
- [ ] T014 [US3] 死锁 4 不变量审计(代码层):① selectRunnable NORMAL/BACKFILL/TEST 都带 `FOR UPDATE SKIP LOCKED` ② CAS WHERE state='WAITING' 不变 ③ 锁顺序 task→workflow(batchUpstreamReady 是 SELECT 无锁,无新锁路径)④ 状态事务内 + dispatch 事务外(claimAndMark 内,048 已验)

**Checkpoint**: US3 不变量全核对通过

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T015 [P] `SchedulerMetrics` 加 `dw.claim.candidate.size` gauge(候选批量大小观测)in `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerMetrics.java`
- [ ] T016 full mvn test:`cd backend/dataweave-master && mvn clean test -Dmaven.build.cache.enabled=false`(避 build-cache 假绿,核 `Tests run: N>0`,BackfillServiceTest 预存红忽略 [[main-preexisting-red-tests]])
- [ ] T017 R11 实测结论回写 `specs/049-select-runnable-optimization/research.md`(round_duration + WAITING + claim 吞吐 + 饱和点 + EXPLAIN + 与 R10 对比)
- [ ] T018 memory 更新(新建 `weft-049-select-runnable-optimization.md`)+ 最终 commit

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖
- **Foundational (Phase 2)**: 依赖 Setup;**BLOCKS** 所有 US
- **US1 (Phase 3, MVP)**: 依赖 Foundational;T005-T008 同改 SchedulerKernel.java(顺序)
- **US2 (Phase 4)**: 依赖 US1
- **US3 (Phase 5)**: 依赖 US1;T012 可与 T010 并行
- **Polish (Phase 6)**: 依赖所有 US

### Within US1（核心,顺序）

T004 测试 → T005 selectRunnable NORMAL 重构 → T006 BACKFILL 分支 → T007 batchUpstreamReady → T008 claimAndMark 串联 → T009 EXPLAIN 回归

### Parallel Opportunities

- T002 与 T001(Setup)可并行
- T010(US2 真机)与 T012(US3 单测)可并行
- T015(metrics)与 T017(回写)可并行(不同文件)

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup(T001-T002)
2. Phase 2 Foundational(T003 配置)
3. Phase 3 US1(T004-T009 selectRunnable + batchUpstreamReady)
4. **STOP and VALIDATE**: EXPLAIN Index Scan + 单测绿
5. 进入 US2 真机

### Incremental Delivery

1. Setup + Foundational → claimCandidateSize 可配
2. US1 selectRunnable 重构 → EXPLAIN Index Scan + 单测绿 → MVP
3. US2 真机 R11 → SC-001/002 达成
4. US3 不变量 → 崩溃注入通过
5. Polish → full test + 回写 + memory

---

## Notes

- US1 T005-T008 同改 SchedulerKernel.java(顺序,非 [P])
- WSL2 长命令(mvn / docker / EXPLAIN)必 detach([[wsl2-long-command-detach]])
- maven-build-cache 假绿陷阱([[maven-build-cache-masks-tests]]):full test 必 `clean` + `-Dmaven.build.cache.enabled=false`
- 真机 rebuild:mvn clean package → compose build → force-recreate master([[weft-distributed-restart-neo4j-gap]])
- 046+048 不退化是硬约束:`dw.dispatch.queue.size` ≤18 / `queue.full.count` = 0 / batchCrossCycleReady + casDispatchBatch 单测仍绿
- EXPLAIN 回归是 US1 验收关键(Index Scan 无 Seq Scan/Sort),非可选
