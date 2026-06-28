# Implementation Plan: DAG Dialog Consolidation

**Branch**: `005-dag-dialog-consolidation` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-dag-dialog-consolidation/spec.md`

## Summary

将 `DagViewerDialog`（周期任务流 DAG）和 `InstanceDagDialog`（任务流实例 DAG）中 ~80% 的重复代码抽取为公共组件。两个弹窗共享相同的 Dialog 外壳、Header/Footer 布局、面板拖拽逻辑、DAG 渲染模式。差异仅在于数据源、侧面板内容、以及实例特有的节点运行时状态和高亮。同时将 `NodeDetailPanel` 和 `InstanceDetailSidePanel` 中重复的 UI 子组件（LoadingState、ErrorState、CodeBlock、ParamsTable）提升为共享模块。

## Technical Context

**Language/Version**: TypeScript 5, React 19, Next.js 16 (App Router)

**Primary Dependencies**: @xyflow/react, motion/react, shadcn/ui, next-intl, hugeicons, Shiki (highlightCode), zustand

**Storage**: localStorage（面板宽度持久化，两个场景独立 key）

**Testing**: vitest + browser verification gate（pnpm typecheck + 浏览器手动验证）

**Target Platform**: Web（Next.js App Router）

**Project Type**: Frontend refactoring — 纯组件层合并，无后端变更

**Performance Goals**: 重构后弹窗打开/节点点击/面板拖拽性能与重构前一致（无退化）

**Constraints**: 必须保持与重构前 100% 一致的用户交互行为；`pnpm typecheck` 零错误

**Scale/Scope**: 涉及 ~6 个文件的合并重组，预计净减少 ~200 行重复代码

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 模板为空（无项目级宪章约束）。本项目遵循 CLAUDE.md 中定义的规则：

- ✅ 前端栈门（Frontend Stack Gate）：不引入新依赖，使用现有 shadcn/ui + motion/react + @xyflow/react
- ✅ 设计契约门（Design Contract Gate）：UI 不变，无需修改 DESIGN.md
- ✅ AG-UI 协议门：不涉及
- ✅ 浏览器验证门（Browser Verification Gate）：重构后必须浏览器实跑验证两个弹窗
- ✅ Post-Edit Verification：每次编辑后 `pnpm typecheck` 零错误

**Gate 结果**: PASS — 无违规，无豁免需求

## Project Structure

### Documentation (this feature)

```text
specs/005-dag-dialog-consolidation/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── components/
│   └── workspace/
│       ├── dag-dialog.tsx              # NEW: 公共 DAG 弹窗组件（从两个弹窗提取）
│       ├── dag-renderer.tsx            # EXISTING: 公共 DAG 渲染器（不变）
│       ├── detail-panel-shell.tsx      # NEW: 公共侧面板外壳（Header + DwScroll 骨架）
│       ├── shared/                     # NEW: 共享 UI 子组件目录
│       │   ├── loading-state.tsx       #   loading 指示器
│       │   ├── error-state.tsx         #   错误 + 重试
│       │   ├── code-block.tsx          #   Shiki 代码高亮块
│       │   ├── params-table.tsx        #   键值对参数表
│       │   └── info-row.tsx            #   单行 label-value
│       ├── dag-viewer-dialog.tsx       # REFACTOR: 薄包装，委托 dag-dialog
│       ├── node-detail-panel.tsx       # REFACTOR: 使用共享子组件 + detail-panel-shell
│       ├── nodes/
│       │   └── task-node.tsx           # EXISTING: TaskNode（已支持 runState）
│       └── views/
│           └── ops/
│               ├── instance-dag-dialog.tsx      # REFACTOR: 薄包装，委托 dag-dialog
│               └── instance-detail-side-panel.tsx # REFACTOR: 使用共享子组件 + detail-panel-shell
└── lib/
    ├── types.ts                        # EXISTING: DagView, InstanceDagView 类型（不变）
    └── workspace/
        ├── dag-helpers.ts              # EXISTING: dagViewToFlow, instanceDagViewToFlow（不变）
        └── node-detail-store.ts        # EXISTING: zustand store（不变）
