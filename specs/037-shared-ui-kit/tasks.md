# Tasks: 复用优先的公共前端组件契约与目录

**Input**: Design documents from `/specs/037-shared-ui-kit/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 本特性以文档/编目为主，代码改动集中在少数收敛/迁移点。测试门 = `pnpm typecheck` + `pnpm design:lint` 常绿 + 迁移组件 vitest + 明暗主题浏览器抽查（宪法"新功能必带测试"落在有代码改动处）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未决依赖）
- **[Story]**: US1–US4（映射 spec 用户故事）；Setup/Foundational/Polish 无 Story 标签
- ⚠️ 同一文件（尤其 `frontend/DESIGN.md`、`adoption-inventory.md`）的多个任务**不可并行**，按序执行

## Path Conventions

单一前端项目：`frontend/`。无 backend 改动。目录真相源 = `frontend/DESIGN.md`。

---

## Phase 1: Setup（共享前置）

**Purpose**: 锚定基线与真相源位置，确保编辑前门禁常绿

- [ ] T001 [P] 核对 037 基线：`git log --oneline main | grep -Ei '033|表格边框|035|loading|加载'` 确认 033（DataTable 边框包裹）与 035（LoadingState 居中转圈）已落 main；把结论回填 `specs/037-shared-ui-kit/adoption-inventory.md` 的就绪度表
- [ ] T002 前置门禁基线：`cd frontend && pnpm typecheck && pnpm design:lint` 均绿；打开 `frontend/DESIGN.md` 确认新章节插入点 = `## 用法约束`（现 L103–108）之后、`## 统一标签条 —— TabStrip` 之前（遵守 Design Contract Gate：先读 DESIGN.md 约束）

**Checkpoint**: 基线确认，真相源落点锁定

---

## Phase 2: Foundational（阻塞性前置 —— 跨特性依赖）

**Purpose**: 消解首要风险——下划线 Tabs 组件的跨特性依赖；不闭则 US2 的 Tabs 收敛无处落地

**⚠️ CRITICAL**: T003 阻塞 US2 中所有下划线 Tabs 相关任务（T012/T013/T014）

- [ ] T003 取用 030 下划线 Tabs 组件（处置已定死，见 research R3）：执行 `git checkout 030-unify-tab-styles -- frontend/components/ui/tabs.tsx` **仅文件级**取入组件（**不**整提交 cherry-pick `d094f67`——其 5 视图迁移对当前 main 已陈旧、会冲突）；`cd frontend && pnpm typecheck` 验证组件与当前 main 兼容。**Fallback**：若不兼容，按 `frontend-tab-style-rule`（closable 驱动双风格）在 037 内新建等价下划线组件。无论哪条，FR-006 全程 in-scope，产出必是"037 基线存在可复用下划线 `Tabs` 组件"，决策记入 `adoption-inventory.md`

**Checkpoint**: 037 基线存在可复用的下划线 `Tabs` 组件（取自 030 或新建），T012–T014 可执行

---

## Phase 3: User Story 1 - 复用优先目录 + 约定 (P1) 🎯 MVP

**Goal**: `frontend/DESIGN.md` 出现单一权威「公共组件目录」章节 + 复用优先约定，agent 能"先查此处"；卡片内边距 token 固化为真相源

**Independent Test**: 打开 DESIGN.md 目录章节，确认①复用优先约定（先查→命中复用→未命中新建回填→改组件即更新目录）成文；②`--card-spacing` 被声明为卡片内容内边距唯一 token（24/16，禁页面魔法值）；③`pnpm design:lint` 绿

- [ ] T004 [US1] 在 `frontend/DESIGN.md` 于 T002 锁定的插入点新增 `## 公共组件目录（先查此处 · reuse-first）` 章节骨架，写入**复用优先约定序言**（实现任何原语前先查目录；命中必复用、禁止手写同类；未命中方可新建并**回填目录**；改公共组件即更新对应条目防漂移）——落实 FR-001/FR-002
- [ ] T005 [US1] 在该章节增加**间距/token 小节**：声明卡片内容内边距 = `--card-spacing`（默认 `--spacing(6)`=24px、`data-[size=sm]`=`--spacing(4)`=16px，源 `frontend/components/ui/card.tsx`），引用 `app/globals.css`/Tailwind `--spacing()`，明确"页面禁止手填内边距魔法值（含 20px/p-5）"——落实 FR-010（决策见 research R2）
- [ ] T006 [US1] 在任务创作知识层补"复用优先"指引可发现性：于 `.claude/skills/weft-task-authoring/SKILL.md`（或其引用的前端创作说明）加一句"改前端 UI 前先读 `frontend/DESIGN.md` 的公共组件目录"，指向 `specs/037-shared-ui-kit/contracts/reuse-first-checklist.md`——落实 FR-002 可发现性
- [ ] T007 [US1] 门禁：`cd frontend && pnpm design:lint && pnpm typecheck` 均绿

**Checkpoint**: MVP 达成——目录与复用优先约定存在且可发现（SC-001 的前提就绪）

