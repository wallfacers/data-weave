# Tasks: 产品面收缩——移除中台货架页面

**Input**: Design documents from `/specs/042-product-surface-shrink/`
**Prerequisites**: plan.md ✅ · research.md ✅ · data-model.md ✅ · contracts/ui-surface.md ✅ · quickstart.md ✅

**组织方式**: 按用户故事分阶段；每个故事独立可测。**并行执行按 3-agent 切分**：Agent A = US1（核心收缩）、Agent B = US2（降级）、Agent C = US3（i18n），文件所有权互不相交（见 Dependencies 节）。

**Tests**: 项目规约"无测试 = 未完成"，测试任务包含在各故事内（断言联动 + 新增降级用例）。

## Phase 1: Setup

- [ ] T001 记录基线：在 worktree 内 `cd frontend && pnpm typecheck && pnpm test` 确认全绿，作为收缩前基线（任何既有红灯先上报，不得静默带过）

## Phase 2: Foundational

（无——删除型特性没有阻塞性前置；直接进入用户故事。）

## Phase 3: User Story 1 - 导航只呈现新方向认可的功能 (P1) 🎯 MVP — Agent A

**Goal**: 四个中台视图从视图全集、注册表、导航分组中彻底移除，删除全部孤儿文件，导航不变量测试在 14 视图全集下通过。

**Independent Test**: `pnpm typecheck` 零错误 + `pnpm test` 全绿（i18n 检查除外，见 US3）；浏览器中左侧导航 = contracts/ui-surface.md §3 的 12 入口。

- [ ] T002 [US1] 收缩视图全集：`frontend/lib/workspace/views.ts` — ViewType 删除 `marketplace`/`reports`/`service`/`integration` 四成员；VIEW_META 删除对应四项（注意 reports 带 `defaultPinned: true`，删除后 PINNED_VIEWS 自动缩为 freshness/metrics，符合 data-model.md）
- [ ] T003 [US1] 收缩注册表：`frontend/lib/workspace/registry.tsx` — 删除四个 VIEW_RENDER 项及其 icon import；删除 `placeholder()` 工厂与 `PlaceholderView` import（integration/service 是仅有消费者）
- [ ] T004 [US1] 收缩导航分组：`frontend/lib/workspace/nav-groups.ts` — `assets` 组 items 改为 `["datasources"]`；`analytics` 组整组删除
- [ ] T005 [P] [US1] 删除文件 `frontend/components/workspace/views/metric-marketplace-view.tsx`
- [ ] T006 [P] [US1] 删除文件 `frontend/components/workspace/views/reports-view.tsx`
- [ ] T007 [P] [US1] 删除目录 `frontend/components/workspace/views/metric/`（metric-listing-dialog.tsx、metric-reuse-dialog.tsx）
- [ ] T008 [P] [US1] 删除文件 `frontend/components/workspace/views/placeholder-view.tsx`
- [ ] T009 [P] [US1] 删除文件 `frontend/lib/metric-listing.ts` 与 `frontend/lib/metric-listing.test.ts`
- [ ] T010 [US1] 断言联动：`frontend/lib/workspace/nav-groups.test.ts` — 移除/改写点名被删视图的断言（如 `resolveActiveHighlight("reports")` 改用保留视图）；"入口∪详情=VIEW_META 全集"不变量结构不动
- [ ] T011 [US1] 断言联动：`frontend/lib/workspace/nav-permissions.test.ts` — 改写 `viewRequiredPermission("marketplace")`、`metric:manage` 可见性等用例（改用 `datasources`/`datasource:manage` 等保留视图等价断言）
- [ ] T012 [US1] 验证：`cd frontend && pnpm typecheck && pnpm test` 全绿；`grep -rn "marketplace\|ReportsView\|PlaceholderView\|metric-listing" frontend/lib/workspace frontend/components/workspace frontend/app --include="*.ts*"` 无孤儿引用（`catalog-api.ts`/`views/asset/` 内的市场 API 客户端残留属 research D2 豁免，不算）

**Checkpoint**: US1 完成即 MVP——产品面已与新方向一致。

## Phase 4: User Story 2 - 旧会话与旧链接优雅降级 (P2) — Agent B

**Goal**: 三个遗留 redirect 路由改指 `/`；含被删视图的历史快照恢复降级有测试护住。

**Independent Test**: store.test.ts 新增用例通过（依赖 US1 的 ViewType 收缩落地后才会转绿）；浏览器深链矩阵按 quickstart §3–4 验证。

