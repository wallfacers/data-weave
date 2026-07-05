# API Contracts: dispatch 链路并行化优化

> 046 是后端内部优化,**无新外部 API**(REST/MCP 端点不变)。本文件描述内部组件契约。

## 无外部 API 改动

046 不动任何 REST 端点(`/api/ops/*` / `/api/workflows/*` / `/api/fleet` 等)或 MCP 工具。前端 / MCP 客户端 / dw CLI **无感知**。唯一外部可观测变化是 `/actuator/prometheus` 新增 dispatch 指标(见 data-model.md)。

## 内部组件契约

### SchedulerKernel.runRound → claimRound(重构)

- **输入**:WAKE 事件(`EventBus` DWAKE 通道)/ poll 兜底(`scheduler.poll-interval-ms`)
- **行为**:
  1. `running.compareAndSet(false,true)` 护 claim 事务(范围缩小,只护事务不护 dispatch)
  2. `txTemplate.execute(claimAndMark)`:`selectRunnable … FOR UPDATE SKIP LOCKED`(batch=50)+ `assign`(批量预取 content/params + Caffeine cache + crossCycleReady 批量化)+ `casDispatch`(WAITING→DISPATCHED,`recordDispatchLatency`)
  3. 事务提交 → 收集 `List<DispatchCommand>`
  4. 逐个 `dispatchQueue.offer(cmd, 200ms)`(满则降级同步 dispatch + `markQueueFull`)
  5. 释放 `running`(**不等 dispatch**)
- **不变量**:`casDispatch` 在事务内;`gateway.dispatch` 在事务外(dispatchExecutor 异步,事务提交后才 offer)

### ParallelDispatcher.dispatchAll → dispatchAllAsync(重构)

- **输入**:`List<DispatchCommand>`(claimRound 收集)
- **行为**:逐个 `dispatchQueue.offer`(立即返回,**去 `invokeAll` 屏障**);dispatchExecutor 消费调 `gateway.dispatch`
- **失败处理**:`onFailure.casRequeue`(DISPATCHED→WAITING)+ lease 清除 → 下一轮 claim 重派(已有机制,不变)
- **并发度**:由 dispatchExecutor 池大小承载(默认 64,替代原 invokeAll parallelism=16)

### DispatchCommand(record)

见 [data-model.md](../data-model.md)。claim 事务内已读全的载荷,避免 dispatch 时再查(N+1 消除)。

### WebClientConfig(扩展)

- 所有 `WebClient` bean 加 `responseTimeout(3s)` + `connectTimeout(2s)`(reactor-netty `HttpClient`)
- **影响范围**:`DistributedTaskExecutionGateway` dispatch + 其他 WebClient 依赖(如有,统一超时)
- **超时后**:dispatch Mono → `onError` → `onFailure.casRequeue`(DISPATCHED→WAITING)

## 不变的契约(复用)

- `TaskExecutionGateway` 接口:`dispatch(DispatchCommand)` / `InProcessTaskExecutionGateway` / `DistributedTaskExecutionGateway` 实现签名不变(内部超时化)
- `WorkerReportService`:`/api/cluster/report` 回报契约不变(reportStarted/Finished/Failed + recomputeWorkflow)
- `SchedulerKernel` 对外:`scheduleOnce()`(WAKE 触发)签名不变
