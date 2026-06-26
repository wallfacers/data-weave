# Implementation Plan: DAG 节点详情侧面板

**Branch**: `004-dag-node-detail-panel` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-dag-node-detail-panel/spec.md`

## Summary

在 Ops 中心 DAG 弹框（002-ops-dag-viewer）中增加节点交互能力：点击/右击 TASK 节点 → 弹框右侧滑出 1/4 宽度的详情面板 → 展示该节点关联任务的发布时配置（名称、类型、版本号、代码/脚本只读高亮、配置参数键值对）。面板支持拖拽调整宽度（1/5~1/3，min 280px）和 Escape 键关闭。纯前端布局变更 + 一个后端 API（按 taskId+versionNo 取任务版本快照详情）。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 (frontend); Java 25 / Spring Boot 4.0 (backend)
**Primary Dependencies**: ReactFlow (@xyflow/react), Shiki (syntax highlighting), motion/react (drag-resize), @base-ui/react (context menu)
**Storage**: PostgreSQL — `task_def_version` table (already contains `content`, `params_json`, `type`, etc.)
**Testing**: vitest (frontend); JUnit 5 + AssertJ (backend)
**Target Platform**: Desktop browser (Chrome, Edge, Firefox modern versions)
**Project Type**: Web application (Next.js SPA + Spring Boot WebFlux API)
**Performance Goals**: Node click → panel content visible <1s (for ≤500 lines of code)
**Constraints**: Zero new npm/Maven dependencies; reuse existing patterns (motion drag-resize, Shiki highlighting, ApiResponse wrapper)
**Scale/Scope**: ~3 new frontend components, ~2 backend files changed, ~1 new API endpoint

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Status | Evidence |
|------|--------|----------|
| DDD dependency direction | ✅ PASS | New endpoint in `interfaces/` → delegates to `application/` service → reads `domain/` repository. No reverse dependency. |
| Post-edit verification | ✅ PASS | Will run `./mvnw compile` (backend) and `pnpm typecheck` (frontend) after each edit. |
| Browser verification gate | ✅ PASS | Feature touches DAG interaction seams — must pass browser test before completion. |
| No new dependencies | ✅ PASS | Reuses motion/react for resize, Shiki for highlighting, @base-ui/react context-menu, DwScroll. Zero new packages. |
| Testing required | ✅ PASS | Backend: `@SpringBootTest` for new endpoint. Frontend: vitest for panel state + browser verification for real interaction. |
| i18n ownership | ✅ PASS | Backend-generated copy (panel labels, error messages) via `Messages.get`. Frontend static copy via next-intl `useTranslations("ops")`. |

## Project Structure

### Documentation (this feature)

```text
specs/004-dag-node-detail-panel/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── node-detail-api.yaml
└── tasks.md              # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
# Frontend changes
frontend/
├── components/workspace/
│   ├── dag-viewer-dialog.tsx        # MODIFY: layout split 3/4+1/4, node click/right-click handlers
│   ├── dag-renderer.tsx             # MODIFY: allow onNodeClick in readOnly mode, fix type
│   ├── node-detail-panel.tsx        # NEW: right-side detail panel
│   └── nodes/
│       ├── task-node.tsx            # MODIFY: read-only context menu support
│       └── virtual-node.tsx         # MODIFY: read-only context menu (empty for virtual)
├── lib/
│   └── workspace/
│       └── node-detail-store.ts     # NEW: zustand store for selected node + panel state
└── messages/
    ├── zh-CN.json                   # MODIFY: add ops.nodeDetail.* keys
    └── en-US.json                   # MODIFY: add ops.nodeDetail.* keys

# Backend changes
backend/
├── dataweave-api/src/main/java/com/dataweave/api/
│   └── interfaces/
│       └── OpsController.java       # MODIFY: add GET /api/ops/workflows/{id}/nodes/{key}/detail
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/
│   │   ├── WorkflowService.java     # MODIFY: add taskVersionNo to DagNodeDto, add getNodeDetail()
│   │   └── OpsService.java          # MODIFY: delegate node detail retrieval
│   ├── domain/
│   │   └── TaskDefVersionRepository.java  # (no change; findByTaskIdAndVersionNo exists)
│   └── resources/
│       └── messages.properties      # MODIFY: add node detail i18n keys
```

**Structure Decision**: Web application layout. Frontend in `components/workspace/` + `lib/workspace/`. Backend follows DDD layering: new endpoint in `interfaces/OpsController` → delegates to `application/OpsService`/`WorkflowService` → reads existing `domain/TaskDefVersionRepository`.
