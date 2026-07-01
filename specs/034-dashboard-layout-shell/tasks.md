# Tasks: Dashboard-01 外壳布局迁移（保留多 Tabs）

**Input**: Design documents from `/specs/034-dashboard-layout-shell/`
**Prerequisites**: [plan.md](plan.md)（必需）、[spec.md](spec.md)（必需，用户故事）、[research.md](research.md)、[data-model.md](data-model.md)、[contracts/breadcrumb-visual-contract.md](contracts/breadcrumb-visual-contract.md)、[quickstart.md](quickstart.md)

**Tests**: 本仓库约定「新特性必须带测试」（CLAUDE.md）。测试范围为**纯函数单测**（vitest，co-located `*.test.ts`，无组件级 `.test.tsx`、无提交式 Playwright spec 文件），UI 行为走 quickstart.md 的人工浏览器验证门。

**Organization**: 按用户故事分组；US1/US2 均为 P1（可并行由不同人执行，二者只共享 Foundational 阶段产出，互不阻塞），US3 为 P2。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 归属用户故事（US1/US2/US3）
- 所有任务给出精确文件路径；工作目录固定为 worktree `/home/wushengzhou/workspace/github/dw-034-dashboard-layout-shell`（分支 `034-dashboard-layout-shell`），下方路径均相对该目录。

---

## Phase 1: Setup

**Purpose**: 确认基线可用，无需新增依赖/脚手架（本特性零新增依赖）

- [ ] T001 基线校验：`cd frontend && pnpm typecheck` 零错误、`pnpm dev` 可正常起服务，作为后续改动的对照基线（不改代码）

---

## Phase 2: Foundational（阻塞全部用户故事）

**Purpose**: 面包屑派生逻辑与其复用的公共接口，是 US1 的直接依赖、US2 的行为不变基线；US3 与本阶段产出无代码依赖（见下方 Dependencies 说明，可与本阶段并行开工）

**⚠️ CRITICAL**: T002-T005 完成前不要开始 US1/US2

- [ ] T002 [P] 在 `frontend/components/workspace/tab-bar.tsx` 把模块私有函数 `tabLabel(tab, t)` 改为 `export function tabLabel(...)`，不改函数签名/行为，仅导出可见性
- [ ] T003 [P] 新增 `frontend/lib/workspace/breadcrumb.ts`：导出纯函数 `deriveBreadcrumbNodes(view, params, projectName, t)`，复用 `resolveActiveHighlight`/`NAV_GROUPS`（来自 `./nav-groups`）与 `VIEW_META`（来自 `./views`）计算「项目/分组(可选)/视图/动态参数(可选)」节点序列；分组缺失时序列退化为二级（不产生空节点），动态参数级复用 `tab-bar.tsx` 导出的 `tabLabel` 同款"首个 param 值"约定
- [ ] T004 新增 `frontend/lib/workspace/breadcrumb.test.ts`：覆盖三种输入场景——① 入口视图（如 `ops`）→ 三级节点 ② 无分组视图（如 `settings`，若其未归组）→ 二级节点 ③ 带 `params` 的详情视图（如 `instance-log`）→ 四级节点且末级值与 `tabLabel` 输出一致（对应契约 A1/A2/A3；依赖 T002/T003）
- [ ] T005 [P] 在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 的 `workspace` 命名空间下新增 `breadcrumb.ariaLabel` key（面包屑 `<nav>` 的无障碍标签），两文件 key 集合保持一致

**Checkpoint**: `deriveBreadcrumbNodes` 单测全绿、`tabLabel` 导出后 `pnpm typecheck` 零错误 —— US1/US2 可以开始

---

## Phase 3: User Story 1 - 顶部面包屑呈现当前位置 (Priority: P1) 🎯 MVP

**Goal**: 内容区顶部为当前激活 Tab 渲染「项目 > 分组 > 视图(> 动态参数)」面包屑，侧边栏折叠不影响其可读性

**Independent Test**: 打开任意视图 Tab，不看侧边导航即可通过顶部面包屑判断当前分组/视图；折叠侧边栏后面包屑内容不变

### Implementation for User Story 1

