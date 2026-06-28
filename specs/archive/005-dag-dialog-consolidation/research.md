# Research: DAG Dialog Consolidation

**Feature**: 005-dag-dialog-consolidation
**Date**: 2026-06-26

## Decision 1: 公共弹窗组件的数据获取策略

**Decision**: 数据获取保留在包装组件中，公共 `DagDialog` 只接受已获取的数据（`dagData`, `loading`, `error`, `onRetry` props）。

**Rationale**: 
- 两个弹窗的数据源完全不同（`GET /api/workflows/{id}/published-dag` vs `GET /api/ops/workflow-instances/{id}/dag`），强行统一 fetch 逻辑会引入不必要的抽象层
- InstanceDagDialog 使用 `useInstanceDag` hook 封装了 fetch + SSE + abort 逻辑，这是实例特有的需求
- DagViewerDialog 直接在组件内 fetch，依赖 `workflowId` 变化触发
- 将数据获取与 UI 渲染分离符合"容器组件 vs 展示组件"模式，每个包装组件 ~60 行足够容纳其特有的数据获取逻辑

**Alternatives considered**:
- 传入 `fetchDag: () => Promise<DagData>` 函数：增加泛型复杂度，且无法由公共组件控制 loading/error 状态转换
- 使用 render props 模式：过度工程化，两个场景差异不够大

## Decision 2: 侧面板外壳的设计

**Decision**: 抽取 `DetailPanelShell` 组件，封装 Header（标题 + 关闭按钮）+ Body（DwScroll + 内容区）+ loading 半透明遮罩逻辑。两个侧面板通过 `children` prop 注入具体内容。

**Rationale**:
- 两个面板的 Header 结构 100% 相同（标题 truncate + Cancel01Icon 关闭按钮 + border-b 分割线）
- Body 的 DwScroll + gap-4 padding 布局 100% 相同
- loading 时的半透明遮罩逻辑 100% 相同（InstanceDetailSidePanel 最近刚修复了闪烁问题，DagViewerDialog 的 NodeDetailPanel 没有这个优化）
- 通过 `children` 注入内容是最简单的 React 组合模式，无需额外的 render prop 类型体操

**Alternatives considered**:
- 不抽取侧面板外壳，只抽取共享子组件：面板 Header/Body 骨架仍会重复 ~30 行
- 使用 slot 模式：next.js 不支持，需要用 props 模拟

## Decision 3: 共享 UI 子组件的位置

**Decision**: 放在 `frontend/components/workspace/shared/` 目录下，每个子组件一个文件。`taskTypeToLang` 工具函数和 `InfoRow` 放入 `params-table.tsx`（与参数展示密切相关）。

**Rationale**:
- 这些子组件当前只被 DAG 相关面板使用，放在 workspace 下语义清晰
- 如果未来其他模块需要（如 catalog 面板），可以进一步提升到 `components/ui/` 或 `components/shared/`
- 每个组件一个文件符合项目惯例
- `InfoRow` 过于简单（仅 6 行），不值得独立文件；与 ParamsTable 放一起合理

**Alternatives considered**:
- 直接放 `components/ui/`：这些组件不是通用 UI 组件，是业务相关的展示组件
- 合并到单一 `shared.tsx` 文件：不利于按需导入和 tree-shaking

## Decision 4: 面板宽度 localStorage key 策略

**Decision**: 公共 `DagDialog` 接受 `panelStorageKey` prop，两个包装组件分别传入 `"dw.dagViewer.panelWidth"` 和 `"dw.instanceDag.panelWidth"`。

**Rationale**:
- 用户对周期 DAG 和实例 DAG 的面板宽度偏好可能不同（实例面板内容更多，可能需要更宽）
- 保持独立 key 不影响现有用户的 localStorage 数据（向后兼容）
- 公共组件不需要知道 key 的具体值，只是透传

**Alternatives considered**:
- 统一 key：失去独立偏好，违反 FR-005
- 由公共组件自动推断：增加不必要的复杂度

## Decision 5: 状态管理（zustand store vs local state）

**Decision**: 保持现状不变。DagViewerDialog 继续使用 `useNodeDetailStore`（全局 store），InstanceDagDialog 继续使用 local `useState`。

**Rationale**:
- `useNodeDetailStore` 是 DagViewerDialog 特有的——它关联 `workflowId` 并调用 `GET /api/ops/workflows/{id}/nodes/{key}/detail` 端点
- InstanceDagDialog 的节点点击触发 `GET /api/ops/task-instances/{id}/resolved-{code,config}` 两个端点，数据结构完全不同
- 强行统一 store 会增加不必要的抽象，收益为零
- 两种状态管理方式通过 `onNodeClick` 回调参数化即可在公共 DagDialog 中工作

**Alternatives considered**:
- 统一为 zustand store：两个 store 数据结构不同，统一只会增加复杂度
- 统一为 local state：DagViewerDialog 需要失去 store 的优势（如面板状态在弹窗关闭/重开时保留）
