# Tasks: 周期/手动任务流列表字段增强

**Input**: Design documents from `specs/039-workflow-list-fields/`（spec.md / plan.md / research.md / data-model.md / contracts/list-api.md / quickstart.md）

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: 项目硬规则 "no test = not done"（CLAUDE.md），故包含必要测试任务（后端 WebTestClient + 前端 vitest），非严格 TDD 顺序。

**Organization**: 按 user story 组织（US1 P1 / US2 P2 / US4 P2 依赖 US2 / US3 P3），每 story 独立可测。

## Format: `[ID] [P?] [Story?] 描述（含文件路径）`

- **[P]**: 可与同 phase 兄弟任务并行（不同文件、无未完成依赖）
- **[Story]**: US1/US2/US3/US4（Setup/Foundational/Polish 不标）
- 所有路径为仓库相对路径；接缝与决策编号引用 research.md (D1–D6) / data-model.md

---

## Phase 1: Setup

**Purpose**: 隔离工作区 + 确认前后端基线可编译（CLAUDE.md 并行隔离硬规则）

- [ ] T001 建 feature worktree + 分支并确认基线：`git worktree add ../dw-039-workflow-list-fields -b feat/039-workflow-list-fields`；`cd backend && ./dev-install.sh`（装 master 到本地 m2）；`cd frontend && pnpm install && pnpm typecheck`（零错误基线）

---

## Phase 2: Foundational（阻塞 US1/US4 的公共底座）

**Purpose**: 被多个 story 依赖的契约/类型底座，MUST 先于 US1/US4 完成

**⚠️ CRITICAL**: US1（后端投影）依赖 T002；US4（查询/排序）依赖 T002+T003

- [x] T002 [P] 后端契约底座：`backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsContracts.java` — `WorkflowListRow` record 增 `String nextTriggerTime`（ISO，null 透传）；`WorkflowQuery` record 增 `String priorityTier`("high"|"normal"|null) / `String sortField`(白名单 "priority"|null) / `String sortDir`("asc"|"desc")。编译：`./mvnw -q -pl dataweave-master -am compile`（data-model.md §2）
- [x] T003 [P] 前端 DataTable 排序公共类型扩展：`frontend/lib/data-table.ts` — `ColumnDef<T>` 增 `sortable?: boolean`（缺省 false，向后兼容）；`FetchQuery` 增 `sort?: { field: string; dir: "asc"|"desc" }`；`toQueryParams` 增 `sort=field:dir` 序列化。同步 vitest `frontend/lib/data-table.test.ts` 断言 sort 序列化。`pnpm typecheck && pnpm vitest run data-table`（research.md D4）

**Checkpoint**: 后端 record + 前端公共类型就绪，编译/单测通过。

---

## Phase 3: User Story 1 — 周期「下次触发时间」 (Priority: P1) 🎯 MVP

**Goal**: 周期任务流卡片出现「下次触发时间」列，相对时间展示（spec US1 / FR-001/004/008）

**Independent Test**: 周期卡片多出「下次触发时间」列——未来约 3 小时显示"3 小时后"；未回填/手动流显示 `—`。

- [x] T004 [P] [US1] 后端投影：`backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java#queryWorkflows` — SELECT 增 `wd.next_trigger_time`；RowMapper 增 `nextTriggerTime`（`LocalDateTime`→`toString`，null 透传）。编译。（依赖 T002；data-model.md §3）
- [x] T005 [P] [US1] 前端相对时间 util：新建 `frontend/lib/relative-time.ts` — 纯函数 `relativeNextTrigger(iso: string|null, now: Date): { key: string; values: Record<string,number> } | null`，三态映射（临近→relSoon / 未来→relInMinutes·relInHours·relInDays / 已过期→relExpiredMinutes·relExpiredHours）；新建 vitest `frontend/lib/relative-time.test.ts` 覆盖三态 + 单位选择 + null。（research.md D5）
- [x] T006 [P] [US1] i18n：`frontend/messages/zh-CN.json` 与 `en-US.json`（`ops` 命名空间）— 加列头 `colNextTriggerTime` + 相对时间 keys（`relSoon`/`relInMinutes`/`relInHours`/`relInDays`/`relExpiredMinutes`/`relExpiredHours`，ICU `{n}`），两 bundle key 集一致。
- [x] T007 [US1] 周期 panel 接线：`frontend/components/workspace/views/ops/periodic-workflows-panel.tsx` — `WorkflowRow` 增 `nextTriggerTime: string|null`；加「下次触发时间」列（`relativeNextTrigger` + `t()` 渲染，null→`—`）。`pnpm typecheck`。（依赖 T004/T005/T006）

**Checkpoint**: US1 独立可验（浏览器：周期卡片下次触发列，相对时间三态）。

---

## Phase 4: User Story 2 — 两卡片「优先级」展示 (Priority: P2)

