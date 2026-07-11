# Tasks: 监督席

**Input**: Design documents from `/specs/064-supervisor-desk/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Feature spec mandates tests (SC-002 100% reuse compliance). Test tasks included for critical paths.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Exact file paths in descriptions

---

## Phase 1: Setup (Schema Migration)

**Purpose**: Database schema changes — prerequisite for all backend work

- [x] T001 Bump schema version to 0.17.0, add `heal_by_type` and `heal_by_ref_id` columns + index to `incident` table in `backend/dataweave-api/src/main/resources/schema.sql`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entity and type updates that both frontend and backend depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T002 [P] Add `healByType` and `healByRefId` fields to `Incident.java` domain entity in `backend/dataweave-master/src/main/java/com/dataweave/master/domain/incident/Incident.java`
- [x] T003 [P] Update `IncidentCard` TypeScript interface with `healByType: string | null` and `healByRefId: string | null` in `frontend/lib/incident-api.ts`
- [x] T004 [P] Add i18n keys for signal stream namespace (`signalStream.*`) to `frontend/messages/zh-CN.json` (at least: title, empty, filter.allTypes, filter.allSeverities, summary.TASK_FAILED, type.*, severity.*)
- [x] T005 [P] Add matching i18n keys to `frontend/messages/en-US.json` for parity

**Checkpoint**: Foundation ready — user story implementation can now begin

---

## Phase 3: User Story 1 — 监督席主视图：信号总览与工单队列 (Priority: P1) 🎯 MVP

**Goal**: 运维人员打开监督席，看到"信号流"和"工单队列"两个 Tab，使用标准 `Tabs` 组件切换，信号流按时间倒序且支持类型/严重度筛选，工单队列展示活跃和已解决工单。

**Independent Test**: 打开 http://localhost:4000 → 左侧导航 "监督席" → 看到两个 Tab，默认信号流面板，切换到工单队列面板。

### Implementation for User Story 1

- [x] T006 [US1] Rewrite `IncidentsView` main component using `<Tabs>` + `<TabsList>` + `<TabsTrigger>` + `<TabsContent>` (underline style, `size="md"`) instead of hand-rolled `role="tablist"` in `frontend/components/workspace/views/incidents-view.tsx`
- [x] T007 [P] [US1] Create `SignalStreamPanel` component with `DwScroll` scroll container, type/severity `DropdownSelect` filters, `ViewRefreshControl` (15s auto), sorted HealthEvent list using `Card` container and `Badge` semantic variants in `frontend/components/workspace/views/incident/signal-stream-panel.tsx`
- [x] T008 [P] [US1] Refactor ticket queue section inside `IncidentsView` to use `DwScroll` wrapping active/resolved card lists, `Card` component for incident cards with `--card-spacing`, `Badge` semantic variants for severity/state, `LoadingState` for initial load in `frontend/components/workspace/views/incidents-view.tsx`
- [x] T009 [US1] Add `incidentOnly` query parameter support to `GET /api/events` endpoint — when `true`, JOIN `incident` table to filter only signals linked to non-CLOSED incidents in `backend/dataweave-alert/src/main/java/com/dataweave/alert/application/EventCenterService.java`
- [x] T010 [US1] Add vitest test for `IncidentsView` Tab switching and empty states in `frontend/components/workspace/views/incident/incidents-view.test.ts`

**Checkpoint**: 监督席主视图可用——信号流 Tab 和工单队列 Tab 均可独立展示数据

---

## Phase 4: User Story 2 — 工单时间线抽屉（DAG/日志同款风格） (Priority: P1)

**Goal**: 点击工单卡片"时间线"按钮，弹出与 DAG 弹窗同风格的 Dialog + 右侧面板，使用 `DetailPanelShell` + `DwScroll`，不再使用手写 `fixed right-0 w-80` div。

**Independent Test**: 在工单队列中点击任意工单的"时间线"按钮 → Dialog 弹出 → 右侧面板滑入，展示时间线条目列表，滚动条为 OverlayScrollbars 细条浮叠。

### Implementation for User Story 2

- [x] T011 [US2] Create `IncidentTimelineDialog` component: `Dialog` container + `DetailPanelShell` (title=工单标题, `scrollBody=true`, `hasData`/`loading`/`error` state handling). Timeline entries rendered as vertical list with kind icons, actor labels, timestamps. Reuses `fetchIncidentDetail` from existing API in `frontend/components/workspace/incident-timeline-dialog.tsx`
- [x] T012 [US2] Remove old `TimelineDrawer` (hand-rolled `fixed right-0 w-80` div) from `frontend/components/workspace/views/incident/actions.tsx`. Wire incident card "时间线" button to open new `IncidentTimelineDialog` in `frontend/components/workspace/views/incidents-view.tsx`
- [x] T013 [US2] Add vitest test for `IncidentTimelineDialog` — loading/empty/data/error states in `frontend/components/workspace/views/incident/actions.test.tsx`

**Checkpoint**: 时间线抽屉风格与 DAG/日志详情一致，旧手写 `TimelineDrawer` 已删除

---

## Phase 5: User Story 3 — 异常自动开单与自动愈合 (Priority: P2)

**Goal**: 信号签名改用原始 `failureReason`（精确指纹），开单时存储愈合条件映射（`heal_by_type`/`heal_by_ref_id`），愈合时按精确指纹匹配而非全量 sourceRefId 匹配。

**Independent Test**: 注入 TASK_FAILED 信号（failureReason=`EXIT_CODE_-1`, taskId=100）→ 验证工单签名=`T:100:EXIT_CODE_-1`, `heal_by_type`=`TASK_SUCCESS`, `heal_by_ref_id`=`100`；注入 TASK_SUCCESS 信号（taskId=100）→ 验证仅该工单愈合。

### Implementation for User Story 3

- [x] T014 [US3] Modify `IncidentSignalListener` — signature generation: replace `failureClass` normalization with raw `failureReason` string (e.g., `EXIT_CODE_-1`). Signature format unchanged: `T:<taskId>:<failureReason>` in `backend/dataweave-master/src/main/java/com/dataweave/master/application/incident/IncidentSignalListener.java`
- [x] T015 [US3] Modify `IncidentService.openOrAttach()` — on INSERT, populate `heal_by_type`/`heal_by_ref_id` columns based on signal type (TASK_FAILED → `TASK_SUCCESS`+taskId; TASK_TIMEOUT → `TASK_SUCCESS`+taskId; NODE_OFFLINE → `NODE_ONLINE`+nodeCode; SLA_BREACH → null). On ATTACH (UPDATE), preserve existing heal conditions in `backend/dataweave-master/src/main/java/com/dataweave/master/application/incident/IncidentService.java`
- [x] T016 [US3] Modify `IncidentService.healByTask()` — change WHERE clause from `source_kind='TASK' AND source_ref_id=?` to `heal_by_type=? AND heal_by_ref_id=? AND state IN ('OPEN','MITIGATING')` in `backend/dataweave-master/src/main/java/com/dataweave/master/application/incident/IncidentService.java`
- [x] T017 [US3] Modify `IncidentHealListener.onTaskSucceeded()` — pass `("TASK_SUCCESS", String.valueOf(taskId))` to updated `healByTask`. Modify `onWorkflowSucceeded()` similarly. No change to `IncidentSweeper.healNodesByHeartbeat()` (node healing already precise by nodeCode) in `backend/dataweave-master/src/main/java/com/dataweave/master/application/incident/IncidentHealListener.java`
- [x] T018 [US3] Add/update JUnit tests for signature generation (raw failureReason), heal condition storage, and precise heal matching in `backend/dataweave-master/src/test/java/com/dataweave/master/application/incident/`

**Checkpoint**: 自动开单使用精确指纹，自动愈合使用精确匹配

---

## Phase 6: User Story 5 — 原始信号详情展示 (Priority: P3)

**Goal**: 在信号流面板点击一条信号，展开该信号的完整 JSON 原始数据（contextJson），等宽字体格式化呈现。

**Independent Test**: 在信号流面板点击任意信号 → 面板展开显示格式化 JSON；空 contextJson 时显示"无原始上下文数据"。

### Implementation for User Story 5

- [x] T019 [US5] Add click-to-expand behavior in `SignalStreamPanel` — clicking a signal row reveals a `DetailPanelShell`-style inline panel (or reuses `IncidentTimelineDialog` pattern) showing formatted JSON (`font-mono text-xs whitespace-pre-wrap`). Empty contextJson shows localized empty message in `frontend/components/workspace/views/incident/signal-stream-panel.tsx`

**Checkpoint**: 信号详情 JSON 可展开查看

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: US4 设计规范合规验证 + i18n parity + typecheck + full test pass

- [x] T020 Verify US4 compliance — confirm zero instances of hand-rolled `overflow-auto`, `role="tablist"`, hardcoded `p-4`/`p-5`, custom colored spans, plain "加载中..." text across all changed files in `frontend/components/workspace/views/incident/` and `frontend/components/workspace/incident-timeline-dialog.tsx`. Run reuse-first checklist from `specs/037-shared-ui-kit/contracts/reuse-first-checklist.md`
- [x] T021 [P] Verify i18n parity — all `signalStream.*` keys exist in both `frontend/messages/zh-CN.json` and `frontend/messages/en-US.json` with identical key sets
- [x] T022 [P] Run `cd frontend && pnpm typecheck` — zero errors
- [x] T023 [P] Run `cd frontend && pnpm vitest run` — all tests green
- [x] T024 Run `cd backend && ./mvnw -pl dataweave-master,dataweave-alert test` — all JUnit tests green
- [x] T025 Run quickstart validation per `specs/064-supervisor-desk/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Phase 2 — No dependencies on other stories
- **US2 (Phase 4)**: Depends on Phase 2 — May share `IncidentsView` with US1 but independently testable
- **US3 (Phase 5)**: Depends on Phase 2 — Backend-only, no frontend dependencies
- **US5 (Phase 6)**: Depends on US1 (reuses `SignalStreamPanel`) — builds on US1's component
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — Independent
- **US2 (P2)**: Can start after Phase 2 — Independently testable (standalone Dialog), but wires into US1's `IncidentsView`. Recommend completing US1 first or implementing in parallel with coordination on the "时间线" button wiring
- **US3 (P2)**: Can start after Phase 2 — Backend-only, fully independent of frontend
- **US5 (P3)**: Depends on US1 (extends `SignalStreamPanel`)

