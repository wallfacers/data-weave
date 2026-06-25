# Implementation Plan: 分布式 Cron 精确触发（移植 PowerJob 调度思想）

**Branch**: `001-distributed-cron-trigger` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-distributed-cron-trigger/spec.md`

## Summary

把当前 `CronScheduler` 的 **60s 全量轮询**改造成 PowerJob 式 **「短周期扫描 + 预读窗口 + 到点精确触发」** 两级时序:以 15s 周期扫描、预读未来 ~30s 内 `next_trigger_time` 到期的 cron 工作流,按精确延迟交给进程内定时器到点触发;触发后立即据计时策略重算并持久化 `next_trigger_time`;逾期点「延迟归零立即补一次」。去重仍由现有 `cron_fire` 唯一键零协调保证,下游 `WorkflowTriggerService.trigger()` 与死锁防御不变量**原样不动**。新增秒级 cron(Spring `CronExpression` 原生支持)/ FIXED_RATE / FIXED_DELAY 计时策略;为支撑 >10k 工作流,新增 `master_nodes` 注册表做哈希分片,使每个 master 只预读自己分片。

改动收敛在 `dataweave-master`,核心是 `CronScheduler` 重构 + 新增 `TriggerEngine`(扫描/计时策略/精确触发)与 `MasterRegistry`(分片),以及一处 schema 加列 + 一张新表。

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.0 / Spring Framework 7

**Primary Dependencies**: Spring Data JDBC + JdbcTemplate;`org.springframework.scheduling.support.CronExpression`(6 字段,**原生含秒**,无需 cron-utils);`java.util.concurrent.ScheduledExecutorService`(精确触发,**零新依赖**;`io.netty:netty-common` HashedWheelTimer 为备选)

**Storage**: PostgreSQL(默认)/ H2(`profiles=h2`,DDL 须双兼容)。表:`workflow_def`(加 `next_trigger_time` 等列)、`cron_fire`(不变)、`master_nodes`(新增)

**Testing**: JUnit 5 + AssertJ;`@SpringBootTest`;扩展 `SchedulerConcurrencyTest`(cron_fire 去重必保通过)+ 新增预读窗口/秒级/misfire/分片回归

**Target Platform**: Linux server(后端 JVM,`dataweave-master` 模块)

**Project Type**: 后端调度服务(Maven 多模块,改动集中在 `dataweave-master`)

**Performance Goals**: cron 时刻→实例创建 p99 ≤ 2s(p50 ≤ 1s);秒级周期相邻间隔误差 ≤ 2s 且无累计漂移;>10k 工作流下延迟与去重不退化

**Constraints**: ① 保留 `cron_fire` 唯一键零协调去重(无分布式锁、无选主);② 死锁防御四不变量(SKIP LOCKED / 乐观 CAS / 锁序 task→workflow / 事务内只落状态);③ 现有分钟级 cron 工作流零迁移、行为兼容;④ DDL H2+PG 双兼容;⑤ 下游 `trigger()` 签名与语义不变

**Scale/Scope**: ≥10,000 活跃 cron 工作流,≥3 peer master 对等部署

## Constitution Check

*GATE: 须在 Phase 0 前通过,Phase 1 后复检。*

`.specify/memory/constitution.md` 当前为**未填充模板**(占位符),无项目级强制条款 → 形式上 **PASS,无 gate 违例**。实质约束以本仓库既有工程约定替代(见 Technical Context 的 Constraints 与 [CLAUDE.md](../../CLAUDE.md) 的 Scheduler deadlock-defense invariants / Backend Build / Testing 三条),设计严格遵守:

- ✅ 不引入跨进程共享时间轮组件,不引入选主/分布式锁到 cron 去重路径。
- ✅ 死锁防御不变量原样保留(改动不触碰 `SchedulerKernel` 的认领/派发/CAS)。
- ✅ 新增依赖最小化(优先 JDK 内置 `ScheduledExecutorService`,不强制引 Netty)。
- ✅ 测试先行,cron_fire 并发去重回归必须持续通过。

Phase 1 复检结论见末尾「Post-Design Constitution Re-check」。

## Project Structure

### Documentation (this feature)

```text
specs/001-distributed-cron-trigger/
├── plan.md              # 本文件
├── spec.md              # 已完成(specify + clarify)
├── research.md          # Phase 0:选型与切入点决策
├── data-model.md        # Phase 1:表/列/状态/计时策略模型
├── quickstart.md        # Phase 1:本地验证步骤
├── contracts/           # Phase 1:内部契约(TriggerEngine SPI / 配置键 / master 注册 / 指标)
│   ├── trigger-engine.md
│   ├── master-registry.md
│   └── config-and-metrics.md
├── checklists/requirements.md   # 已有(spec 质量清单)
└── tasks.md             # Phase 2:由 /speckit-tasks 生成(本命令不产出)
```

### Source Code (repository root)

改动集中在 `dataweave-master`(调度域)+ 一处 schema/seed + `dataweave-api` 测试:

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   ├── CronScheduler.java            # 重构:60s tick → 15s scan + 委派 TriggerEngine 精确触发
│   ├── TriggerEngine.java            # 新增:计时策略缓存 + 预读窗口扫描 + 进程内精确触发器(ScheduledExecutorService)
│   ├── TimingStrategy.java           # 新增:CRON / FIXED_RATE / FIXED_DELAY 的 nextTriggerTime 计算(策略接口 + 3 实现)
│   ├── MasterRegistry.java           # 新增:本机 master 自注册 + 心跳 + 活 master 集合(分片用)
│   └── (SchedulerKernel.java)        # 不改 —— 实例认领/派发与 cron 触发解耦
├── domain/
│   ├── WorkflowDef.java              # 加字段:nextTriggerTime / scheduleInterval(映射新列)
│   ├── MasterNode.java               # 新增:master_nodes 实体
│   ├── MasterNodeRepository.java     # 新增
│   ├── CronFire.java / CronFireRepository.java   # 不改
│   └── WorkflowDefRepository.java    # 加分片查询(按 schedule_type/status + 可选 id 取模)
backend/dataweave-api/src/main/resources/
├── schema.sql                        # workflow_def 加列 + 新建 master_nodes(H2/PG 双兼容)
├── application.yml                   # 新增 scheduler.* 配置键
└── (data.sql)                        # 必要时回填新列默认值(种子 i18n 豁免)
backend/.../db/migration/             # 新增 PG 迁移脚本 V__add-next-trigger-and-master-nodes-pg.sql
backend/dataweave-api/src/test/java/com/dataweave/api/
├── SchedulerConcurrencyTest.java     # 扩展:cron_fire 去重必通过 + 分片不重不漏
└── (新增) CronTriggerEngineTest / TimingStrategyTest / MisfireRecoveryTest / SecondLevelCronTest
```

