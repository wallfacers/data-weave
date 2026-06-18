## Context

类目树 `<CatalogTree>`（`frontend/components/workspace/catalog-tree.tsx`）当前用 **hover 行内按钮**暴露部分 CRUD：叶子行 hover 出 ✏️/🗑️，文件夹行 hover 出「新建子文件夹」。文件夹的**改名/删除**后端有接口（`PATCH/DELETE /api/catalog/nodes/{id}`）但前端无入口；叶子的新建只在右侧 TabStrip，且只能落「未分类」。

后端能力盘点（均现成，本变更**零后端改动**）：
- 文件夹：`POST /api/catalog/nodes`（建）、`PATCH /api/catalog/nodes/{id}`（改名 `name` / 移动 `parentId`）、`DELETE /api/catalog/nodes/{id}`（非空返回 `409 CATALOG_NODE_NOT_EMPTY`）。
- 叶子：`POST /api/tasks`、`/api/workflows`（`create()` 即 `save(entity)`，body 含 `catalogNodeId` 即落库归属）；`PUT`（改名）、`DELETE`（软删）、`PATCH /catalog`（改归属）。
- 树节点已带 `taskCount`、`workflowCount`、`children`，前端可本地判定「非空」。

约束：base 风格组件用 `render` prop；图标用 hugeicons；写交互用 base `Dialog`（非原生）；改 `frontend/` 走 Browser Verification Gate 实跑。`@base-ui/react@^1.5.0` 已是依赖，自带 ContextMenu。

## Goals / Non-Goals

**Goals:**
- 类目树全部 CRUD 收敛进**统一右键上下文菜单**，按节点类型（文件夹 / 任务叶子 / 工作流叶子 / 未分类 / 空白）差异化。
- 补齐文件夹**改名 / 删除**入口；非空文件夹「删除」**置灰 + tooltip**。
- 右键文件夹可**「在此新建任务/工作流」**——草稿一步带 `catalogNodeId` 归入该文件夹，建成即开子 Tab。
- **移除 hover 行内按钮**，交互通道唯一。
- 新增可复用 `components/ui/context-menu.tsx`（封 Base UI）。

**Non-Goals:**
- 不改后端任何接口或数据模型。
- 不把「移动到…」做成右键二级子菜单——移动仍只靠拖拽（保持菜单轻）。
- 不动右侧 TabStrip 的「+任务/+工作流」（在根新建，兼容保留）。
- 不实现文件夹递归删除（后端不支持级联，非空仍禁删）。
- 不涉及标签的右键操作（本期范围只到任务/工作流/文件夹）。

## Decisions

### D1：封装 `context-menu.tsx` over Base UI ContextMenu
用 `@base-ui/react` 的 `ContextMenu.*`（Root/Trigger/Portal/Positioner/Popup/Item/Separator）封一层 base 风格组件，对齐既有 `dialog.tsx`/`select.tsx` 的封装范式（语义 token、`render` prop、forwardRef）。
- **为何**：仓库无现成 context-menu/dropdown；Base UI 已在依赖内，避免引新包；与 shadcn base 风格一致。
- **替代**：手写 `onContextMenu` + 绝对定位浮层——要自己处理定位/键盘/外点关闭/可达性，重复造轮子，弃。

### D2：菜单挂载方式——每行包一个 `ContextMenu.Trigger`
文件夹行、叶子行各自作为 trigger 包裹，菜单项集合由节点类型决定。空白/根级右键挂在树容器，给「新建根文件夹 / 新建任务 / 新建工作流」。
- **为何**：节点类型与菜单项是局部关系，就近渲染最直观；trigger 复用现有行 DOM，不重构布局。
- **注意**：行同时是拖拽源（`draggable`）与点击打开源，右键 trigger 不能吞掉 `onClick`/`onDragStart`——Base UI ContextMenu 走 `contextmenu` 事件，与二者正交，验证时确认三者并存。

### D3：「在此新建」由 CatalogTree 自包含（POST 带 catalogNodeId + 回调开 Tab）
CatalogTree 的 `DialogState` 扩两态 `create-task`（name + type SQL/SHELL）、`create-workflow`（name）；提交时 `POST` body 带 `catalogNodeId`，成功后调既有 `onOpenTask/onOpenWorkflow` 回调开子 Tab，并 `reload()` 刷新树。
- **为何**：让 CatalogTree 成为类目 CRUD 的唯一真相源；父组件只需提供 `onOpenTask/onOpenWorkflow`（已有），无需把文件夹上下文回传父级再下发。
- **替代**：回调 `onCreateInFolder(nodeId)` 给父组件处理——父组件的新建 Dialog 不天然知道目标文件夹，需额外透传状态，更绕，弃。
- **复用**：创建 Dialog 字段复用 workflow-canvas-view 既有 name+type 形态，保持观感一致。

### D4：非空禁删——前端本地判定置灰
菜单「删除文件夹」项的 `disabled = (node.taskCount + node.workflowCount > 0) || node.children.length > 0`，禁用态附 tooltip「请先清空或移走子项」。
- **为何**：树节点已带计数与 children，本地即可判定，无需先点再吃 409，体验更好。
- **保留兜底**：即便误放行，后端 409 仍会被 toast 兜底报错（双保险）。

### D5：移除 hover 行内按钮，写交互仍走统一 Dialog
删掉 `renderLeaf` 的 hover ✏️/🗑️ 与 `renderFolder` 的「新建子文件夹」图标按钮；所有写入口改由右键菜单触发，复用现有统一 `Dialog` 状态机（folder/rename/delete + 新增 create-task/create-workflow）。
- **为何**：交互通道唯一、行更干净；Dialog 状态机已在，扩两态成本低。

## Risks / Trade-offs

- **右键与拖拽/点击事件打架** → Base UI ContextMenu 用 `contextmenu` 事件，与 `onClick`/`onDragStart` 正交；Browser Verification Gate 实跑确认三者并存（点击开 Tab、拖拽移动/上画布、右键弹菜单都正常）。
- **移除 hover 按钮后老用户找不到操作** → 右键是 IDE 通用心智；如需可后续补一个行尾「⋯」点击触发同菜单，本期不做。
- **「在此新建」依赖 `catalogNodeId` 落库未验证** → 已确认 `create()` 直接 `save(entity)` 且 search/PATCH 证明字段映射；实现首步即用一次 POST 验证草稿确实落入目标文件夹。
- **已发布任务/工作流改名/删被后端拒** → 既有领域约束（仅 DRAFT 可改），非本变更引入；菜单不预判状态，依赖后端 toast 报错，与现状一致。
- **触屏/无右键设备无法触发** → 桌面 IDE 场景为主，本期接受；移动端非目标。

## Migration Plan

纯前端增量，无数据迁移。部署即生效；回滚 = 还原两个前端文件。分步：① 加 `context-menu.tsx` 并 typecheck；② catalog-tree 接线菜单 + 扩 DialogState + 移除 hover；③ Browser Verification Gate 实跑各右键分支；④ 清理 `tmp/` 验证产物。

## Open Questions

- 无阻塞性问题。「移动到…」二级菜单、行尾「⋯」触发器、标签右键均明确划为 Non-Goals，可后续单独提案。
