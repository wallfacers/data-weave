# Implementation Plan: selectRunnable 优化

**Branch**: `049-select-runnable-optimization` | **Date**: 2026-07-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/049-select-runnable-optimization/spec.md`

## Summary

048 批量化后 selectRunnable 重 SQL 在 WAITING 大量堆积(53 万)时退化:EXPLAIN 三轮隔离定位两瓶颈——① `run_mode IN` 打破 idx_task_instance_claim 索引(Seq Scan 244ms + 磁盘 Sort);② NOT EXISTS 上游门 327-493ms(标量子查询实测 1ms 快,非瓶颈)。本 feature 去 NOT EXISTS + `run_mode='NORMAL'` Index Scan(0.43ms,566x)+ BACKFILL 分开 + NOT EXISTS 上游门移 Java 层 `batchUpstreamReady`(类比 048 batchCrossCycleReady)。预期 round ~7-10ms,claim ~5000-7000 inst/s,SC-001/002 达成。死锁 4 不变量全保持(SELECT 无锁,无新核对面)。

## Technical Context

**Language/Version**: Java 25(LTS),Spring Boot 4,Jackson 3

**Primary Dependencies**: Spring JDBC(JdbcTemplate)、Spring TX、reactor-netty(046)、HikariCP

**Storage**: PostgreSQL(生产)/ H2 2.4.240(测试,`MODE=PostgreSQL`)。无 schema 变更(复用 task_instance/workflow_edge/workflow_instance)。selectRunnable 用 Index Scan(idx_task_instance_claim);batchUpstreamReady 用行构造器 IN(H2 T2 实测 OK)

**Testing**: JUnit 5 + Mockito + AssertJ;H2 集成测试(参照 SlotManagerDistributedIT/048 SchedulerKernelBatchCrossCycleTest 风格);distributed 双 master 真机压测(cron-stress 1000wf `*/2s`)

**Target Platform**: Linux server(distributed master 节点)

**Project Type**: web-service(后端调度内核查询优化)

**Performance Goals**: 1000wf `*/2s` 全负载下 selectRunnable < 5ms(Index Scan)、round_duration < 30ms 稳定、claim ≥ 600 inst/s、WAITING 不堆积

**Constraints**: 死锁防御 4 不变量(SKIP LOCKED / CAS / 锁顺序 / 状态事务内+dispatch事务外);H2/PG 双兼容;不退化 046+048(dispatch_queue 不积压、batchCrossCycleReady/casDispatchBatch 不破坏);EXPLAIN 回归(Index Scan 无 Seq Scan/Sort)

**Scale/Scope**: 单文件主改 `SchedulerKernel.java`(selectRunnable + claimAndMark + 新增 batchUpstreamReady);新增 ~1 个测试文件(SchedulerKernelBatchUpstreamTest);无前端、无 API、无 schema 变更

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Conformance | Evidence |
|---|---|---|
| I. Files-First | N/A | 不涉文件定义/目录树,纯调度内核优化 |
| II. Server is Source of Truth | PASS | claim 是 server 端调度内核,不改 pull/push/隔离 |
| III. Two-Legged Debugging | N/A | 不改 executor/CLI |
| IV. AI Lives in Local Agent | PASS | 不改 AI 链路 |
| V. Reuse the Kernel | **PASS** | 复用 SchedulerKernel/048 batchCrossCycleReady/casDispatchBatch/SchedulingPolicy,selectRunnable 内部优化 + 上游门 Java 化,**不重写内核**;claim 语义(SKIP LOCKED + CAS)保留 |
| 不可让渡内核③(调度内核不损伤 + 可观测性) | **PASS** | 优化 selectRunnable 实现但保持调度语义(4 不变量全核对见 research R5);metrics 保留(round_duration 验证目标);run logs/ops overview 不动 |

**Gate 结果**:全 PASS,无违反,Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/049-select-runnable-optimization/
├── plan.md              # 本文件
├── research.md          # Phase 0(EXPLAIN 三轮隔离 + 不变量 + 量化)
├── data-model.md        # Phase 1(无 schema 变更,进程内结构)
├── quickstart.md        # Phase 1(EXPLAIN 回归 + 真机 R11)
├── contracts/           # Phase 1(无新外部 API)
└── tasks.md             # Phase 2(speckit-tasks)
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/application/
└── SchedulerKernel.java          # 主改:selectRunnable(NORMAL 去 NOT EXISTS+run_mode='NORMAL';新增 BACKFILL 分支)+ batchUpstreamReady + claimAndMark 串联

backend/dataweave-master/src/test/java/com/dataweave/master/application/
└── SchedulerKernelBatchUpstreamTest.java  # 新增:batchUpstreamReady 全边界单测

backend/dataweave-master/src/main/resources/application.yml  # claim-candidate-size: 200(可配)
tmp/cron-stress/                  # 复用 045/046/048 压测
```

**Structure Decision**:单后端模块(`dataweave-master`)优化,无前端/API/schema 变更。batchUpstreamReady 在 SchedulerKernel 内(同 048 batchCrossCycleReady inline 风格)。selectRunnable 加 runMode 参数区分 NORMAL/BACKFILL。

## Complexity Tracking

> 无 Constitution Check 违反,本节留空。
