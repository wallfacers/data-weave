# Implementation Plan: 运营中心实例列表切换与 DAG 查看

**Branch**: `003-instance-dag-viewer` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-instance-dag-viewer/spec.md`

## Summary

在运营中心"实例"页签增加任务流实例列表视图，与现有任务实例列表并排切换。两个列表均支持下钻到实例级 DAG 弹窗，节点叠加运行时状态（10 种状态视觉区分），点击节点以侧边面板展示参数替换后的实际代码和配置。参数替换复用执行引擎现有解析逻辑，DAG 拓扑使用实例实际运行的历史版本。

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7, WebFlux, Next.js 16 (App Router), React 19, ReactFlow, shadcn/ui
**Storage**: PostgreSQL (primary), H2 (test/CI)
**Testing**: JUnit 5 + AssertJ (backend), Vitest (frontend), Playwright (browser gate)
**Target Platform**: Linux server, modern browsers
**Project Type**: Web application (frontend + backend)
**Performance Goals**: List switch <2s, DAG render <2s (≤20 nodes) / <5s (≤100 nodes), state update delay <3s, code/config load <1s
**Constraints**: Existing AG-UI SSE event stream for DAG live updates; existing DagViewerDialog/DagRenderer for DAG rendering; reuse WorkhorseBridge parameter resolver
**Scale/Scope**: 2 new list endpoints (backend), 1 new DAG detail endpoint, frontend list toggle + instance DAG dialog + side panel

## Constitution Check (Post-Design)

*Re-check after Phase 1 design: No violations introduced. All new endpoints follow existing REST patterns. No new tables or schema changes. Reuses existing ScheduleParamResolver and DagRenderer. DDD layering preserved (queries via domain services, API via controller → bridge → service).*

## Project Structure

### Documentation (this feature)

```text
specs/003-instance-dag-viewer/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/
│   │   └── OpsService.java                    # ADD: queryWorkflowInstances(), getInstanceDag(), resolveActualCode()
│   ├── domain/
│   │   └── WorkflowInstance.java              # (existing, read)
│   └── interfaces/
│       └── OpsContracts.java                  # ADD: WorkflowInstanceRow, WorkflowInstanceQuery, InstanceDagView, ResolvedCodeView
├── dataweave-api/src/main/java/com/dataweave/api/
│   └── interfaces/
│       └── OpsController.java                 # ADD: GET /api/ops/workflow-instances, GET /api/ops/workflow-instances/{id}/dag, GET /api/ops/task-instances/{id}/resolved-code
frontend/
├── components/workspace/views/ops/
│   ├── ops-view.tsx                           # MODIFY: instance tab logic with toggle
│   ├── workflow-instances-panel.tsx           # NEW: workflow instance list panel
│   ├── instance-dag-dialog.tsx                # NEW: instance-level DAG dialog (extends dag-viewer-dialog pattern)
│   └── instance-detail-side-panel.tsx         # NEW: side panel for code/config within DAG dialog
├── components/workspace/
│   └── dag-renderer.tsx                       # MODIFY: support instance state overlay on nodes
├── lib/hooks/
│   └── use-instance-dag.ts                    # NEW: fetch instance DAG + subscribe to SSE
└── lib/types.ts                               # ADD: InstanceDagView, ResolvedCodeView, WorkflowInstanceRow types
```

## Complexity Tracking

> No violations. Constitution template only, no formal gates.

## Phase 0: Research

### Research Tasks

Our exploration during `/speckit-specify` already resolved most unknowns. Two items need explicit research:

1. **How does the worker resolve parameters at execution time?** We need to identify the exact code path used for parameter substitution so we can reuse it for the "actual code" display.
2. **What is the existing WorkflowInstance query pattern?** The OpsService already has `queryInstances()` for task instances. We need to understand the pattern to replicate it for workflow instances.

### Research Findings

See [research.md](./research.md) for detailed findings.
