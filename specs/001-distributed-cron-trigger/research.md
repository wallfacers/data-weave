# Phase 0 Research: 分布式 Cron 精确触发

调研基于精读 `dataweave-master` 调度代码、`schema.sql`/`application.yml`、以及 `mvn dependency:tree`。所有结论带代码事实支撑。

## D1. 触发引擎机制:进程内精确触发器(替代 60s 轮询)

- **Decision**: 采用 `java.util.concurrent.ScheduledExecutorService`(单/少线程,内部 DelayedWorkQueue 为堆式定时器)承担「到点精确触发」角色;**不**默认引入 Netty `HashedWheelTimer`。
- **Rationale**:
  - 分片后单 master 只预读自己分片、且只装「未来 ~30s 预读窗口」内的到期点 → 同时在册的延迟任务量级为**数百级**,远未到 HashedWheelTimer 才有优势的「单进程数万定时器」规模。
  - 精度目标是**秒级**(p99 ≤ 2s),`schedule(delay)` 的亚秒级精度绰绰有余。
  - **零新依赖**:`io.netty:netty-common` 当前只在 `dataweave-api`(经 Lettuce 传递),`dataweave-master` 直接依赖中**无 Netty**;用 STPE 免去为 master 引库。
- **Alternatives considered**:
  - **Netty HashedWheelTimer**(备选):预读+时间轮经 PowerJob 验证,但需给 master 显式加 `io.netty:netty-common`(~500KB);仅当后续 profiling 显示定时器吞吐成瓶颈再切换,接口层对 `TriggerEngine` 透明。
  - **DelayQueue 自研**:过度工程,与现有事件驱动不契合,否决。
  - **继续缩短轮询(如 1s tick)**:仍是粗轮询,抖动大、DB 压力高,无法稳定达标秒级,否决。
- **设计含义**: `TriggerEngine` 对外暴露「计时器」抽象,内部实现可在 STPE / HWT 间替换,不影响 `CronScheduler` 与测试。

## D2. 切入点:改动收敛到 `CronScheduler`,下游与内核不动

- **Decision**: 仅重构 `CronScheduler.java`;新增 `TriggerEngine` / `TimingStrategy`;`SchedulerKernel`、`WorkflowTriggerService`、`cron_fire` 去重、`InstanceStateMachine` **零改动**。
- **Rationale**(代码事实):
  - `CronScheduler.tick()` `@Scheduled(fixedRate=60000)`(`CronScheduler.java:49-67`)是唯一 cron 命中入口;`tryFire()`(:70-131)负责 cron 解析→到期点→`cron_fire` 护栏插入(:115,`DataIntegrityViolationException` 捕获去重)→ 调 `triggerService.trigger(...)`(:120)→ 更新 `last_fire_time`。
  - `SchedulerKernel.poll()`(`SchedulerKernel.java:102-106`)/`scheduleOnce()`/`runRound()` 是**实例认领与派发**,与 cron 命中解耦;cron 只产出 `workflow_instance`,再经 `EventBus.publish("dw:wake")`(`WorkflowTriggerService` 内,:272)唤醒派发。
  - `WorkflowTriggerService.trigger(WorkflowDef, String triggerType, String bizDate, Integer priorityOverride, Locale)`(`WorkflowTriggerService.java:97-100`)签名稳定,另有 3 处调用点(CronScheduler / DefaultPlatformActionExecutor / BackfillService)→ **必须保持不变**。
- **Alternatives considered**: 把扫描+触发塞进 `SchedulerKernel` 统一轮询 —— 会把「实例派发」与「cron 命中」两套关注点耦合,破坏死锁防御边界,否决。
- **改造要点**: `tick()` 60s→15s 且只做「预读窗口扫描 + 委派精确触发」;`tryFire` 退化为「校验生效期 + 护栏去重 + 调下游 + 重算 next_trigger_time」,cron 解析/到期点计算移入 `TimingStrategy`。

## D3. 去重与分片:保留零协调,新增 master 成员表做预读分片

- **Decision**: 去重真相仍是 `cron_fire` 唯一键 `(workflow_id, scheduled_fire_time)`(`schema.sql:567-577`,`CronFire.java`);为 >10k 规模新增 `master_nodes` 注册表 + `MasterRegistry`,按 `hash(workflow_id) % activeMasterCount` 让每个 master **只预读自己分片**。分片只决定「谁把点装进自己的定时器」,**不**决定「谁有权触发」——到点触发瞬间仍竞争 `cron_fire` 唯一键。
- **Rationale**(代码事实):
  - 现状**无 master 成员机制**:无 `master_nodes`/`MasterRegistry`,多 master 靠「全量扫 + cron_fire 唯一键冲突」零协调去重(正确但 >10k 下每 master 全量入定时器,负载随总量线性膨胀,违反 SC-007 单机负载目标)。
  - 已有 `worker_nodes` 表 + 心跳惯例(`schema.sql:695-718`,`FleetService` 30s `OFFLINE_THRESHOLD`,`incarnation` 纪元)可**照搬**到 master 自注册:`master_code = hostname + "-" + pid`、`last_heartbeat`、超时剔除。
  - 分片漂移(master 上下线、活集合变化导致某点短暂被两个/零个 master 预读)由 `cron_fire` 唯一键兜底:被两个预读 → 唯一键保证只触发一次;短暂零预读 → 下一轮 15s 扫描 + 过期点 delay=0 立即补,延迟有界。