- [ ] T006 [US1] 新增 `frontend/components/workspace/breadcrumb.tsx`：`WorkspaceBreadcrumb` 组件——读 `useWorkspaceStore` 的 `activeTabId`/`tabs` 找到当前 tab，读 `useProjectContext` 的项目名，调用 `deriveBreadcrumbNodes`（T003）渲染节点，节点间用不可点击的分隔符（如 `HugeiconsIcon ArrowRight01Icon` 或 `/`），根元素 `<nav aria-label={t("workspace.breadcrumb.ariaLabel")}>`（依赖 T003/T005）
- [ ] T007 [US1] 面包屑视觉样式遵守 `frontend/DESIGN.md`「无分割线」条款：不加 `border-b`/`border-t`/`<Separator>`，用 `bg-foreground/[0.04]`（或与 Tab 条同量级的背景层次）+ `px-4 py-2` 类留白区隔，长文案 `truncate`（同 T006 文件内实现）
- [ ] T008 [US1] 在 `frontend/components/workspace/workspace.tsx` 的 `<WorkspaceTabBar />` 之后、内容渲染 `<div>` 之前插入 `<WorkspaceBreadcrumb />`（依赖 T006）
- [ ] T009 [US1] 浏览器验证（quickstart.md §3.1，契约 A1/A2/A5/A6）：打开「运维总览」核对三级面包屑与左侧导航高亮一致；打开「系统设置」核对无分组兜底；折叠侧栏确认面包屑不受影响；DevTools 确认面包屑行无分割线

**Checkpoint**: US1 独立可测——面包屑正确展示，不依赖 US2/US3

---

## Phase 4: User Story 2 - 多 Tabs 之间面包屑与布局互不串台 (Priority: P1)

**Goal**: 多 Tab 切换时面包屑与既有 Tab 行为（新开/关闭/固定/keep-alive）100% 准确对应当前 Tab，零回归

**Independent Test**: 打开 5 个不同视图 Tab 随机切换 50 次，面包屑与内容零错位；新开/关闭/固定/keep-alive 行为与改造前一致

### Implementation for User Story 2

- [ ] T010 [US2] 在 `frontend/lib/workspace/breadcrumb.test.ts` 追加用例：模拟连续两次不同 `view`/`params` 调用 `deriveBreadcrumbNodes`，断言两次返回节点互不残留（对应契约 A4、Edge Case「同视图不同参数」）（依赖 T004）
- [ ] T011 [US2] 回归确认：运行 `cd frontend && pnpm test -- store.test.ts tab-bar` 相关用例全绿，验证 T002 的 `tabLabel` 导出未改变其在 `WorkspaceTabBar` 内的调用结果（依赖 T002）；如发现回归在 `frontend/components/workspace/tab-bar.tsx` 就地修复
- [ ] T012 [US2] 浏览器验证（quickstart.md §3.2，契约 A3/A4、B1/B2）：打开 5 个不同视图 Tab 随机切换 ≥10 次核对面包屑零残留；从某实例列表打开 `instance-log` 详情 Tab 核对面包屑末级与 Tab 标签一致；执行新开/关闭/固定/取消固定/keep-alive 逐项核对行为不变

**Checkpoint**: US1 + US2 均独立可测且互不干扰

---

## Phase 5: User Story 3 - 卡片与数据表格按统一栅格重新排布 (Priority: P2)

**Goal**: 含统计卡片/数据表格的视图版式对齐既有标杆（`metrics-view.tsx` 卡片栅格、033 交付的 `DataTable` 边框 frame），不新增图表、不强插空区块

**Independent Test**: 对比改造前后同一视图，卡片/表格栅格间距一致、数据内容不变、无新增图表

> 本阶段任务与 Foundational（T002-T005）无代码依赖，可与 Phase 2-4 并行开工（详见下方 Dependencies）

### Implementation for User Story 3

- [ ] T013 [P] [US3] 审计 `frontend/components/workspace/views/fleet-view.tsx`、`frontend/components/workspace/views/reports-view.tsx`：卡片栅格是否已对齐 `metrics-view.tsx` 的 `grid gap-3 sm:grid-cols-2 lg:grid-cols-4` 惯例，偏离则最小 class 调整（不改数据/文案）
- [ ] T014 [P] [US3] 审计 `frontend/components/workspace/views/settings-view.tsx`、`frontend/components/workspace/views/datasources-view.tsx`、`frontend/components/workspace/views/freshness-view.tsx`：`<DataTable>` 调用点版式是否符合并行分支 `033-ui-table-frame-spacing` 合入 main 后的边框 frame 基线（若 033 尚未合入，先读其 `contracts/ui-visual-contract.md` 对齐预期，实现阶段以后续合入版本复核）
- [ ] T015 [P] [US3] 审计 `frontend/components/workspace/views/ops/` 下 `backfill-panel.tsx`、`manual-workflows-panel.tsx`、`periodic-instances-panel.tsx`、`periodic-workflows-panel.tsx`、`workflow-instances-panel.tsx`、`top-strip.tsx`：卡片/表格版式对齐
- [ ] T016 [P] [US3] 审计 `frontend/components/workspace/views/asset-catalog-view.tsx`、`metric-marketplace-view.tsx`、`lineage-view.tsx` 及其 `asset/`、`metric/`、`lineage/` 子目录组件：卡片/表格版式对齐
- [ ] T017 [P] [US3] 审计其余视图 `alerts-view.tsx`、`event-center-view.tsx`、`quality-view.tsx`、`workflow-canvas-view.tsx`、`instance-log-view.tsx`、`workflow-instance-detail.tsx`、`placeholder-view.tsx`：确认无卡片/表格的视图未被强插空卡片容器（FR-010）
- [ ] T018 [US3] 契约 C1 核验：`cd frontend && grep -riE "recharts|chart\.js|d3|visx|apexcharts" package.json` 应无命中，确认本阶段未引入图表库依赖
- [ ] T019 [US3] 浏览器验证（quickstart.md §3.3，契约 C2-C4）：逐视图目测卡片栅格/表格版式与标杆一致、无新增图表、无强插空区块

