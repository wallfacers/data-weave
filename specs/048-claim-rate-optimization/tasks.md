# Tasks: claim 速率深优化

**Input**: Design documents from `/specs/048-claim-rate-optimization/`(spec.md / plan.md / research.md / data-model.md / contracts/api.md / quickstart.md)

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓

**Tests**: TDD — 测试先写(失败)再实现(本 feature 涉及调度正确性,测试必备)。

**Organization**: 按 user story 组织。US1(P1)= 认领批量化核心 = MVP;US2(P2)= 真机上限;US3(P3)= 不变量可靠性。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件,无依赖)
- **[Story]**: US1/US2/US3
- 含精确文件路径

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 确认基线 + 无新依赖/配置/Schema

- [ ] T001 确认 046 基线在位(本 worktree HEAD 含 046 全部 commit `42b3665→d5a8670`)+ 核对无新依赖/无新配置/无 schema 变更(本 feature 纯代码重构,application.yml 不改)
- [ ] T002 [P] 复核 cron-stress 工具可复用(`tmp/cron-stress/cron-stress.sh` 1000wf `*/2s` 极限档 + cron-watch,distributed 双 master)

**Checkpoint**: 基线就位,可开始实现

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1/US2/US3 都依赖的前置改动

- [ ] T003 Row 加 `workflowId` 字段 + `selectRunnable(false)` NORMAL SQL 加标量子查询 `(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wfid`(Row 映射填入) in `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerKernel.java`

**Checkpoint**: Row 携带 workflowId,crossCycleReady 批量化可读

---

## Phase 3: User Story 1 - 认领速率跟上物化 (Priority: P1) 🎯 MVP

**Goal**: 1000wf `*/2s` 全负载下 `scheduler_dispatch_latency` p99 < 5s、WAITING 不堆积(消除三个 N+1)

**Independent Test**: cron-stress 1000wf `*/2s` 跑 3min,dispatch_latency p99<5s + WAITING 稳态不涨

### Tests for User Story 1（先写,失败）

- [ ] T004 [US1] `batchCrossCycleReady` 单测:deps Map + COUNT Map 组装 readyIds 正确性,覆盖 ① 上游周期 SUCCESS 就绪 ② 未就绪排除 ③ 首周期豁免(bizDate<earliestBizDate) ④ prevBizDate LAST_DAY 偏移 ⑤ 多 dep 全就绪 ⑥ 非 CRON 直通 ⑦ 空 deps in `backend/dataweave-master/src/test/java/com/dataweave/master/application/SchedulerKernelTest.java`
- [ ] T005 [US1] `casDispatchBatch` + assign 三阶段集成测试:一批 placements → UPDATE FROM VALUES updateCount==size → 后处理 out.add + publishTaskState;含 content 解析失败 casFailed(DISPATCHED→FAILED)不下发 in `SchedulerKernelTest.java`

### Implementation for User Story 1

- [ ] T006 [US1] 实现 `batchCrossCycleReady(List<Row> cronNormals) → Set<UUID readyIds>`:① 批量查依赖 `WHERE (workflow_id, node_id) IN (...) AND enabled=1 AND deleted=0 AND earliest_biz_date IS NOT NULL`(行构造器 IN)② Java 算 prevBizDate=offsetBizDate 去重 ③ 批量 COUNT `SELECT workflow_node_id, biz_date, COUNT(*) … GROUP BY` ④ 组装 Map 校验就绪(保留首周期豁免) in `SchedulerKernel.java`
- [ ] T007 [US1] `claimAndMark` 用 `batchCrossCycleReady(normals)` 返回的 readyIds filter normals,替换单行 `.filter(this::crossCycleReady)` in `SchedulerKernel.java`
- [ ] T008 [US1] 实现 `casDispatchBatch(List<Placement> placements, LocalDateTime now) → int updateCount`:`UPDATE task_instance SET state='DISPATCHED', worker_node_code=v.nc, lease_expire_at=v.ls, attempt=v.at, updated_at=? FROM (VALUES (?,?,?,?),…) AS v(id,nc,ls,at) WHERE task_instance.id=v.id AND state='WAITING' AND deleted=0`(无 RETURNING,H2 T5 + PG 双兼容) in `backend/dataweave-master/src/main/java/com/dataweave/master/application/InstanceStateMachine.java`
- [ ] T009 [US1] 重构 `assign` 为三阶段:① 阶段1 place 所有行收集 placements(`ns.used++` 乐观占槽,policy.place 逻辑不变)② 阶段2 `casDispatchBatch`(updateCount==size 防御核对,不符 log warn)③ 阶段3 后处理逐个 `resolveContentSafely`(读 046 T008 缓存)+ `out.add(DispatchCommand)`+ `publishTaskState(id,"DISPATCHED")`;content 失败 `casFailed(DISPATCHED→FAILED)` 保留现状语义 in `SchedulerKernel.java`

**Checkpoint**: US1 单测全绿 + 集成测试通过,assign 三阶段闭环,三个 N+1 消除

---

## Phase 4: User Story 2 - 找认领吞吐上限 (Priority: P2)

**Goal**: 知道认领层饱和点与最先饱和资源,容量规划

**Independent Test**: 加压到认领吞吐不再提升,记录饱和吞吐 + 最先饱和资源

