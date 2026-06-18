## 1. ContextMenu 组件封装

- [x] 1.1 新增 `frontend/components/ui/context-menu.tsx`：封 `@base-ui/react` 的 `ContextMenu`（Root/Trigger/Portal/Positioner/Popup/Item/Separator），对齐 `dialog.tsx`/`select.tsx` 的 base 风格封装（语义 token、forwardRef、`render` prop），导出 `ContextMenu` / `ContextMenuTrigger` / `ContextMenuContent` / `ContextMenuItem` / `ContextMenuSeparator`，菜单项支持 `disabled` 与（禁用态）tooltip。
- [x] 1.2 `cd frontend && pnpm typecheck` 确认零类型错误。

## 2. catalog-tree 接线右键菜单 + 移除 hover

- [x] 2.1 扩 `DialogState`：新增 `create-task`（含 `parentId` + `name` + `type: SQL|SHELL`）与 `create-workflow`（含 `parentId` + `name`）两态；`submitDialog` 增对应分支。
- [x] 2.2 实现 `createLeaf(kind, parentId, name, type?)`：`POST /api/tasks`/`/api/workflows`，body 携带 `catalogNodeId=parentId`（空白区为 null）；成功后调 `onOpenTask/onOpenWorkflow` 开子 Tab、`toast` 提示、`reload()` 刷新树。
- [x] 2.3 `renderLeaf`：用 `ContextMenuTrigger` 包裹叶子行，菜单项「重命名 / 删除」复用现有 `setDialog`；**移除** hover 行内 ✏️/🗑️ 按钮块。确认 `onClick`（开 Tab）与 `onDragStart`（move/canvas 载荷）保留不受影响。
- [x] 2.4 `renderFolder`：用 `ContextMenuTrigger` 包裹文件夹行，菜单项「新建子文件夹 / 新建任务（在此）/ 新建工作流（在此）/ 分隔 / 重命名 / 删除」；删除项 `disabled = node.taskCount + node.workflowCount > 0 || node.children.length > 0` 并附 tooltip「请先清空或移走子项」；**移除** hover「新建子文件夹」图标按钮。确认 `onClick`（展开）与 drop target（移动归属）保留。
- [x] 2.5 文件夹「重命名」对接 `PATCH /api/catalog/nodes/{id}`（body `{name}`）；新增 `renameFolder` 写方法 + Dialog `folder-rename` 态，成功后 `reload()`。
- [x] 2.6 文件夹「删除」对接 `DELETE /api/catalog/nodes/{id}`，二次确认 Dialog；成功后 `reload()`，409 时 toast 兜底。
- [x] 2.7 空白区/根级右键：树容器包裹 `ContextMenuTrigger`，菜单项「新建根文件夹 / 新建任务 / 新建工作流」（叶子创建 `parentId=null`）。未分类节点不提供改名/删除。
- [x] 2.8 `cd frontend && pnpm typecheck` 确认零类型错误。

## 3. Browser Verification Gate（硬性）

- [x] 3.1 起前后端，打开数据开发 IDE，playwright 实跑：右键文件夹/叶子/空白区分别弹出正确菜单项；console 无 error。
- [x] 3.2 验「在此新建任务」：右键文件夹 F 新建任务，经 API 核对草稿落入 F 下（`catalogNodeId` 生效）且自动打开编辑子 Tab（截图确认）。
- [x] 3.3 验文件夹改名/删除：空文件夹可删（删除项可用）、非空文件夹删除项置灰带 tooltip「请先清空或移走子项」；改名生效。
- [x] 3.4 验交互并存：叶子左键开编辑 Tab（截图确认）、右键弹菜单、拖拽载荷保留；console 无 error。
- [x] 3.5 验证产物（截图/脚本）写入项目根 `tmp/`，验证后已清理，不留仓库。

## 4. 收尾

- [x] 4.1 自检 spec 各 Scenario 均被实跑覆盖；左键开 Tab 截图确认正常（首次断言因折叠误判，非产品问题）。
- [x] 4.2 运行 `openspec validate catalog-context-menu` 通过。
