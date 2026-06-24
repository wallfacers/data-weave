## 1. Schema & 列(dataweave-api/schema.sql)

- [x] 1.1 `task_instance` CREATE 增加 `backfill_held SMALLINT DEFAULT 0` 列(紧随 `backfill_run_id`)
- [x] 1.2 增加索引 `idx_task_instance_backfill_held ON task_instance(backfill_run_id, backfill_held)`(普通复合索引,H2/PG 兼容;持有集随晋升单调缩小)
- [x] 1.3 H2 与 PG 各启动一次,确认建表/索引无方言报错(api 218 测试均跑 H2;真后端 E2E 跑 PG)

## 2. 认领守卫(dataweave-master/SchedulerKernel)

- [x] 2.1 claim SQL(`SchedulerKernel.java:262` 非 test 分支)在 frozen 守卫后追加 `AND COALESCE(ti.backfill_held,0)=0`
- [x] 2.2 确认 test 分支(run_mode=TEST)不受影响(补数据不会是 TEST)
- [x] 2.3 回归:NORMAL 实例 held 默认 0 仍正常认领;死锁不变量(SKIP LOCKED/CAS)未动(master 197 + api 218 全绿)

## 3. 生成侧配额分配(dataweave-master/BackfillService)

- [x] 3.1 `submitBackfill` 按 bizDate 升序,前 `parallelism` 个 bizDate held=0、其余 held=1;held 经 trigger 在 INSERT 时即定(根除「插入后→置 held 前」被抢认领的竞态)
- [x] 3.2 `parallelism ≥ |bizDates|` 时全部 held=0(零开销退化 M1)
- [x] 3.3 `TaskInstance` domain 加 `Integer backfillHeld` 字段 + getter/setter;`newTaskInstance` 默认 0;两个 backfill trigger 入口 threading held

## 4. 晋升器(dataweave-master/BackfillPromoter)

- [x] 4.1 新增 `BackfillPromoter`(application 层):构造注入 `JdbcTemplate` + `EventBus` + `PlatformTransactionManager`(真 `TransactionTemplate`)
- [x] 4.2 `promoteEligible()`:遍历 RUNNING backfill_run,`SELECT … FOR UPDATE` per-run 行锁 → 重算 activeDates → while activeDates<parallelism 晋升最小 held bizDate(`UPDATE … SET backfill_held=0 …`)→ 提交后 `eventBus.publish(WAKE_CHANNEL)`;全终态收敛 run.state
- [x] 4.3 订阅 `WAKE_CHANNEL`(`@PostConstruct`):每次 wake 调 `promoteEligible()`
- [x] 4.4 `@Scheduled`(默认 15s,`backfill.promote.sweep-ms`)兜底 sweep
- [x] 4.5 晋升与认领事务隔离:行锁只锁 backfill_run、副作用(publish)在提交后(invariant ④)

## 5. 进度可观测(契约透传)

- [x] 5.1 `OpsContracts.BackfillRunView` 加 `activeDates`/`heldDates`
- [x] 5.2 `BackfillService.toView` 聚合派生 activeDates(held=0 且非终态 distinct bizDate)/heldDates(held=1 distinct bizDate)
- [x] 5.3 api `BackfillRun` dto + `DataOpsBridgeRealImpl.toDtoRun` 透传两字段
- [x] 5.4 前端 backfill-panel run 行展示「N 并发 / M 待晋升」(`backfillThrottle` 双语键,zh/en 零漂移)

## 6. 测试

- [x] 6.1 `BackfillServiceTest.taskBackfillGeneratesOnePerDate`:parallelism=2、3 个 bizDate → 前 2 held=0、第 3 held=1
- [x] 6.2 `parallelismAtLeastDateCountReleasesAll`:parallelism≥bizDate 数 → 全 held=0
- [x] 6.3 `BackfillPromoterTest.promotesNextHeldDateWhenCapacityFree`:配额空闲 → 晋升下一个 held bizDate + 发 WAKE
- [x] 6.4 `doesNotPromoteWhenAtCapacity`:active 达 parallelism → 不晋升不超发(严格 N)
- [x] 6.5 `convergesRunStateWhenAllTerminal`:全终态 → run.state 收敛 SUCCESS
- [x] 6.6 `workflowBackfillTriggersDagPerDate`:同 bizDate 整张 DAG 共享同一 held(不串行化同日并行节点)
- [x] 6.7 `KernelSchedulingTest.backfillHeldInstance_notClaimed_untilReleased`(H2 集成):held=1 不被认领、置 0 后离开 WAITING
- [x] 6.8 `sweepAlsoPromotes`:无 WAKE 时 sweep 仍补晋升

## 7. 收尾验证

- [x] 7.1 `./mvnw -q -pl dataweave-master compile` + `-pl dataweave-api compile` 零错误
- [x] 7.2 master 197 + api 218 测试全绿(含新增用例);前端 typecheck 通过
- [x] 7.3 真闭环 E2E(`BackfillThrottleE2ETest`,@SpringBootTest H2 全栈):提交 4 天 parallelism=2 → 真 BackfillPromoter bean + 真调度 + 真 worker → 收敛 4/4 SUCCESS、heldDates=0;日志实证 `[BackfillPromoter] 收敛终态 SUCCESS（total=4 success=4 failed=0）`
- [x] 7.4 spec 与实现一致性回读:行为一致;design D2 由「先插入再批量 UPDATE」回写为「INSERT 时即定 held」(根除竞态的正向偏差)
