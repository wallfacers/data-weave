# Research: cron 触发并发吞吐优化

> Phase 0 产出。技术决策详情见 [design doc](../../docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md);本文件提炼关键决策与证据,无未解决项。

## R1. 多级瓶颈根因(explore 实测 + 代码定位)

044 结论"单线程串行 fire"经 explore 精化为**多级瓶颈**:

| 层 | 现状(代码证据) | 影响 |
|---|---|---|
| `DefaultTriggerEngine.timer` | `Executors.newScheduledThreadPool(2)` 硬编码(`DefaultTriggerEngine.java:58`) | 50 点同到期仅 2 线程并发 fire |
| `fire()` 同步阻塞 | 调 `triggerService.trigger(...)` 串行等物化(`:229`) | timer 线程被占满 |
| `WorkflowTriggerService.trigger` | 无 `@Transactional`;`taskInstanceRepository.save(ti)` 循环逐条 INSERT(`WorkflowTriggerService.java:282`) | N 节点 = N 次 DB 往返 + N 次 commit |
| HikariCP | 无 `maximum-pool-size` 配置 → Spring Boot 默认 10 | 044 入口已打满 |

实测每 fire ~0.25s ≈ 3-4 SELECT + 1 INSERT(wi)+ N INSERT(ti)+ 1 UPDATE(cron_fire)+ 1 UPDATE(wf)。**真正节流的是"池=2 × 每 fire 同步 N 次往返"**,非单线程。

## R2. 方案选型(A vs B vs C)

- **Decision**:**方案 A**(进程内队列 + reconciler + 三层幂等)。
- **Rationale**:① 最快(无 DB 锁)贴合"找极限";② 复用 `cron_fire` 真相源(无新介质);③ 崩溃 ≤30s 补偿可接受;④ 完全符合死锁 4 不变量。
- **Alternatives rejected**:
  - B(DB 持久队列 `FOR UPDATE SKIP LOCKED`):trigger 物化在 SKIP LOCKED 事务内持锁久 → 阻塞认领 → 重新节流,违性能目标;且 `cron_fire` 读放大 + 锁竞争。
  - C(Redis Stream):ACK/PEL 处理复杂 + Redis 强依赖(all-in-one 模式风险),收益不抵复杂度。

## R3. fire 拆 arm/execute 的时序(不变量保持)

- **fireArm(timer 线程)**:校验 ONLINE/生效期/misfire → `INSERT cron_fire (status=PENDING)`(UNIQUE 去重,与现状语义一致)→ `fireQueue.offer(FireTask, 200ms)`;超时 → fallback 同步 `fireExecute`(不丢)+ `markQueueFull`。
- **fireExecute(worker / 降级 timer)**:应用层幂等查 → `triggerService.trigger`(物化)→ 回填 `cron_fire(instance_id, status=FIRED, fired_at)`→ `advanceNext` → metrics。
- **关键**:去重真相仍是 `cron_fire` UNIQUE(同步,不变);异步化的是"物化",不改变"谁创建"的语义。多 master 仍各自 arm,到点 INSERT 撞键放弃(零协调)。

## R4. 幂等三层(防重复 instance)

1. `cron_fire UNIQUE (workflow_id, scheduled_fire_time)` — 谁创建(已有,不变)。
2. `workflow_instance UNIQUE (workflow_id, scheduled_fire_time) WHERE scheduled_fire_time IS NOT NULL` — DB 兜底并发/崩溃重试/TOCTOU(新增)。
   - 手动/补数据 `scheduled_fire_time=NULL` 不受约束(零误伤);`triggerBackfillTaskRun` 走独立路径不传 scheduled_fire_time。
3. 应用层快查 — `fireExecute`/reconciler 入口 SELECT 已存在则跳过(新增,避免撞键异常开销)。
- **多 master 并发扫同 NULL 行**:DB 唯一约束撞键 → `DataIntegrityViolationException` → 查已有回填(安全,无需 advisory lock)。

## R5. 资源池两档 + PG 约束(实测)

- **约束链**:worker(物化占 1 连接)≤ HikariCP;双 master × HikariCP ≤ PG `max_connections`。
- **实测**:PG `max_connections=100`(默认),当前 26 连接(双 master + 双 worker)。双 master × HikariCP ≤ 90 → 默认档 HikariCP=40 安全(双×40=80,留 20 余量)。
- **极限档**:worker=64/HikariCP=64,双×64=128 → 需 `docker-compose` postgres `command: postgres -c max_connections=200`。
- **两档均 application.yml 可配**;045 验证:默认档量化基线 → 极限档找天花板。

## R6. 死锁防御 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 本方案 | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | 不依赖(进程内队列 + `cron_fire` UNIQUE) | reconciler 普通 SELECT,多 master 并发由 workflow_instance 唯一约束兜底 |
| ② CAS 状态推进 | trigger 物化 = INSERT 新行;`advanceNext` 沿用现有 `workflow_def` save | 不引入新竞态(现有行为) |
| ③ 锁顺序 task→workflow | 不持有跨表行锁 | trigger `@Transactional` 内仅 INSERT,无显式锁 |
| ④ 状态事务内 + dispatch 事务外 | trigger `@Transactional`;`wake()` 移 `@TransactionalEventListener(AFTER_COMMIT)` | redis publish 在事务提交后 |

## R7. 测试方法(找极限)

- 复用 044 cron-stress(50/100/200 wf `*/10 * * * * *`,distributed 双 master)。
- **两档对比**:默认档(8/32/40/4000,PG=100)量化基线 → 极限档(8/64/64/8000,PG=200)找饱和点。
- **新断言**:队列深度不持续涨、`queue.full.count`=0(默认档)/可观测(极限档)、`reconcile.replayed`=0(无崩溃不应触发)、**幂等**(无重复 scheduled_fire_time 实例)。
- **崩溃注入**:kill master → 队列 FireTask 丢 → reconciler 30s 补 → 核对 instance 最终创建。
- **饱和判定**:持续加压到吞吐不再提升,记录最先饱和指标(CPU/DB 连接/锁/worker 池)。

