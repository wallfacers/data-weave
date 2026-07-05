# Research: dispatch 链路并行化优化

> Phase 0 产出。技术决策详情见 [design doc](../../docs/superpowers/specs/2026-07-05-dispatch-parallelization-design.md);本文件提炼根因 + 决策 + 不变量核对,无未解决项。

## R1. 根因(Explore agent 实测 + 代码定位)

045 R9 结论"dispatch 瓶颈"经 Explore 精确定位为**三个串行放大器**:

| 放大器 | 现状(代码证据) | 影响 |
|---|---|---|
| SchedulerKernel `running` AtomicBoolean 全局串行 | `running.compareAndSet(false,true)`(`SchedulerKernel.java:61,110-123`),claim 事务 + dispatch 屏障捆成单线程 | 所有 WAKE 事件只有一个线程跑 round,其余 `rerun=true` 空转 |
| `assign()` N+1 查询 | 每行 `crossCycleReady`(1+N,`:325-355`)+ `contentOf`(1-2,`:381-391`)+ `paramsJsonOf`(1-2,`:402-413`)+ `casDispatch`(1),batch=50 → **250-350 次/轮** | 单轮 round duration ~200ms,认领速率 ≤ 250 inst/s |
| `dispatchAll` `invokeAll` 屏障 | `pool.invokeAll(tasks)`(`ParallelDispatcher.java:79`)等全完成才返回;parallelism=16 × batch=50 要 4 批 | dispatch 慢拖死 claim;distributed WebClient 无超时放大 |

**227s 语义**:`waitingSince`(task WAITING 的 `updated_at`)→ `casDispatch` 成功的 now(`:200-203`)= **纯认领排队延迟**(与下发/执行无关)。

**证据链**:claim_empty 61% / slot_util=0 / HikariCP active=1 → **CPU 串行点,非 DB/worker**。

## R2. 方案选型(杠杆 1 A/B/C + 杠杆 7 defer)

- **Decision**:杠杆 1 选 **方案 A(进程内队列)**,同 045 fireQueue 模式;杠杆 2+3 + 配套 4+5;杠杆 7(聚合)defer。
- **Rationale**:① 同 045 模式一致性高(复用 fireQueue/fireExecutor 经验 + 降级语义);② 单 claim + N+1 消除后 ~2500 inst/s 够(远超触发层 600 inst/s 物化);③ 死锁最安全(单 claim,SKIP LOCKED 无跨线程冲突);④ 聚合 defer 符合 YAGNI(先验证核心,实测再决)。
- **Alternatives rejected**:
  - 杠杆 1 方案 B(claim 分片):单 claim + N+1 消除后速率够,分片增加死锁核对面(多线程并发 SKIP LOCKED)。
  - 杠杆 1 方案 C(claim 内联异步):无背压(dispatch 池积压无控,可能 OOM)。
  - 杠杆 7(聚合异步化)本轮做:YAGNI,先验证 1+2+3 收益;聚合是否变瓶颈实测后决(可能开 047)。

## R3. claim/dispatch 解耦时序(不变量保持)

- **claimRound**(单线程,`running` 护事务):`claimAndMark`(SKIP LOCKED + CAS + assign 批量预取)→ 收集 `DispatchCommand` List → 事务提交 → 逐个 `dispatchQueue.offer(cmd, 200ms)`。`running` 在 offer 完成后释放(不等 dispatch)。
- **dispatchExecutor**(独立线程池):消费 dispatchQueue → `gateway.dispatch`(fire-and-forget)→ 失败 `onFailure.casRequeue`。
- **关键**:`casDispatch` 在 claim 事务内(状态事务内 ✓);`gateway.dispatch` 在事务外(dispatchExecutor 异步,事务提交后才 offer ✓)。`running` 范围从"claim+dispatch 整轮"缩到"只护 claim 事务"。

## R4. N+1 消除策略(杠杆 3)

