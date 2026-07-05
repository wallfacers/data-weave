# Design: dispatch 链路并行化优化(046)

> 基于 045 R9 瓶颈结论(触发层并行化后瓶颈转移到 dispatch 端),解除 dispatch 认领串行,使 dispatch 吞吐跟上触发层物化。
>
> 上游:045 cron 触发层并行化(已 merge main,`a0e47bf`)。045 R9 实测:1000wf `*/2s` 稳态物化 600 inst/s(触发层 queue=0 未饱和),但 `scheduler_dispatch_latency` max **227.77s**,task_instance 物化后排队等 dispatch,workflow_instance RUNNING 堆积 159347,SUCCESS 聚合仅 ~17/s。

## 1. 根因分析(Explore agent 结论)

### 227s 延迟语义
`waitingSince`(task_instance 置 WAITING 的 `updated_at`)→ `casDispatch` 成功的 `now`(`SchedulerKernel.java:200-203`)。**纯认领排队延迟,与下发/执行无关**。

### 三个串行放大器(认领速率 < 物化速率 → WAITING 队列单调增长)

1. **SchedulerKernel `running` AtomicBoolean 全局串行**(`SchedulerKernel.java:61, 110-123`):所有 `scheduleOnce`(WAKE 触发)被串行化,只有一个线程跑 `runRound`(claim 事务 + dispatch 屏障),其余 `rerun=true` 空转。claim 事务与 dispatch I/O 捆绑成单线程。
2. **`assign()` N+1 查询**(`SchedulerKernel.java:181-223`):每行 `crossCycleReady`(1+N)+ `contentOf`(1-2)+ `paramsJsonOf`(1-2)+ `casDispatch`(1 UPDATE),batch=50 → **250-350 次串行 DB 往返/轮**,严重拖长 round duration。
3. **`dispatchAll` `invokeAll` 屏障**(`ParallelDispatcher.java:79`):parallelism=16 处理 batch=50 要 4 批,且 `pool.invokeAll` 阻塞至全完成才返回,下一轮 claim 才能开始。distributed 下 `DistributedTaskExecutionGateway` 的 `webClient.post().block()` **无超时**(`WebClientConfig.java:17-21`),慢 worker 拖死整轮。

### 证据链(瓶颈是 CPU 串行点,非 DB/worker)
- `claim_empty=13021 / claim_rounds=21215`(61% 空):认领受限于 worker 槽位回收速率
- `slot_utilization=0`:worker 快速回报(task 不在 worker 排队),执行端非瓶颈
- `HikariCP active=1/64`:scheduler 单线程持连做认领事务,采样瞬间只看到 1
- PG 138/200:DB 远未饱和

## 2. 设计目标 + 范围

**目标**:解除 dispatch 认领串行,使 dispatch 吞吐跟上触发层物化(≥600 inst/s),dispatch_latency p99 < 5s。

**范围(用户批准:激进 1+2+3 + 配套 4+5,聚合 7 defer)**:
- 杠杆 1:claim↔dispatch 解耦(进程内队列,同 045 fireQueue 模式)
- 杠杆 2:`dispatchAll` 去屏障(fire-and-forget)
- 杠杆 3:`assign()` N+1 消除(批量预取 + Caffeine 缓存 + crossCycleReady 批量化)
- 配套 4:`WebClient` 加超时(去屏障必须,防慢 worker 挂 dispatchExecutor 线程)
- 配套 5:`claim-batch-size` / `dispatch-executor-threads` 配比(随队列模式重调)
- **杠杆 7(聚合异步化)defer**:解除认领后 SUCCESS 速率飙升,若 `computeAndUpdate` 变新瓶颈,实测后开新 feature。spec 文档化为后续。

## 3. 架构与数据流

```
当前(045 后):
  物化 → WAKE → scheduleOnce →[running 全局串行] runRound:
    claimAndMark 事务(SKIP LOCKED + CAS + assign N+1: 250-350 查询/轮)
    dispatchAll(invokeAll 屏障: 16 并发 × batch=50 要 4 批,等全完才下一轮)

046 后:
  物化 → WAKE → scheduleOnce → claimRound(running 只护 claim 事务):
    claimAndMark(SKIP LOCKED + CAS + assign 批量预取: 10-20 查询/轮)
    DispatchCommand.offer(dispatchQueue, 200ms 超时)→ 满则降级同步 dispatch + markQueueFull
                                  ↓
    dispatchExecutor 池(64 线程,异步消费):
      gateway.dispatch(fire-and-forget, WebClient 3s 超时)
      失败 → onFailure.casRequeue(DISPATCHED→WAITING) + lease 清除 → 下一轮重试
```

