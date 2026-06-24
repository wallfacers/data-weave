## Why

补数据(data-ops-center M1)已支持按 任务/工作流 × 日期区间生成 `run_mode='BACKFILL'` 实例,`parallelism` 字段被**记录但不强制**——一次提交 365 天区间会把全部 bizDate 实例一次性插入 WAITING,跨 bizDate 并发只受全局调度/worker 容量自然约束。这会在补长区间时瞬间打满集群、挤占线上周期调度。M1 当时刻意推迟硬节流,原因是**朴素的「按实例数限并发」会错误串行化单个 bizDate 的工作流 DAG**(同一天的 DAG 有多个本应并行的节点)。本 change 落地正确的 **bizDate 粒度** 节流:`parallelism=N` 严格表示「同时最多 N 个 bizDate 链在跑」。

## What Changes

- 新增 `task_instance.backfill_held` 标志列:补数据实例生成时,超出 `parallelism` 配额的 bizDate 整批置 `held=1`(不可被认领),配额内的置 `held=0`。
- 调度认领门(`SchedulerKernel` claim SQL)新增 `AND ti.backfill_held=0` 守卫——与 M1 已有的 `frozen` 守卫同构,**不新增认领分支、不破坏四条死锁不变量**。
- 新增 `BackfillPromoter` 组件:当一个 backfill_run 的某个活跃 bizDate 链全部进入终态,经**乐观 CAS** 晋升下一个 held bizDate(整批 `held=1→0`)并发 WAKE 事件。事件驱动(订阅实例终态)+ 周期 sweep 兜底(防漏事件)。
- `BackfillService.submitBackfill` 按 bizDate 升序分配 held 配额(前 N 个 bizDate held=0,其余 held=1);`parallelism ≥ bizDate 总数` 时全部 held=0,退化为 M1 行为(无节流)。
- `BackfillRunView` 进度补充 `activeDates`/`heldDates` 计数,供前端展示「N 并发 / M 待晋升」。
- 测试:promoter 晋升时序、bizDate 粒度(工作流 DAG 同日不被串行化)、parallelism 退化、死锁不变量回归。

## Capabilities

### New Capabilities
- `backfill-parallelism-throttle`: 补数据 bizDate 粒度并发节流——held 标志 + 调度认领守卫 + 晋升器(完成即晋升下一批),保证 `parallelism=N` 严格语义且不串行化单 bizDate 的 DAG。

### Modified Capabilities
- `scheduler-core`: 周期实例认领门新增 `backfill_held=0` 守卫(与 `frozen` 守卫同构),held 实例不可被认领;守卫推进经晋升器乐观 CAS,认领路径四条死锁不变量不变。

## Impact

- **schema.sql**(dataweave-api):`task_instance` 加 `backfill_held SMALLINT DEFAULT 0` + `idx_task_instance_backfill_held`(部分索引,仅 held=1)。
- **dataweave-master**:`SchedulerKernel`(claim SQL 加守卫)、`BackfillService`(生成侧配额分配 + 视图计数)、新增 `BackfillPromoter`(application 层,EventBus 订阅 + `@Scheduled` sweep)、`OpsContracts.BackfillRunView`(加 activeDates/heldDates)。
- **dataweave-api**:`DataOpsBridgeRealImpl` + `BackfillRun` dto 透传新增计数字段(纯映射,无新端点)。
- **frontend**:补数据 run 卡片展示并发/待晋升计数(非阻塞,可独立小改)。
- **不影响**:认领路径死锁不变量、PolicyEngine 闸门链路、AG-UI 事件 schema、既有 REST 端点签名(仅响应体加字段,向后兼容)。