---

## Phase 4: User Story 2 - 9 类原语唯一规范条目 + 收敛 (P1)

**Goal**: 目录覆盖 9 类原语、各有唯一规范条目；分裂/散落原语收敛并示范迁移

**Independent Test**: 抽查 5 个用同类原语的页面外观 100% 一致（SC-002）；9 类原语在目录各有唯一条目且可复用（SC-003）

> ⚠️ T004 完成后进入；DESIGN.md 编辑任务（T008–T012）**同文件、按序**执行

- [ ] T008 [US2] 在目录表登记条目：滚动条→`DwScroll`（`components/ui/dw-scroll.tsx`）、卡片容器→`Card`（`--card-spacing`）——每条按 `contracts/catalog-entry.schema.md` 填齐（用途/何时不用禁写/关键props变体/路径/深章节链接）；滚动条链接既有 `## 滚动条` 深章节；卡片容器条目**并入 034 已落地的卡片栅格约定**（`gap-2.5`，面包屑已撤回不恢复），使 034 保留部分收口于此条目。FR-004/005/009（F5）
- [ ] T009 [US2] 在目录表登记条目：表格→`DataTable`+`DataTableToolbar`（`components/ui/data-table.tsx`，链接 `## 数据表格` 深章节，注明 033 边框包裹为规范）。FR-004/005/007
- [ ] T010 [US2] 在目录表登记条目：下拉→`DropdownSelect`（`components/ui/select.tsx`）、弹框→`Dialog`（`components/ui/dialog.tsx`），声明二者共用浮层规范（触发器/圆角/内边距/滚动条一致）。FR-004/005/008
- [ ] T011 [US2] 在目录表登记 **Tabs 双条目**：closable 主 tab→`TabStrip`（卡片式，链接 `## 统一标签条` 深章节）；非 closable 页内子 tab→下划线 `Tabs`（`components/ui/tabs.tsx`），写明 closable 驱动变体规则。FR-006（依赖 T003）
- [ ] T012 [P] [US2] 迁移 `frontend/components/workspace/views/ops-view.tsx` 页内手写下划线 Tab（约 L96–132）→ 复用共享下划线 `Tabs`，保持切换行为与 aria 语义。FR-005 示范迁移（依赖 T003）
- [ ] T013 [P] [US2] 迁移 `frontend/components/workspace/views/alerts-view.tsx` 页内手写下划线 Tab → 复用共享下划线 `Tabs`，保持行为。FR-005 示范迁移（依赖 T003）
- [ ] T014 [US2] 为迁移后的下划线 `Tabs` 补 vitest（变体渲染、激活态、非closable 无关闭按钮）于 `frontend/components/ui/__tests__/tabs.test.tsx`；`pnpm typecheck` 绿；浏览器抽查 ops/alerts 两页 Tab 明暗主题一致。FR-013 + 测试门（依赖 T012/T013）
- [ ] T015 [US2] 收敛魔法间距：审视 `frontend/components/ui/data-table-toolbar.tsx`（L78–103 硬编码 `px-*/py-*/gap-*`），凡属"卡片内容内边距"语义者改引 `--card-spacing`/token；纯工具条内部间距若为有意设计则在目录条目注明豁免。FR-010
- [ ] T016 [US2] 补录 `specs/037-shared-ui-kit/adoption-inventory.md`：盘点剩余 ~11 个 view 的原语采用状态（表格/下拉/弹框/加载/刷新/Tabs），逐行标 `保持`/`待迁移`。FR-015/SC-007

**Checkpoint**: 9 类原语全部有唯一目录条目，示范迁移完成，差距清单成型

---

## Phase 5: User Story 3 - 业务日期默认 + 带时间变体 (P2)

**Goal**: 目录"日期"条目文档化——业务日期为默认口径、带时间为统一变体

**Independent Test**: 两页 date 字段一律业务日期粒度且格式统一；datetime 字段一律统一"带时间"变体（SC-004）

- [ ] T017 [US3] 在 `frontend/DESIGN.md` 目录表登记"日期"条目：默认口径=业务日期 bizDate（`yyyy-MM-dd`，`yesterdayBizDate()` T-1 兜底 @ `lib/workspace/biz-date.ts`，选择器 `DatePicker`）；带时间变体=`useFormatDateTime()`（`hooks/use-format-date-time.ts`）+ `date-format-store`（`yyyy-MM-dd HH:mm:ss`，dash/slash 偏好）；声明单一 date-fns 实现、禁 dayjs/Intl 混用；注明 bizDate 直出、时间戳走 `useFormatDateTime`。FR-011（决策见 research R4）
- [ ] T018 [US3] 审计前端日期用法一致性：`grep -rn 'bizDate\|formatDateTime\|new Date\|Intl.DateTimeFormat\|dayjs' frontend/components frontend/lib`，将粒度/格式偏离项登记进 `adoption-inventory.md` 待迁移（增量）。SC-004

**Checkpoint**: 日期口径约定成文可查

---

