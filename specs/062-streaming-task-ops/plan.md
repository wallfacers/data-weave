# Implementation Plan: 实时任务运维（062）

**Branch**: `062-streaming-task-ops` | **Date**: 2026-07-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/062-streaming-task-ops/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command.

## Summary

在运维中心 OpsView 追加独立的"实时任务"面板（第 5 tab + fork DataTable），把 `long_running` 流式任务提为一等运维对象：集中视图、最新日志（复用 SSE）、优雅停止（保留 checkpoint，区别于强制 kill）、检查点续跑（N=3 滚动、可选回滚点）、SUSPENDED 一等化与健康监控。技术路径（详见 [research.md](research.md)）：新表 `task_checkpoint` + `task_instance.long_running` 快照列（schema 0.15.0→0.16.0）；复用 STOPPED/RUNNING 状态不扩散枚举；OpsService 新增 `stopWithSavepoint`/`resumeFromCheckpoint`（保留 kill/rerun 存量语义不变），全 L1 直执 + audit。续跑的引擎侧 savepoint 能力硬依赖 061，其余 US 可先于 061 交付。

## Technical Context

**Language/Version**: Java 25（backend）+ TypeScript / React 19（frontend）

**Primary Dependencies**: Spring Boot 4 / WebFlux / Spring Data JDBC / JdbcTemplate（backend）；Next.js 16 (App Router) / shadcn/ui (base) / hugeicons（frontend）。复用既有：060 `FlinkTaskExecutor`（detached/reattach）、`OpsService`、`InstanceLogView` + SSE 日志流、`SchedulerMetrics`、`PolicyEngine`/audit（仅审计不经审批）。

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`，DDL 兼容）。新增 `task_checkpoint` 表 + `task_instance.long_running` 快照列；`schema_version` 0.15.0 → **0.16.0**（DROP+CREATE 幂等，存量零影响）。

**Testing**: JUnit5 + AssertJ + WebTestClient（backend，SB4 `@WebFluxTest` 在 `org.springframework.boot.webflux.test.autoconfigure`）；vitest + 浏览器验证（frontend）。

**Target Platform**: Linux server（backend，端口 8000）+ Web（frontend，端口 4000）。

**Project Type**: web-service（backend 四模块）+ web-app（frontend）。

**Performance Goals**: SC-001 30s 内定位目标实例；SC-002 最新日志 10s 内刷新、连续 7 天长期连接不退化；DataTable server 分页（实时任务通常数十量级，非高并发）。

**Constraints**:
- 所有 stop/resume/kill 操作 **L1 直执 + audit，不经审批**（Clarification④，与 OpsService 现有 kill/rerun 范式一致）
- 不破坏 060 已合 main 的不变量：`killTask`（CANCEL 无 savepoint）/`rerunInstance`（清 handle 全量重跑）语义不变；状态推进走乐观 CAS；SUSPENDED 非终态、不被 claim
- 061 未合前，savepoint 实际触发返回 `503 streaming.savepoint.unavailable`，**不冒充**真续跑（Clarification①）
- schema 向后兼容（新表 + 一列，无破坏性变更）
- i18n：后端错误码走 `BizException` + `Messages.get`，前端 next-intl（`streaming.*` 命名空间，zh-CN/en-US 双 bundle 等键）

**Scale/Scope**: 1 新表 + 1 新列；2 新 OpsService 方法 + 2 新 REST 端点 + 1 面板查询端点；1 新前端 tab + StreamingTasksPanel 组件；复用 SSE 日志/batch/kill/rerun 端点。无新模块、无跨 DDD 层方向反转（依赖方向 outer→inner 不变）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

依据 `.specify/memory/constitution.md` v1.2.0 五原则：

| 原则 | 核对 | 结论 |
|---|---|---|
| **I. Files-First** | 062 是运行态运维（实时任务实例的视图/停止/续跑），不触碰 task/workflow/catalog 的文件定义格式。`long_running` 是 task_def 既有标记。 | ✅ 合规（不适用，无冲突） |
| **II. Server is Source of Truth** | stop/resume/kill 均为服务端 OpsService 治理操作，服务端为真相源，audit 留痕；实时任务面板只读 + 受项目隔离约束。 | ✅ 合规 |
| **III. Two-Legged Debugging** | 非本特性核心；long_running 任务的本地调试复用 CLI/worker executor 既有路径，不另立执行引擎。 | ✅ 无冲突 |
| **IV. AI Lives in Local Agent** | 062 不引入服务端 AI；是运行态观测/控制面的**增强**（ops overview / run logs），契合内核③"拆除不得损伤运行态观测"。 | ✅ 合规 |
| **V. Reuse the Kernel** | 复用调度器（claim/CAS/reattach）、SSE 日志、PolicyEngine/audit、060 FlinkTaskExecutor、OpsService。新方法 `stopWithSavepoint`/`resumeFromCheckpoint` **并行存在、不覆盖** kill/rerun 存量语义。写操作 L1 直执 + audit 留痕（运维 UI 操作复用 OpsService 既有范式，等同 kill/rerun 的门控级别）。 | ✅ 合规（V 的典型实践） |

**post-design 重评**：Phase 1 设计（新表 + 复用状态 + fork DataTable + 新方法并行）未引入新模块、未反转依赖方向、未触碰文件定义层、未嵌服务端 AI、未绕过审计——五原则仍全部通过。无 violation。

## Project Structure

### Documentation (this feature)

```text
specs/062-streaming-task-ops/
├── spec.md               # /speckit-specify（已 clarify）
├── plan.md               # 本文件
├── research.md           # Phase 0：5 决策 + 理由 + 备选 + 代码锚点
├── data-model.md         # Phase 1：task_checkpoint + 状态迁移
├── quickstart.md         # Phase 1：本地验证步骤
├── contracts/
│   └── streaming-tasks-api.md  # Phase 1：REST 契约
└── tasks.md              # /speckit-tasks（下一步，本命令不创建）
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/src/main/resources/
│   └── schema.sql                          # +task_checkpoint 表 + task_instance.long_running 列 + schema_version 0.16.0
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/
│   │   ├── OpsService.java                 # +stopWithSavepoint +resumeFromCheckpoint；rerunInstance SQL 加 long_running
│   │   ├── InstanceStateMachine.java       # +casResumeFromCheckpoint (STOPPED/SUSPENDED→WAITING)
│   │   └── SchedulerMetrics.java           # +streaming.checkpoint.total / streaming.recovering gauge
│   └── domain/InstanceStates.java          # 不改（复用 11 态）
├── dataweave-api/src/main/java/com/dataweave/api/
│   ├── interfaces/OpsController.java       # +GET /streaming-tasks +GET /streaming-tasks/{id}/checkpoints +POST /stop +POST /resume
│   └── .../dto/                            # +StreamingTaskRow +CheckpointDTO +ResumeRequest
└── dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/
    └── FlinkTaskExecutor.java              # +savepoint 触发（/jobs/{id}/stop?targetDirectory）—— 待 061 落地

