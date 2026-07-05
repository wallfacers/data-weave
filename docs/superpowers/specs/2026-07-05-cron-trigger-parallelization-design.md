# 045 cron-trigger 并行化优化 — 设计

**日期**:2026-07-05
**状态**:已批准(brainstorming 终态 → 转 SDD specify/plan)
**worktree**:`dw-045-cron-parallel`(分支 `045-cron-trigger-parallelization`,基于 main `f2bc30c`)
**前置**:044 瓶颈结论(详见 `dw-044-cron-perf/specs/044-cron-concurrency-perf/research.md` R7)

## 背景与动机

044 实测(50wf `*/10 * * * * *`,distributed 双 master):
- US1 正确性 ✓:970 个 CRON 实例全 SUCCESS,0 重复
- US2 量化:吞吐 1-3 inst/s(被节流),p99 0.248s
- **US3 瓶颈 = cron-trigger 单线程串行 fire**:`slot_utilization=0.1` 执行端全闲,但 cron_fire 增长 < 50wf/10s 预期

explore 定位**多级瓶颈**(非单线程):
1. `DefaultTriggerEngine.timer` **硬编码池=2**(`newScheduledThreadPool(2)`)→ 50 点同到期仅 2 线程并发 fire
2. `fire()` 同步阻塞 `triggerService.trigger()` 物化(~0.25s/次)
3. `WorkflowTriggerService.trigger` **无 `@Transactional`**;`taskInstanceRepository.save(ti)` **循环逐条 INSERT**(L282)→ N 节点 N 次 DB 往返 + N 次 commit
4. HikariCP 无配置,**默认=10**(044 入口已打满)

实测每 fire ~0.25s ≈ 3-4 SELECT + 1 INSERT(wi)+ N INSERT(ti)+ 1 UPDATE(cron_fire)+ 1 UPDATE(wf)。**真正节流的是「池=2 × 每 fire 同步 N 次往返」**。

## 目标

- **找极限**:迭代压测直到 CPU / DB 连接 / 锁 先饱和,记录最大吞吐与最先饱和者
- **底线**:相对 044 基线(1-3 inst/s)提升 **≥10x**(达 30+ inst/s)且 `slot_utilization > 0.5`(执行端真干活,不再空转)
- **硬约束**:无死锁(遵守 CLAUDE.md 死锁防御 4 不变量)

## 设计概览(方案 A:进程内队列 + reconciler)

```
timer 线程(池 cron-trigger-timer-threads)
  到点 → fireArm(): 校验 → INSERT cron_fire(同步去重,不变)
        → fireQueue.put(FireTask) → 返回(μs 级,不阻塞)

fire worker 池(cron-fire-worker-threads)
  从 fireQueue(有界)取 FireTask → fireExecute():
     triggerService.trigger(物化) → 回填 cron_fire.instance_id+status=FIRED
     → advanceNext → metrics

CronFireReconciler(@Scheduled)
  扫 cron_fire instance_id IS NULL && created_at<now-grace → 幂等重试
  超 timeout 仍 NULL → status=DEAD + 告警
```

**三路径**:
- **正常**:arm(μs)→ 队列 → N worker 并发物化 → 吞吐 ∝ worker 数(解 044 的池=2 串行)
- **崩溃**:队列 FireTask 丢 → cron_fire 有行无 instance_id → reconciler ≤30s 补 → 幂等挡重
- **满队列**:offer 超时 → 降级同步(timer 直接 fireExecute,不丢)+ `metrics.markQueueFull`(背压可观测)

**为何选 A 而非 B(DB 持久队列)/ C(Redis Stream)**:A 最快(无 DB 锁)贴合"找极限";复用 cron_fire 真相源;崩溃 ≤30s 补偿可接受;符合 4 不变量。B 的 trigger 物化在 SKIP LOCKED 事务内持锁久 → 重新节流,违性能目标;C 复杂且 Redis 强依赖(all-in-one 模式风险)。

