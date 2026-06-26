# Tasks: DAG 节点详情侧面板

**Input**: Design documents from `/specs/004-dag-node-detail-panel/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: Backend endpoint test included (new API must have coverage). Frontend tests optional — browser verification gate replaces E2E.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Frontend**: `frontend/components/workspace/`, `frontend/lib/workspace/`, `frontend/messages/`
- **Backend**: `backend/dataweave-master/src/main/java/com/dataweave/master/`, `backend/dataweave-api/src/main/java/com/dataweave/api/`
- Based on plan.md project structure

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: Backend API + frontend infrastructure that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Backend — DagNodeDto 扩展 + 节点详情 API

- [x] T001 [P] Extend `DagNodeDto` with `Integer taskVersionNo` field and update constructor in `backend/dataweave-master/src/main/java/com/dataweave/master/application/WorkflowService.java`
- [x] T002 [P] Create `NodeTaskDetail` record (nodeKey, taskId, taskName, taskType, versionNo, content, paramsJson, datasourceId, targetDatasourceId, timeoutSec, retryMax, publishedAt, hasCode, deleted) in `backend/dataweave-master/src/main/java/com/dataweave/master/application/WorkflowService.java`
- [x] T003 Implement `getNodeDetail(Long workflowId, String nodeKey)` method: read published snapshot → find node → query `TaskDefVersionRepository.findByTaskIdAndVersionNo()` → map to `NodeTaskDetail`, in `backend/dataweave-master/src/main/java/com/dataweave/master/application/WorkflowService.java` (depends on T001, T002)
- [x] T004 Add `GET /api/ops/workflows/{workflowId}/nodes/{nodeKey}/detail` endpoint returning `ApiResponse<NodeTaskDetail>`, with 400 for VIRTUAL nodes and 404 for missing snapshot/node, in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` (depends on T003)
- [x] T005 [P] Add i18n keys (`node_detail.virtual_no_task`, `node_detail.task_deleted`, `node_detail.no_code`, `node_detail.fetch_error`) to `backend/dataweave-master/src/main/resources/messages.properties` and `messages_en_US.properties`

### Frontend — DagRenderer 修复 + 状态管理

- [x] T006 [P] Fix `onNodeClick` type from `() => void` to `(event: React.MouseEvent, node: Node) => void` and allow it to fire in readOnly mode (remove `readOnly ? undefined :` guard) in `frontend/components/workspace/dag-renderer.tsx`
- [x] T007 [P] Create `NodeDetailStore` (zustand) with `selectedNode`, `detail`, `loadState`, `errorMessage` fields and `selectNode`, `deselectNode`, `setDetail`, `setError` actions in `frontend/lib/workspace/node-detail-store.ts`
- [x] T008 [P] Add `ops.nodeDetail.*` i18n keys (title, taskName, taskType, versionNo, code, params, noCode, taskDeleted, fetchError, retry, close) to `frontend/messages/zh-CN.json` and `frontend/messages/en-US.json`

**Checkpoint**: Foundation ready — DagNodeDto has taskVersionNo, node detail API works, DagRenderer accepts clicks in readOnly mode, zustand store initialized

---

## Phase 2: User Story 1 - 点击节点查看任务配置信息 (Priority: P1) 🎯 MVP

**Goal**: 运维人员点击 DAG 弹框中的任务节点 → 右侧滑出 1/4 宽度面板，展示任务配置（名称、类型、版本号、代码高亮、配置参数）

**Independent Test**: 打开 DAG 弹框 → 点击 TASK 节点 → 右侧面板展示配置。切换节点 → 面板内容更新。点击空白 → 面板关闭。

### Implementation for User Story 1