frontend/
├── components/workspace/views/
│   ├── ops-view.tsx                        # TabId +streamingTasks +TAB_ORDER +渲染分支
│   └── ops/streaming-tasks-panel.tsx       # 新建（fork PeriodicInstancesPanel：DataTable + 操作按钮 + SUSPENDED badge）
└── lib/
    └── instance-actions.ts                 # +stop(保留进度) +resume 操作门控

tests/
├── backend/.../OpsServiceTest.java         # stopWithSavepoint/resumeFromCheckpoint/rerunInstance-long_running
├── backend/.../InstanceStateMachineTest.java  # casResumeFromCheckpoint 单赢 + 060 不变量兼容
├── backend/.../StreamingTasksWebTest.java  # /streaming-tasks* 契约 + 项目隔离
└── frontend/.../streaming-tasks-panel.test.tsx  # 渲染 + 操作（vitest）
```

**Structure Decision**: Web application（backend 四模块 + frontend）。复用既有 DDD 分层（domain←application←infrastructure←interfaces），无新模块、无依赖方向变更。前端在 `components/workspace/views/ops/` 下新增 panel，与 `periodic-instances-panel.tsx` 同层同模式。

## Complexity Tracking

> 无 Constitution Check violation。所有架构决策均在既有模式内（新表 / 复用状态枚举 / fork 已验证 DataTable 面板 / 新方法并行不覆盖存量），无需填此表。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| （无） | — | — |
