# Implementation Plan: claim 速率深优化

**Branch**: `048-claim-rate-optimization` | **Date**: 2026-07-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/048-claim-rate-optimization/spec.md`

## Summary

046 解除 dispatch 串行后瓶颈转移到 claim 速率:单线程认领(~333 inst/s)跟不上触发层物化(~600 inst/s),1000wf `*/2s` 全负载下 `dispatch_latency` 135s、WAITING 堆积 6.7 万。本 feature 消除认领链路三个剩余 N+1:① crossCycleReady 逐行查 workflow_dependency → 批量 JOIN;② 逐行 COUNT → 批量 GROUP BY;③ casDispatch 逐行 UPDATE → 单条 `UPDATE FROM VALUES`。批量化后单轮 round ~100-150ms → ~10-15ms,claim 速率 ~3000+ inst/s,达成 `dispatch_latency` p99 < 5s。死锁 4 不变量全保持(单线程 claim 不变,无新增核对面)。

## Technical Context

**Language/Version**: Java 25(LTS),Spring Boot 4,Jackson 3

**Primary Dependencies**: Spring JDBC(JdbcTemplate)、Spring TX(txTemplate)、reactor-netty(WebClient,046)、HikariCP、Caffeine(048 未直接用,048 用 ConcurrentHashMap 同 046)

**Storage**: PostgreSQL(生产)/ H2 2.4.240(测试,`MODE=PostgreSQL`)。无 schema 变更(task_instance 字段已齐)。批量 SQL:`UPDATE FROM VALUES`(H2 T5 实测 + PG 原生双兼容,无 RETURNING)、行构造器 `IN ((?,?),(?,?))`(H2 T2 实测)

**Testing**: JUnit 5 + Mockito + AssertJ;H2 集成测试(api 套件风格);distributed 双 master 真机压测(cron-stress 1000wf `*/2s`)

**Target Platform**: Linux server(distributed master 节点)

**Project Type**: web-service(后端调度内核优化)

**Performance Goals**: 1000wf `*/2s` 全负载下 claim 速率 ≥ 600 inst/s(估算 ~3000+)、`dispatch_latency` p99 < 5s、WAITING 不堆积

**Constraints**: 死锁防御 4 不变量(SKIP LOCKED / CAS / 锁顺序 / 状态事务内+dispatch事务外);H2/PG 双兼容;不退化 046 成果(dispatch_queue 不积压、可观测性保留)

**Scale/Scope**: 单文件主改 `SchedulerKernel.java`(claimAndMark/assign/selectRunnable/crossCycleReady)+ `InstanceStateMachine.java`(批量 cas 入口);新增 ~2 个测试文件;无前端、无 API、无 schema 变更

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Conformance | Evidence |
|---|---|---|
| I. Files-First | N/A | 本 feature 不涉文件定义/目录树,纯调度内核优化 |
| II. Server is Source of Truth | PASS | claim 是 server 端调度内核,不改 pull/push/隔离 |
| III. Two-Legged Debugging | N/A | 不改 executor/CLI,本地调试路径不变 |
| IV. AI Lives in Local Agent | PASS | 不改 AI 链路;本 feature 是调度性能优化 |
| V. Reuse the Kernel | **PASS** | 复用 SchedulerKernel/InstanceStateMachine/PolicyEngine/slotManager,内部批量化重构,**不重写内核**;claim 语义(SKIP LOCKED + CAS)保留 |
| 不可让渡内核③(调度内核不损伤 + 可观测性) | **PASS** | 优化 claim 实现但保持调度语义(4 不变量全核对见 research R6);metrics 保留 + 可能加 `claim_batch_size` gauge;run logs/ops overview 不动 |

**Gate 结果**:全 PASS,无违反,Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/048-claim-rate-optimization/
├── plan.md              # 本文件
├── research.md          # Phase 0(H2 兼容实测 + 形态选型 + 不变量核对)
├── data-model.md        # Phase 1(无 schema 变更,进程内结构)
├── quickstart.md        # Phase 1(验证场景)
├── contracts/           # Phase 1(无新外部 API)
└── tasks.md             # Phase 2(speckit-tasks)
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/application/
├── SchedulerKernel.java          # 主改:claimAndMark/assign/selectRunnable/crossCycleReady
└── InstanceStateMachine.java     # 新增批量 casDispatchBatch(或在 Kernel 内直写 SQL)

backend/dataweave-master/src/test/java/com/dataweave/master/application/
├── SchedulerKernelTest.java       # 新增/扩展:批量 crossCycleReady + 批量 cas + assign 三阶段
└── InstanceStateMachineTest.java  # 扩展:casDispatchBatch(若新增)

tmp/cron-stress/                  # 复用 045/046 压测(1000wf */2s,distributed 双 master)
```

**Structure Decision**:单后端模块(`dataweave-master`)优化,无前端/API/schema 变更。批量 SQL 直写在 SchedulerKernel 内(同 selectRunnable inline SQL 风格),InstanceStateMachine 可暴露批量入口或 Kernel 直写(倾向后者,避免 StateMachine 膨胀)。

## Complexity Tracking

> 无 Constitution Check 违反,本节留空。