**核心变化**:
- `running` AtomicBoolean 范围从"claim 事务 + dispatch 屏障整轮"缩小到"只护 claim 事务";dispatch 移出 `runRound`,推队列后 claimRound 立即返回,下一轮 claim 可马上开始。
- `dispatchAll` 的 `invokeAll` 屏障 → `dispatchQueue.offer` + `dispatchExecutor` 异步消费(类似 045 fireQueue + fireExecutor)。
- `assign()` N+1(250-350 查询/轮)→ 批量预取 + 缓存(10-20 查询/轮),单轮 round duration 从 ~200ms 降到 ~20ms。

## 4. 组件改动

| 组件 | 变化 | 文件:行号 |
|---|---|---|
| `SchedulerKernel` | `runRound` 拆 `claimRound`(只跑 claim 事务 + 推队列,不等 dispatch);`running` 只护 claim 事务;新增 `dispatchQueue`(`LinkedBlockingQueue<DispatchCommand>`,capacity 可配)+ `dispatchExecutor`(`ThreadPoolExecutor`,threads 可配,自定义 `RejectedExecutionHandler` 满则降级同步)+ `markQueueFull` | `SchedulerKernel.java:61, 110-151` |
| `DispatchCommand`(新 record) | `(instanceId, workflowId, taskId, content, paramsJson, attempt, lease, workerNodeCode, ...)` —— claim 事务内已读全的数据,避免 dispatch 时再查 | `SchedulerKernel` 内部 record |
| `ParallelDispatcher.dispatchAll → dispatchAllAsync` | 改 `dispatchQueue.offer` 立即返回(去 `invokeAll`);失败回调 `onFailure.casRequeue` 不变;`dispatchExecutor` 消费时调 `gateway.dispatch` | `ParallelDispatcher.java:63-84` |
| `assign()` 批量化 | `TaskDefVersionBatchLoader`(本轮 `SELECT … WHERE (task_id, version_no) IN (...)` 一次拿全 content/params)+ Caffeine 缓存(task_def_version key,content/params 静态可缓存)+ `crossCycleReady` 批量化(本轮所有 CRON 行依赖一次性 JOIN,Java 层批量校验) | `SchedulerKernel.java:181-223, 325-355, 381-413` |
| `WebClientConfig` | 加 `responseTimeout(3s)` + `connectTimeout(2s)`(reactor-netty `HttpClient`),所有 WebClient 共享(distributed dispatch + 其他) | `WebClientConfig.java:17-21` |
| `SchedulerMetrics` | 新增 `dw.dispatch.queue.size`(gauge)/ `queue.full.count`(counter)/ `dw.dispatch.execute.latency`(timer,dispatch HTTP 耗时) | `SchedulerMetrics.java` |

### claim/dispatch 解耦实现(杠杆 1,进程内队列)
- **claim 线程**:单线程(`running` 护事务),跑 SKIP LOCKED + CAS + assign 批量预取 → 收集 `List<DispatchCommand>` → 事务提交后逐个 `dispatchQueue.offer(cmd, 200ms)`。`running` 在 offer 完成后释放(不等 dispatch)。
- **dispatchQueue**:有界 `LinkedBlockingQueue`(capacity 2000,可配),背压信号。
- **dispatchExecutor**:`ThreadPoolExecutor`(64 线程,可配),消费队列,`gateway.dispatch`。满拒绝(队列 + executor 都满)→ `RejectedExecutionHandler` 降级:当前调用线程同步跑 `gateway.dispatch` + `markQueueFull`(不丢,同 045 fireQueue)。
- **N+1 消除后单 claim 轮询快**(~20ms):claim 速率 = ~50 轮/s × batch=50 = 2500 inst/s,远超触发层 600 inst/s 物化,claim 不再瓶颈。

## 5. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 046 方案 | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | 保留;claim 单线程(`running` 护事务),无跨线程冲突 | `claimRound` 内 `selectRunnable … FOR UPDATE SKIP LOCKED`,单线程持锁 |
| ② CAS 状态推进 | `casDispatch`/`casRequeue` 不变 | WAITING→DISPATCHED(claim)→失败→WAITING(requeue),沿用 `InstanceStateMachine` |
| ③ 锁顺序 task→workflow | 不变(无跨表行锁) | `assign` 仅 task_instance CAS,不持 workflow 行锁 |
| ④ 状态事务内 + dispatch 事务外 | `casDispatch` 在 claim 事务内;`gateway.dispatch` 在事务外(`dispatchExecutor` 异步,事务提交后才 offer 队列) | `claimRound`:txTemplate.execute(claim)→ commit → offer queue → executor 异步 dispatch |

**多 master distributed**:每 master 独立 claim(各自 `running` + SKIP LOCKED),`casDispatch` 跨 master 由 DB 行锁保证唯一认领(task_instance 状态 CAS)。

## 6. 错误处理

