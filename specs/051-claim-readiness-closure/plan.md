# Implementation Plan: 认领就绪态物化 + 性能链收口

**Branch**: `051-claim-readiness-closure` | **Date**: 2026-07-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/051-claim-readiness-closure/spec.md`

## Summary

把认领的就绪判定从"每轮 O(WAITING) 扫描 + Java 上游/跨周期门 + 窗口游标兜底"改为查一个**物化就绪态** `task_instance.unmet_deps`（未满足依赖数，涵盖 DAG 内上游门与跨周期门），认领候选 `WHERE unmet_deps=0` 走索引 seek，与堆积规模脱钩。就绪态用**最终一致**模型维护：上游/跨周期满足方到达放行终态时，在完成事务内 append 一条廉价 **事务型 outbox 信号**（`readiness_signal`）；一个 **Maintainer** sweeper 轮询信号、解析受影响的**下游扇出集** D、对 D 按权威状态 **scoped 重算** unmet_deps（幂等、无漂移、无账本）并 `wake()`；一个低频 **Reconciler** 有界审计自愈残留漂移。就绪滞后 p99 < 3s。同时一次性收口 046/048/049 三连欠的**崩溃注入 + 四不变量审计**，并用**真跑慢任务**压测补 slot_util（绑定约束 + ≥80%）。死锁防御四不变量硬保持，H2/PG 双兼容，不退化 046/048/049 指标。

## Technical Context

**Language/Version**: Java 25（Spring Boot 4.0 / Spring Framework 7，Jackson 3）

**Primary Dependencies**: WebFlux、Spring Data JDBC + JdbcTemplate、Micrometer/Actuator、EventBus（Redis `RedisEventBus` / `InMemoryEventBus`）；调度内核 `SchedulerKernel` · `SchedulerMetrics` · `InstanceStateMachine` · `WorkerReportService` · `WorkflowTriggerService`

**Storage**: PostgreSQL（默认）· H2（`profiles=h2` in-memory，DDL 兼容）；`task_instance` 加列 + 新表 `readiness_signal`；schema_version 0.9.0 → **0.10.0**

**Testing**: JUnit 5 + AssertJ；`@SpringBootTest` / WebTestClient；H2 与 PG 双跑；cron-stress 压测框架（045/046 复用）+ `docker kill` fault-injection；worker 真跑 sleep 任务档

**Target Platform**: Linux server（peer masters，`scheduler.mode=all-in-one|distributed`）

**Project Type**: 后端多模块 DDD（本 feature 仅触 `dataweave-master`，schema 在 `dataweave-api`）

**Performance Goals**: WAITING 50 万+ 时单轮就绪判定耗时与堆积无关；claim 吞吐 ≥ 触发层物化（~600–1000 inst/s）、WAITING 稳态不堆积；就绪滞后 p99 < 3s；慢任务 slot_util ≥ 80% 且成为绑定约束

**Constraints**: 死锁防御四不变量硬保持（① SKIP LOCKED claim ② CAS 状态推进 ③ 锁顺序 task→workflow ④ 状态事务内/下发事务外）；H2/PG 双兼容；不退化 046/048/049 已达成指标；指标不可变（只增不改）

**Scale/Scope**: 1000 工作流 `*/2s` 极限档；WAITING 堆积 50 万+；单 feature，仅 master application 层 + 一次 schema 变更

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **V. Reuse the Kernel（内核复用而非重写）— 直接相关，PASS**：本 feature 复用调度内核（peer master + SKIP LOCKED claim + CAS 状态机 + EventBus wake），不重写。就绪态物化是在既有认领链路上加一列 + 两个 sweeper（Maintainer/Reconciler）+ 一张 outbox 表，`selectRunnable` 只改过滤条件并删除已被物化取代的 Java 就绪门；不新建平行调度引擎。
- **I–IV — 不直接相关，无违反**：本 feature 不触及文件定义契约（I）、pull/push 治理（II）、CLI 本地运行时（III）、本地 agent/MCP（IV）；纯调度运行态内核优化，不引入服务端 AI 脑，不损伤运行态观测（ops/metrics/run logs/DAG views 不变）。
- **写闸门**：本 feature 无 agent/CLI 发起的写操作（就绪态由调度内核自维护），不涉及 PolicyEngine 写闸门；`readiness_signal` 是内部调度状态，非 agent_action。

**结论**：Constitution Check 通过，无违反项，Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/051-claim-readiness-closure/
├── plan.md              # 本文件
├── research.md          # Phase 0：设计决策（一致性模型/维护机制/载体/对账/上线回填）
├── data-model.md        # Phase 1：unmet_deps 列 + readiness_signal 表 + 索引 + 状态语义
├── quickstart.md        # Phase 1：端到端验证（编译/单测/集成/崩溃注入/压测/慢任务 slot_util）
├── contracts/           # Phase 1：内部组件契约 + schema DDL 契约 + 指标契约
│   ├── schema.contract.md
│   ├── components.contract.md
│   └── metrics.contract.md
└── tasks.md             # Phase 2：/speckit-tasks 产出（本命令不建）
```