## 组件改动

### 1. `DefaultTriggerEngine` 重构
- `timer` 池:2 → 可配 `scheduler.cron-trigger-timer-threads`(默认 8)
- 新增 `fireExecutor`:`scheduler.cron-fire-worker-threads`(默认 32),固定线程池
- 新增 `fireQueue`:`LinkedBlockingQueue<FireTask>`,容量 `scheduler.cron-fire-queue-capacity`(默认 4000)
- `fire()` 拆分:
  - **`fireArm(wfId, due)`**(timer 线程):校验 ONLINE / 生效期 / misfire → `INSERT cron_fire (status=PENDING)`(UNIQUE 去重,不变)→ `fireQueue.offer(FireTask, 200ms)`:超时 → **fallback 同步 `fireExecute`**(不丢)+ `metrics.markQueueFull` + warn
  - **`fireExecute(FireTask)`**(worker / 降级 timer):应用层幂等查 → `triggerService.trigger` → 回填 cron_fire(`instance_id`, `status=FIRED`, `fired_at`)→ `advanceNext` → `metrics.recordFireExecuteLatency`

### 2. `WorkflowTriggerService` 批量化
- `@Transactional` 包裹 trigger(原子写 workflow_instance + N task_instance,减 commit)
- taskInstance 循环 `save` → 收集 `saveAll`(N 次往返 → 1 次)
- `wake()` 移到 `@TransactionalEventListener(AFTER_COMMIT)`(redis publish 在事务提交后,守不变量 ④)

### 3. `CronFireReconciler`(新组件)
- `@Scheduled(fixedRateString = "${scheduler.cron-reconcile-interval-ms:10000}")`
- `SELECT cron_fire WHERE instance_id IS NULL AND created_at < :threshold LIMIT :batch`
- 每行:应用层幂等查 → 已有 instance 则回填;无 → `triggerService.trigger` → 回填
- 超 `cron-reconcile-timeout-ms` 仍 NULL → `status=DEAD` + `log.error`
- 多 master 并发扫同 NULL 行:DB 唯一约束撞键 → 查已有回填(安全)

### 4. schema 改动(`schema_version` bump)
- `cron_fire`:+ `status` 列(`PENDING`/`FIRED`/`DEAD`)+ 索引 `(instance_id, created_at)`(reconciler 扫)
- `workflow_instance`:UNIQUE `(workflow_id, scheduled_fire_time) WHERE scheduled_fire_time IS NOT NULL`(幂等防重;手动/补数据 scheduled_fire_time=NULL 不受约束,零误伤)
- `application.yml`:新增 `scheduler.cron-trigger-timer-threads` / `cron-fire-worker-threads` / `cron-fire-queue-capacity` / `cron-reconcile-interval-ms` / `cron-reconcile-grace-ms` / `cron-reconcile-timeout-ms`;`spring.datasource.hikari.maximum-pool-size` 默认 10 → 40

### 5. docker-compose(distributed)
- postgres 加 `command: postgres -c max_connections=200`(支持极限档)
- master×2 env:追加 `SCHEDULER_CRON_TRIGGER_TIMER_THREADS` / `SCHEDULER_CRON_FIRE_WORKER_THREADS` / `SCHEDULER_CRON_FIRE_QUEUE_CAPACITY` / `SCHEDULER_CRON_RECONCILE_*` / `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`

## 死锁防御不变量核对(CLAUDE.md 4 硬规则)

| 不变量 | 本方案 |
|---|---|
| ① SKIP LOCKED claim | 不依赖(进程内队列 + cron_fire UNIQUE);reconciler 普通 SELECT,多 master 并发重复由 workflow_instance 唯一约束兜底(撞键 → 查已有回填) |
| ② CAS 状态推进 | trigger 物化 = INSERT 新行;advanceNext 沿用现有 workflow_def save(现有行为,不引入新竞态) |
| ③ 锁顺序 task→workflow | 不持有跨表行锁(trigger 事务内仅 INSERT,无显式锁) |
| ④ 状态事务内 + dispatch 事务外 | trigger `@Transactional`;`wake()` 移到 `AFTER_COMMIT`(redis publish 在提交后) |

