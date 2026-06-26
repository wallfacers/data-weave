# Tasks: 运维任务流 DAG 查看器

**Input**: Design documents from `/specs/002-ops-dag-viewer/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Included per CLAUDE.md rule "New features must have tests; no test = not done."

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: i18n keys required by all subsequent frontend tasks

- [x] T001 Add dagViewer i18n keys to `frontend/messages/zh-CN.json` and `frontend/messages/en-US.json` — keys: `dagViewer.title`, `dagViewer.empty`, `dagViewer.error`, `dagViewer.retry`, `dagViewer.versionInfo`, `dagViewer.close`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend endpoint + shared frontend components that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Backend — Published DAG endpoint

- [x] T002 Implement `WorkflowService.readPublishedDag(Long workflowId)` method in `backend/dataweave-master/src/main/java/com/dataweave/master/application/WorkflowService.java`:
  - Load `WorkflowDef` by id, validate `status == "ONLINE"` and `currentVersionNo != null`
  - Query `WorkflowDefVersionRepository.findByWorkflowIdAndVersionNo(workflowId, currentVersionNo)`
  - Deserialize `dagSnapshotJson` via Jackson `ObjectMapper` into `WorkflowDagSnapshot`
  - Map snapshot nodes/edges to `DagNodeDto`/`DagEdgeDto`, return `DagView`
  - Throw `BizException("workflow.not_online_or_unpublished")` for non-ONLINE workflows
- [x] T003 Add `GET /api/workflows/{id}/published-dag` endpoint in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/WorkflowController.java` — delegate to `workflowService.readPublishedDag(id)`, return `ApiResponse<DagView>`

### Frontend — Shared node components (extract from canvas)

- [x] T004 [P] Extract `TaskNode` component from `frontend/components/workspace/views/workflow-canvas-view.tsx` (lines 178-230) into new file `frontend/components/workspace/nodes/task-node.tsx`:
  - Preserve: rounded-rect shape, `DatabaseIcon`, `RunStateDot`, left/right `Handle`, label truncation, selected state styling
  - Add optional `readOnly?: boolean` prop (default `false`); when `true`, omit `ContextMenu` wrapper
  - Update `workflow-canvas-view.tsx` to import from new path
- [x] T005 [P] Extract `VirtualNode` component from `frontend/components/workspace/views/workflow-canvas-view.tsx` (lines 232-256) into new file `frontend/components/workspace/nodes/virtual-node.tsx`:
  - Preserve: dashed pill shape, `CircleIcon`, selected state, muted styling
  - Add optional `readOnly?: boolean` prop (default `false`); when `true`, omit `ContextMenu` wrapper
  - Update `workflow-canvas-view.tsx` to import from new path

### Frontend — DAG viewer dialog

- [x] T006 Create `DagViewerDialog` component in `frontend/components/workspace/dag-viewer-dialog.tsx`:
  - **Props**: `workflowId: number`, `workflowName: string`, `open: boolean`, `onOpenChange: (open: boolean) => void`
  - **Data fetching**: `GET /api/workflows/{workflowId}/published-dag` → auto-fetch on `open=true`, use `authFetch` + `API_BASE` pattern from canvas
  - **ReactFlow setup** (read-only):
    - `nodeTypes = { task: TaskNode, virtual: VirtualNode }` with `readOnly={true}`
    - `nodesDraggable={false}`, `nodesConnectable={false}`
    - No `onNodesChange`, `onEdgesChange`, `onConnect`, `onDrop`, `onDragOver`, `deleteKeyCode`
    - `fitView` with `padding: 0.2` on initial load
    - `<Background />` + `<Controls showInteractive={false} />`
    - No `MiniMap`
  - **DAG mapping**: Use same `toFlow()` logic as `workflow-canvas-view.tsx` (DagNode → CanvasNode, DagEdge → Edge with WEAK=animated dashed)
  - **Dialog shell**: `@base-ui/react/dialog` `Dialog` + `DialogContent` with `className="max-w-[90vw] max-h-[90vh] w-[90vw] h-[90vh]"`
  - **Header**: `DialogHeader` → `DialogTitle` with `t("dagViewer.title", { name: workflowName })`
  - **Footer**: `DialogFooter` → version info (`t("dagViewer.versionInfo", { versionNo, publishedAt })`) + `DialogClose` button (`t("dagViewer.close")`)
  - **Close**: Support `Escape` key, backdrop click (via `Dialog` default behavior), close button
  - **Loading state**: Show loading indicator while fetching (use existing app spinner pattern, no `…` in text)
  - **Empty state**: When `nodes.length === 0` after load, render `t("dagViewer.empty")` centered in dialog body
  - **Error state**: On fetch error, render error message + retry button (`t("dagViewer.error")` / `t("dagViewer.retry")`)

**Checkpoint**: Foundation ready — backend endpoint working, DagViewerDialog renders published DAG in read-only mode. User story wiring can now begin.

