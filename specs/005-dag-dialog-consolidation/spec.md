# Feature Specification: DAG Dialog Consolidation

**Feature Branch**: `005-dag-dialog-consolidation`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "历史记录里面，酷酷的让任务流实例对其周期任务流的DAG展示，这里为什么不把两个DAG的绚烂抽取为一个公共组件呢，只是任务流实例多了节点状态，实例流的状态而已，你评估下聚合掉"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 统一 DAG 弹窗组件 (Priority: P1)

运维人员在周期任务流面板点击"查看 DAG"按钮，看到 DAG 弹窗展示该工作流已发布版本的拓扑结构。点击 DAG 节点可以在右侧面板查看该任务的配置信息（代码、参数等）。弹窗支持面板拖拽调整宽度。

同时在任务流实例面板点击"查看 DAG"按钮，看到同一个风格的 DAG 弹窗，但节点上叠加了运行时状态（成功/失败/运行中等颜色标识），点击节点在右侧面板查看该次实例运行的实际代码和实际配置。

两个场景使用同一个 DAG 弹窗组件，只是数据来源和侧面板内容不同。

**Why this priority**: 消除重复代码是本次重构的核心目标。两个 DAG 弹窗在 Dialog 外壳、Header/Footer 布局、面板拖拽、DAG 渲染等方面完全重复，差异仅在于数据获取和侧面板内容。

**Independent Test**: 在周期任务流面板打开 DAG 弹窗，验证拓扑展示、节点点击面板、面板拖拽均正常；在任务流实例面板打开 DAG 弹窗，验证节点运行时状态颜色、高亮、侧面板实际代码/配置展示均正常。两个场景互不影响。

**Acceptance Scenarios**:

1. **Given** 用户在周期任务流面板，**When** 点击某工作流的"查看 DAG"按钮，**Then** 打开 DAG 弹窗，展示已发布版本的 DAG 拓扑，节点无运行时状态标识
2. **Given** 周期 DAG 弹窗已打开，**When** 点击某个 TASK 节点，**Then** 右侧滑出详情面板，展示该任务的配置信息（名称、类型、代码、参数）
3. **Given** 用户在任务流实例面板，**When** 点击某实例的"查看 DAG"按钮，**Then** 打开 DAG 弹窗，展示实例 DAG 拓扑，节点显示运行时状态颜色
4. **Given** 实例 DAG 弹窗已打开，**When** 点击某个 TASK 节点，**Then** 右侧滑出详情面板，展示该实例的实际代码和实际配置（参数解析后的结果）
5. **Given** 任意 DAG 弹窗已打开且右侧面板可见，**When** 拖拽分割线，**Then** 面板宽度实时变化，松手后宽度持久化（两个场景独立记忆）
6. **Given** 从任务实例列表进入实例 DAG 弹窗，**When** 弹窗打开，**Then** 对应任务实例的节点被高亮边框标记

---

### User Story 2 - 侧面板组件统一 (Priority: P2)

周期 DAG 的节点详情面板（NodeDetailPanel）和实例 DAG 的侧面板（InstanceDetailSidePanel）在视觉结构上高度一致：相同的 Header（标题 + 关闭按钮）、相同的 DwScroll 容器、相同的 section 分段布局（标题 uppercase tracking-wide + 内容容器）。差异仅在于展示的具体字段（设计态配置 vs 运行时实际代码/配置）。

将两个侧面板的共享 UI 结构抽取为公共的"详情面板外壳"组件，两个面板各自填充具体内容。

**Why this priority**: 侧面板是弹窗内部最大的子组件，虽然两个面板展示的数据不同，但 UI 骨架完全一致。抽取后减少维护负担，新增面板类型也更快捷。

**Independent Test**: 在周期 DAG 弹窗中点击节点，面板展示设计态配置；在实例 DAG 弹窗中点击节点，面板展示运行时实际代码/配置。两个面板的 Header、滚动行为、关闭交互完全一致。

**Acceptance Scenarios**:

1. **Given** 周期 DAG 弹窗打开并选中节点，**When** 查看右侧面板，**Then** 面板 Header 显示标题和关闭按钮，Body 使用 DwScroll 滚动，section 标题为 uppercase tracking-wide 风格
2. **Given** 实例 DAG 弹窗打开并选中节点，**When** 查看右侧面板，**Then** 面板 Header 显示节点名称和关闭按钮，Body 结构与周期面板一致
3. **Given** 任意面板正在加载数据，**When** 已有旧数据展示，**Then** 半透明遮罩覆盖在旧内容上，不出现宽度闪烁

---

### Edge Cases

