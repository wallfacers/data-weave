# Tasks: 实例列表排序 + 操作按钮状态化

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Branch**: `056-instance-list-sort-actions`

## Phase 1: Setup

- [ ] T001 Verify dev environment — `docker compose --profile distributed ps` all healthy, `pnpm dev` frontend ready

## Phase 2: Backend Sort API (shared foundation for US1)

- [ ] T002 [P] Add `sortField`/`sortDir` fields to `OpsContracts.InstanceQuery` in `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsContracts.java`
- [ ] T003 [P] Add `sortField`/`sortDir` fields to `OpsContracts.WorkflowInstanceQuery` in `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsContracts.java`
- [ ] T004 [P] Add `sortField`/`sortDir` fields to `api.dto.InstanceQuery` record in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/dto/InstanceQuery.java`
- [ ] T005 Add `instanceOrderByClause(String tableAlias, String sortField, String sortDir)` whitelist method to `OpsService` in `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` — maps scheduledFireTime/bizDate/startedAt/finishedAt/durationMs → DB columns, NULLS LAST, fallback to `id DESC`
- [ ] T006 Wire `instanceOrderByClause` into `OpsService.queryInstances()` replacing hardcoded ORDER BY — default (sortField=null) keeps existing priority-tier sort
- [ ] T007 Wire `instanceOrderByClause` into `OpsService.queryWorkflowInstances()` replacing hardcoded ORDER BY — same default behavior
- [ ] T008 Accept `sort` query param in `OpsController.instances()` in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` — parse `field:dir`, pass to `InstanceQuery`
- [ ] T009 Accept `sort` query param in `OpsController.workflowInstances()` in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` — same parse pattern
- [ ] T010 Pass sort fields through `DataOpsBridgeRealImpl.queryInstances()` and `queryWorkflowInstances()` in `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeRealImpl.java`
- [ ] T011 Verify backend sort — `./mvnw -pl dataweave-master -am compile` zero errors, curl test `sort=scheduledFireTime:desc` returns correct order

## Phase 3: Frontend Sort UI (User Story 1)

- [ ] T012 [US1] Add `sortable: true` + `sortKey` to scheduledFireTime/bizDate/startedAt/finishedAt/durationMs columns in `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`
- [ ] T013 [US1] Add `sortable: true` + `sortKey` to same columns in `frontend/components/workspace/views/ops/periodic-instances-panel.tsx`
- [ ] T014 [US1] Set default sort `{ field: "scheduledFireTime", dir: "desc" }` in both panels' fetcher or DataTable initial state — `frontend/lib/data-table.ts` DataTable `initialSort` prop
- [ ] T015 [US1] Read sort from URL searchParams on mount (persistence across navigation), update URL on sort change — in both panels
- [ ] T016 [US1] Verify frontend sort — `pnpm typecheck` clean, browser: Workflow Instances tab defaults to scheduledFireTime DESC, click column header toggles ▲/▼, data reorders

## Phase 4: Frontend Action Buttons (User Story 2)

- [ ] T017 [US2] Create `lib/instance-actions.ts` with `isActionEnabled(state: string, action: 'rerun' | 'recover' | 'stop'): boolean` pure function implementing the state matrix in `frontend/lib/instance-actions.ts`
- [ ] T018 [US2] Wire action buttons in `workflow-instances-panel.tsx` — each button's `disabled` prop calls `isActionEnabled(row.state, action)`, `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`
- [ ] T019 [US2] Wire action buttons in `periodic-instances-panel.tsx` — same `dispayed` logic for row-level buttons, `frontend/components/workspace/views/ops/periodic-instances-panel.tsx`
- [ ] T020 [US2] Implement bulk action button state — check all selected rows via `selectedRows.every(r => isActionEnabled(r.state, action))`, disable + Tooltip if any fail, in both panels
- [ ] T021 [US2] Add Tooltip to disabled bulk buttons explaining why ("所选实例包含不可重跑的状态"), `frontend/messages/zh-CN.json` + `en-US.json`
- [ ] T022 [US2] Verify action buttons — browser: check RUNNING/SUCCESS/FAILED/STOPPED rows, verify correct button disabled/enabled states, test bulk select with mixed states

## Phase 5: Polish & Final Verification

- [ ] T023 Backend test — add JUnit tests for `instanceOrderByClause` whitelist (valid fields, invalid fallback, NULLS LAST) in `backend/dataweave-master/src/test/java/`
- [ ] T024 Frontend unit test — test `isActionEnabled` function all 9 states × 3 actions in `frontend/lib/__tests__/instance-actions.test.ts`
- [ ] T025 E2E smoke — `./mvnw -pl dataweave-master -am test` green, `pnpm typecheck` green, browser verify full flow

## Dependencies

```
Phase 1 (Setup)
    │
Phase 2 (Backend Sort API)
    │
    ├── Phase 3 (Frontend Sort UI) [US1]  ── ✓ independently testable: sort by any column
    │
    └── Phase 4 (Frontend Action Buttons) [US2]  ── ✓ independently testable: button states per row
                                            │
                                    Phase 5 (Polish)
```

- US1 and US2 are **independent** — can be implemented in parallel after Phase 2
- T002-T004 are parallelizable (different files)
- T012-T013 are parallelizable (different files)
- T017 is a prerequisite for T018-T020

## Parallel Execution

```bash
# After Phase 2 backend is done, run US1 and US2 in parallel:
Agent A: T012-T016 (Frontend Sort UI)
Agent B: T017-T022 (Frontend Action Buttons)
```

## Implementation Strategy

**MVP (US1 only)**: Phase 2 + Phase 3 → delivers sortable instance lists with default scheduledFireTime DESC. Skippable: US2 action button states.

**Full**: All phases → sort + action buttons complete.