```

**Structure Decision**: 单项目前端重构。共享组件放在 `components/workspace/` 下与现有 DAG 组件同级，共享 UI 子组件放在 `components/workspace/shared/` 子目录。不创建新的顶层目录结构。

## Complexity Tracking

无违规项。此重构降低复杂度（消除 ~200 行重复代码），符合 YAGNI 原则。

---

## Phase 0: Research

### 0.1 现有代码重复度分析

| 代码块 | DagViewerDialog | InstanceDagDialog | 重复度 |
|--------|-----------------|-------------------|--------|
| Dialog 外壳（className、尺寸） | L232-236 | L163-166 | 100% |
| Header 布局 | L238-240 | L168-177 | 90% |
| Body flex-row 布局 | L243 | L180 | 100% |
| Loading/Error/Empty 状态 | L246-278 | L183-217 | 90% |
| DagRenderer 调用 | L269-278 | L201-210 | 90% |
| 面板 motion.div + 分割线 | L284-299 | L222-245 | 100% |
| Footer 布局 | L331-343 | L249-258 | 90% |
| calcPanelWidth | L50-60 | L45-50 | 100% |
| useMotionValue + useTransform | L88-93 | L71-76 | 100% |
| onPanelResizeDown | L106-135 | L89-117 | 100% |
| useLayoutEffect 校准 | L96-103 | L79-86 | 100% |

**结论**: ~200 行完全/近乎完全重复。面板拖拽逻辑 3 个函数逐字相同。

### 0.2 重复子组件分析

| 子组件 | NodeDetailPanel | InstanceDetailSidePanel | 重复度 |
|--------|----------------|------------------------|--------|
| LoadingState | L55-62 | L35-42 | 100% |
| ErrorState | L65-75 | L44-53 | 95% |
| CodeBlock（Shiki） | L78-106 | L56-83 | 95% |
| ParamsTable | L109-134 | L87-110 | 100% |
| InfoRow | L235-243 | L114-121 | 100% |
| taskTypeToLang | L22-36 | L24-31 | 95% |

**结论**: 5 个子组件 + 1 个工具函数完全可直接提升为共享模块。

### 0.3 参数化方案

公共 `DagDialog` 组件需要以下 props 参数化：

```typescript
interface DagDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  
  // ── 数据源 ──
  dagData: DagView | InstanceDagView | null  // 外部传入已获取的数据
  loading: boolean
  error: string | null
  onRetry: () => void
  
  // ── 内容 ──
  title: ReactNode              // Header 标题
  subtitle?: ReactNode          // Header 副标题（实例 DAG 需要）
  footerInfo: ReactNode         // Footer 版本/状态信息
  
  // ── 面板 ──
  renderSidePanel?: (selectedNodeInfo: SelectedNodeInfo) => ReactNode  // 侧面板渲染函数
  panelStorageKey: string       // localStorage key（独立持久化）
  
  // ── DAG 交互 ──
  flowNodes: Node[]             // 已转换的 ReactFlow nodes
  flowEdges: Edge[]             // 已转换的 ReactFlow edges
  rfId: string                  // ReactFlow instance id
  onNodeClick?: (event: React.MouseEvent, node: Node) => void
  onNodeContextMenu?: (event: React.MouseEvent, node: Node) => void  // 可选（仅设计态）
  
  // ── 额外功能 ──
  renderContextMenu?: () => ReactNode  // 可选（仅设计态右键菜单）
  escapeToDeselect?: boolean           // Escape 键是否先关面板再关弹窗
}
```

**设计决策**: 数据获取保留在外部（包装组件中），`DagDialog` 只负责渲染。这比传入 fetch 函数更简单、更可测试。

### 0.4 侧面板外壳参数化方案

```typescript
interface DetailPanelShellProps {
  title: ReactNode
  onClose: () => void
  loading: boolean
  error: string | null
  onRetry: () => void
  hasData: boolean        // 有数据时 loading 显示半透明遮罩而非居中 spinner
  children: ReactNode     // 具体内容
}
```

---

## Phase 1: Design

### 1.1 组件对比矩阵（无数据模型 —— 纯 UI 重构）

本次重构不涉及数据模型变更。所有类型（DagView、InstanceDagView、NodeTaskDetail、ResolvedCodeView、ResolvedConfigView）保持不变。

### 1.2 API 合约（不变）

后端 API 不受影响：
- `GET /api/workflows/{id}/published-dag` → DagView
- `GET /api/ops/workflows/{id}/nodes/{key}/detail` → NodeTaskDetail
- `GET /api/ops/workflow-instances/{id}/dag` → InstanceDagView
- `GET /api/ops/task-instances/{id}/resolved-code` → ResolvedCodeView
- `GET /api/ops/task-instances/{id}/resolved-config` → ResolvedConfigView

### 1.3 重构前后对比

```
BEFORE (6 files):                     AFTER (9 files, net -200 lines):
┌──────────────────────┐              ┌──────────────────────┐
│ dag-viewer-dialog    │ 357 lines    │ dag-viewer-dialog    │ ~60 lines (wrapper)
│   calcPanelWidth     │              └──────────────────────┘
│   useMotionValue     │              ┌──────────────────────┐
│   onPanelResizeDown  │              │ instance-dag-dialog  │ ~60 lines (wrapper)
│   Dialog layout      │              └──────────────────────┘
│   Panel JSX          │              ┌──────────────────────┐
│   Footer JSX         │              │ dag-dialog           │ ~200 lines (shared)
│   Header JSX         │              │   All layout + panel │
└──────────────────────┘              │   resize + states    │
┌──────────────────────┐              └──────────────────────┘
│ instance-dag-dialog  │ 262 lines    ┌──────────────────────┐
│   calcPanelWidth     │ (DUPLICATE) │ node-detail-panel    │ ~80 lines (uses shared)
│   useMotionValue     │ (DUPLICATE) └──────────────────────┘
│   onPanelResizeDown  │ (DUPLICATE) ┌──────────────────────┐
│   Dialog layout      │ (DUPLICATE) │ instance-detail-side │ ~100 lines (uses shared)
│   Panel JSX          │ (DUPLICATE) └──────────────────────┘
│   Footer JSX         │ (DUPLICATE) ┌──────────────────────┐
│   Header JSX         │ (DUPLICATE) │ detail-panel-shell   │ ~50 lines (shared)
└──────────────────────┘              └──────────────────────┘
┌──────────────────────┐              ┌──────────────────────┐
│ node-detail-panel    │ 243 lines    │ shared/              │ ~120 lines (5 files)
│   LoadingState       │              │   loading-state      │
│   ErrorState         │              │   error-state        │
│   CodeBlock          │              │   code-block         │
│   ParamsTable        │              │   params-table       │
│   InfoRow            │              │   info-row           │
└──────────────────────┘              └──────────────────────┘
┌──────────────────────┐
│ instance-detail-side │ 283 lines
│   LoadingState       │ (DUPLICATE)
│   ErrorState         │ (DUPLICATE)
│   CodeBlock          │ (DUPLICATE)
│   ParamsTable        │ (DUPLICATE)
│   InfoRow            │ (DUPLICATE)
└──────────────────────┘
```

### 1.4 文件变更清单

**新建文件（6 个）**:
1. `frontend/components/workspace/dag-dialog.tsx` — 公共 DAG 弹窗组件
2. `frontend/components/workspace/detail-panel-shell.tsx` — 公共侧面板外壳
3. `frontend/components/workspace/shared/loading-state.tsx` — 共享加载指示器
4. `frontend/components/workspace/shared/error-state.tsx` — 共享错误+重试
5. `frontend/components/workspace/shared/code-block.tsx` — 共享 Shiki 代码块
6. `frontend/components/workspace/shared/params-table.tsx` — 共享参数表（含 InfoRow + taskTypeToLang）

**修改文件（4 个）**:
7. `frontend/components/workspace/dag-viewer-dialog.tsx` — 替换为薄包装
8. `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` — 替换为薄包装
9. `frontend/components/workspace/node-detail-panel.tsx` — 改用共享子组件
10. `frontend/components/workspace/views/ops/instance-detail-side-panel.tsx` — 改用共享子组件

**不变文件**:
- `frontend/components/workspace/dag-renderer.tsx`
- `frontend/components/workspace/nodes/task-node.tsx`
- `frontend/components/workspace/nodes/virtual-node.tsx`
- `frontend/lib/workspace/dag-helpers.ts`
- `frontend/lib/workspace/node-detail-store.ts`
- `frontend/lib/types.ts`
- i18n bundles（无新增 UI 文案）

### 1.5 依赖顺序

```
Phase 1: 创建共享子组件（无上游依赖）
  shared/loading-state.tsx
  shared/error-state.tsx
  shared/code-block.tsx
  shared/params-table.tsx
  
Phase 2: 创建外壳组件（依赖 Phase 1）
  detail-panel-shell.tsx
  dag-dialog.tsx

Phase 3: 重构现有组件（依赖 Phase 2）
  node-detail-panel.tsx → 使用 shared/* + detail-panel-shell
  instance-detail-side-panel.tsx → 使用 shared/* + detail-panel-shell
  dag-viewer-dialog.tsx → 使用 dag-dialog
  instance-dag-dialog.tsx → 使用 dag-dialog

Phase 4: 验证
  pnpm typecheck
  Browser verification gate（两个弹窗完整交互测试）
  删除旧代码确认无残留引用
```