## Phase 6: User Story 4 - 加载 / 刷新交互统一 (P2)

**Goal**: 目录登记加载/刷新条目；确立居中转圈 + 刷新入口统一位置 + 减少动画降级

**Independent Test**: 需加载视图 100% 居中转圈；刷新入口位置一致（SC-005）

- [ ] T019 [US4] 在 `frontend/DESIGN.md` 目录表登记"加载"条目：`LoadingState`（`components/workspace/shared/loading-state.tsx`，centered/overlay 双模式、垂直+水平居中、`motion-safe:animate-spin`、`prefers-reduced-motion` 静态降级 + `role=status`/aria 可读、最小 1s 兜底）。FR-012/FR-014（035 已落地，主要文档化）
- [ ] T020 [US4] 在目录表登记"刷新"条目：`ViewRefreshControl`（`components/workspace/views/view-refresh-control.tsx`），写明**统一位置约定**（视图右上/工具条内一致落点）+ 旋转 `motion-safe`。FR-012
- [ ] T021 [US4] 审计加载/刷新一致性：`grep -rn '加载中\|loading\|RefreshIcon' frontend/components/workspace/views`，将"纯文字加载/非居中/刷新入口位置不一"的页面登记进 `adoption-inventory.md` 待迁移。SC-005

**Checkpoint**: 加载/刷新条目成文，偏离项登记

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: 收口验收、跨特性缝合、清单闭环

- [ ] T022 全量门禁：`cd frontend && pnpm typecheck && pnpm design:lint` 均绿
- [ ] T023 SC-001 证伪式验收：按 `quickstart.md` + `contracts/reuse-first-checklist.md`，(a) 模拟"仅凭目录新建含表格+筛选下拉+卡片的页面"，确认命中复用、0 澄清；(b) 追加演练**"未命中→新建组件→回填目录"路径**（含 reuse-first-checklist「例外判定」：确认新组件非样式微调而属新原语），验证回填闭环与例外判定可用。SC-001 + FR-003（F3）
- [ ] T024 跨页外观一致性抽查（浏览器，admin/admin 登录注入 JWT，**明+暗双主题各一轮**）：抽 5 页比对同类原语一致——**须显式覆盖 Tabs/表格/下拉/弹框/滚动条/卡片留白/日期** 每类至少一处明暗核对（SC-002 + FR-013 全 9 类，非仅 5 类）；加载/刷新居中与位置一致（SC-005）。发现偏差登记 `adoption-inventory.md`（F2）
- [ ] T025 跨特性缝合检查（宪法 seam-closure）：确认 030 已合入且下划线 Tabs 收敛闭环、ops/alerts 迁移生效；`git worktree list` 无残留；033/035 种子条目与 main 实现一致
- [ ] T026 清单闭环：核对 `adoption-inventory.md` 每个 view 均已登记 `保持`/`待迁移`（登记即算收口，SC-007）；更新 `MEMORY.md` 037 条目为"plan+tasks 落地"状态

---

## Dependencies & Execution Order

**故事完成顺序**（按优先级）：Setup → Foundational(T003) → **US1(MVP)** → US2 → US3 → US4 → Polish

- **US1** 仅依赖 Setup（不依赖 030）——可最先独立交付为 MVP（目录 + 约定 + 间距 token）
- **US2** 依赖 US1（目录骨架存在）+ Foundational T003（下划线 Tabs）；T012–T014 硬依赖 T003
- **US3 / US4** 依赖 US1（目录骨架）；彼此独立，可与 US2 并行推进（不同文件段，但注意 DESIGN.md 同文件需按序编辑）
- **Polish** 依赖全部故事

**关键路径**：T003（030 协调）是唯一跨特性阻塞点——若 030 迟滞，US1/US3/US4 仍可全部交付，仅 US2 的 Tabs 部分（T011–T014）冻结。

## Parallel Opportunities

- **Setup**: T001 [P]（只读核对）
- **US2 迁移**: T012 [P]（ops-view）与 T013 [P]（alerts-view）不同文件、可并行（均待 T003）
- **审计任务**: T018 / T021 为只读 grep，可与对应故事的文档编辑并行
- ⚠️ **不可并行**：所有 `frontend/DESIGN.md` 编辑任务（T004,T005,T008–T011,T017,T019,T020）同文件，必须按序；`adoption-inventory.md` 编辑（T016,T018,T021,T026）同文件按序

## Implementation Strategy

- **MVP = US1**：仅交付 DESIGN.md 公共组件目录 + 复用优先约定 + 卡片内边距 token 真相源，即可立刻减少"抽卡式"重复对话（SC-001/SC-006 首要价值）。
- **增量交付**：US1 → US2（9 类条目+收敛，需先解 030）→ US3（日期）→ US4（加载/刷新）。每个故事独立可测、可单独 demo。
- **存量迁移按 clarify 决策增量**：本特性只迁移 ops/alerts 手写下划线 Tab 作示范（T012/T013），其余偏离项登记 `adoption-inventory.md` 待迁移，不阻断交付。