- **dispatchQueue 满**:`offer` 200ms 超时 → 降级同步 dispatch(当前 claim 线程跑 `gateway.dispatch`)+ `markQueueFull` 背压信号(同 045 fireQueue 满)。降级同步会让该轮 claim 变慢(背压传导到 claim),但不丢。
- **dispatch 失败(worker 不可达/HTTP 错误)**:`onFailure` 回调 `casRequeue(DISPATCHED→WAITING)` + lease 清除 → 下一轮 claim 重试(已有机制,`SchedulerKernel.java:147-149`,不变)。
- **WebClient 3s 超时**:distributed 下慢 worker 3s 超时 → 触发 `onFailure.casRequeue`,不挂 dispatchExecutor 线程(去屏障必须)。
- **dispatchExecutor shutdown**(master 关闭):`shutdown` 时 `drainQueue`,剩余 DispatchCommand `casRequeue`(防丢,同 045 fireExecutor 关闭)。

## 7. 配置(`application.yml`)

```yaml
scheduler:
  dispatch-queue-capacity: 2000          # dispatchQueue 有界容量(背压)
  dispatch-executor-threads: 64          # dispatchExecutor 池大小
  claim-batch-size: 50                   # 不变(可调,随队列模式重调)
  dispatch-webclient-timeout-ms: 3000    # distributed WebClient 响应超时
```

`docker-compose.yml`(distributed 双 master env)同步追加 `SCHEDULER_DISPATCH_QUEUE_CAPACITY` / `SCHEDULER_DISPATCH_EXECUTOR_THREADS` / `SCHEDULER_DISPATCH_WEBCLIENT_TIMEOUT_MS`。

## 8. 成功标准

- **SC-001**:`scheduler_dispatch_latency` p99 < 5s(R9 = 227s max,p99 未测但 RUNNING 堆积暗示极高)
- **SC-002**:dispatch 吞吐 ≥600 inst/s(跟上触发层物化,R9 SUCCESS 仅 ~17/s)
- **SC-003**:幂等(无重复 dispatch,`casDispatch` CAS 保证;dispatch 失败 `casRequeue` 不产生重复)
- **SC-004**:死锁 4 不变量保持(代码审查 + 长跑核对)
- **SC-005**:`dispatchQueue.size` 稳态不持续涨(`queue.full.count`=0 或可观测,非无限积压)
- **SC-006**(找极限,同 045):探 dispatch 层天花板,记录最先饱和指标(dispatchExecutor 池 / DB 连接 / worker slot)

## 9. 测试方法

- **复用 045 cron-stress**(`main tmp/cron-stress/cron-stress.sh`,1000wf `*/2s` 极限档):量化 dispatch_latency / queue.size / queue.full / dispatch 吞吐。
- **cron-watch 扩展**:抓 `dw.dispatch.queue.size` / `queue.full.count` / `dw.dispatch.execute.latency`(经 `/actuator/prometheus`,双 master)。
- **崩溃注入**:`docker kill dataweave-master-2 && docker start` → dispatchExecutor 残余 DispatchCommand → 核对 `casRequeue` 回填(WAITING 重派)+ 无重复 dispatch。
- **单元测试**:
  - `SchedulerKernelTest`:`claimRound` 不等 dispatch(offer 立即返回)/ dispatchQueue 满降级同步 + markQueueFull / assign 批量预取(批量 SELECT IN,N+1 消除)/ WebClient 超时 casRequeue
  - `ParallelDispatcherTest`:`dispatchAllAsync` offer 队列立即返回(去 invokeAll 屏障)
- **slot_util 复测**(SC-002 045 遗留):解除 dispatch 瓶颈后,task 真正到 worker,slot_util 应升高(>0.5?需 rebuild worker image 让 ShellTaskExecutor 真跑 sleep)。

## 10. 杠杆 7(聚合)defer

解除认领后 SUCCESS 速率从 ~17/s 飙升,`WorkflowStateService.computeAndUpdate`(`:110-136`,每次 task 终态同步聚合 3-5 查询)可能变新瓶颈。**046 不做,实测后开新 feature**:
- 实测方法:046 落地后跑 1000wf `*/2s`,看 SUCCESS 速率是否被聚合限制(若 dispatch_latency 低但 SUCCESS 速率仍低,聚合是瓶颈)
- 若瓶颈:debounce(同 wf 多 task 完成合并一次聚合)或异步(丢队列 worker 池消费)或 workflow_instance 加 dirty 标志后台扫描

spec 文档化为 046 后续待办。

## 11. 不做(YAGNI)

- **不动 worker 侧执行**(ShellTaskExecutor / InProcessTaskExecutionGateway 池):slot_util=0 证明执行端非瓶颈(解除 dispatch 后复测)
- **不改 EventBus 同步派发**(杠杆分析里的辅助放大器,影响有限)
- **不重构 claim SQL**(SKIP LOCKED + NOT EXISTS 上游门 SQL 保留,PG 非瓶颈)
- **不做 claim 分片**(杠杆 1 备选方案 B):单 claim + N+1 消除后 ~2500 inst/s 够,分片增加死锁核对面
