## Why

任务和工作的版本发布基础设施（`task_def_version` / `workflow_def_version` 表、实体、Repository、发布 API）已在后端就绪，`GET /api/tasks/{id}` 也已返回 `versions[]`，但前端完全没有消费这些数据——用户无法浏览、对比或回滚历史版本。数据开发 IDE 的编辑器右侧栏以平铺方式承载所有配置字段，随着功能增加（调度参数、替换预览等）已显得拥挤，需要结构化为 tab 布局以腾出空间承载版本历史入口。

## What Changes

- **任务编辑器右侧栏重构**：将 `TaskEditorPane` 右侧配置栏拆分为两个 tab——"配置"（现有字段原封不动迁入）与"版本历史"（新增）。
- **工作流画布新增右侧栏**：`WorkflowCanvasPane` 当前无右侧栏，新增同样的 [配置 | 版本历史] tab 结构，配置 tab 承载工作流级属性（名称、描述、调度、优先级等）。
- **版本历史列表**：两个实体共用一套版本历史列表组件，按时间倒序展示版本号、发布时间、发布人、备注。
- **版本详情弹窗**：只读 Dialog 展示某个历史版本的完整快照（代码 + 配置）。
- **版本对比 (diff) 弹窗**：基于 Monaco DiffEditor，选择任意两个版本进行代码/配置 diff 对比。
- **回滚操作**：将某历史版本的快照内容恢复为当前草稿（不直接发布）。如果当前已有未发布改动，弹窗确认是否覆盖。
- **后端补全**：
  - 新增 `WorkflowDetail` DTO（含 `versions[]`），改造 `GET /api/workflows/{id}` 返回该 DTO（与 `TaskDetail` 对称）。
  - 新增 `POST /api/tasks/{id}/rollback` 和 `POST /api/workflows/{id}/rollback`，按 `versionNo` 把快照写回草稿并置 `hasDraftChange=1`。

## Capabilities

### New Capabilities
- `version-history`: 任务与工作流的历史版本浏览、详情查看、版本对比 (diff)、回滚到草稿的完整能力（后端 API + 前端 UI）。

### Modified Capabilities
- `data-development-ide`: 任务编辑器右侧栏重构为 [配置 | 版本历史] tab 布局；工作流画布新增右侧栏。
- `workflow-authoring`: `GET /api/workflows/{id}` 响应结构变更为 `WorkflowDetail { workflow, versions[] }`（含版本历史列表）。
- `task-crud`: `POST /api/tasks/{id}/rollback` 新增回滚 API。

## Impact

- **后端**：`dataweave-master` 新增 `WorkflowDetail` DTO、`rollback()` 服务方法；`dataweave-api` 的 `WorkflowController`/`TaskController` 新增/改造端点；`schema.sql` 和 H2 DDL 无需变更（版本表已存在）。
- **前端**：`TaskEditorPane` 右侧栏重构；`WorkflowCanvasPane` 新增右侧栏；新增 `VersionHistoryPanel`、`VersionDetailDialog`、`VersionDiffDialog`、`RollbackConfirmDialog` 组件；新增 `TaskDefVersion` / `WorkflowDefVersion` TypeScript 类型。
- **API 契约**：`GET /api/workflows/{id}` 响应结构变更（从扁平 `WorkflowDef` 变为 `{ workflow, versions[] }`）——**前端必须同步适配**。
- **i18n**：新增版本历史相关前端 next-intl key（中英文）；后端 `Messages` 新增回滚成功/失败消息。
