# Tasks: DAG Dialog Consolidation

**Input**: Design documents from `/specs/005-dag-dialog-consolidation/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, quickstart.md

**Tests**: No test tasks — spec does not request TDD. Verification via `pnpm typecheck` + browser validation gate.

**Organization**: Tasks grouped by user story. US1 and US2 are independent after Foundational phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

All paths relative to `frontend/`.

---

## Phase 1: Setup

**Purpose**: Verify prerequisites — no new project initialization needed.

- [X] T001 Verify `pnpm typecheck` passes clean before starting any changes

---

## Phase 2: Foundational — Shared UI Components

**Purpose**: Create new shared sub-components that have no dependencies on existing code. These are building blocks for US2's side panel refactoring. Also create the two shell components (dag-dialog, detail-panel-shell) needed by US1 and US2 respectively.

**⚠️ CRITICAL**: All new files must pass typecheck independently. No existing code is modified in this phase.

### Shared UI Sub-components (for US2 side panels)

- [X] T002 [P] Create `frontend/components/workspace/shared/loading-state.tsx` — inline spinner + "Loading…" text, matches existing LoadingState in NodeDetailPanel
- [X] T003 [P] Create `frontend/components/workspace/shared/error-state.tsx` — error message + retry button, accepts `message: string` and `onRetry: () => void`
- [X] T004 [P] Create `frontend/components/workspace/shared/code-block.tsx` — Shiki highlightCode wrapper with DwScroll, accepts `code: string` and `lang: string`
- [X] T005 [P] Create `frontend/components/workspace/shared/params-table.tsx` — key-value params table + InfoRow + taskTypeToLang helper, matches existing ParamsTable/InfoRow in NodeDetailPanel

### Shell Components

- [X] T006 Create `frontend/components/workspace/detail-panel-shell.tsx` — shared side panel skeleton: Header (title + close button) + DwScroll Body + loading overlay. Accepts `title`, `onClose`, `loading`, `error`, `onRetry`, `hasData`, `children`
- [X] T007 Create `frontend/components/workspace/dag-dialog.tsx` — shared DAG dialog shell: Dialog (90vw×90vh) + Header + Body (flex-row: left DAG area + right panel motion.div) + Footer + panel resize logic (calcPanelWidth, useMotionValue, onPanelResizeDown) + loading/error/empty states + DagRenderer integration. Accepts all props defined in plan.md §Phase 0.3

**Checkpoint**: All new components exist, typecheck passes, no existing code modified yet.

---

## Phase 3: User Story 1 - 统一 DAG 弹窗组件 (Priority: P1) 🎯 MVP

**Goal**: DagViewerDialog and InstanceDagDialog both delegate to the shared `dag-dialog.tsx`, each becoming a ~60-line wrapper that only handles its own data fetching + node interaction logic.

**Independent Test**: Open periodic workflow DAG → topology renders correctly; open instance DAG → node run states visible. Panel resize works in both, widths independently persisted. Right-click context menu still works on periodic DAG.

### Implementation for User Story 1

- [X] T008 [US1] Refactor `frontend/components/workspace/dag-viewer-dialog.tsx` — replace all Dialog/Panel/Footer/layout JSX with a call to `DagDialog`, passing: dagData from published-dag fetch, panelStorageKey="dw.dagViewer.panelWidth", title=workflowName, footerInfo=version info, renderSidePanel → NodeDetailPanel, onNodeClick → selectNode from store, onNodeContextMenu for right-click, escapeToDeselect=true. Keep ReactFlowProvider wrapper. Target: ~60 lines.

- [X] T009 [US1] Refactor `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` — replace all Dialog/Panel/Footer/layout JSX with a call to `DagDialog`, passing: dagData/loading/error/onRetry from useInstanceDag hook, panelStorageKey="dw.instanceDag.panelWidth", title=dag.workflowName + bizDate, subtitle=trigger+state info, footerInfo=version+trigger+bizDate, renderSidePanel → InstanceDetailSidePanel, onNodeClick → local setState, highlightNodeKey support. Target: ~60 lines. Remove duplicated panel resize code (calcPanelWidth, useMotionValue, onPanelResizeDown, useLayoutEffect).

- [X] T010 [US1] Run `pnpm typecheck` — verify zero errors after both dialog refactors

**Checkpoint**: US1 complete — both DAG dialogs work through shared DagDialog component. Ready for browser verification.

---

## Phase 4: User Story 2 - 侧面板组件统一 (Priority: P2)

**Goal**: NodeDetailPanel and InstanceDetailSidePanel both use shared UI sub-components (LoadingState, ErrorState, CodeBlock, ParamsTable/InfoRow) and DetailPanelShell for their skeleton. Remove all duplicated sub-component definitions.

**Independent Test**: Click node in periodic DAG → panel shows task config with Shiki-highlighted code. Click node in instance DAG → panel shows actual code/config. Both panels have identical Header, DwScroll, section heading style. No width flash when switching nodes.

### Implementation for User Story 2

- [X] T011 [US2] Refactor `frontend/components/workspace/node-detail-panel.tsx` — replace local LoadingState, ErrorState, CodeBlock, ParamsTable, InfoRow with imports from `@/components/workspace/shared/*`. Wrap content in `DetailPanelShell` (title="节点详情", onClose=deselectNode, loading/error/onRetry/hasData from store). Remove local sub-component definitions (~120 lines removed). Target: ~120 lines (from current 243).

- [X] T012 [US2] Refactor `frontend/components/workspace/views/ops/instance-detail-side-panel.tsx` — replace local LoadingState, ErrorState, CodeBlock, ParamsTable, InfoRow, taskTypeToLang with imports from `@/components/workspace/shared/*`. Wrap content in `DetailPanelShell` (title=nodeName, onClose=onClose, loading/error/onRetry/hasData from local state). Remove local sub-component definitions (~120 lines removed). Target: ~160 lines (from current 283).

- [X] T013 [US2] Run `pnpm typecheck` — verify zero errors after both side panel refactors

**Checkpoint**: US2 complete — both side panels use shared components and have identical visual structure.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Final verification, cleanup, and regression check.

- [X] T014 Verify no unused imports remain in any modified file (clean up DagViewerDialog/InstanceDagDialog's old imports: ReactFlowProvider @xyflow/react, motion/react, Dialog components if no longer directly used)
- [X] T015 Run `pnpm typecheck` — final check, must be zero errors
- [ ] T016 Browser verification gate — test both DAG dialogs end-to-end per quickstart.md scenarios VS-1 through VS-5
- [X] T017 Run `git diff --stat` to confirm net negative line count (~200 lines removed)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — verify current state
- **Foundational (Phase 2)**: Depends on Phase 1 — creates all new files, no existing code modified
- **User Story 1 (Phase 3)**: Depends on Phase 2 (needs dag-dialog.tsx) — refactors both DAG dialogs
- **User Story 2 (Phase 4)**: Depends on Phase 2 (needs shared/* + detail-panel-shell.tsx) — refactors both side panels. Independent of US1 (different files)
- **Polish (Phase 5)**: Depends on US1 + US2 complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Phase 2. Independent of US2.
- **User Story 2 (P2)**: Can start after Phase 2. Independent of US1.

### Within Each User Story

- Refactor both files in each story can run in parallel (different files)

### Parallel Opportunities

```
Phase 2: T002, T003, T004, T005 all [P] — launch together (4 shared sub-components, different files)
         → then T006 (detail-panel-shell) — depends on shared sub-components conceptually but can be created simultaneously
         → T007 (dag-dialog) — independent of T006

Phase 3: T008, T009 [P] — launch together (different files, both depend on T007)
         → T010 after both complete

Phase 4: T011, T012 [P] — launch together (different files, both depend on Phase 2)
         → T013 after both complete

Phase 5: Sequential (typecheck → browser → diff)
```

---

## Parallel Example: Phase 2 Foundational

```bash
# Launch all 4 shared UI sub-components together:
Task: "Create frontend/components/workspace/shared/loading-state.tsx"
Task: "Create frontend/components/workspace/shared/error-state.tsx"
Task: "Create frontend/components/workspace/shared/code-block.tsx"
Task: "Create frontend/components/workspace/shared/params-table.tsx"

# Then shell components (can also run together):
Task: "Create frontend/components/workspace/detail-panel-shell.tsx"
Task: "Create frontend/components/workspace/dag-dialog.tsx"
```

## Parallel Example: User Story 1

```bash
# Both dialog refactors together:
Task: "Refactor frontend/components/workspace/dag-viewer-dialog.tsx"
Task: "Refactor frontend/components/workspace/views/ops/instance-dag-dialog.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Verify typecheck baseline
2. Complete Phase 2: Create all shared components (no existing code changed yet)
3. Complete Phase 3: Refactor both DAG dialogs → User Story 1 complete
4. **STOP and VALIDATE**: Browser test both dialogs, typecheck
5. This already delivers the primary value: ~200 lines of duplicated dialog code eliminated

### Incremental Delivery

1. Setup + Foundational → shared components ready
2. Add US1 → DAG dialogs unified → Browser verify (MVP!)
3. Add US2 → Side panels unified → Browser verify
4. Polish → cleanup + final verification
5. Each story adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- No test tasks — spec does not require TDD for this refactoring
- Browser verification gate is MANDATORY (per CLAUDE.md rule) — T016 is not optional
- All shared components must be backward compatible — existing behavior preserved 100%
- Commit after each phase checkpoint
