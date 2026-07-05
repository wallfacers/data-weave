# Implementation Plan: dispatch 链路并行化优化

**Branch**: `046-dispatch-parallelization` | **Date**: 2026-07-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/046-dispatch-parallelization/spec.md`

**Note**: 技术设计源 `docs/superpowers/specs/2026-07-05-dispatch-parallelization-design.md`(brainstorming 产出)。

## Summary

基于 045 R9 瓶颈结论(dispatch_latency max 227.77s,三个串行放大器:SchedulerKernel `running` 全局串行 + assign N+1 查询 + dispatchAll `invokeAll` 屏障),解除 dispatch 认领串行。方案:**杠杆 1+2+3 + 配套 4+5,聚合 7 defer** —— claim/dispatch 解耦(进程内 dispatchQueue + dispatchExecutor,同 045 fireQueue 模式)+ dispatchAll 去屏障(fire-and-forget)+ assign N+1 消除(批量预取 + Caffeine 缓存)+ WebClient 3s 超时 + 配比。目标 dispatch_latency p99 < 5s / 吞吐 ≥600 inst/s,死锁 4 不变量保持。

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7(WebFlux, Jackson 3),Spring Data JDBC + JdbcTemplate,Micrometer,**Caffeine**(新增 cache),reactor-netty(WebClient 超时)

**Storage**: PostgreSQL(default)/ H2(`profiles=h2`);Redis(EventBus/LogBus)。**无 schema 改动**(046 是进程内 + 配置优化,不动表;`schema_version` 不 bump)

**Testing**: JUnit 5 + AssertJ;WebFlux → `@SpringBootTest` / WebTestClient;复用 045 cron-stress 真跑压测(`tmp/cron-stress/`,git-ignored)

**Target Platform**: Linux server(distributed 双 master + 双 worker)

**Project Type**: web-service(后端 master 模块 application 层优化)

**Performance Goals**: dispatch_latency p99 < 5s(045 R9 = 227s max);dispatch 吞吐 ≥600 inst/s(R9 SUCCESS 仅 ~17/s)

**Constraints**: 死锁 4 不变量保持(SKIP LOCKED / CAS / 锁顺序 task→workflow / 状态事务内 + dispatch 事务外);不重复 dispatch(casDispatch CAS);不丢 dispatch(队列满降级 + shutdown drain)

**Scale/Scope**: `SchedulerKernel` + `ParallelDispatcher` + `WebClientConfig` + `SchedulerMetrics` + `TaskDefVersionBatchLoader`(application 层);配置 `application.yml` + `docker-compose.yml`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Files-First | 046 不动文件定义(纯后端性能优化) | N/A ✓ |
| II. Server is Source of Truth | 046 不动 sync/governance/version snapshot | N/A ✓ |
| III. Two-Legged Debugging | 046 不动 CLI runtime / local executor | N/A ✓ |
| IV. AI Lives in Local Agent | 046 不动 AI/MCP/server brain;不动 observability(metrics 反而**新增** dispatch 指标) | N/A ✓ |
| V. Reuse the Kernel | 046 **复用调度内核**:`SchedulerKernel` claim 的 SKIP LOCKED + CAS + assign 框架**不变**,仅内部解耦(claim↔dispatch 队列衔接)+ 批量化(N+1 消除)+ 去屏障(invokeAll → 队列)。**不重写内核**,不引入新调度模型。 | ✓ **PASS** |

**无违反。无 Complexity Tracking 条目。** 046 是对现有调度内核的**内部性能重构**(解耦 + 批量化 + 异步化),沿用所有现有不变量与组件契约(InstanceStateMachine / DefaultSchedulingPolicy / SlotManager / EventBus 不变)。

## Project Structure

### Documentation (this feature)

```text
specs/046-dispatch-parallelization/
├── plan.md              # This file
├── research.md          # Phase 0 output(根因 + 杠杆选型 + 死锁核对)
├── data-model.md        # Phase 1 output(进程内组件,无表改动)
├── quickstart.md        # Phase 1 output(测试场景)
├── contracts/           # Phase 1 output(无新外部 API,内部优化)
└── tasks.md             # Phase 2 output(speckit-tasks)
```

### Source Code

```text
backend/dataweave-master/src/main/java/com/dataweave/master/application/
├── SchedulerKernel.java           # runRound→claimRound 拆(running 只护 claim 事务)+ dispatchQueue/dispatchExecutor 新字段 + DispatchCommand record + N+1 消除(assign 批量预取)
├── ParallelDispatcher.java        # dispatchAll→dispatchAllAsync(offer 队列立即返回,去 invokeAll 屏障)
├── SchedulerMetrics.java          # +dw.dispatch.queue.size(gauge)/ queue.full.count(counter)/ execute.latency(timer)
├── TaskDefVersionBatchLoader.java # 新(本轮 SELECT IN 批量预取 content/params)+ Caffeine cache
├── InstanceStateMachine.java      # 不变(casDispatch/casRequeue 复用)
├── WorkflowStateService.java      # 不变(聚合 defer,046 不动)
└── WorkerReportService.java       # 不变(recomputeWorkflow 沿用)

backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/
└── WebClientConfig.java           # +responseTimeout 3s + connectTimeout 2s(reactor-netty HttpClient)

backend/dataweave-api/src/main/resources/
├── application.yml                # +scheduler.dispatch-queue-capacity/dispatch-executor-threads/dispatch-webclient-timeout-ms
└── schema.sql                     # 不变(无 DDL)

docker-compose.yml                 # +master×2 env SCHEDULER_DISPATCH_QUEUE_CAPACITY/EXECUTOR_THREADS/WEBCLIENT_TIMEOUT_MS

backend/dataweave-master/src/test/java/com/dataweave/master/application/
├── SchedulerKernelTest.java       # 新(claimRound 不等 dispatch / 队列满降级 + markQueueFull / assign 批量预取 N+1 消除 / WebClient 超时 casRequeue / shutdown drain)
└── ParallelDispatcherTest.java    # 扩展(dispatchAllAsync offer 队列立即返回,去 invokeAll)
```

**Structure Decision**: 046 是后端 master 模块 application 层 + api 模块 WebClientConfig + 配置。无前端、无新模块、无 DDL。复用 045 cron-stress(`tmp/cron-stress/`)真跑压测。

## Complexity Tracking

> 无 Constitution Check 违反,本表空。