- **content/params 批量预取**:`TaskDefVersionBatchLoader` 本轮 `SELECT … WHERE (task_id, version_no) IN (...)` 一次拿全(50 行 → 1 查询,原 50×2)。
- **Caffeine cache**:key=(task_id, version_no),content/params 静态可缓存(同 task 多 instance 共享,命中率高)。
- **crossCycleReady 批量化**:本轮所有 CRON 行依赖一次性 JOIN 查出,Java 层批量校验(50 行 → 1 查询,原 1+N)。
- **收益**:250-350 次/轮 → 10-20 次/轮,round duration ~200ms → ~20ms,claim 速率 ~2500 inst/s。

## R5. dispatchQueue 背压 + 降级(同 045 fireQueue)

- **有界队列**:`LinkedBlockingQueue`(capacity 2000,可配)。
- **offer 200ms 超时**:满则降级同步 dispatch(当前 claim 线程跑 `gateway.dispatch`)+ `markQueueFull` 背压信号。降级同步让该轮 claim 变慢(背压传导),不丢。
- **dispatchExecutor 池**:`ThreadPoolExecutor`(64 线程,可配),`RejectedExecutionHandler` 满则降级同步(双保险)。
- **shutdown drain**:master 关闭时 `drainQueue`,残余 `casRequeue` 防丢(同 045 fireExecutor shutdown)。

## R6. WebClient 超时(去屏障必须)

- **问题**:`DistributedTaskExecutionGateway` 的 `webClient.post().block()` 无超时(`WebClientConfig.java:17-21` 只 `WebClient.builder()`)。去屏障 fire-and-forget 后慢 worker 会挂 dispatchExecutor 线程。
- **fix**:`WebClientConfig` 加 `HttpClient.create().responseTimeout(3s)` + `connectTimeout(2s)`,所有 WebClient 共享。
- **超时后**:`onFailure.casRequeue`(DISPATCHED→WAITING),下一轮重派。

## R7. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 046 方案 | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | 保留;claim 单线程(`running` 护事务),无跨线程冲突 | claimRound 内 `selectRunnable … FOR UPDATE SKIP LOCKED`,单线程持锁 |
| ② CAS 状态推进 | `casDispatch`/`casRequeue` 不变 | WAITING→DISPATCHED(claim)→失败→WAITING(requeue) |
| ③ 锁顺序 task→workflow | 不变(无跨表锁) | assign 仅 task_instance CAS |
| ④ 状态事务内 + dispatch 事务外 | `casDispatch` 在 claim 事务内;`gateway.dispatch` 在事务外(dispatchExecutor 异步,事务提交后才 offer) | claimRound:`txTemplate.execute(claim)` → commit → offer queue → executor 异步 dispatch |

**多 master distributed**:每 master 独立 claim(各自 `running` + SKIP LOCKED),`casDispatch` 跨 master 由 DB 行锁保证唯一认领(task_instance 状态 CAS)。

## R8. 测试方法(待 implement 后实测回写 R9)

- 复用 045 cron-stress(`tmp/cron-stress/`,1000wf `*/2s` 极限档,distributed 双 master)。
- cron-watch 扩展抓 `dw.dispatch.queue.size` / `queue.full.count` / `execute.latency`(`/actuator/prometheus`,双 master)。
- 新断言:dispatch_latency p99 < 5s(R9=227s)/ dispatch 吞吐 ≥600 inst/s / queue.size 稳态不涨 / 幂等无重复 dispatch。
- 崩溃注入:`docker kill dataweave-master-2 && docker start` → dispatchExecutor 残余 DispatchCommand → `casRequeue` 回填核对 + 无重复。
- **SC-002 slot_util 复测**:rebuild worker image(ShellTaskExecutor 真跑 sleep),验证 slot 真实容量(045 SC-002 遗留;当前 slot_util=0 是 dispatch 瓶颈掩盖的结果)。
- 饱和判定:持续加压到 dispatch 吞吐不再提升,记录最先饱和指标(dispatchExecutor / DB 连接 / worker slot / 聚合)。
