## Why

类目树的 CRUD 后端能力早已齐备，但前端只用 hover 行内按钮零散暴露了一部分：文件夹的**改名 / 删除**后端支持却根本没有入口，叶子的新建只能去右侧 TabStrip 且只能落「未分类」。用户期望像 IDE 那样**右键即操作**，且任务与工作流对称支持完整 CRUD。本变更把类目树的全部 CRUD 收敛进统一的右键上下文菜单。

## What Changes

- **新增右键上下文菜单**：在类目树的文件夹行、任务/工作流叶子行、未分类节点、空白区右键弹出按节点类型差异化的菜单。
- **补齐文件夹 CRUD 入口**：右键文件夹提供「新建子文件夹 / 重命名 / 删除」，对接已有 `PATCH/DELETE /api/catalog/nodes/{id}`。非空文件夹的「删除」项**置灰 + tooltip 说明**（节点已带 `taskCount/workflowCount/children`，前端即可判定，不依赖往返报错）。
- **「在此新建任务/工作流」**：右键文件夹（及空白/未分类的「新建根」）可直接创建草稿并**一步归入该文件夹**——`POST /api/tasks`/`/api/workflows` 的 body 携带 `catalogNodeId`（后端 `create()` 即 `save(entity)`，字段已映射，**零后端改动**）；建成后直接打开编辑/画布子 Tab。
- **移除 hover 行内按钮**：叶子行的 ✏️/🗑️ 与文件夹行的「新建子文件夹」图标按钮移除，统一由右键菜单承载，行更干净、交互通道唯一。
- **封装 `ContextMenu` 组件**：新增 `components/ui/context-menu.tsx`，封装 `@base-ui/react` 的 ContextMenu，遵循 base 风格（`render` prop、语义 token）。
- **保留兼容路径**：右侧 TabStrip 的「+任务 / +工作流」（在根新建）与树内拖拽移动归属维持不变，与右键菜单并行不冲突。

## Capabilities

### New Capabilities
<!-- 无新增独立能力，均为既有类目能力的交互增强 -->

### Modified Capabilities
- `task-workflow-catalog`: ① 既有「叶子（任务/工作流）重命名与删除」requirement 的入口语义从 hover 行内按钮改为右键上下文菜单（移除 hover）；② 新增「类目树右键上下文菜单」「文件夹前端 CRUD 入口（含非空禁删置灰）」「在文件夹内新建任务/工作流（带归属）」三项前端交互需求。

## Impact

- **前端**：新增 `frontend/components/ui/context-menu.tsx`；改 `frontend/components/workspace/catalog-tree.tsx`（接线菜单、移除 hover 按钮、扩 DialogState 支持在此新建叶子）。`@base-ui/react` 已是依赖，无需新增包。
- **后端**：无改动（CRUD 接口与 `catalogNodeId` 落库均现成）。
- **测试**：前端 Browser Verification Gate 实跑右键菜单各分支；后端不涉及。