- [ ] T010 [US2] distributed 双 master 真机 R10 复测:① rebuild master image(mvn clean package + compose build)② cron-stress 1000wf `*/2s` ③ cron-watch 抓 `scheduler_dispatch_latency` / WAITING / `dw.dispatch.queue.size` ④ 核 SC-001 p99<5s(R9=135s) ⑤ 核 SC-002 WAITING 不堆积(R9=67859) ⑥ 核 046 不退化(queue.size≤18 / full=0) 复用 `tmp/cron-stress/`
- [ ] T011 [US2] 饱和判定:加压(增 wf 数或触发频率)到认领吞吐不再提升,记录饱和认领吞吐(inst/s)+ 最先饱和资源(认领线程 / DB 连接 HikariCP / worker slot / 聚合),写 quickstart 观察

**Checkpoint**: R10 实测达成 SC-001/002 + 知饱和点

---

## Phase 5: User Story 3 - 认领不重复、不丢、无死锁 (Priority: P3)

**Goal**: 批量化改变认领形态后,4 不变量与恰好一次语义仍保持

**Independent Test**: 崩溃注入(认领事务提交后下发前杀 master-2)→ 重启核对无重复/无丢/无死锁

- [ ] T012 [US3] idempotency 单测:同一实例并发可见恰好认领一次(casDispatchBatch WHERE state='WAITING' CAS 语义,无重复 dispatch) in `SchedulerKernelTest.java`
- [ ] T013 [US3] 崩溃注入不变量核对:`docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2`,认领事务已提交未下发的实例被重派(casRequeue 路径),核 ① 无重复 dispatch ② 无丢失 ③ actuator/日志无 deadlock
- [ ] T014 [US3] 死锁 4 不变量审计(代码层):① SKIP LOCKED 单线程 claim 保留(`running` 护事务)② casDispatchBatch 带 WHERE state='WAITING' CAS 语义 ③ 锁顺序 task→workflow 无跨表锁(dependency/COUNT 无锁读)④ 状态事务内(casDispatchBatch 在 txTemplate)+ dispatch 事务外(dispatchAllAsync 不变)

**Checkpoint**: US3 不变量全核对通过,批量化无正确性退化

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T015 [P] `SchedulerMetrics` 加 `dw.claim.batch.size` gauge(认领批量大小观测,AutoGauge/AtomicLong,build+meter) in `backend/dataweave-master/src/main/java/com/dataweave/master/application/SchedulerMetrics.java`
- [ ] T016 full mvn test:`cd backend/dataweave-master && mvn clean test -Dmaven.build.cache.enabled=false`(避 build-cache 假绿,核 `Tests run: N>0`)
- [ ] T017 R10 实测结论回写 `specs/048-claim-rate-optimization/research.md`(dispatch_latency p99 + WAITING 稳态 + 饱和吞吐 + 最先饱和资源 + 与 R9 对比)
- [ ] T018 memory 更新(新建 `weft-048-claim-rate-optimization.md` 或在 `weft-046-dispatch-parallelization.md` 补 048 续作)+ 最终 commit

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖,立即开始
- **Foundational (Phase 2)**: 依赖 Setup;**BLOCKS** 所有 US
- **US1 (Phase 3, MVP)**: 依赖 Foundational;核心实现(T006-T009 同改 SchedulerKernel/InstanceStateMachine,顺序执行)
- **US2 (Phase 4)**: 依赖 US1(批量化实现完才能真机测)
- **US3 (Phase 5)**: 依赖 US1(批量化实现完才能测不变量);US3 测试(T012)可与 US2 真机(T010)并行
- **Polish (Phase 6)**: 依赖所有 US

### Within US1(核心,顺序)

T004/T005 测试 → T006 batchCrossCycleReady → T007 claimAndMark filter → T008 casDispatchBatch → T009 assign 三阶段

### Parallel Opportunities

- T002 与 T001(Setup)可并行
- T010(US2 真机)与 T012(US3 idempotency 单测)可并行
- T015(metrics gauge)与 T017(回写 research)可并行(不同文件)

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 Setup(T001-T002)
2. Phase 2 Foundational(T003 Row+workflowId)
3. Phase 3 US1(T004-T009 批量化核心)
4. **STOP and VALIDATE**: 单测全绿 + H2 集成测试通过
5. 进入 US2 真机验证

### Incremental Delivery

1. Setup + Foundational → Row 携带 workflowId
2. US1 批量化核心 → 单测绿(三个 N+1 消除) → MVP
3. US2 真机 R10 → SC-001/002 达成
4. US3 不变量 → 崩溃注入通过
5. Polish → full test + 回写 + memory

---

## Notes

- US1 T006-T009 同改 SchedulerKernel.java(顺序,非 [P]);T008 改 InstanceStateMachine.java(可与 T006/T007 不同文件但语义依赖,顺序)
- WSL2 长命令(mvn / docker)必 detach(见 CLAUDE.md 硬规则);java -cp 兼容探针已秒回
- maven-build-cache 假绿陷阱:full test 必 `clean` + `-Dmaven.build.cache.enabled=false`,只认 `Tests run: N>0`([[maven-build-cache-masks-tests]])
- 真机 rebuild master image:mvn clean package → compose build → down && up(见 [[weft-distributed-restart-neo4j-gap]])
- 046 不退化是硬约束:`dw.dispatch.queue.size` ≤18 / `queue.full.count` = 0
