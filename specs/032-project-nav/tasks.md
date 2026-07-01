---
description: "Task list for 032-project-nav implementation"
---

# Tasks: 企业项目左侧导航（按功能模块划分目录）

**Input**: Design documents from `specs/032-project-nav/`
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓
**Worktree**: 所有任务在 `/home/wallfacers/project/dw-032-project-nav`（分支 `032-project-nav`）内执行。
**Tests**: 包含测试任务 —— 项目硬规则「新功能必须有测试」(CLAUDE.md)；约定为**同目录 `*.test.ts`**（vitest），如 `lib/workspace/store.test.ts`。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1/US2/US3/US4
- 路径相对仓库根（worktree）；前端均在 `frontend/`

## Path Conventions

Web 前端单体：源码 `frontend/lib/...`、`frontend/components/...`；测试同目录 `*.test.ts`；i18n `frontend/messages/{zh-CN,en-US}.json`。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: i18n 命名空间与类型骨架，供后续各 story 复用

- [x] T001 [P] 在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 新增 `leftNav` 命名空间骨架键（`leftNav.groups.{dev,ops,alerting,governance,assets,analytics,admin}`、`leftNav.switcher.{label,empty,error,loading}`、`leftNav.collapse.{collapse,expand}`），两套 bundle key 集完全一致（CI 校验）
- [x] T002 [P] 在 `frontend/lib/types.ts` 新增 `ProjectVO` 类型（`id:number; name:string; code:string; status:string`），供项目切换器消费

**Checkpoint**: i18n 键与类型就位，可被后续 import

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 分组数据 + 导航容器挂载 —— US1/US3 渲染与高亮、US4 收起的共同地基（**必须先完成**）

- [x] T003 [P] 创建 `frontend/lib/workspace/nav-groups.ts`（纯数据，无 React）：导出有序 `NAV_GROUPS`（7 组，items 见 research D3）、派生 `viewToGroup`、`NAV_ENTRY_VIEWS`、`CONTEXT_DETAIL_VIEWS={instance-log,workflow-instance-detail}`、`detailViewParent`（两详情视图 → `{group:"ops"}`）
- [x] T004 [P] 单测 `frontend/lib/workspace/nav-groups.test.ts`：覆盖不变量「`flatten(NAV_GROUPS.items) ∪ CONTEXT_DETAIL_VIEWS === keys(VIEW_META)` 且 flatten 无重复」(SC-003)；`viewToGroup` 反查正确；分组顺序稳定
- [x] T005 创建 `frontend/components/workspace/left-nav.tsx` 常驻容器骨架（展开态结构占位，留分组/切换器/收起插槽），并在 `frontend/components/app-shell.tsx` 双栏 flex 最左侧（`<main>` 之前）挂载 `<LeftNav/>`

**Checkpoint**: 应用最左侧出现常驻导航容器；分组数据可用且经测试

---

## Phase 3: User Story 1 - 按功能模块浏览并打开功能 (Priority: P1) 🎯 MVP

**Goal**: 左侧按 7 功能模块分目录展示全部入口视图，点击即打开/激活对应视图（与「+」菜单、深链一致）

**Independent Test**: 打开应用 → 左侧见 7 分组目录与组内功能项 → 点击任一项在工作区打开正确视图 → 再点同项只激活不新建；分组覆盖全部入口视图无遗漏/重复

- [x] T006 [US1] 在 `frontend/components/workspace/left-nav.tsx` 渲染 `NAV_GROUPS`：每组中文标题（`useTranslations("leftNav") groups.<id>`）+ 组内功能项（图标 `VIEW_RENDER[view].icon`，名称 `useTranslations("views") <view>`），顺序严格依 `NAV_GROUPS`（FR-002/003/005）
- [x] T007 [US1] 在 `frontend/components/workspace/left-nav.tsx` 为功能项接点击 → `useWorkspaceStore.getState().open(view)`（去重激活，FR-004/FR-008）；触发器遵循 base-style：自定义触发用 `render`，按钮渲染为非 `<button>` 时 `nativeButton={false}`（[[dataweave-frontend-stack]]）
- [x] T008 [US1] 导航主体超高可纵向滚动（FR-011）；视觉遵循 `frontend/DESIGN.md`：语义 token、`gap-*`/`size-*`、hugeicons、区域留白不用分割线（[[no-header-footer-divider-lines]]）；空分组不渲染空标题（边界）
- [x] T009 [P] [US1] 渲染契约由纯逻辑测试覆盖（项目无 @testing-library/jsdom 组件测试栈，约定=纯 vitest + 浏览器验证，见 CLAUDE.md）：分组顺序/覆盖/高亮在 `nav-groups.test.ts`，`open` 行为在既有 `store.test.ts`；交互渲染由 T024 浏览器验收。**不新增组件测试栈**

