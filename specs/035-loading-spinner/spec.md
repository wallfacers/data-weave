# Feature Specification: 加载状态统一转圈动画

**Feature Branch**: `035-loading-spinner`

**Created**: 2026-07-01

**Status**: Draft

**Input**: User description: "项目中的加载中文字改为转圈动画，并且必须加载的页面组件内，居中对齐，你找下shadcn/ui的组件是否有实现了"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 页面首次加载看到统一转圈动画 (Priority: P1)

用户打开任何需要等待数据加载的页面或视图时，不再看到孤零零的"加载中"文字，而是看到一个带旋转动画的图标配合加载文字，整体在视图区域内居中显示。动画为用户提供明确的视觉反馈，减少"是不是卡死了"的困惑。

**Why this priority**: 这是最核心的体验改进——覆盖所有视图的首次加载场景，直接影响用户对平台响应性的感知。

**Independent Test**: 打开任意需要异步加载数据的视图（如运维中心、资产目录、指标市场等），在数据返回前应看到居中旋转动画而非纯文字。

**Acceptance Scenarios**:

1. **Given** 用户打开一个需要加载数据的视图，**When** 数据尚在请求中，**Then** 视图中央显示旋转图标动画 + "加载中"文字，整体居中
2. **Given** 用户打开一个有缓存/旧数据的视图，**When** 后台正在刷新数据，**Then** 旧数据上方显示半透明遮罩 + 居中旋转图标（维持现有 detail-panel-shell 的体验）
3. **Given** 页面正在加载，**When** 数据在极短时间内（<200ms）返回，**Then** 旋转动画不闪烁（始终显示至少 600ms，避免一闪而过）

---

### User Story 2 - 表格/列表区域加载时显示动画 (Priority: P2)

用户在 DataTable、列表、下拉选项等局部区域等待数据时，同样看到旋转动画而非纯文字。这些区域保持各自现有的布局约束（如表格内的居中占位），仅将文字替换为动画图标。

**Why this priority**: 局部加载覆盖了数据表格、搜索下拉等高频交互，统一此处体验可消除剩余的纯文字加载状态。

**Independent Test**: 在 DataTable 翻页或筛选时，表格区域应显示旋转图标而非纯文字"加载中"。

**Acceptance Scenarios**:

1. **Given** 用户在 DataTable 中翻页或筛选，**When** 下一页数据尚在请求中，**Then** 表格内容区域显示居中旋转动画（有数据时覆盖层，无数据时居中占位）
2. **Given** 用户打开代码编辑器，**When** Monaco 编辑器尚在加载，**Then** 编辑器区域显示居中旋转动画

---

### User Story 3 - 刷新按钮与加载状态区分 (Priority: P3)

用户在视图右上角点击刷新按钮时，刷新按钮自身变为旋转图标表示"刷新中"，与页面内容区的加载动画形成视觉层次区分——按钮动画表示主动操作，内容区动画表示被动等待。

**Why this priority**: 这是已有 `view-refresh-control` 的润色，确保新统一 spinner 不与此冲突，两者语义清晰。

**Independent Test**: 点击刷新按钮后，按钮图标旋转，同时页面内容区也显示加载动画，两者视觉可区分。

**Acceptance Scenarios**:

1. **Given** 用户点击视图的刷新按钮，**When** 刷新请求进行中，**Then** 刷新按钮图标旋转（已有行为），页面内容区同时显示加载覆盖层
2. **Given** 自动轮询刷新触发，**When** 数据更新中，**Then** 仅内容区显示加载，刷新按钮保持静止

---

### Edge Cases

- 加载时间极短（<100ms）时，spinner 不应闪烁；通过现有的 `useMinSpin` hook（min 1000ms）保证旋转动画至少可见一段时间
- 加载超时或网络错误时，应从加载动画切换到错误提示（已有 `ViewStatus` 的 connectError 状态），不永久停留于加载状态
- 多个独立数据源同时加载时（如同一页面内图表区和表格区各有独立数据），各自独立显示加载动画，不互相阻塞
- 视图切换时（Tab 切换），旧视图的加载状态应立即清除，新视图显示自己的加载动画
- 全屏/大屏场景下，居中旋转动画应始终在可视区域内，字体和图标大小保持适中不随屏幕缩放失调

## Requirements *(mandatory)*