## R8. 实测结论(2026-07-05,distributed 双 master 极限档 8/64/64/8000 + PG=200)

**US1 并发吞吐 ✓✓ 超 ≥10x 目标(SC-001 达成)**:
- 200wf `*/10 * * * * *`:每触发点 +200 instance(并发全创建,无积压),稳态 **40 inst/s**(cron 间隔限制,非 worker 瓶颈)
- 700wf `*/2`(密负载探天花板):峰值 **~340 inst/s**(1700/5s),对比 044 **~100-300x**;worker=64 **仍未饱和**(queue=0/full=0),平均物化 24.4ms(max 0.123s)
- 50wf `*/10`:~10 inst/s(50/10s 间隔)
- 对比 044 基线 1-3 inst/s:**~13-340x 提升**

**US2 找极限(SC-006)**:
- 平均物化 **29.7ms**(`dw.cron.fire.execute.latency` sum 49.1s / count 1651)vs 044 ~250ms → **~8x 物化加速**(`@Transactional` + `saveAll` + worker 并发)
- worker=64 **未饱和**(200wf queue.size=0 / queue.full=0)→ worker 天花板未触及,需 1000+ wf 或 `*/2s` cron 继续加压;**700wf */2s 实测 340 inst/s 仍 queue=0**,worker 天花板 > 340 inst/s(HikariCP=64 双 master 理论 ~5000 inst/s,实测受 wf 创建速度 + cron 间隔限制未触顶)
- p99 0.51s(200wf 并发,稳定波动 0.1-0.5s)

**US3 可靠**:
- 幂等 **0 重复**(workflow_instance UNIQUE + 应用层快查,SC-003 ✓)
- queue.full.count=0(无背压降级,FR-006 待命)
- T017 崩溃注入(kill master-2):reconciler 待命(replayed=0 因 kill 时机无悬空 FireTask;cron_fire NULL=0 即系统健康无丢失);死锁 4 不变量保持(SC-005 ✓)
- slot_utilization=0.0:ECHO task 秒完不占 slot(非节流;真实慢 task 场景才有意义,SC-002 需慢 task 验证)

**额外 fix(045 rebuild 暴露 pre-existing main bug)**:
- I18nConfig 冲突:api pom 依赖 worker + 两个 `@Configuration I18nConfig` 同名 class bean 撞车(commit `0329143` 加 worker I18nConfig 后,api fat jar 含两份;044 distributed 用旧 image 未触发,045 rebuild 暴露)。fix:worker I18nConfig `@Configuration("workerI18nConfig")` + `@Bean` 重命名(workerMessageSource/workerMessages);api I18nConfig `@Primary`。

**结论**:045 方案 A(进程内队列 + worker 池 + 三层幂等 + reconciler + 批量物化)**完全解除 044 的触发层节流**,达成 ≥10x 吞吐目标(实测 13-40x)。worker=64 在 200wf 下未饱和,真实极限需更密负载进一步压榨(后续可探)。

## R9. 探天花板(2026-07-05,1000wf */2s 极限档)— 瓶颈转移到 dispatch 端

**目标**:探触发层 worker(fireExecutor=64)天花板,逼近 HikariCP=64 双 master 理论 ~5000 inst/s。truncate 14.9万 残留后干净基线。

**实测(1000wf `*/2 * * * * *`,3min cron-watch 稳态)**:
- 触发层物化:**稳态 600 inst/s**(每 5s +3000),双 master fire count=303517,**queue_size=0 / queue_full=0 全程** → **触发层未饱和**
- 触发延迟 p99=**0.561s**(物化→started_at,稳定波动 0.54-0.56s)
- 幂等:**0 重复**(202260 CRON instance,cron_fire 1:1 零丢失)→ SC-003 在 20万规模保持

**瓶颈转移到 dispatch 端(核心发现)**:
- `scheduler_dispatch_latency` max=**227.77s**(!)→ task 物化后排队等 dispatch
- workflow_instance RUNNING 持续堆积:85505(30s)→ **159347**(180s),~410 inst/s 进 RUNNING 未消化
- SUCCESS 仅 42913(3min +3158,~17/s 聚合)→ dispatch + 状态聚合跟不上触发层
- ECHO task 秒完,堆积非执行慢,是 **SchedulerKernel claim / ParallelDispatcher 速率限制**

**天花板判定**:
- **触发层 worker(fireExecutor=64)天花板未触顶**:理论 64/24ms × 2 master ≈ 5333 inst/s,实测需求 600 远低;queue 全程 0
- **HikariCP/PG 不是瓶颈**:active=**1**/64,PG 138/200,连接池远未用
- **系统真实天花板 = dispatch 端**:600 inst/s 物化已让 dispatch 堆积;要测触发层真实极限(5333)需更密 cron(如 2000wf `* * * * * *`),但 dispatch 会先爆

**结论**:045 触发层并行化**成功解除触发层节流**,瓶颈明确**转移到 dispatch/执行端**。触发层 worker 天花板(理论 ~5333 inst/s)**无法在本配置独立触顶**——dispatch 端先饱和(600 inst/s 即堆积)。后续优化方向 = dispatch 端(ParallelDispatcher 并发 / SchedulerKernel claim 速率 / 状态聚合),属新 feature 范畴。

**slot_util(SC-002)**:RUNNING 堆积 159347 体现执行端 backlog,但 ECHO 秒完非真占 slot;真实 slot_util 需慢 task(SHELL sleep / HTTP)验证,defer。