**Checkpoint**: US1 可独立验收 —— MVP 完成（导航浏览+打开闭环）

---

## Phase 4: User Story 2 - 切换企业项目 (Priority: P2)

**Goal**: 导航顶部项目切换器，切换后全平台按所选项目重新取数；保留功能标签、自动关失效详情标签；首次默认取列表首个、刷新记住

**Independent Test**: 顶部切到另一项目 → 资产目录/运营中心重取为新项目数据、带旧参数详情标签自动关；清持久化后刷新默认选首个；切后刷新恢复上次所选

- [x] T010 [P] [US2] 创建 `frontend/lib/project-api.ts`：`listProjects(): Promise<ProjectVO[]>` 复用 `GET /api/projects`（无参，租户级；参照 `catalog-api.ts` 的 api 客户端 `get` 用法）
- [x] T011 [US2] 创建 `frontend/lib/project-context.ts`（zustand + localStorage，仿 `lib/date-format-store.ts` 同步初始读取）：`currentProjectId/projects/status` + `loadProjects()`（ready 后若持久化值无效则取列表**首个**，FR-019/FR-015）+ `setProject(id)`（写 `dw.project.current`）；空列表→`empty`、错误→`error`（FR-017）
- [x] T012 [US2] 在 `frontend/lib/project-context.ts` 的 `setProject` 中接副作用：调用 `useWorkspaceStore.getState().closeMany(t => !!t.params && CONTEXT_DETAIL_VIEWS.has(t.view))` 关闭带旧项目参数的详情标签，保留功能标签（FR-018）
- [x] T013 [US2] 在 `frontend/components/workspace/left-nav.tsx` 顶部加项目切换器 UI：展示 `currentProject.name` + 下拉列出 `projects`；首屏 `loadProjects()`；`empty`/单项只读/`error` 态分别呈现（FR-013/017），不阻塞导航其余部分
- [x] T014 [US2] 去硬编码：`frontend/lib/catalog-api.ts` 与 `frontend/lib/datasource-api.ts` 中 `projectId = 1`/`?? 1` 改为读 `useProjectContext.getState().currentProjectId`（保留 `?? 1` 兜底兼容），不改各端点契约（FR-014）
- [x] T015 [US2] 让按项目取数的视图随 `currentProjectId` 重查：在 `frontend/components/workspace/views/asset-catalog-view.tsx`、`metric-marketplace-view.tsx`、`datasources-view.tsx`（及 pinned `freshness-view/reports-view/metrics-view` 若按项目取数）的数据 hook 依赖数组纳入 `currentProjectId`（FR-014/SC-007）
- [x] T016 [P] [US2] 单测 `frontend/lib/project-context.test.ts`：`loadProjects` 默认取首个；持久化恢复；空列表→`empty`；`setProject` 触发 `closeMany`（mock store）写 localStorage

**Checkpoint**: US2 可独立验收 —— 项目切换闭环，无跨项目串数

---

## Phase 5: User Story 3 - 标识当前所在功能 (Priority: P2)

**Goal**: 左侧导航高亮当前激活功能，随工作区标签切换实时更新；详情视图归父模块高亮

**Independent Test**: 切换工作区标签 → 左侧对应功能项即高亮；激活某实例日志 → 高亮落在 ops 模块，无误导高亮

- [x] T017 [US3] 在 `frontend/components/workspace/left-nav.tsx` 加高亮：订阅 `useWorkspaceStore(s => s.activeTabId)` 解析 `activeView`；`activeView ∈ NAV_ENTRY_VIEWS` → 高亮该项 + `viewToGroup[activeView]`；`∈ CONTEXT_DETAIL_VIEWS` → `detailViewParent` 归 `ops` 模块；否则无高亮（FR-006/FR-007/SC-004）
- [x] T018 [P] [US3] 单测 `frontend/lib/workspace/nav-highlight.test.ts`（或并入 nav-groups.test）：入口视图→自身+组；`instance-log`/`workflow-instance-detail`→ops 模块；未知→无高亮

**Checkpoint**: US3 可独立验收 —— 方向感闭环

---

## Phase 6: User Story 4 - 收起/展开导航 (Priority: P3)

**Goal**: 导航可收起为仅图标 icon rail，悬停/展开显示文字，偏好刷新后记住