### 技术背景：shadcn/ui 无内置 Spinner 组件

经过代码库调研确认：**shadcn/ui 是一个 headless 组件库，不提供 spinner/loading 动画组件**。项目已选择使用 `@hugeicons/core-free-icons` 的 `RefreshIcon` 配合 Tailwind `animate-spin` 作为旋转动画方案，且已存在一个未使用的共享组件 `LoadingState`（`components/workspace/shared/loading-state.tsx`）和最小旋转时间 hook `useMinSpin`（`hooks/use-min-spin.ts`）。本功能的核心是**统一应用已有的动画模式**，而非引入新依赖。

### Functional Requirements

- **FR-001**: 系统 MUST 在所有视图首次加载（无缓存数据）时，于视图区域内居中显示旋转图标 + 加载文字，替代现有纯文字"加载中"
- **FR-002**: 系统 MUST 在所有视图有旧数据后台刷新时，显示半透明遮罩 + 居中旋转动画覆盖层，替代纯文字状态指示
- **FR-003**: 系统 MUST 在所有 DataTable 加载（翻页、筛选、初始加载）时显示旋转动画，替代纯文字"加载中"
- **FR-004**: 系统 MUST 在所有局部加载区域（代码编辑器加载、下拉选项加载、对话框数据加载）中显示旋转动画，替代纯文字
- **FR-005**: 旋转动画 MUST 使用项目已有的 `RefreshIcon` + Tailwind `animate-spin` 样式，保持与现有设计系统一致
- **FR-006**: 旋转动画 MUST 有最小显示时长（≥600ms），避免快速加载时视觉闪烁（复用已有 `useMinSpin` hook）
- **FR-007**: 加载动画 MUST 在加载完成、加载失败或组件卸载时正确停止/清理，不残留旋转状态
- **FR-008**: 项目中已有的 `LoadingState` 共享组件（`components/workspace/shared/loading-state.tsx`）MUST 被正式采用或重构为统一的加载组件，所有视图通过该组件展示加载状态
- **FR-009**: 旋转图标与"加载中"文字 MUST 保持合理的间距（gap），整体在容器内水平和垂直居中
- **FR-010**: 加载动画区域 MUST 具有合适的尺寸（图标 ~20-24px，文字 ~14px），确保在各种容器中视觉比例协调

### Key Entities

- **LoadingState 组件**: 统一的加载状态展示单元，包含旋转图标 + 文字描述 + 居中布局。支持两种模式：全区域居中占位（无数据时）和半透明覆盖层（有旧数据时）。
- **useMinSpin hook**: 已有的最小旋转时间控制逻辑，确保 spinner 至少可见 `minMs` 毫秒（当前默认 1000ms），避免快速完成导致的闪烁。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 项目中所有用户可见的"加载中"纯文字状态（12 处 i18n 键使用点）全部替换为旋转动画 + 文字组合
- **SC-002**: 用户在任何需要加载的页面组件中，看到居中旋转动画而非静态文字
- **SC-003**: 加载动画的视觉一致性：所有页面使用相同的图标、动画效果、间距和居中方式
- **SC-004**: 快速加载（<500ms 数据返回）时不出现闪烁——spinner 有最小显示时长保障
- **SC-005**: 现有功能无回归：DataTable 分页/筛选、视图 Tab 切换、刷新按钮、详情面板覆盖层等行为保持正常
- **SC-006**: 用户首次看到页面加载时，能明确感知"系统正在工作"（旋转动画提供持续视觉反馈），消除"是否卡死"的疑虑

## Assumptions

- 项目继续使用 `@hugeicons/core-free-icons` 的 `RefreshIcon` 作为旋转图标，不引入新的图标库或依赖
- 已有的 `useMinSpin` hook 满足最小显示时长需求，无需重新实现
- "居中"指的是在父容器内水平和垂直居中，不一定要求整个视口居中（取决于具体组件的容器范围）
- 本功能仅涉及加载状态的展示层，不改变数据获取逻辑（`useApi`、`useLiveData`、DataTable 的 loading 状态管理不变）
- i18n 翻译键保持现有结构，仅替换使用处的渲染方式（文本 → 组件），翻译内容不变
- 极短加载（<200ms）的闪烁避免阈值可以通过 `useMinSpin` 的 `minMs` 参数调整，默认沿用 1000ms