---

## Phase 3: User Story 1 - 查看周期任务流发布版 DAG (Priority: P1) 🎯 MVP

**Goal**: Ops 中心周期任务流列表中的"查看 DAG"按钮改为打开 DagViewerDialog 弹框，展示已发布版本的只读 DAG

**Independent Test**: 在周期任务流列表中点击 ONLINE 任务流的"查看 DAG"按钮 → 弹框展示发布版 DAG（非草稿）

### Implementation for User Story 1

- [x] T007 [US1] Update "查看 DAG" button in `frontend/components/workspace/views/ops/periodic-workflows-panel.tsx`:
  - Add `const [dagWorkflow, setDagWorkflow] = useState<WorkflowRow | null>(null)` state
  - Change button `onClick` from `open("workflow-canvas", { workflowId: w.id, name: w.name })` to `setDagWorkflow(w)`
  - Add status gate: only render button when `w.status === "ONLINE"` (hide or disable for DRAFT)
  - Conditionally render `<DagViewerDialog workflowId={dagWorkflow.id} workflowName={dagWorkflow.name} open={!!dagWorkflow} onOpenChange={(v) => { if (!v) setDagWorkflow(null) }} />`
  - Remove `open` import if no longer used for this button (check for other usages first)

**Checkpoint**: 周期任务流列表的"查看 DAG"按钮可独立端到端测试 — 点击 ONLINE 工作流 → 弹框展示发布版 DAG

---

## Phase 4: User Story 2 - 查看手动任务流发布版 DAG (Priority: P1)

**Goal**: Ops 中心手动任务流列表中的"查看 DAG"按钮改为打开 DagViewerDialog 弹框

**Independent Test**: 在手动任务流列表中点击 ONLINE 任务流的"查看 DAG"按钮 → 弹框展示发布版 DAG

### Implementation for User Story 2

- [x] T008 [US2] Update "查看 DAG" button in `frontend/components/workspace/views/ops/manual-workflows-panel.tsx`:
  - Add `const [dagWorkflow, setDagWorkflow] = useState<WorkflowRow | null>(null)` state
  - Change button `onClick` from `open("workflow-canvas", { workflowId: w.id, name: w.name })` to `setDagWorkflow(w)`
  - Add status gate: only render button when `w.status === "ONLINE"` (hide or disable for DRAFT)
  - Conditionally render `<DagViewerDialog workflowId={dagWorkflow.id} workflowName={dagWorkflow.name} open={!!dagWorkflow} onOpenChange={(v) => { if (!v) setDagWorkflow(null) }} />`
  - Remove `open` import if no longer used for this button (check for other usages — "Run Once" button may still need it)

**Checkpoint**: 手动任务流列表的"查看 DAG"按钮可独立端到端测试 — 与 US1 对等的能力

---

## Phase 5: User Story 3 - 弹框交互与 DAG 导航 (Priority: P2)

**Goal**: 确保弹框内 DAG 图在所有状态下（空/错误/大图）交互流畅，空状态和错误状态正确展示

**Independent Test**: 打开包含不同规模（0 节点、10+ 节点）的 DAG 弹框，验证空状态展示 + 缩放平移 + 错误重试

### Implementation for User Story 3

- [x] T009 [US3] Verify and polish empty state in `frontend/components/workspace/dag-viewer-dialog.tsx`:
  - Confirm empty state renders when backend returns `nodes: []` (not just null/undefined)
  - Style empty state: centered text with muted icon, match existing app empty-state patterns
- [x] T010 [US3] Verify and polish error state in `frontend/components/workspace/dag-viewer-dialog.tsx`:
  - Confirm error state renders on network failure or non-2xx response
  - Retry button re-calls fetch (reset error state first)
  - Error message uses `t("dagViewer.error")` not raw text
- [x] T011 [US3] Verify read-only DAG interaction constraints in `frontend/components/workspace/dag-viewer-dialog.tsx`:
  - Confirm nodes cannot be dragged (`nodesDraggable={false}`)
  - Confirm edges cannot be created by drag-connect (`nodesConnectable={false}`)
  - Confirm Delete/Backspace keys do nothing (no `deleteKeyCode` prop)
  - Confirm zoom (scroll wheel) and pan (canvas drag) work correctly at 10+ node scale
  - Confirm `fitView` runs on initial load (DAG centered in viewport)

**Checkpoint**: DAG 查看弹框在所有边界条件下都可用 — 空/错误/大图三种场景均验证通过

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Tests, i18n parity, and browser verification gate

### Backend tests