**Structure Decision**: 单后端模块改造(无前端、无新服务)。新增逻辑全部落在 `dataweave-master` 的 application/domain 层,遵循 domain ← application 依赖方向;schema 与配置落在 `dataweave-api/resources`(项目惯例:H2 用 `schema.sql`,PG 另写 `db/migration` 迁移脚本)。`CronScheduler` 仅作为「扫描触发外壳」,实际计时与精确触发下沉到可测的 `TriggerEngine` + `TimingStrategy`。

## 实施阶段拆分(供 /speckit-tasks 参照)

按用户故事优先级分两批,核心先闭环、规模化后置(对应 spec 的 P1/P2 与 FR-016/SC-007):

- **Batch A — 准点 + 去重 + 容错 + 秒级(US1/US2/US3/US4,P1/P2)**
  1. schema:`workflow_def` 加 `next_trigger_time`(+ FIXED_RATE/DELAY 的 `schedule_interval_ms`),H2+PG 双写。
  2. `TimingStrategy`:CRON(Spring `CronExpression`,秒级)/ FIXED_RATE / FIXED_DELAY 的 `next(prev, now)` 计算 + misfire(过期点 delay=0 + 重算到未来最近点,等价 `fire_once`;`skip` 备选)。
  3. `TriggerEngine`:启动/增量预热计时策略缓存;15s 扫 `next_trigger_time ≤ now + lookahead`;命中点交 `ScheduledExecutorService` 按 `due-now`(过期则 0)精确触发;触发瞬间走 `cron_fire` 唯一键去重 → 命中则调 `WorkflowTriggerService.trigger()`(不变)→ 重算并持久化 `next_trigger_time`。
  4. `CronScheduler` 重构:`tick()` 60s→15s,委派 `TriggerEngine`;`tryFire` 简化但保留护栏 + 下游 + 生效期校验 + 重叠允许并发(不阻塞)。
  5. 测试:扩展 `SchedulerConcurrencyTest`(去重),新增秒级/窗口边界/misfire 恢复/重叠并发。
- **Batch B — >10k 规模分片(FR-016/SC-007)**
  6. `master_nodes` 表 + `MasterRegistry`(自注册 `host-pid`、心跳、超时剔除,复用 `worker_nodes` 心跳惯例)。
  7. 分片:`shard = hash(workflow_id) % activeMasterCount`,本 master 只预读 `shard==myIndex`;`cron_fire` 唯一键作为分片重平衡漂移期去重兜底。
  8. 测试:分片下 ≥10k 工作流不重不漏、单 master 负载随分片下降;master 上下线重平衡无丢触发(漂移期靠 cron_fire 兜底)。

> Batch A 独立可交付(无分片时每 master 扫全量、cron_fire 兜底去重,功能正确仅负载未优化);Batch B 叠加规模化。两批之间无破坏性依赖。

## Complexity Tracking

Constitution 无 gate 违例,无需复杂度豁免表。两处「看似新增复杂度」的合理性说明:

| 决策 | 为何需要 | 被否的更简方案 |
|------|---------|---------------|
| 新增 `master_nodes` + 分片 | spec FR-016/SC-007 要求 >10k 工作流;无分片时每 master 全量扫描+全量入定时器,负载随总量线性膨胀 | 「每 master 全量、仅靠 cron_fire 去重」—— 正确但 >10k 下重复负载浪费,不满足 SC-007 单机负载目标 |
| 引入 `ScheduledExecutorService` 精确触发器 | 实现「预读 + 到点精确」两级时序,达成秒级 p99 ≤ 2s | 继续缩短轮询周期(如 1s tick)—— 仍是粗轮询、抖动大、DB 压力高,无法稳定达标秒级 |

## Post-Design Constitution Re-check

Phase 1 设计完成后复检:仍 PASS。设计未触碰死锁防御不变量(SchedulerKernel 零改动);去重路径仍是 `cron_fire` 唯一键(分片只决定「谁预读」,不改「谁能触发一次」的真相);新增依赖为 JDK 内置;DDL 双兼容;下游 `trigger()` 不变。无新增违例。
