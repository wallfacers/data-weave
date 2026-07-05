# Data Model: dispatch 链路并行化优化

> 046 是进程内 + 配置优化,**无 schema 改动**(`schema_version` 不 bump,保持 0.7.1)。本文件描述进程内组件 + 配置。

## 无表改动

046 不动任何 DB 表(`task_instance` / `workflow_def` / `worker_nodes` / `cron_fire` 等不变)。`SchedulerKernel` claim 的数据访问框架不变,仅 SQL 形态批量预取化(SELECT IN)。`schema_version` 保持 **0.7.1**(045 落地值)。

## 进程内组件(新)

### DispatchCommand(record,新)

认领事务内已读全的下发任务载荷,认领线程 → `dispatchQueue` → `dispatchExecutor` 间传递。**避免 dispatch 时再查**(N+1 消除的关键)。

| 字段 | 类型 | 说明 |
|---|---|---|
| instanceId | Long | task_instance.id |
| workflowId | Long | workflow_def.id |
| taskId | Long | task_def.id |
| content | String | 任务内容(claim 批量预取,已读) |
| paramsJson | String | 参数 JSON(claim 批量预取,已读) |
| attempt | int | 重试次数 |
| lease | LocalDateTime | lease 到期时刻 |
| workerNodeCode | String | 目标 worker(SlotManager 分配) |
| bizDate | String | 业务日期(注入 DW_BIZ_DATE) |
| timeoutSeconds | int | 执行超时 |

### dispatchQueue + dispatchExecutor(进程内,新)

- **dispatchQueue**:`LinkedBlockingQueue<DispatchCommand>`(capacity 可配,默认 2000)
- **dispatchExecutor**:`ThreadPoolExecutor`(threads 可配,默认 64;自定义 `RejectedExecutionHandler` —— 满则当前线程同步跑 `gateway.dispatch` + `markQueueFull`,不丢)
- 模式同 045 fireQueue + fireExecutor(降级语义一致)
- **shutdown drain**:`@PreDestroy` drainQueue,残余 DispatchCommand `casRequeue` 防丢

### TaskDefVersionBatchLoader + Caffeine cache(新)

- **批量预取**:本轮 `SELECT … FROM task_def_version WHERE (task_id, version_no) IN (...)` 一次拿全 content/params
- **Caffeine cache**:key=`(task_id, version_no)`,value=content/params;content/params 静态(版本冻结),同 task 多 instance 共享,命中率高;maximumSize/expireAfterAccess 可配

## 配置项(application.yml,新)

| 配置 | 默认 | 说明 |
|---|---|---|
| `scheduler.dispatch-queue-capacity` | 2000 | dispatchQueue 有界容量(背压) |
| `scheduler.dispatch-executor-threads` | 64 | dispatchExecutor 池大小 |
| `scheduler.dispatch-webclient-timeout-ms` | 3000 | distributed WebClient 响应超时 |

`docker-compose.yml`(distributed 双 master env)同步追加 `SCHEDULER_DISPATCH_QUEUE_CAPACITY` / `SCHEDULER_DISPATCH_EXECUTOR_THREADS` / `SCHEDULER_DISPATCH_WEBCLIENT_TIMEOUT_MS`。

## 指标(SchedulerMetrics,新)

| 指标 | 类型 | 说明 |
|---|---|---|
| `dw.dispatch.queue.size` | gauge | dispatchQueue 当前深度(背压观测) |
| `dw.dispatch.queue.full.count` | counter | 队列满降级次数(背压信号) |
| `dw.dispatch.execute.latency` | timer | dispatch HTTP 耗时(`gateway.dispatch` 调用) |

## 不变的组件(复用,V 复用内核)

- `InstanceStateMachine`:`casDispatch`/`casRequeue` 状态机不变(WAITING→DISPATCHED→失败→WAITING)
- `DefaultSchedulingPolicy`:least-loaded 纯 CPU 策略不变
- `SlotManager`:`snapshotOnline` + 槽位分配不变
- `EventBus`:WAKE 通道不变(InMemoryEventBus 同步派发保留)
- `WorkflowStateService` / `WorkerReportService`:聚合 + 回报路径不变(杠杆 7 defer)
