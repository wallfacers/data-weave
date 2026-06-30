# Implementation Plan: 运行态同步行数采集（recordSynced 接入）

**Branch**: `025-lineage-synced-rows` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/025-lineage-synced-rows/spec.md`

## Summary

打通"今日同步行数"端到端：SQL 执行器已 per-statement 拿到 affected-rows（只进诊断日志），本特性把它**结构化进 `ExecutionResult.statementMetrics`**，经**双上报路径**（all-in-one 进程内 + distributed HTTP）透传到 master，`WorkerReportService.reportFinished` 注入 `LineageStore`、复用 `SqlTableExtractor` 逐 statement 解析写表、逐表调**已实现但零调用**的 `recordSynced`。recordSynced 接口 + neo4j 实现就绪——本特性是上游采集 + 接线。scope = SQL 任务（Spark/Python/Shell 出范围）；MVP 写表识别 INSERT/MERGE。

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.0 / Spring Framework 7 (Jackson 3), WebFlux。

**Primary Dependencies**: JDBC（`SqlTaskExecutor` 已用）、Apache Calcite（`SqlTableExtractor` 复用）、Neo4j（`recordSynced` 已实现）、Jackson（上报 DTO 序列化，替代手拼 JSON）。

**Storage**: Neo4j（`:TaskRun`/`:SYNCED`，recordSynced 已实现）；PG `task_instance`（bizDate/tenant/project 来源）。

**Testing**: JUnit 5 + AssertJ；testcontainers-neo4j 端到端（SQL 任务跑完 → `:SYNCED` → syncSummary SUM）；单测 mock LineageStore 覆盖采集/解析/降级。

**Target Platform**: Linux server（worker + master JVM；all-in-one 默认单 JVM）。

**Project Type**: web-service（跨 worker + api + master 三模块的上报链路 + master 接线）。

**Performance Goals**: 行数采集/上报/解析 MUST NOT 阻塞任务执行/回报主链路（降级优先）。

**Constraints**: 增强项绝不阻断（与现有 lineage 写入降级一致）；零回归（非 SQL/失败任务）；新旧 worker/master 向后兼容。

**Scale/Scope**: SQL 任务；多 statement/多表；UPDATE/DELETE 记债；Spark/Python/Shell 出范围。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | 状态 | 依据 |
|---|---|---|
| I. Files-First（文件优先） | N/A | 运行态采集，非定义态；不碰 `.task.yaml` 声明（那是 024）。 |
| II. Server is Source of Truth | ✅ PASS | recordSynced 落 neo4j 服务端；`reportFinished` 是 master 统一写入点，push/治理仍在服务端。 |
| III. Two-Legged Debugging | ✅ PASS | 不改 CLI 本地 runtime / TEST 语义；`dw run` 复用同一 `SqlTaskExecutor`（执行器改动是 code-level 共享、非分叉引擎），本地多收一个 statementMetrics 字段但本地不调 recordSynced（本地无 master 回报）——零回归、不破坏两条腿。 |
| IV. AI Lives in Local Agent | ✅ PASS | 无服务端 AI。 |
| V. Reuse the Kernel（内核复用） | ✅ PASS | 复用 `SqlTableExtractor`、`recordSynced` 已实现、`WorkerReportService` 主链路、双上报路径；不新建第二条执行/回报引擎。 |
| Additional: round-trip integrity | N/A | 不涉及定义态 round-trip（运行态采集）。 |
| Additional: sub-spec isolation | ✅ PASS | 不改 recordSynced 契约 / syncSummary 读侧；与 024（定义态列 catalog）边界清晰、代码路径独立、互不阻塞。 |

**Gate 结果**: 无违规，无需 Complexity Tracking。进入 Phase 0。

## Project Structure

### Documentation (this feature)

```text
specs/025-lineage-synced-rows/
├── plan.md              # 本文件
├── research.md          # Phase 0 产出
├── data-model.md        # Phase 1 产出
├── quickstart.md        # Phase 1 产出
├── contracts/           # Phase 1 产出（上报 payload + recordSynced 调用契约）
└── tasks.md             # Phase 2 (/speckit-tasks，本命令不生成)
```

### Source Code (repository root)

```text
backend/dataweave-worker/src/main/java/com/dataweave/worker/
├── domain/
│   └── TaskExecutor.java                  # [改] ExecutionResult record 加 statementMetrics 字段（+ StatementMetric record）
└── infrastructure/
    └── SqlTaskExecutor.java               # [改] 收 per-statement (sqlText, updateCount) 进 statementMetrics（替代只 emit onLine）

backend/dataweave-api/src/main/java/com/dataweave/api/
├── infrastructure/
│   └── InProcessTaskExecutionGateway.java # [改] 从 ExecutionResult 取 statementMetrics 透传 reportFinished（all-in-one 路径）
└── interfaces/
    └── ClusterController.java             # [改] TaskReportRequest 扩 statementMetrics → 透传 reportFinished（HTTP 路径）

backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   └── WorkerReportService.java           # [改] reportFinished 签名加 statementMetrics；注入 LineageStore + SqlTableExtractor；逐 statement 解析写表 → recordSynced；try-catch 不阻断
└── domain|infrastructure/lineage/
    ├── LineageStore.java                  # [既有 不改契约] recordSynced 接口
    └── Neo4jLineageStore.java             # [既有] recordSynced 实现（建议 :TaskRun SET taskDefId）

# HTTP 上报序列化：WorkerExecController.ReportCallback.reportToMaster（worker 侧，:204 手拼 JSON）改 Jackson ObjectMapper
```

**Structure Decision**: 跨 worker/api/master 三模块接线；改动集中在 `SqlTaskExecutor`（采集）+ `ExecutionResult`/`TaskReportRequest`（DTO）+ 双上报路径（`InProcessTaskExecutionGateway` + `WorkerExecController`/`ClusterController`）+ `WorkerReportService.reportFinished`（解析+写入）。复用现成 `SqlTableExtractor`/`recordSynced`，无新引擎。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
