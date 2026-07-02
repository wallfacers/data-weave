# Tasks: 加载状态统一转圈动画

**Input**: Design documents from `/specs/035-loading-spinner/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md

**Tests**: 不生成测试任务——本功能为纯 UI 润色，验证依赖浏览器实跑确认。

**Organization**: 任务按用户故事分组，支持独立实现和验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件，无依赖）
- **[Story]**: 所属用户故事（US1, US2, US3）
- 描述中包含精确文件路径

## Path Conventions

- 所有路径相对于 `frontend/`

---

## Phase 1: Foundational（基础组件改造）

**Purpose**: 改造 `LoadingState` 为带 props + 无障碍的规范组件，这是所有用户故事的前置依赖

**⚠️ CRITICAL**: 必须先完成此阶段，才能开始任何用户故事

- [x] T001 改造 LoadingState 组件，增加 `text`/`variant`/`fullHeight` props、集成 `useMinSpin`、添加 `motion-safe:animate-spin` + `role="status"` + `aria-label` 无障碍支持，在 `frontend/components/workspace/shared/loading-state.tsx`
- [x] T002 运行 `cd frontend && pnpm typecheck`，确认 LoadingState 编译通过零错误

**Checkpoint**: 基础组件就绪——可以开始替换各视图的加载状态

---

## Phase 2: User Story 1 - 页面首次加载看到统一转圈动画 (Priority: P1) 🎯 MVP

**Goal**: 所有视图首次加载（无缓存数据）时显示居中旋转动画 + 文字，替代纯文字"加载中"

**Independent Test**: 打开任意需要异步加载数据的视图（运维中心、资产目录、指标市场等），在数据返回前应看到居中旋转动画而非纯文字

### 策略 A：全文替换（无数据时全区域居中 LoadingState）

- [x] T003 [P] [US1] 替换 app-shell 的纯文字加载为 `<LoadingState />`，在 `frontend/components/app-shell.tsx`（第 35-39 行）
- [x] T004 [US1] 替换 ViewStatus 内部纯文字为 `<LoadingState />`，间接修复 fleet/metrics/reports/workflow-canvas 四个视图，在 `frontend/components/workspace/views/view-status.tsx`（第 8-16 行）
- [x] T005 [P] [US1] 替换 alerts-view 的纯文字加载为 `<LoadingState />`，在 `frontend/components/workspace/views/alerts-view.tsx`（第 170 行）
- [x] T006 [P] [US1] 替换 lineage-view 的纯文字加载为居中 `<LoadingState />`，在 `frontend/components/workspace/views/lineage-view.tsx`（第 131-134 行）
- [x] T007 [P] [US1] 替换 workflow-instance-detail 的纯文字加载为 `<LoadingState />`，在 `frontend/components/workspace/views/workflow-instance-detail.tsx`（第 162-167 行）
- [x] T008 [P] [US1] 替换 quality-view 的纯文字加载为 `<LoadingState />`，在 `frontend/components/workspace/views/quality-view.tsx`（第 216-218 行）
- [x] T009 [P] [US1] 替换 task-editor-pane 的纯文字加载为 `<LoadingState />`，在 `frontend/components/workspace/task-editor-pane.tsx`（第 510-514 行）
- [x] T010 [P] [US1] 替换 workflow-config-panel 的内联纯文字加载为小尺寸 spinner（策略 E：`size-3.5` 图标 + `text-xs` 文字），在 `frontend/components/workspace/workflow-config-panel.tsx`（第 190-192 行）
- [x] T011 [P] [US1] 替换 code-editor 的纯文字加载为 `<LoadingState />`，在 `frontend/components/code-editor.tsx`（第 138-142 行）

### 策略 C：DataTable 表格内居中

- [x] T012 [US1] 替换 DataTable 的纯文字加载为 `<LoadingState />`（保留外层 `loading && !result` 条件判断），在 `frontend/components/ui/data-table.tsx`（第 243-246 行）

**Checkpoint**: US1 完成——所有视图首次加载均显示旋转动画。`pnpm typecheck` 通过后浏览器逐个打开各视图验证。

---

## Phase 3: User Story 2 - 表格/列表区域加载时显示动画 (Priority: P2)

**Goal**: DataTable、下拉选项、对话框等局部区域的纯文字加载替换为 spinner 动画

**Independent Test**: 在 DataTable 翻页或筛选时，或在对话框中加载数据时，应看到旋转图标而非纯文字

### 策略 D：覆盖层模式（有旧数据时半透明遮罩）

- [x] T013 [US2] 重构 detail-panel-shell 两处内联 spinner 为 `<LoadingState variant="overlay" />`（loading+hasData）和 `<LoadingState />`（loading+!hasData），移除手动 RefreshIcon 导入，在 `frontend/components/workspace/detail-panel-shell.tsx`（第 52-66 行）
- [x] T014 [P] [US2] 重构 dag-dialog 的内联 spinner + 文字为 `<LoadingState variant="overlay" />`，在 `frontend/components/workspace/dag-dialog.tsx`（第 207-211 行）

### 策略 E：内联小图标 + 文字

- [x] T015 [P] [US2] 替换 asset-catalog-view 总数标签中的纯文字加载为内联小 spinner（`size-3.5` 图标 + `text-sm` 文字），在 `frontend/components/workspace/views/asset-catalog-view.tsx`（第 295 行）
- [x] T016 [P] [US2] 替换 metric-marketplace-view 总数标签中的纯文字加载为内联小 spinner，在 `frontend/components/workspace/views/metric-marketplace-view.tsx`（第 154 行）
- [x] T017 [P] [US2] 替换 subscriptions-dialog 的纯文字加载为 `<LoadingState />`，在 `frontend/components/workspace/views/asset/subscriptions-dialog.tsx`（第 69 行）

### 局部区域加载

- [x] T018 [P] [US2] 替换 lineage-tree 的纯文字加载为 `<LoadingState />`（保留节点的 ⟳ spinner 不变——那是每个树节点独立的加载指示器），在 `frontend/components/workspace/views/lineage/lineage-tree.tsx`（第 132 行）

**Checkpoint**: US2 完成——所有局部加载区域也使用统一 spinner。`pnpm typecheck` 通过。

---

## Phase 4: User Story 3 - 刷新按钮与加载状态区分 (Priority: P3)

**Goal**: 确认刷新按钮动画与内容区加载动画视觉可区分，两者语义不重叠

**Independent Test**: 点击刷新按钮后按钮图标旋转，同时页面内容区也显示加载动画（覆盖层模式），两者可区分

- [x] T019 [US3] 验证 view-refresh-control 中 `useMinSpin` + `animate-spin` 行为与 LoadingState 独立不冲突（无需代码改动，确认两套 spinner 语义独立），在 `frontend/components/workspace/views/view-refresh-control.tsx`
- [x] T020 [US3] 验证 event-center-view 刷新按钮的 `useMinSpin` 行为与新 LoadingState 共存正常，在 `frontend/components/workspace/views/event-center-view.tsx`

**Checkpoint**: US3 完成——刷新按钮和内容区加载层次清晰。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 类型检查、浏览器验证、清理

- [x] T021 运行 `cd frontend && pnpm typecheck`，确认全项目零类型错误
- [x] T022 清理 detail-panel-shell 和 dag-dialog 中不再需要的 `RefreshIcon` 直接导入（已由 LoadingState 内部处理）
- [x] T023 浏览器验证：逐一打开各视图 Tab（运维中心、资产目录、指标市场、数据质量、告警、血缘、工作流画布），确认加载状态均显示旋转动画 + 居中文字
- [x] T024 浏览器验证：系统"减少动画"偏好开启时确认显示静态图标、屏幕阅读器可识别加载状态
- [x] T025 浏览器验证：DataTable 翻页/筛选时确认 loading spinner 正常显示，有数据时保留旧数据显示覆盖层

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: 无依赖——直接开始。**BLOCKS 所有用户故事**
- **US1 (Phase 2)**: 依赖 Phase 1 完成
- **US2 (Phase 3)**: 依赖 Phase 1 完成，可与 US1 并行（不同文件）
- **US3 (Phase 4)**: 依赖 Phase 1 完成，可与 US1/US2 并行
- **Polish (Phase 5)**: 依赖所有用户故事完成

### User Story Dependencies

- **US1 (P1)**: 依赖 Foundational —— 无其他故事依赖
- **US2 (P2)**: 依赖 Foundational —— 独立于 US1，可并行
- **US3 (P3)**: 依赖 Foundational —— 独立于 US1/US2，可并行（主要是验证，无实际代码改动）

### Within Each User Story

- 标记 [P] 的任务可并行执行（不同文件，互不冲突）
- 未标记 [P] 的任务需顺序执行（如同文件内多处改动）

### Parallel Opportunities

- Phase 2 中 T003–T011（除 T004）全部标记 [P]，可并行执行
- Phase 3 中 T014–T018 全部标记 [P]，可并行执行
- US1、US2、US3 在 Phase 1 完成后可并行执行

---

## Parallel Example: User Story 1

```bash
# Phase 1 完成后，以下任务可同时执行：
Task: "T003 替换 app-shell 的纯文字加载"
Task: "T005 替换 alerts-view 的纯文字加载"
Task: "T006 替换 lineage-view 的纯文字加载"
Task: "T007 替换 workflow-instance-detail 的纯文字加载"
Task: "T008 替换 quality-view 的纯文字加载"
Task: "T009 替换 task-editor-pane 的纯文字加载"
Task: "T010 替换 workflow-config-panel 的内联文字加载"
Task: "T011 替换 code-editor 的纯文字加载"