- **Alternatives considered**:
  - **PowerJob App→Server 选主 + 分布式锁**:引入单点选主与锁,与本平台「零协调」哲学相悖,且 `cron_fire` 已能保证一次触发,否决(spec Assumptions 已锁定)。
  - **不做分片、仅靠 cron_fire**:功能正确,但 >10k 下重复负载,不达 SC-007,故作为 Batch A 的可交付中间态保留、Batch B 补分片。
- **设计含义**: `master_nodes` 与分片是**独立的 Batch B**,Batch A 不依赖它即可正确运行(退化为全量预读)。

## D4. Cron 库:Spring `CronExpression` 原生含秒,无需 cron-utils

- **Decision**: 秒级 cron 直接用现有 `org.springframework.scheduling.support.CronExpression`(6 字段:秒/分/时/日/月/周),**不**引入 cron-utils / Quartz。
- **Rationale**:
  - `CronExpression` 在 Spring Framework 6+/Boot 4.0 原生支持 6 字段,`*/30 * * * * *`(每 30 秒)可直接解析;当前代码已用 `CronExpression.parse`(`CronScheduler.java:13`)。
  - 现状「分钟级」是**轮询粒度(60s)所致,非解析器限制**——换成 15s 扫 + 精确触发后,秒级 cron 自然可用(FR-009 的 cron 部分近乎零增量)。
- **Alternatives considered**: 引 cron-utils 支持 Quartz `L`/`W`/`#` 高级语法 —— spec 未要求,YAGNI,否决。

## D5. FIXED_RATE / FIXED_DELAY:纯新增计时逻辑 + 新增间隔字段

- **Decision**: 新增 `TimingStrategy` 策略接口,三实现:
  - `CRON`: `next = CronExpression.next(base)`。
  - `FIXED_RATE`: `next = prevScheduledFire + intervalMs`(按计划间隔,不等上次完成)。
  - `FIXED_DELAY`: `next = lastCompletion + intervalMs`(等上次实例完成后再隔 N;首轮用创建时刻)。
  - 统一处理 misfire:`next ≤ now` 即过期 → 立即触发(delay=0)+ 重算到「以 now 为基准的未来最近点」,不逐个回放(等价 `fire_once`,默认);`skip` 仅推进基准。
- **Rationale**(代码事实): `WorkflowDef` 现有 `schedule_type`(MANUAL/CRON/DEPENDENCY)、`cron`、`last_fire_time`,**无** interval / nextTriggerTime 字段(`schema.sql:301-325`)→ 需新增 `next_trigger_time` 列与 `schedule_interval_ms` 列,并扩展 `schedule_type` 取值含 `FIXED_RATE`/`FIXED_DELAY`。
- **FIXED_DELAY 的完成时刻来源**: 需读所属工作流最近一次实例的完成时间(运行态);设计上由 `TriggerEngine` 在重算 next 时查询,或由实例完成事件回填 `next_trigger_time`(二选一,data-model 记为待定实现细节,优先「重算时查询」简单路径)。

## D6. Schema 迁移:H2 改 `schema.sql`,PG 另写 `db/migration` 脚本

- **Decision**: `workflow_def` 加列 + 新建 `master_nodes` 同时落 `schema.sql`(H2,开发态 `spring.sql.init.mode=always` 幂等重建)与 `backend/.../db/migration/V__*-pg.sql`(PG 生产手工迁移,项目惯例,如既有 `distributed-scheduler-m1-uuidv7-pg.sql`)。
- **Rationale**: 项目无 Flyway/Liquibase 自动跑(开发靠 `schema.sql` 快照,PG 靠手写迁移)。`GENERATED BY DEFAULT AS IDENTITY` + 标准 `CONSTRAINT` 语法 H2/PG 双兼容(参照 `cron_fire` DDL)。
- **兼容性注意**: 新列 `next_trigger_time TIMESTAMP NULL`(允许空,旧数据首轮扫描时回填)、`schedule_interval_ms BIGINT NULL`;避免 H2 不支持的 PG 专有类型。

## 关键代码事实索引

| 主题 | 文件:行 |
|------|---------|
| cron 命中入口 / tryFire / 护栏去重 / 下游调用 | `CronScheduler.java:49-67` / `:70-131` / `:115` / `:120` |
| misfire 配置与策略 | `CronScheduler.java:42`(`scheduler.cron-misfire:fire_once`)/ `:102-109` |
| 实例派发(与 cron 解耦) | `SchedulerKernel.java:102-106` poll / `:124-150` runRound |
| 下游触发签名(不变) | `WorkflowTriggerService.java:97-100`,唤醒 `:272` |
| cron_fire DDL + 实体 | `schema.sql:567-577` / `CronFire.java` / `CronFireRepository.java` |
| workflow_def DDL(缺 next_trigger_time) | `schema.sql:301-325` |
| 调度配置 | `application.yml:69-74`(mode / poll-interval-ms / claim-batch-size / cron-misfire) |
| worker 心跳惯例(分片可照搬) | `schema.sql:695-718` worker_nodes / `FleetService`(30s OFFLINE)/ `SlotManager.snapshotOnline()` |
| 并发去重回归 | `SchedulerConcurrencyTest.cronFireGuardrail_dedupsConcurrentInserts`(:123-153) |
| Netty 可用性 | `dataweave-api` 经 `lettuce-core:6.8.1` → `io.netty:netty-common:4.2.7.Final`;`dataweave-master` 直接依赖无 Netty |

所有 spec 中的 NEEDS CLARIFICATION 均已解决(spec 阶段已澄清并发/misfire/秒级/规模四项;本 Phase 解决时间轮选型、切入点、分片前置、cron 库、schema 迁移五项技术未知)。