**Goal**: 周期/手动两卡片出现「优先级」列，priority 0–2 高优徽标（spec US2 / FR-002/009）

**Independent Test**: 两表「优先级」列——priority=1 显示"1 + 橙色高优徽标"，priority=3 显示纯数字，null 显示 `—`。

- [x] T008 [P] [US2] i18n：`frontend/messages/{zh-CN,en-US}.json`（`ops`）— 加列头 `colPriority` + 高优徽标 `priorityHigh`（"高优"/"High"），两 bundle 一致。
- [x] T009 [US2] 两 panel 优先级列：`frontend/components/workspace/views/ops/periodic-workflows-panel.tsx` + `manual-workflows-panel.tsx` — 加「优先级」列：纯数字，`priority!=null && priority<=2` 时附橙色高优徽标（shadcn 语义 token amber/warning，不手写 `dark:`）；null→`—`。`priority` 已在 DTO，纯前端。`pnpm typecheck`。（依赖 T008）

**Checkpoint**: US1 + US2 均独立可验。

---

## Phase 5: User Story 4 — 优先级筛选 + 列排序 (Priority: P2，依赖 US2 + Foundational)

**Goal**: 两卡片可按"高优/普通"筛选 + 点「优先级」列头 server 端排序（spec US4 / FR-010/011）

**Independent Test**: 选「高优」仅显 priority 0–2（URL 含 `priorityTier=high`）；点列头按 priority 重排（`sort=priority:desc`），NULL 行置末。

- [x] T010 [US4] 后端查询扩展：`backend/.../OpsService.java#queryWorkflows` — WHERE 增 priorityTier（`high`→`AND wd.priority BETWEEN 0 AND 2`；`normal`→`BETWEEN 3 AND 9`）；ORDER BY 由写死 `wd.id` 改动态：`sortField=priority` 时 `ORDER BY wd.priority <dir> NULLS LAST, wd.id`，否则默认；sortField 白名单仅 "priority"（防注入）。编译。（依赖 T002；research.md D2）
- [x] T011 [US4] 后端端点参数：`backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` — `/periodic-workflows` 与 `/manual-workflows` 两端点增 `@RequestParam(required=false) String priorityTier, String sort`，解析 `sort=field:dir` → `sortField`/`sortDir` 传入 `WorkflowQuery`。编译。（依赖 T002/T010；contracts/list-api.md）
- [x] T012 [P] [US4] 前端 DataTable 表头排序 UI：`frontend/components/ui/data-table*.tsx` — `sortable` 列表头渲染可点击区域 + hugeicons 方向图标（升/降/未排序三态，点击 asc→desc→清除），点击更新 `FetchQuery.sort` 触发 server refetch。（依赖 T003）
- [x] T013 [US4] 两 panel 筛选器 + sortable 接线：`periodic-workflows-panel.tsx` + `manual-workflows-panel.tsx` — 增「优先级」`segmented` FilterDef（options high/normal，空=全部）；「优先级」列标 `sortable: true`；fetcher 透传 `sort`。（依赖 T003/T009/T011/T012）
- [x] T014 [P] [US4] 后端契约测试：新建 `backend/.../OpsControllerWorkflowListTest.java`（或既有 ops 测试类）— WebTestClient 带 JWT（MEMORY `backend-fullstack-http-test-jwt`）、独立 H2 库名（MEMORY `h2-shared-mem-db-test-pollution`），断言：`priorityTier=high` 仅返 0–2、`sort=priority:desc` 排序正确且 NULL `NULLS LAST`、items 含 `nextTriggerTime`。

**Checkpoint**: US1/US2/US4 均独立可验。

---

## Phase 6: User Story 3 — 名称下方描述副标题 (Priority: P3)

**Goal**: 两卡片「任务流名称」下方显示描述副标题，空描述不预留空行（spec US3 / FR-007）

**Independent Test**: 有描述的流名称下方一行描述（超长截断 + tooltip）；无描述仅名称、无空行。

- [x] T015 [P] [US3] 两 panel 描述副标题：`frontend/components/workspace/views/ops/periodic-workflows-panel.tsx` + `manual-workflows-panel.tsx` — 「任务流名称」列 cell 改为名称 + 下方 `description` 副标题（`truncate` + `title` tooltip，复用 name 列既有模式；`description` 空时不渲染副标题、不占行高）。`description` 已在 DTO，纯前端。`pnpm typecheck`。

**Checkpoint**: 全部 US（US1/US2/US3/US4）独立可验。

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: 列宽校准 + 全量验证 + 双方言