- [x] T009 [US1] Create `NodeDetailPanel` component shell (collapsible panel with header + body + close button, slide-in animation, 1/4 default width) in `frontend/components/workspace/node-detail-panel.tsx`
- [x] T010 [US1] Integrate `NodeDetailPanel` into `DagViewerDialog` layout: change body to `flex flex-row`, left side = `DagRenderer` (flex-1), right side = `NodeDetailPanel` (conditional), wire `onNodeClick` to `NodeDetailStore.selectNode()` in `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T011 [US1] Implement `NodeDetailPanel` content sections: basic info (taskName, taskType, versionNo, publishedAt), code block with Shiki `highlightCode()`, params table (key-value from paramsJson), using `DwScroll` for overflow in `frontend/components/workspace/node-detail-panel.tsx`
- [x] T012 [US1] Implement panel state rendering: loading spinner → loaded content → error with retry button → "task deleted" fallback. Wire `authFetch` to `GET /api/ops/workflows/{id}/nodes/{key}/detail` in `NodeDetailStore` in `frontend/lib/workspace/node-detail-store.ts`
- [x] T013 [US1] Implement Escape key handling: when panel open → close panel (not dialog); when panel closed → close dialog. Add click-on-pane (blank area) → deselectNode. In `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T014 [US1] Add `@SpringBootTest` for `GET /api/ops/workflows/{id}/nodes/{key}/detail`: test TASK node returns detail, VIRTUAL node returns 400, missing node returns 404, deleted task returns degraded response in `backend/dataweave-api/src/test/java/com/dataweave/api/NodeDetailEndpointTest.java`

**Checkpoint**: User Story 1 fully functional — click node → see configuration, switch nodes, close panel with Escape or blank click

---

## Phase 3: User Story 2 - 右击节点弹出快捷操作菜单 (Priority: P2)

**Goal**: 运维人员右击 TASK 节点 → 弹出上下文菜单 → "查看任务详情" → 打开右侧面板

**Independent Test**: 右击 TASK 节点 → 菜单出现 → 点击"查看任务详情" → 面板展示。右击 VIRTUAL 节点 → 菜单不含任务选项。

### Implementation for User Story 2

- [x] T015 [US2] Add `onNodeContextMenu` prop to `DagRenderer` (type: `(event: React.MouseEvent, node: Node) => void`) and pass it through to `<ReactFlow>` in both readOnly and edit modes in `frontend/components/workspace/dag-renderer.tsx`
- [x] T016 [US2] Implement right-click context menu in `DagViewerDialog`: on `onNodeContextMenu` → show `ContextMenu` at mouse position with "查看任务详情" item → onClick calls `NodeDetailStore.selectNode()`. Use floating menu in `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T017 [US2] Handle VIRTUAL node exclusion: check `node.type === "virtual"` in context menu handler → no menu for VIRTUAL nodes in `frontend/components/workspace/dag-viewer-dialog.tsx`

**Checkpoint**: User Story 2 fully functional — right-click shows contextual menu, triggers same panel as click

---

## Phase 4: User Story 3 - 面板交互与尺寸适配 (Priority: P3)

**Goal**: 面板宽度可拖拽调整（1/5~1/3，min 280px），持久化到 localStorage，小屏幕自适应

**Independent Test**: 拖拽分割线 → 面板宽度变化 → 刷新页面 → 宽度保持。长代码滚动 → 面板等高。关闭按钮 → 面板关闭。

### Implementation for User Story 3

- [x] T018 [US3] Implement resizable divider between DAG and panel using motion `useMotionValue` + `useTransform` pointer events pattern (reuse from `agent-rail.tsx`): `onPointerDown` captures startX, `pointermove` clamps to [MIN, MAX], `pointerup` finalizes in `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T019 [US3] Persist panel width to `localStorage` key `dw.dagViewer.panelWidth` on drag release; restore on panel open. Default = `dialogWidth / 4`, min = 280px, max = `dialogWidth / 3` in `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T020 [US3] Handle small dialog width (<900px): when container < 900px, panel opens at fixed 320px (no resize handle). PANEL_MIN_WIDTH=280 provides baseline; panel never exceeds dialog * 1/3 in `frontend/components/workspace/dag-viewer-dialog.tsx`
- [x] T021 [US3] Add panel close button (X icon, top-right) + click-on-blank-area handler already done in T013 — verify both work together in `frontend/components/workspace/node-detail-panel.tsx`
- [x] T022 [US3] Ensure long content (500+ lines of code) scrolls within panel using `DwScroll direction="vertical"`, panel height matches dialog height in `frontend/components/workspace/node-detail-panel.tsx`

**Checkpoint**: All user stories independently functional — resize works, persists, adapts to screen size

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Edge cases, verification, final quality gate

- [x] T023 [P] Implement rapid-click debounce in `NodeDetailStore`: if `selectNode()` called while `loadState === "loading"`, abort previous fetch (AbortController) and start new one — panel always shows last-clicked node in `frontend/lib/workspace/node-detail-store.ts`
- [x] T024 [P] Handle missing fields gracefully in `NodeDetailPanel`: null content → "该任务类型无执行代码" label; null paramsJson → "无配置参数" placeholder; empty string content → same as null in `frontend/components/workspace/node-detail-panel.tsx`
- [x] T025 Run `pnpm typecheck` (frontend) + `./mvnw -pl dataweave-api,dataweave-master compile` (backend) — zero errors required in both `frontend/` and `backend/`
- [x] T026 Run quickstart.md browser verification: open DAG dialog → click node → panel shows config → right-click → context menu → resize → close → all no console errors in `specs/004-dag-node-detail-panel/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: No dependencies — start immediately. BLOCKS all user stories.
- **User Story 1 (Phase 2)**: Depends on Phase 1 completion. 🎯 MVP
- **User Story 2 (Phase 3)**: Depends on Phase 1 + US1 (shares panel + store). Can partially overlap if US1 panel shell is done.
- **User Story 3 (Phase 4)**: Depends on Phase 1 + US1 (needs panel to exist). Independent of US2.
- **Polish (Phase 5)**: Depends on all desired user stories complete.

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Phase 1 — No dependencies on other stories
- **User Story 2 (P2)**: Can start after Phase 1 + US1 panel exists — Independent test possible
- **User Story 3 (P3)**: Can start after Phase 1 + US1 panel exists — Independent test possible

