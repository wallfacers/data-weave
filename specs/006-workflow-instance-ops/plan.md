# Implementation Plan: 工作流实例运维操作

**Branch**: `006-workflow-instance-ops` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-workflow-instance-ops/spec.md`

## Summary

为工作流/任务实例构建完整的运维操作面板。后端已实现核心操作 API（停止/重跑/置成功/暂停/恢复/批量），本 feature 聚焦于：① 前端补充缺失的运维操作交互界面（工作流实例级操作、单任务节点操作面板、批量操作上限）；② 后端修复 5 个功能缺口（重跑状态守卫、DEV 环境限制、env 字段透出、单任务端点、批量上限）。

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript 5 + React 19 (frontend)
**Primary Dependencies**: Spring Boot 4.0 / WebFlux / Spring Data JDBC (backend); Next.js 16 / shadcn/ui / zustand / DataTable (frontend)
**Storage**: PostgreSQL (default) / H2 (dev/test)
**Testing**: JUnit 5 + AssertJ + WebTestClient (backend); vitest + Playwright browser verification (frontend)
**Target Platform**: Linux server (backend, port 8000); modern browsers (frontend, port 4000)
**Project Type**: Web application — frontend SPA + backend REST/SSE
**Performance Goals**: 操作定位+执行 <10s (SC-001), 状态转换 <2s (SC-002)
**Constraints**: 批量操作上限 100 个实例 (FR-008), DEV 环境仅暴露停止操作 (FR-013), 无需审批直接执行 (FR-011)
**Scale/Scope**: 7 用户故事, 13 FRs, 前后端并重 (5 个后端缺口 + 6 个前端新增)

## Constitution Check

*GATE: Constitution template is empty (placeholder only). No project-specific principles to enforce. Proceeding with standard gates:*

- [x] DDD layering preserved: changes stay within existing module boundaries (master domain/application, api interfaces, frontend components)
- [x] AG-UI protocol: no changes to /agui event stream
- [x] PolicyEngine: operations bypass gate for direct execution (FR-011 — deliberate spec choice)
- [x] i18n: new error codes follow `<domain>.<semantic>` pattern; frontend labels use `t()` keys
- [x] Testing: new backend endpoints require JUnit 5 + WebTestClient; frontend requires browser verification gate
- [x] CAS invariants: new guard checks use optimistic locking pattern consistent with InstanceStateMachine

## Project Structure

### Documentation (this feature)

```text
specs/006-workflow-instance-ops/
├── plan.md              # This file
├── research.md          # Phase 0: gap analysis & decisions
├── data-model.md        # Phase 1: entity changes
├── quickstart.md        # Phase 1: validation guide
├── contracts/           # Phase 1: API contract changes
│   └── api-changes.md
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/
│   └── src/main/java/com/dataweave/api/
│       └── interfaces/
│           └── OpsController.java           # [MODIFY] add single-task endpoints, env field, batch limit
├── dataweave-master/
│   └── src/main/java/com/dataweave/master/
│       ├── application/
│       │   ├── OpsService.java              # [MODIFY] env field in queries, DEV check
│       │   ├── RecoveryService.java         # [MODIFY] state guard on rerunAll, frozen node protection
│       │   ├── OpsContracts.java            # [MODIFY] add env to row DTOs, batch limit constant
│       │   └── WorkflowStateService.java   # [no change needed]
│       └── domain/
│           └── WorkflowInstance.java        # [no change needed] (env field already exists)
└── src/test/
    └── java/com/dataweave/
        ├── api/
        │   └── OpsControllerTest.java      # [NEW/MODIFY] test new endpoints
        └── master/
            └── OpsServiceTest.java          # [MODIFY] test guard/logic changes

frontend/
├── components/workspace/views/ops/
│   ├── workflow-instances-panel.tsx         # [MODIFY] add batch operations, env column
│   ├── periodic-instances-panel.tsx         # [MODIFY] add batch size enforcement
│   ├── instance-dag-dialog.tsx              # [MODIFY] show env-based operation buttons
│   └── task-node-actions.tsx               # [NEW] single task node action buttons
├── lib/workspace/
│   └── views.ts                             # [no change needed]
└── app/
    └── globals.css                          # [no change needed]
```

**Structure Decision**: Web application pattern. Backend changes are localized to existing modules (dataweave-api interfaces, dataweave-master application). Frontend changes are within the existing ops panel component tree. No new modules or directories needed.

## Complexity Tracking

> No constitution violations. This section intentionally left empty.