## 幂等三层(防重复 instance)

1. `cron_fire UNIQUE (workflow_id, scheduled_fire_time)` — **谁创建**(已有,不变)
2. `workflow_instance UNIQUE (workflow_id, scheduled_fire_time) WHERE scheduled_fire_time IS NOT NULL` — **DB 兜底**并发/崩溃重试(新增)
3. 应用层快查 — fireExecute/reconciler 入口 SELECT 已存在则跳过(新增,避免撞键异常开销)

## 资源池配置(两档,均 application.yml 可配)

约束链:**worker ≤ HikariCP**;**双 master × HikariCP ≤ PG max_connections**(实测 PG=100、当前 26 连接)。下表 timer/worker/queue 值均为**单个 master 进程内**的配置(distributed 双 master 各自独立持有);HikariCP 也是每 master 一个池。

| 配置项 | 默认档(开箱即用) | 极限档(找极限,改 PG) |
|---|---|---|
| `cron-trigger-timer-threads` | 8 | 8(fireArm 极轻,非瓶颈) |
| `cron-fire-worker-threads` | 32 | **64** |
| `hikari.maximum-pool-size` | 40 | **64** |
| `cron-fire-queue-capacity` | 4000 | **8000** |
| PG `max_connections` | 100(不动) | **200**(docker-compose postgres `command`) |
| 双 master 连接占用 | 80(留 20 余量)✓ | 128 < 200 ✓ |
| 预期 | 50wf 同点 <100ms 全创建,达 ≥10x 底线 | 打满 DB 写/CPU,定位下一饱和点 |

**045 验证方法论**:先跑默认档量化基线 → 切极限档找天花板,两次对比即"找极限"。

## Metrics(找极限必需)

**新增**:`dw.cron.fire.queue.size`(gauge)/ `dw.cron.fire.queue.full.count`(counter,降级频率=背压)/ `dw.cron.fire.execute.latency`(timer,物化耗时)/ `dw.cron.fire.arm.latency`(timer)/ `dw.cron.reconcile.replayed|skipped|dead`(counter)
**复用 044**:`dw.cron.trigger.latency` / `slot_utilization` / `dispatch_count`

## 测试策略

- **复用 044 cron-stress**(50/100/200 wf 同触发点)
- **新断言**:队列深度不持续涨、`queue.full.count`=0(默认档)/可观测(极限档)、`reconcile.replayed`=0(无崩溃不应触发)、**workflow_instance 幂等**(无重复 scheduled_fire_time 实例)
- **单元**:fireArm/fireExecute 拆分、满队列降级、reconciler 幂等跳过、`@Transactional` 回滚原子性、`saveAll` 批量正确性
- **真跑**:cron-watch 加 `queue.size` / `full.count` / `reconcile` 列
- **压测**:默认档 + 极限档对比,记录饱和点

## 风险

- `@Transactional` 包裹 trigger:物化在单事务,节点多时锁持有长 → 但 INSERT 不持显式锁,且 `saveAll` 减往返净改善
- reconciler 误重试:三层幂等兜底,撞键查已有回填
- 极限档 64 worker × 双 master = 128 连接 → 必须 PG `max_connections=200`(docker-compose 改)
- `eventBus.publish` 移到 AFTER_COMMIT:延迟 publish ~物化时长(可接受,触发即排队,worker 立即消化)

## 不在范围

- 入口 `/run` 吞吐优化(044 基线 185 req/s;HikariCP 扩容后自动受益,非本 feature 重点)
- 执行端 / dispatch 内核(044 证明非瓶颈,`slot_util=0.1`)
- Redis Stream(方案 C)/ DB 持久队列(方案 B)— 未选