# T004 单独执行（修改 ViewStatus，间接修复 4 个视图）
# T012 单独执行（修改 DataTable，影响面较大需独立验证）
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Foundational（改造 LoadingState）
2. 完成 Phase 2: User Story 1（全文替换 + ViewStatus + DataTable）
3. **STOP and VALIDATE**: `pnpm typecheck` + 浏览器打开各视图确认
4. 此时 ~12 处加载状态已修复，覆盖所有视图首次加载场景

### Incremental Delivery

1. Foundational → 基础组件就绪
2. US1 → 所有全页面加载显示 spinner（MVP!）
3. US2 → 局部区域加载也显示 spinner
4. US3 → 刷新按钮与内容区加载层次清晰
5. Polish → 全量验证

### 建议执行顺序（单人）

由于所有任务都是纯前端文件替换，建议按文件顺序逐个完成，每个文件改完立即保存：
1. T001 → T002（改造 LoadingState + typecheck）
2. T003–T012（US1，按文件列表逐个替换）
3. T013–T018（US2）
4. T019–T020（US3 验证）
5. T021–T025（Polish 全量验证）

---

## Notes

- [P] 任务 = 不同文件，无依赖，可并行
- [Story] 标签将任务映射到具体用户故事，方便追溯
- 每个用户故事可独立完成和验证
- 每次修改后运行 `pnpm typecheck` 确保零错误
- 内联小 spinner 场景（策略 E）图标用 `size-3.5`，与 `text-sm` 文字比例协调
- 策略 D（overlay）需要父容器 `relative` 定位——detail-panel-shell 已有，dag-dialog 需确认
- code-block.tsx 的 "Highlighting…" spinner 保持不变（它是语法高亮的临时状态，非数据加载）