- [x] T012 Implement backend tests for `readPublishedDag` in `backend/dataweave-api/src/test/java/com/dataweave/api/WorkflowPublishedDagTest.java`:
  - **Happy path**: ONLINE workflow with published version → returns `DagView` with correct nodes/edges from snapshot
  - **DRAFT workflow**: returns `BizException("workflow.not_online_or_unpublished")`
  - **ONLINE but null currentVersionNo**: returns `BizException("workflow.not_online_or_unpublished")`
  - **Empty DAG**: ONLINE workflow with 0 nodes in snapshot → returns `DagView` with empty nodes list
  - Use `@SpringBootTest` with H2 profile, insert test data via `JdbcTemplate`

### Frontend tests

- [x] T013 [P] Implement frontend component test in `frontend/components/workspace/__tests__/dag-viewer-dialog.test.tsx`:
  - Renders loading state on mount
  - Renders nodes and edges correctly from mock API response
  - Renders empty state when nodes array is empty
  - Renders error state with retry button on fetch failure
  - Calls `onOpenChange(false)` on close button click

### i18n

- [x] T014 [P] Run i18n key parity check: confirm `frontend/messages/zh-CN.json` and `frontend/messages/en-US.json` have identical key sets for all `dagViewer.*` keys

### Browser verification gate (per CLAUDE.md)

- [x] T015 Browser verification (Playwright E2E) in `tmp/`:
  - Navigate to Ops → 周期任务流 tab
  - Click "查看 DAG" on an ONLINE workflow row
  - Confirm dialog opens with DAG nodes visible (not blank)
  - Confirm console has no errors
  - Take screenshot → `tmp/dag-viewer-dialog.png`
  - Close dialog via Escape key → confirm dialog closed
  - Repeat for 手动任务流 tab

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup: i18n)
  │
  ▼
Phase 2 (Foundational: Backend endpoint + Shared nodes + DagViewerDialog)
  │
  ├──▶ Phase 3 (US1: Periodic panel wiring)
  │
  ├──▶ Phase 4 (US2: Manual panel wiring)
  │
  └──▶ Phase 5 (US3: Edge case polish)
            │
            ▼
      Phase 6 (Polish: Tests + Browser verify)
```

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Phase 2 — No dependencies on other stories
- **User Story 2 (P1)**: Can start after Phase 2 — No dependencies on US1 (different file)
- **User Story 3 (P2)**: Can start after Phase 2 — Enhances DagViewerDialog created in Phase 2

### Within Each Phase

| Phase | Sequential | Parallel |
|-------|-----------|----------|
| Phase 2 | T002 → T003 (controller → service) | T004 ∥ T005 (different files); T002/T003 ∥ T004/T005 (backend ∥ frontend) |
| Phase 3 | — | T007 standalone |
| Phase 4 | — | T008 standalone (parallel with T007) |
| Phase 5 | — | T009 ∥ T010 ∥ T011 (all modify same file, sequential recommended; order doesn't matter) |
| Phase 6 | — | T012 ∥ T013 ∥ T014; T015 last (depends on all features done) |

### Parallel Opportunities

```bash
# Phase 2: Backend developer + Frontend developer
Task T002: "Implement WorkflowService.readPublishedDag()"
# (then T003 after T002)
Task T004: "Extract TaskNode"    } same developer,
Task T005: "Extract VirtualNode"  } or parallel

# Phase 3 & 4 can run in parallel (different files):
Task T007: "Update periodic-workflows-panel.tsx"
Task T008: "Update manual-workflows-panel.tsx"

# Phase 6: Tests can run in parallel with different owners:
Task T012: "Backend tests"
Task T013: "Frontend tests"
Task T014: "i18n parity check"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (T001): i18n keys
2. Complete Phase 2 (T002–T006): Foundation — backend endpoint + DagViewerDialog
3. Complete Phase 3 (T007): Wire periodic panel button
4. **STOP and VALIDATE**: Test US1 independently — click button → dialog shows published DAG
5. Demo/deploy if ready

### Incremental Delivery

1. Phase 1 + 2 → Foundation ready (endpoint + dialog work, can't demo yet)
2. Add Phase 3 (US1) → Periodic workflow DAG view works → **MVP Demo**
3. Add Phase 4 (US2) → Manual workflow DAG view works → Full coverage
4. Add Phase 5 (US3) → Edge cases handled → Production ready
5. Phase 6 → Tests + browser verify → Ship

### Suggested MVP Scope

**Phases 1–3** (T001–T007) = MVP:
- Backend published-dag endpoint
- DagViewerDialog with read-only ReactFlow, zoom/pan, close
- Periodic workflow list "查看 DAG" button wired
- ~7 tasks, estimated 2–3 hours for a single developer

---

## Notes

- [P] tasks = different files, no dependencies — can run concurrently
- [US1]/[US2]/[US3] label maps task to user story for traceability
- Each user story is independently testable after its phase completes
- Commit after each task or logical group
- `pnpm typecheck` after each frontend task; `./mvnw -q -pl <module> compile` after each backend task
- Browser verification (T015) is mandatory per CLAUDE.md gate — no "pnpm build passing" short-circuit
