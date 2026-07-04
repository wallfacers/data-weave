# Tasks: 资产目录页面规范化重设计

**Input**: Design documents from `specs/043-asset-catalog-polish/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: 不新增测试（spec 未要求 TDD）。依赖既有 vitest 单测 + `pnpm typecheck` + 浏览器手验。

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

All paths relative to `frontend/`. This is a frontend-only feature.

---

## Phase 1: Setup (i18n 基线 + 依赖确认)

**Purpose**: 确认开发环境基线，补全 i18n key 骨架。

- [ ] T001 运行基线绿：`cd frontend && pnpm typecheck && pnpm test && pnpm design:lint`，记录任何预存在失败（与 043 无关）
- [ ] T002 [P] 在 `frontend/messages/zh-CN.json` 的 `assetCatalog` 命名空间新增约 15 个 key（cardExpandHint / cardCollapseHint / detailDescription / detailLineage / detailQuality / detailMetadata / lineageSource / qualityScore / qualityNull / filterClear / filterActive / errorRetry / emptyFiltered / skeletonLoading / expandActions），填入中文文案
- [ ] T003 [P] 在 `frontend/messages/en-US.json` 的 `assetCatalog` 命名空间同步新增相同 key 集，填入英文文案（key 集严格一致）

**Checkpoint**: 基线绿 + i18n 双 bundle key 集一致

---

## Phase 2: Foundational（AssetCard 组件 + 筛选 Toolbar）

**Purpose**: 核心 UI 组件——资产卡片（含内联展开）和筛选 toolbar——这两者阻塞所有用户故事。

**⚠️ CRITICAL**: 完成前任何用户故事不能闭环。

- [ ] T004 [P] 新建 `frontend/components/workspace/views/asset/asset-card.tsx`：实现 `AssetCard` 组件骨架——接收 `AssetSummary` props，渲染卡片壳（图标 + 名称 + 限定名 + 敏感度 Badge + 状态 Badge + 负责人 + 更新日期），含 hover 态（border 色过渡）和选中态（border 高亮 + 左侧 accent 边条）。使用项目 Badge 组件映射敏感度/状态 → variant（按 data-model.md 映射表）
- [ ] T005 [P] 新建 `frontend/components/workspace/views/asset/asset-filter-toolbar.tsx`：实现 `AssetFilterToolbar` 组件——使用 `segmented` 控件承载敏感度/负责人维度筛选（对齐 FilterDef 体系），使用 `search` 控件承载关键词搜索；已激活筛选时显示"清除筛选"按钮；暴露 `onChange` 回调给父组件
- [ ] T006 实现 `AssetCard` 内联展开/收起（US2 基础）：在 T004 基础上添加——点击卡片 → motion AnimatePresence 向下展开详情区（描述 / 血缘摘要 / 质量评分 Badge / 元数据标签行）；再次点击或点击其他卡片收起（accordion 单展开）；使用 motion `height: "auto"` 动画（`duration: 0.2`）
- [ ] T007 实现 `AssetCard` 展开态操作按钮组：在 T006 展开区底部添加操作按钮——编辑 / 下线 / 对账 / 订阅（/退订），使用项目 Button 组件（`size="sm" variant="ghost"`），按钮事件以回调暴露（`onEdit` / `onRetire` / `onReconcile` / `onSubscribe` / `onUnsubscribe`）
- [ ] T008 实现 `AssetCard` 数据降级逻辑：血缘为 null → 隐藏血缘行（FR-012）；质量评分为 null → 隐藏质量行；STALE 状态 → 展开区顶部显示 warning 提示条 "底层表已变更，待对账"（调用 `t("staleHint")` 既有多语言 key）

**Checkpoint**: AssetCard + AssetFilterToolbar 组件独立可用（可在 Storybook 级单独预览）

---

## Phase 3: User Story 1 — 资产卡片网格浏览与筛选 (Priority: P1) 🎯 MVP

**Goal**: 用户以卡片网格浏览资产、通过顶部 toolbar 筛选、一键清除筛选。

**Independent Test**: 打开资产目录页 → 卡片网格呈现 → 点击 toolbar segmented 筛选 → 卡片过滤 → 清除筛选恢复。

### Implementation for User Story 1

- [ ] T009 [US1] 重构 `frontend/components/workspace/views/asset-catalog-view.tsx`：移除左侧分面面板（Facet 组件相关的 sensitivity/owner/tag/status 渲染代码），移除列表区原生 button 行样式代码；替换为顶部 `<AssetFilterToolbar>` + 下方卡片网格容器（CSS Grid `auto-fill minmax(380px, 1fr)`）
- [ ] T010 [US1] 在 `asset-catalog-view.tsx` 中实现卡片网格渲染：`result.items.map(a => <AssetCard key={a.id} asset={a} />)`，响应式列数由 CSS Grid 自动处理（宽屏 3 列，中等 2 列，窄屏 1 列）
- [ ] T011 [US1] 在 `asset-catalog-view.tsx` 中连接筛选逻辑：`AssetFilterToolbar.onChange` → 调用既有 `toggleFacet` / `setKeyword` / `setQualityMin` → `buildSearchParams` → `searchAssets` → 更新卡片网格
- [ ] T012 [US1] 在 `asset-catalog-view.tsx` 中替换 loading/empty/error 状态：加载态 → `<LoadingState>` 组件 + 骨架卡片（3-6 张灰色占位卡片）；空态 → 空态卡片（图标 + `t("emptyFiltered")` + "清除筛选"按钮）；错误态 → 错误卡片（`t("loadFailed")` + "重试"按钮）
- [ ] T013 [US1] 在 `asset-catalog-view.tsx` 中将 toast 从自建 `setTimeout` 方案迁移到 sonner：`import { toast } from "sonner"`；`resolveGate` → `toast.success` / `toast.error` / `toast.info`（三态分流）；移除 `useState(toast)` + `setTimeout` 代码
- [ ] T014 [US1] 保留分页：卡片网格下方保留 `<Pagination>` 组件（复用既有），默认 page size=20，total 展示不变
- [ ] T015 [US1] 保留资产详情加载逻辑：`openDetail` → `fetchAsset` + 懒加载 `fetchAssetLineage` / `fetchAssetQuality` → 传入 `AssetCard` props（selected / lineage / quality / subscriptions）

**Checkpoint**: US1 独立可用 = MVP——浏览器打开资产目录即可看到卡片网格 + toolbar 筛选 + 三个标准状态

---

## Phase 4: User Story 2 — 资产详情内联展开 (Priority: P1)

**Goal**: 点击卡片原地展开详情（描述/血缘/质量/操作），无需侧面板或 Dialog。

**Independent Test**: 点击卡片 → 动画展开详情 → 看到描述/血缘/质量/操作按钮 → 再点击收起。

**Note**: 核心展开 UI 已在 T006-T008 实现。本阶段仅需连接数据和回调。

### Implementation for User Story 2

- [ ] T016 [US2] 在 `asset-catalog-view.tsx` 中将 `AssetCard` 连接详情数据：`openDetail` 返回的 `selected` / `lineage` / `quality` / `subscriptions` 作为 props 传入当前展开的卡片（`expandedAsset` / `expandedLineage` / `expandedQuality` / `expandedSubscriptions`）；卡片内部渲染描述/血缘/质量/元数据标签
- [ ] T017 [US2] 在 `asset-catalog-view.tsx` 中连接操作回调：编辑 → `setEditOpen(true)`（复用既有 AssetFormDialog）；下线 → `setConfirm("retire")`（复用既有 ConfirmDialog）；对账 → `setConfirm("reconcile")`（复用既有 ConfirmDialog）；订阅/退订 → 复用既有 `subscribe` / `unsubscribe` + resolveGate + sonner toast
- [ ] T018 [US2] 实现详情面板数据缺失降级（已在 T008 实现 UI 层）：在 `asset-catalog-view.tsx` 中确保 `lineage === null` 或 `quality === null` 时，AssetCard 内部对应行隐藏（非错误占位）。STALE 状态显示 warning 提示条
- [ ] T019 [US2] 实现订阅态内联展示：`findAssetSubscription(subscriptions, selected.id)` → 已订阅显示 `t("subscribed")` Badge + 退订按钮；未订阅显示订阅按钮

**Checkpoint**: US1 + US2 各自独立可用。点击卡片 → 展开详情 → 执行操作 → 反馈完整

---

## Phase 5: User Story 3 — 资产编目与编辑 (Priority: P2)

**Goal**: 编目与编辑 Dialog 对齐项目表单规范，提交按钮 loading 防重复。

**Independent Test**: 点"编目资产"→填表→提交→卡片网格刷新新资产；点编辑→改字段→提交→卡片详情刷新。

**Note**: 编目/编辑 Dialog 已在 029 实现（`asset-form-dialog.tsx`）。本阶段仅微调。

### Implementation for User Story 3

- [ ] T020 [US3] 在 `frontend/components/workspace/views/asset/asset-form-dialog.tsx` 的提交按钮添加 loading 态：`useState(busy)` + button `disabled={busy}` + spinner 图标（使用项目中既有的 LoadingState 或纯 CSS spinner）；防止重复提交（FR-022）
- [ ] T021 [US3] 在 `asset-form-dialog.tsx` 中审查并补全 i18n 覆盖：确保所有 label / placeholder / 错误提示均经 `t()` 获取；零硬编码中英文字符串
- [ ] T022 [US3] 在 `asset-catalog-view.tsx` 中将 "编目资产" 按钮从列表头移至 toolbar 右侧（AssetFilterToolbar 的 rightSlot 或并列），点击 → `setCreateOpen(true)`

**Checkpoint**: US1–US3 各自独立可用。编目/编辑流程完整

---

## Phase 6: User Story 4 — 订阅管理 (Priority: P3)

**Goal**: "我的订阅"面板展示已订阅资产，支持退订。

**Independent Test**: 点"我的订阅"→弹出 Dialog→列表展示→点击退订→确认→刷新。

**Note**: 订阅 Dialog 已在 029 实现（`subscriptions-dialog.tsx`）。本阶段仅微调 + i18n。

### Implementation for User Story 4

- [ ] T023 [US4] 在 `frontend/components/workspace/views/asset/subscriptions-dialog.tsx` 中审查并补全 i18n 覆盖：确保所有文本经 `t()` 获取
- [ ] T024 [US4] 在 `subscriptions-dialog.tsx` 中处理空态：当 `subscriptions.length === 0` 时显示空态（图标 + `t("noSubscriptions")`）
- [ ] T025 [US4] 在 `asset-catalog-view.tsx` 中将 "我的订阅" 按钮从列表头移至 toolbar（与 "编目资产" 并列），点击 → `setSubsOpen(true)`

**Checkpoint**: 全部用户故事独立可用

---

## Phase 7: Polish & 验收

**Purpose**: 门禁、浏览器手验、i18n 校验。

- [ ] T026 运行 `cd frontend && pnpm typecheck`：零错误
- [ ] T027 运行 `cd frontend && pnpm test`：全绿（与 T001 基线一致，无新增失败）
- [ ] T028 运行 `cd frontend && pnpm design:lint`：零错误零警告
- [ ] T029 运行 `cd frontend && node scripts/check-i18n.mjs`：zh-CN/en-US key 集一致
- [ ] T030 浏览器按 `quickstart.md` 10 步手验完整闭环，暗色/亮色双主题各一遍。记录任何差异并修到通过

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies - T001 → T002、T003 并行
- **Phase 2 (Foundational)**: Depends on Phase 1 T002/T003 i18n keys — **BLOCKS all user stories**
- **Phase 3 (US1)**: Depends on Phase 2 T004-T008
- **Phase 4 (US2)**: Depends on Phase 2 T006-T008 + Phase 3 T009-T010（卡片已渲染）
- **Phase 5 (US3)**: Depends on Phase 2 — 可与 US2 并行（不同文件：asset-form-dialog.tsx vs asset-card.tsx）
- **Phase 6 (US4)**: Depends on Phase 2 T004 — 可与 US2/US3 并行（不同文件：subscriptions-dialog.tsx）
- **Phase 7 (Polish)**: Depends on all user stories

### User Story Dependencies

- **US1 (P1)**: Card grid + filter toolbar — 阻塞 US2（US2 需要卡片渲染后才能测试展开）
- **US2 (P1)**: Card expand detail — 可与 US3/US4 并行（不同文件和关注点）
- **US3 (P2)**: Form polish — 可与 US2/US4 并行（仅改 asset-form-dialog.tsx）
- **US4 (P3)**: Subscriptions — 可与 US2/US3 并行（仅改 subscriptions-dialog.tsx）

### Parallel Opportunities

- Phase 1: T002 + T003 并行（不同文件：zh-CN.json / en-US.json）
- Phase 2: T004 + T005 并行（不同文件：asset-card.tsx / asset-filter-toolbar.tsx）
- Phase 3 (US1): T009-T015 为同一文件 `asset-catalog-view.tsx`，需顺序执行
- US3 + US4 可与 US2 并行（不同文件）

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T008)
3. Complete Phase 3: US1 (T009-T015)
4. **STOP and VALIDATE**: 浏览器打开资产目录 → 卡片网格 + toolbar 筛选 + loading/empty/error 三态
5. 此时 MVP 即可用：浏览、筛选、搜索、三个标准状态

### Incremental Delivery

1. US1 → 卡片网格 MVP（浏览+筛选）
2. US2 → 点击卡片展开详情+操作（核心交互闭环）
3. US3 → 编目编辑表单 polish
4. US4 → 订阅管理 polish
5. Polish → 门禁 + 浏览器手验

### Suggested Execution Order

```
T001 → [T002, T003] → [T004, T005] → T006 → T007 → T008 → T009→T010→T011→T012→T013→T014→T015 → T016→T017→T018→T019 → [T020, T023] → T021→T022 → T024→T025 → T026→T027→T028→T029→T030
```

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each phase or logical group
- Stop at any checkpoint to validate story independently
- 资产 Form Dialog 和 Subscriptions Dialog 已在 029 实现，043 仅微调不重写
- 所有 lib 文件（catalog-api.ts / gate-outcome.ts / asset-patch.ts / asset-search-query.ts / subscriptions.ts）保持不变，零改动