### Within Each User Story

- Backend: record → service method → controller endpoint → test
- Frontend: store → component shell → integration → state handling → edge cases
- Core implementation before polish

### Parallel Opportunities

- T001, T002, T005 can run in parallel (backend — different parts of same file for T001/T002, but T005 is different file)
- T006, T007, T008 can run in parallel (frontend — different files)
- After Phase 1: US1, US2, US3 can be worked on sequentially (single developer) or US2+US3 in parallel after US1 panel shell
- T023, T024 can run in parallel (different concerns, same file for T024 but independent from T023)

---

## Parallel Example: Foundational Phase

```bash
# Backend parallel group (different concerns):
Task: "T001 [P] Extend DagNodeDto with taskVersionNo in WorkflowService.java"
Task: "T002 [P] Create NodeTaskDetail record in WorkflowService.java"
Task: "T005 [P] Add i18n keys to messages.properties"

# Frontend parallel group (different files):
Task: "T006 [P] Fix onNodeClick type in dag-renderer.tsx"
Task: "T007 [P] Create NodeDetailStore in node-detail-store.ts"
Task: "T008 [P] Add i18n keys to zh-CN.json and en-US.json"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Foundational (T001–T008)
2. Complete Phase 2: User Story 1 (T009–T014)
3. **STOP and VALIDATE**: Test click-node-to-see-config independently
4. Deploy/demo if ready — this alone delivers core value

### Incremental Delivery

1. Complete Foundational → API ready, store initialized, renderer fixed
2. Add User Story 1 → Click node → config panel → **MVP!**
3. Add User Story 2 → Right-click menu → incremental value
4. Add User Story 3 → Resize + persistence → polished UX
5. Polish → Edge cases + verification
6. Each story adds value without breaking previous stories

### Single Developer Strategy

Recommended order: Phase 1 → Phase 2 (US1) → Phase 3 (US2) → Phase 4 (US3) → Phase 5 (Polish)

US2 and US3 can be swapped in order. US2 is simpler (3 tasks) and adds visible interaction; US3 is more mechanical (5 tasks) and enhances comfort.

---

## Notes

- [P] tasks = different files or independent concerns, no data dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group (e.g., T001–T005 = "backend node detail API")
- Stop at any checkpoint to validate story independently
- Browser verification (T026) is a hard gate — must pass before merge
- Backend: `./dev-install.sh` after backend changes; frontend: `pnpm typecheck` after each edit
