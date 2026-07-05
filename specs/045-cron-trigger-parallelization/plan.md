# Implementation Plan: cron 触发并发吞吐优化

**Branch**: `045-cron-trigger-parallelization` | **Date**: 2026-07-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/045-cron-trigger-parallelization/spec.md`
**技术设计源**:[design doc](../../docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md)(brainstorming 产出,方案 A)

## Summary

基于 044 瓶颈结论(cron-trigger 单线程串行 fire,timer 池硬编码 2 + 每同步 fire 内逐条 INSERT 无 @Transactional + HikariCP=10),将 cron 触发的"去重判定"与"实例创建物化"解耦:到点同步去重(`INSERT cron_fire`,不变)→ 入进程内有界队列 → worker 池并发物化 → 回填。配套 `WorkflowTriggerService` 批量物化(`@Transactional` + `saveAll`)、`CronFireReconciler` 崩溃补偿、三层幂等(`cron_fire` UNIQUE + `workflow_instance` 部分唯一约束 + 应用层快查)、资源池可配(默认/极限两档)、HikariCP 扩容。目标:找极限 ≥10x 吞吐 + slot_util>0.5,守死锁 4 不变量。

## Technical Context

**Language/Version**:Java 25
**Primary Dependencies**:Spring Boot 4.0 / Spring Framework 7(Jackson 3)/ WebFlux / Spring Data JDBC + JdbcTemplate
**Storage**:PostgreSQL(默认)/ H2(`profiles=h2`);Redis(EventBus/LogBus,wake publish)
**Testing**:JUnit 5 + AssertJ;WebFlux → `@SpringBootTest` / WebTestClient;复用 044 cron-stress 真跑
**Target Platform**:Linux server(distributed 模式:双 master + 双 worker)
**Project Type**:web-service(后端调度内核优化)
**Performance Goals**:50wf 同触发点 ≥30 inst/s(≥10x 044 基线);slot_util > 0.5;崩溃 ≤30s 补偿;触发延迟 p99 < 0.5s
**Constraints**:守死锁防御 4 不变量(SKIP LOCKED / CAS / 锁顺序 task→workflow / 状态事务内 + dispatch 事务外);不动 `WorkflowTriggerService.trigger` 签名(手动/补数据/单任务共用路径)
**Scale/Scope**:50-200 wf 同触发点;双 master;PG max_connections 默认 100(极限档 200)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
|---|---|---|
| I Files-First | 本 feature 改后端调度内核,不碰文件定义/目录树 | ✓ 不适用 |
| II Server Source of Truth | `cron_fire`/`workflow_instance` 仍为 server 治理数据;优化 server 内调度,不改治理模型 | ✓ PASS |
| III Two-Legged Debugging | 本 feature 不涉及 CLI runtime / 本地执行 | ✓ 不适用 |
| IV AI Lives in Local Agent | 后端调度优化,无 AI 组件引入/移除 | ✓ 不适用 |
| V 复用内核(非另造) | 优化现有 `DefaultTriggerEngine`/`WorkflowTriggerService`,不另造调度内核;reconciler 是现有 `cron_fire` 真相源的补偿器 | ✓ PASS |
| 死锁防御不变量(CLAUDE.md 硬规则) | FR-009/SC-005 显式:SKIP LOCKED 不依赖、CAS 仅 INSERT、锁顺序不变、dispatch 事务外(trigger `@Transactional` + `wake` AFTER_COMMIT) | ✓ PASS |
| Metric 定义不可变 | 新增 metrics(`dw.cron.fire.queue.*`/`execute.latency`/`reconcile.*`)是新定义,不改既有 | ✓ PASS |
| schema 权威(`schema.sql`) | `cron_fire` +status+索引、`workflow_instance` +部分唯一约束 → bump `schema_version` | ✓ PASS(将 bump) |

**无 violation**。本 feature 是现有后端调度内核的性能优化,不引入新模块/不跨 DDD 层重构/不碰 AI/文件/CLI,符合宪法全部原则与 CLAUDE.md 硬规则。

## Project Structure

### Documentation (this feature)

```text
specs/045-cron-trigger-parallelization/
├── plan.md              # 本文件
├── research.md          # Phase 0 技术决策(从 design doc 提炼)
├── data-model.md        # Phase 1 实体改动(cron_fire/workflow_instance/FireTask/Reconciler)
├── quickstart.md        # Phase 1 压测手册(复用 044 cron-stress)
├── contracts/api.md     # Phase 1 API(无新增端点,复用 + 新 metrics)
├── checklists/requirements.md  # specify 产出(全 [x])
└── tasks.md             # speckit-tasks 产出(下一步)
```

技术设计源:`docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md`

### Source Code (改动点)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/application/
├── DefaultTriggerEngine.java       # 重构:fire 拆 fireArm/fireExecute + fireQueue + fireExecutor
├── WorkflowTriggerService.java     # @Transactional + taskInstance saveAll + wake AFTER_COMMIT
├── CronFireReconciler.java         # 新:@Scheduled 周期补偿(instance_id IS NULL && 超 grace)
├── SchedulerMetrics.java           # 新增 metrics(queue.size/full.count/execute.latency/reconcile.*)
└── CronScheduler.java              # 不变(外壳)

backend/dataweave-master/.../domain/
├── CronFire.java                   # + status 字段(PENDING/FIRED/DEAD)
└── CronFireRepository.java         # + 查 instance_id IS NULL && created_at < :threshold

backend/dataweave-api/src/main/resources/
├── schema.sql                      # cron_fire +status+(instance_id,created_at)索引;
│                                   # workflow_instance +部分唯一约束(workflow_id,scheduled_fire_time) WHERE NOT NULL;
│                                   # bump schema_version
└── application.yml                 # + scheduler.cron-trigger-timer-threads / cron-fire-worker-threads /
                                    #   cron-fire-queue-capacity / cron-reconcile-interval/grace/timeout-ms;
                                    # hikari.maximum-pool-size 10→40(默认档)

docker-compose.yml                  # postgres command: max_connections=200;master×2 env + SCHEDULER_* / HIKARI_POOL_SIZE

backend/dataweave-master/src/test/.../application/
├── DefaultTriggerEngineTest.java   # fireArm/fireExecute 拆 + 满队列降级
├── CronFireReconcilerTest.java     # 幂等跳过 + DEAD 标记
└── WorkflowTriggerServiceTest.java # @Transactional 原子 + saveAll 批量

tmp/cron-stress/cron-stress.sh      # cron-watch 扩展 queue.size/full.count/reconcile 列(git-ignored)
```

**Structure Decision**:沿用现有 backend DDD 四层(application/domain/infrastructure/interfaces),改动集中在 master 模块 application 层(触发引擎)+ domain(`CronFire` 实体)+ api(`schema.sql`/`application.yml`)+ `docker-compose.yml`。无新模块。

## Complexity Tracking

> 无 Constitution Check violation,本表为空。