**Checkpoint**: US1+US2+US3 均独立可测；三者合并即为完整特性

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 收口验收，覆盖全部契约断言

- [ ] T020 [P] `cd frontend && pnpm typecheck` 零错误 + `pnpm design:lint` 通过
- [ ] T021 [P] `cd frontend && pnpm test` 全绿（含 T004/T010 新增用例，及既有 `store.test.ts`/`nav-groups.test.ts` 无回归）
- [ ] T022 视觉约定沉淀：若 T007 的面包屑视觉处理引入了新的间距/背景约定，在 `frontend/DESIGN.md` 补一段简述（不改 YAML 颜色 token）；若完全复用既有 Tab 条量级则跳过并在 PR 描述注明
- [ ] T023 浏览器验证（quickstart.md §3.4）：双主题（浅/深）+ 窄视口下面包屑不撑破 header、文案合理截断
- [ ] T024 最终收口：逐条核对 `contracts/breadcrumb-visual-contract.md` 全部 15 条断言（A1-A7、B1-B3、C1-C5），形成一份「断言 → 通过/不通过 + 证据」的验收记录

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成；**阻塞 US1 (Phase 3) 与 US2 (Phase 4)**
- **US3 (Phase 5)**: 与 Foundational/US1/US2 无代码依赖（不读 `breadcrumb.ts`/`tab-bar.tsx`），可从 Setup 完成后立即并行开工；仍建议放在 Foundational 之后统一收口，避免与 US1/US2 的文件改动在合并时产生不必要的 diff 噪音
- **Polish (Phase 6)**: 依赖 US1+US2+US3 全部完成

### User Story Dependencies

- **US1 (P1)**: 依赖 T002/T003/T005；与 US2/US3 无交叉依赖
- **US2 (P1)**: 依赖 T002/T004；与 US1 共享 Foundational 产出但互不阻塞，可并行
- **US3 (P2)**: 无跨故事依赖，可最早开工，也可最后收尾

### Within Each Story

- Foundational：T002/T003/T005 可并行 → T004 依赖 T002+T003
- US1：T006 依赖 T003；T007 与 T006 同文件顺序执行；T008 依赖 T006；T009（浏览器验证）依赖 T008
- US2：T010 依赖 T004；T011 依赖 T002；T012（浏览器验证）依赖 T008（需要面包屑已挂载）+ T011
- US3：T013-T017 互相独立可并行；T018/T019 收尾

---

## Parallel Example: Foundational + US3 同时开工

```bash
# 一个 agent 做 Foundational：
Task: "T002 export tabLabel in frontend/components/workspace/tab-bar.tsx"
Task: "T003 create frontend/lib/workspace/breadcrumb.ts"
Task: "T005 add workspace.breadcrumb.ariaLabel to both message bundles"

# 另一个 agent 同时做 US3 审计（互不冲突文件）：
Task: "T013 audit fleet-view.tsx / reports-view.tsx card grids"
Task: "T015 audit views/ops/*.tsx panels"
Task: "T016 audit asset-catalog-view.tsx / metric-marketplace-view.tsx / lineage-view.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup
2. 完成 Phase 2 Foundational（阻塞项）
3. 完成 Phase 3 US1
4. **停下验证**：quickstart §3.1 走一遍，面包屑独立可用即为 MVP

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. + US1 → 面包屑上线（MVP）
3. + US2 → 多 Tab 回归确认，消除串台风险
4. + US3 → 卡片/表格审计收尾，视觉一致性达标
5. Polish → 全量断言收口

### 建议的"实习 AI + 我兜底"分工

见对话中给出的三段任务提示词（Foundational+US1 / US2 / US3），每段提示词自带完整上下文与验收标准，可直接派发给另一个 AI agent 独立执行；T009/T012/T019/T023/T024（浏览器验证与最终断言收口）固定由我在收到各段产出后统一执行，不下放。

---

## Notes

- [P] 任务 = 不同文件、无依赖冲突
- [Story] 标签用于按故事追溯任务
- 每个用户故事应可独立完成、独立测试
- 逐任务或逐逻辑组提交
- 避免：模糊任务、同文件冲突、破坏故事独立性的跨故事依赖