- [x] T016 列宽重分配：`periodic-workflows-panel.tsx`（9 列：名称20/下次触发14/Cron12/最近触发10/状态8/上次运行14/版本8/优先级8/操作6）+ `manual-workflows-panel.tsx`（7 列：名称24/最近触发12/状态8/上次运行18/版本8/优先级10/操作20），所有 `widthPct` 和=100，无横向滚动（research.md D6；依赖 T007/T009/T013/T015 列均加完）
- [ ] T017 全量验证：`cd backend && ./mvnw compile`（零错误）；`cd frontend && pnpm typecheck`（零错误）+ `pnpm vitest run`（relative-time / data-table 全绿）；浏览器走 `quickstart.md` V1–V6；i18n 两 bundle key 集一致（CI 检，无 console 缺 key）
- [ ] T018 [P] H2/PG 双方言验证：后端 SQL 改动（`BETWEEN`/`NULLS LAST`/`LIMIT OFFSET`/CONCAT）在 H2（`profiles=h2`）与 PostgreSQL（docker compose）各跑一遍契约测试，确认无方言 regression（MEMORY `h2-pg-sql-dialect-traps`）

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (P1)**: 无依赖，立即开始
- **Foundational (P2)**: 依赖 Setup；**阻塞 US1(T002) 与 US4(T002/T003)**
- **US1 (P3)**: 依赖 T002；不依赖其他 story → MVP
- **US2 (P4)**: 依赖 T008(自身 i18n)；不依赖 Foundational/US1 → 可与 US1 并行
- **US4 (P5)**: 依赖 T002/T003 + **US2(T009 优先级列先在)** + T010/T011/T012
- **US3 (P6)**: 无跨 story 依赖 → 可与 US1/US2/US4 并行
- **Polish (P7)**: 依赖所有列加完（T016）；T017 全量验证依赖 T016

### User Story Dependencies
- US1(P1): Foundational.T002 后即可，独立 → **MVP**
- US2(P2): 独立（priority DTO 已有），可与 US1 并行
- US4(P2): **依赖 US2**（先有优先级列）+ Foundational(T002/T003)
- US3(P3): 完全独立，任意时机可插

### Within Each Story
- 后端 record/SQL → 端点 → 前端 util/类型 → panel 接线 → 测试
- 每 task 后跑对应编译/typecheck（CLAUDE.md Post-Edit Verification）

### Parallel Opportunities
- **Foundational**: T002(后端) ∥ T003(前端) — 不同端不同文件
- **US1**: T004(后端 SQL) ∥ T005(前端 util) ∥ T006(i18n) — 三者不同文件；T007 接线依赖三者
- **US4**: T012(前端 DataTable UI) ∥ T014(后端测试) 与 T010/T011/T013 链并行
- **跨 story**: US3(T015) 可与 US1/US2/US4 任一并行（独立纯前端）
- 不同 story 可由不同 agent 在各自 worktree 并行（见下方策略）

---

## Parallel Example: User Story 1

```bash
# 三个独立文件任务可同时派发：
Task: "T004 后端投影 next_trigger_time in OpsService.java"
Task: "T005 前端 relative-time util + test in lib/relative-time.ts"
Task: "T006 i18n keys in messages/{zh-CN,en-US}.json"
# 三者完成后串行接线：
Task: "T007 周期 panel 加下次触发列 in periodic-workflows-panel.tsx"
```

---

## Implementation Strategy

### MVP First（仅 US1）
1. Phase 1 Setup → Phase 2 Foundational(T002) → Phase 3 US1(T004–T007)
2. **STOP 验证**：周期卡片独立可看「下次触发时间」（相对时间三态）
3. 已交付核心运维价值，可先合并/demo

### Incremental Delivery
1. Setup + Foundational → 底座就绪
2. +US1 → 独立验 → MVP（周期下次触发）
3. +US2 → 独立验（两表优先级 + 高优徽标）
4. +US4 → 独立验（筛选 + 排序，依赖 US2）
5. +US3 → 独立验（描述副标题）
6. Polish → 列宽 + 全量验证 + 双方言

### Parallel Team Strategy（多 agent / worktree）
- 各 US 独立 worktree；US1/US2/US3 可三人并行；US4 等 US2 合并后启动
- 合并时先合兄弟 landed 工作（尤 038 同改 OpsService/DataTable），重跑 shared-surface 测试（OpsService 投影 / data-table sort / 两 panel）

---

## Notes
- [P] = 不同文件、无未完成依赖
- [Story] 标签映射 spec.md 的 US1–US4 便于追溯
- 每个 US 独立可完可测；commit 粒度 = 每 task 或逻辑组
- 任何 checkpoint 可停下独立验证某 story
- 避免：模糊任务、同文件并发冲突、破坏 story 独立性的跨 story 依赖（US4 依赖 US2 已显式标注）
- 共享面风险：`OpsService.queryWorkflows`（US1 T004 + US4 T010 都改）与 `unified-data-table`（US4 T003/T012）—— 顺序执行勿并行同方法