**Independent Test**: 点收起 → 仅图标窄条、图标仍可一键打开；点展开 → 文字恢复；刷新后保留上次态

- [x] T019 [P] [US4] 创建 `frontend/lib/nav-ui-store.ts`（zustand + localStorage `dw.nav.collapsed`，同步初始读取）：`collapsed` + `toggleCollapsed()`
- [x] T020 [US4] `frontend/components/workspace/left-nav.tsx` 接 `collapsed`：收起态渲染为**仅图标 icon rail**（悬停/展开显示文字），加收起/展开控件；收起态功能图标仍调 `open`（FR-009）；容器宽度随状态变化让出工作区空间
- [x] T021 [P] [US4] 单测 `frontend/lib/nav-ui-store.test.ts`：`toggleCollapsed` 切换 + localStorage 持久化与初始恢复

**Checkpoint**: US4 可独立验收

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T022 [P] `cd frontend && pnpm typecheck` 零错误（我的文件零错误；唯二红是基线既存：subscription-dialog.tsx 的 SubscriptionView typecheck 错 + store.test.ts 快照排序断言 bug——两者在 main faac497 上既存，非本特性）
- [x] T023 [P] `cd frontend && pnpm design:lint` 通过；复核 `leftNav.*` 在 zh-CN/en-US key 集一致（FR-012）
- [x] T024 浏览器验收（鉴权见 [[browser-verification-jwt-login]]）：按 `quickstart.md` 验收矩阵逐项全部通过，详见 `quickstart.md` 验证小节
- [x] T025 跨特性对齐（合入前）：重跑 workspace 共享面测试（`lib/workspace/store.test.ts` 等）→ 4/5 文件通过（44/45 tests），唯一失败为基线既存 `.sort()` 快照 bug；确认 `app-shell`/tab 栏/激活态接缝闭合，无 no-op/破坏

---

## Dependencies & Execution Order

```text
Setup (T001-T002)
   └─> Foundational (T003-T005)   ← 阻塞所有 story
          ├─> US1 (T006-T009)     [P1, MVP]  依赖 T003(数据)+T005(挂载)
          ├─> US2 (T010-T016)     [P2]       依赖 Foundational；T012 依赖 T003;T013 依赖 T010/T011;T014 依赖 T011
          ├─> US3 (T017-T018)     [P2]       依赖 T003 + US1 的 T006(渲染项)
          └─> US4 (T019-T021)     [P3]       依赖 T005 + US1 的 T006(渲染项)
   └─> Polish (T022-T025)         依赖全部 story
```

- **Story 独立性**：US1 单独即 MVP（可交付）。US2/US3/US4 各自增量，均在 US1 渲染之上叠加，互不阻塞（US3、US4 各自只追加高亮/收起逻辑到同一 `left-nav.tsx`，实现时注意串行编辑同文件以免冲突）。
- **同文件串行**：T006/T007/T008/T013/T017/T020 都改 `left-nav.tsx` —— **不可并行**，按 story 顺序串行编辑。
- **跨文件并行**：标 `[P]` 的（T001/T002/T003/T004/T009/T010/T016/T018/T019/T021/T022/T023）为独立文件，可并行。

## Parallel Execution Examples

- **Setup 并行**：T001 ‖ T002
- **Foundational 并行**：T003 ‖ T004（数据+其测试可同写，测试依数据接口）
- **各 story 测试并行**：实现完成后 T009 ‖ T016 ‖ T018 ‖ T021（不同测试文件）
- **US2 起步并行**：T010（project-api）‖ T011（project-context 初版）

## Implementation Strategy

1. **MVP 优先**：Setup → Foundational → **US1**，即可演示「左侧分组导航 + 点击打开」。在此停可独立交付。
2. **增量叠加**：US2（项目切换，价值最高的增量）→ US3（高亮，体验）→ US4（收起，优化）。
3. **每步自检**：每改一处 `pnpm typecheck`（CLAUDE.md 硬规则）；story 完成跑该 story 测试 + 对应 quickstart 验收行。
4. **收尾**：Polish 全量校验 + 浏览器验收 + 跨特性对齐后再考虑合入。

## Notes

- **零后端任务**：复用 `GET /api/projects`，无新建端点/表（research D1、contracts）。
- **隔离**：宪法 II —— 项目列表服务端按 `tenant_id` 隔离，前端不做隔离逻辑。
- **WSL2**：跑测试/构建用 `setsid` 脱离 [[wsl2-long-command-detach]]。