- 弹窗打开时后端返回空 DAG（无节点）：展示空状态提示，不渲染 ReactFlow
- 弹窗打开时后端返回错误：展示错误提示 + 重试按钮
- 快速切换选中节点：取消上一次 API 请求，避免数据竞态
- 面板宽度小于最小值时：夹在 PANEL_MIN_WIDTH，不塌陷
- 弹窗关闭后重新打开：面板状态重置，无上次选中节点残留
- 实例 DAG 中所有节点都在终态（全部成功/失败/跳过）：DAG 正常渲染，无特殊处理

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统必须提供一个公共 DAG 弹窗组件，同时服务于周期任务流 DAG 查看和任务流实例 DAG 查看两个场景
- **FR-002**: 公共组件必须接受外部传入的数据获取策略（fetch DagView 或 fetch InstanceDagView），不内置具体 API 调用
- **FR-003**: 公共组件必须接受外部传入的侧面板渲染函数/组件，支持周期配置面板和实例详情面板两种内容
- **FR-004**: 公共组件必须包含：Dialog 外壳（90vw x 90vh）、Header（标题行 + 副标题行）、Body（左侧 DAG 区域 + 右侧可拖拽面板）、Footer（版本信息 + 关闭按钮）
- **FR-005**: 面板拖拽宽度必须独立持久化：周期 DAG 和实例 DAG 使用不同的 localStorage key
- **FR-006**: DagViewerDialog 重构后必须保持右键上下文菜单功能（仅设计态 DAG 有此需求）
- **FR-007**: InstanceDagDialog 重构后必须保持节点高亮功能（从任务实例列表跳转时高亮目标节点）
- **FR-008**: 侧面板共享 UI 骨架（Header + DwScroll + section 布局）必须抽取为公共组件，两个面板各自注入内容
- **FR-009**: 重构后两个 DAG 弹窗的行为必须与重构前完全一致（用户无感知变化）
- **FR-010**: 删除 InstanceDagDialog 中与 DagViewerDialog 重复的 calcPanelWidth、useMotionValue、onPanelResizeDown 等面板拖拽代码
- **FR-011**: 删除 InstanceDetailSidePanel 中与 NodeDetailPanel 重复的 LoadingState、ErrorState、CodeBlock、ParamsTable 等子组件（移到共享模块）

### Key Entities

- **DagDialog（公共弹窗）**: 统一的 DAG 弹窗外壳，参数化数据源、侧面板、标题信息。封装 Dialog 布局、面板拖拽、DAG 渲染、状态展示（loading/empty/error）
- **DetailPanelShell（侧面板外壳）**: 统一的详情面板骨架，包含 Header（标题 + 关闭按钮）和 Body（DwScroll + 内容区）。接受 children 或 render prop 注入具体内容
- **Shared UI 子组件**: LoadingState、ErrorState、CodeBlock、ParamsTable — 从 NodeDetailPanel 和 InstanceDetailSidePanel 中提升为共享组件

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `instance-dag-dialog.tsx` 文件行数从 ~260 行减少到 ~60 行以内（仅保留实例特有的数据获取和节点交互逻辑）
- **SC-002**: 两个侧面板文件（`node-detail-panel.tsx` 和 `instance-detail-side-panel.tsx`）中不再包含重复的子组件定义（LoadingState、ErrorState、CodeBlock、ParamsTable）
- **SC-003**: 重构后 `pnpm typecheck` 零错误，周期 DAG 弹窗和实例 DAG 弹窗在浏览器中行为与重构前一致
- **SC-004**: 面板拖拽宽度独立记忆——周期 DAG 的面板宽度变化不影响实例 DAG 的面板宽度，反之亦然
- **SC-005**: 共享组件（DagDialog、DetailPanelShell、共享 UI 子组件）可被未来的第三个 DAG 弹窗场景复用，无需复制粘贴

## Assumptions

- 两个 DAG 弹窗将继续使用相同的 Dialog 尺寸（90vw x 90vh）和布局结构
- 周期 DAG 和实例 DAG 的节点类型（TASK/VIRTUAL）和渲染组件（TaskNode/VirtualNode）保持不变
- 右侧面板的拖拽交互逻辑（PointerEvent + useMotionValue）已在两个弹窗中验证稳定，直接提升为公共实现
- 右键上下文菜单仅设计态 DAG 需要，实例 DAG 暂不需要（实例节点不支持右键"查看任务详情"跳转到设计态）
- 侧面板的 Shiki 代码高亮为共享能力，已在 `@/components/chat/highlighter` 中统一提供
- 重构不涉及后端 API 变更，仅前端组件层面合并