### Source Code (repository root)

```text
backend/dataweave-api/src/main/resources/
└── schema.sql                          # 加 task_instance.unmet_deps 列 + readiness_signal 表 + 索引扩列；schema_version→0.10.0

backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   ├── SchedulerKernel.java            # selectRunnable 加 unmet_deps=0；删 batchUpstreamReady/batchCrossCycleReady/collectReady 窗口游标
│   ├── WorkflowTriggerService.java     # 物化处接 ReadinessInitializer（算 unmet_deps 初值）
│   ├── WorkerReportService.java        # 完成/reset 事务内接 ReadinessSignalWriter（append 信号）
│   ├── readiness/                      # 新增子包
│   │   ├── ReadinessInitializer.java   #   物化时算 unmet_deps 初值（上游 edge 数 + 适用跨周期依赖数）
│   │   ├── ReadinessSignalWriter.java  #   完成事务内 append readiness_signal（单行，不扇出）
│   │   ├── ReadinessMaintainer.java    #   sweeper：轮询信号→解析下游 D→scoped 重算→CAS→wake
│   │   ├── ReadinessReconciler.java    #   低频有界审计→自愈漂移→drift 指标
│   │   └── ReadinessRecompute.java     #   scoped 重算核心（复用上游/跨周期就绪语义，作用于 D）
│   └── SchedulerMetrics.java           # 新增 readiness_* 指标（只增）
└── infrastructure/
    └── ReadinessSignalRepository.java  # readiness_signal 读写（SKIP LOCKED 批量领取 + 标记 processed）

backend/dataweave-master/src/test/java/com/dataweave/master/application/readiness/
├── ReadinessInitializerTest.java       # 初值正确性（宽 DAG/跨周期/首周期豁免/无依赖直通）
├── ReadinessRecomputeTest.java         # scoped 重算幂等 + STRONG/WEAK + 跨周期逆偏移
├── ReadinessMaintainerTest.java        # 信号→重算→wake；SKIP LOCKED 不重复处理
├── ReadinessReconcilerTest.java        # 注入漂移→自愈
└── SchedulerKernelReadinessTest.java   # 认领只取 unmet_deps=0；无 Java 就绪门；崩溃 idempotency 回归
```

**Structure Decision**: 后端多模块 DDD，仅动 `dataweave-master`（application 层新增 `readiness/` 子包，改 `SchedulerKernel`/`WorkflowTriggerService`/`WorkerReportService`）+ `dataweave-api` 的 `schema.sql`。遵循依赖方向 domain←application←infrastructure←interfaces；sweeper 属 application 编排，`readiness_signal` 持久化落 infrastructure。压测/崩溃注入复用 045/046 既有脚本与 fault-injection 手段。

## Complexity Tracking

> Constitution Check 无违反项，本节留空。