- [ ] T013 [P] [US2] `frontend/app/integration/page.tsx` — `redirect("/?open=integration")` 改为 `redirect("/")`
- [ ] T014 [P] [US2] `frontend/app/service/page.tsx` — `redirect("/?open=service")` 改为 `redirect("/")`
- [ ] T015 [P] [US2] `frontend/app/metrics/page.tsx` — `redirect("/?open=reports")` 改为 `redirect("/")`
- [ ] T016 [US2] 降级测试：`frontend/lib/workspace/store.test.ts` — 新增用例：① restore 含 `view: "reports"`（被删值）标签的快照 → 该标签被静默丢弃；② 被丢弃者为激活标签 → 激活态回退到剩余标签；③ 快照全部标签均为被删视图 → 回到 Pinned 底座（freshness/metrics）。只加用例，不改 store 实现（research D5）
- [ ] T017 [US2] 浏览器验证：按 quickstart §3–4 逐项跑深链矩阵（`/?open=marketplace|reports|service|integration` 落默认视图；`/integration` `/service` `/metrics` 重定向到 `/`；注入旧快照刷新无异常）——若 US1 尚未落地可延后到收口执行，但必须留下执行记录

## Phase 5: User Story 3 - 双语文案无残留 (P3) — Agent C

**Goal**: 两语言包同步删除孤儿键，键集一致性检查通过。

**Independent Test**: i18n 键集一致性检查通过（依赖 US1 代码删除落地后孤儿键才真正无消费者）；双语界面无裸键名。

- [ ] T018 [P] [US3] `frontend/messages/zh-CN.json` — 删除：`views.marketplace`、`views.reports`、`views.integration`、`views.service`；顶层 `reports.*` 与 `metricMarketplace.*` 整命名空间；`leftNav.groups.analytics`；`placeholderView.descIntegration` 与 `placeholderView.descService`（若 placeholderView 因此为空则整命名空间删除）。`leftNav.groups.assets` 保留
- [ ] T019 [P] [US3] `frontend/messages/en-US.json` — 删除与 T018 完全相同的键集（两 bundle 键集必须一致）
- [ ] T020 [US3] 验证：运行仓库既有 i18n 键集一致性检查；`git diff` 复核两 bundle 删除的键集逐一对应；确认未删除仍被保留视图引用的键（如 `metrics.*`、`catalog.*`、`dataTable.*`）

## Phase 6: Polish & 收口（主 Claude 评审执行）

- [ ] T021 全量门：`cd frontend && pnpm typecheck && pnpm test` + i18n 键集检查，三者全绿
- [ ] T022 quickstart 全流程浏览器验证：12 入口逐一打开（SC-004）、清 localStorage 后常驻标签仅 freshness+metrics、双语导航检索不到四个被删功能名（SC-001）、catalog 资产详情/订阅弹窗正常（共享 catalog-api 未误伤，FR-008）
- [ ] T023 终检：`grep -rn "\"marketplace\"\|\"reports\"\|descIntegration\|descService" frontend --include="*.ts*" --include="*.json" | grep -v node_modules` 复核仅剩 research D2 豁免项；三个 agent 的 commit 只触碰各自所有权文件

## Dependencies

- **US1 (Agent A)**：无前置，独立完成即 MVP。
- **US2 (Agent B)**：T013–T015 完全独立可并行；T016 编写不依赖任何人，但**转绿依赖 US1 的 T002 落地**（isKnownView 对 "reports" 返回 false 才会触发丢弃分支）；T017 浏览器验证依赖 US1。
- **US3 (Agent C)**：T018–T019 编写独立；T020 的一致性检查**转绿依赖 US1 落地**（键的消费者被删除后才无静态解析残留）。
- **Phase 6**：依赖全部故事完成，由主 Claude 收口。

**文件所有权（硬边界，防并行冲突）**：
| Agent | 独占文件 |
|---|---|
| A (US1) | `lib/workspace/{views.ts,registry.tsx,nav-groups.ts,nav-groups.test.ts,nav-permissions.test.ts}`、`components/workspace/views/{metric-marketplace-view,reports-view,placeholder-view}.tsx`、`components/workspace/views/metric/`、`lib/metric-listing.ts(.test.ts)` |
| B (US2) | `app/{integration,service,metrics}/page.tsx`、`lib/workspace/store.test.ts` |
| C (US3) | `messages/{zh-CN,en-US}.json` |

三者交集为空；任何 agent 不得触碰边界外文件，git commit 只包含自己所有权内的路径。

## Parallel Execution Examples

- Agent A 内部：T005–T009（五个纯删除）可同时进行；T002–T004 建议一把改完再跑 T012。
- Agent B 内部：T013–T015 三个单行改动并行；T016 随后。
- 三个 agent 跨故事完全并行（同 worktree `../dw-042-surface-shrink`、同分支 `042-product-surface-shrink`，靠文件所有权隔离）。

## Implementation Strategy

MVP = US1（Agent A 单独交付即达成产品面收缩的核心价值）；US2/US3 是降级韧性与文案卫生，随后并行补齐。三个故事的"转绿闸门"在收口阶段统一验证（T021–T023），因为 US2 测试与 US3 检查的绿灯天然依赖 US1 落地。