### Within Each User Story

- Core component creation before integration wiring
- Backend: entity → service → listener
- Frontend: component file → wiring into parent → tests

### Parallel Opportunities

- **Phase 2**: T002, T003, T004, T005 all [P] — different files, can run in parallel
- **Phase 3**: T007 and T008 [P] — different files (signal-stream-panel.tsx vs incidents-view.tsx)
- **Phase 5**: T014, T015, T016, T017 touch different methods/files — review for conflicts before parallelizing
- **Phase 7**: T021, T022, T023 [P] — all different commands
- **Cross-phase**: US1 (Phase 3) and US3 (Phase 5) can run in parallel after Phase 2 — frontend vs backend, zero file conflicts

---

## Parallel Example: Phase 2 Foundational

```bash
# All foundational tasks touch different files:
Task: "Add healByType/healByRefId to Incident.java"
Task: "Update IncidentCard TypeScript interface in incident-api.ts"
Task: "Add signalStream i18n keys to zh-CN.json"
Task: "Add matching i18n keys to en-US.json"
```

## Parallel Example: Phase 3 US1 + Phase 5 US3

```bash
# Frontend developer:
Task: "Rewrite IncidentsView with Tabs component"
Task: "Create SignalStreamPanel component"

# Backend developer (simultaneously):
Task: "Modify IncidentSignalListener signature to raw failureReason"
Task: "Modify IncidentService for heal conditions + precise matching"
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: Setup (T001)
2. Complete Phase 2: Foundational (T002–T005)
3. Complete Phase 3: US1 (T006–T010)
4. **STOP and VALIDATE**: Open http://localhost:4000 → 监督席 → 两个 Tab 功能正常
5. Demo if ready — this is a usable improvement over current state

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1 → 监督席主视图可用 (MVP!)
3. Add US2 → 时间线抽屉风格统一
4. Add US3 → 自动开单/愈合精确化
5. Add US5 → 信号详情可展开
6. Polish → 设计规范合规 + 测试全绿

### Parallel Team Strategy

With multiple developers after Phase 2:
- Developer A: US1 (frontend — incidents-view.tsx + signal-stream-panel.tsx)
- Developer B: US2 (frontend — incident-timeline-dialog.tsx) — coordinate on button wiring with Dev A
- Developer C: US3 (backend — IncidentService/Listener modifications)
- US5 waits for US1 (same file extension)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story independently completable and testable
- `frontend typecheck` after every frontend edit (CLAUDE.md hard rule)
- `./mvnw -pl <module> compile` after every backend edit (CLAUDE.md hard rule)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
