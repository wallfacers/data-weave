# version-history Specification

## Purpose
TBD - created by archiving change task-workflow-version-history. Update Purpose after archive.
## Requirements
### Requirement: 版本历史列表展示

系统 SHALL 在任务编辑子 Tab 与工作流画布子 Tab 的右侧栏"版本历史"tab 中展示该实体的发布历史列表。列表 MUST 按 `versionNo DESC` 排序，每条记录展示：版本号、发布时间（`yyyy-MM-dd HH:mm:ss`）、发布人、备注。当前已发布版本（`versionNo = currentVersionNo`）MUST 标记"当前"徽章。无历史版本时 MUST 显示空态引导（"暂无发布版本"）。

#### Scenario: 任务版本历史列表展示
- **WHEN** 用户在任务编辑子 Tab 切换到右侧栏"版本历史"tab
- **THEN** 系统展示该任务所有发布版本，按版本号倒序，当前版本标记"当前"

#### Scenario: 工作流版本历史列表展示
- **WHEN** 用户在工作流画布子 Tab 切换到右侧栏"版本历史"tab
- **THEN** 系统展示该工作流所有发布版本，按版本号倒序，当前版本标记"当前"

#### Scenario: 无发布版本空态
- **WHEN** 实体从未发布过（`currentVersionNo=0`）
- **THEN** 版本历史 tab 显示空态引导"暂无发布版本"

### Requirement: 版本详情查看（Dialog）

系统 SHALL 提供"查看"操作入口（每个版本条目一个按钮）。点击后 MUST 弹出 Dialog，以只读方式展示该版本的完整快照内容。任务版本快照包含：代码内容（只读 Monaco，按 type 语法高亮）+ 配置字段（名称/类型/优先级/描述/参数/超时/重试）。工作流版本快照包含：DAG 结构信息（nodes/edges 文本描述）+ 工作流配置（名称/描述/调度/优先级）。Dialog MUST 提供关闭按钮，不承载任何编辑操作。

#### Scenario: 查看任务版本详情
- **WHEN** 用户点击任务版本历史中某版本的"查看"按钮
- **THEN** 弹出 Dialog，只读展示该版本快照的代码（Monaco 只读模式，按 type 语法高亮）与配置字段

#### Scenario: 查看工作流版本详情
- **WHEN** 用户点击工作流版本历史中某版本的"查看"按钮
- **THEN** 弹出 Dialog，只读展示该版本快照的 DAG 结构与工作流配置

### Requirement: 版本对比（Diff）

系统 SHALL 支持选择任意两个版本进行 diff 对比。版本历史列表 MUST 提供多选 checkbox（至多选 2 个），选中后显示"对比"按钮。点击后弹出 Dialog，内嵌 Monaco DiffEditor（左旧右新），对比内容为代码（任务）或 DAG 结构 JSON（工作流）。只选一个版本时"对比"按钮禁用；选中两个版本后 MUST 自动按 versionNo 排序（左小右大）。

#### Scenario: 选择两个版本对比
- **WHEN** 用户在版本历史列表勾选 v2 和 v3，点击"对比"
- **THEN** 弹出 Dialog，Monaco DiffEditor 左侧展示 v2 代码，右侧展示 v3 代码，diff 高亮

#### Scenario: 只选一个版本对比按钮禁用
- **WHEN** 用户只勾选了一个版本
- **THEN** "对比"按钮处于禁用态

### Requirement: 回滚到历史版本（恢复为草稿）

系统 SHALL 提供"回滚"操作（每个版本条目一个按钮，当前版本除外）。点击后 MUST 弹出确认 Dialog，明确告知用户"将把 vX 的内容恢复为当前草稿，当前未发布改动将被覆盖"。用户确认后，系统调用 `POST /api/tasks/{id}/rollback`（或 `POST /api/workflows/{id}/rollback`）body `{ "versionNo": N }`。成功后 MUST：①刷新编辑器/画布内容为回滚版本的快照；②标记"未发布改动"状态；③Toast 提示"已回滚到 vX 草稿，请检查后发布"。

#### Scenario: 回滚任务到历史版本
- **WHEN** 用户点击 v2 的"回滚"并确认
- **THEN** 系统调用 rollback API，成功后编辑器内容刷新为 v2 快照，标记未发布改动，Toast 提示

#### Scenario: 回滚工作流到历史版本
- **WHEN** 用户点击工作流 v1 的"回滚"并确认
- **THEN** 系统调用 rollback API，成功后画布 DAG 刷新为 v1 快照，标记未发布改动，Toast 提示

#### Scenario: 当前版本不显示回滚按钮
- **WHEN** 版本条目为当前已发布版本（`versionNo = currentVersionNo`）
- **THEN** 该条目不显示"回滚"按钮（已是当前，无需回滚）

#### Scenario: 回滚前有未发布改动需确认
- **WHEN** 当前存在未发布改动（`hasDraftChange=1`）且用户点击"回滚"
- **THEN** 确认 Dialog 明确提示"当前有未发布改动将被覆盖，是否继续？"

### Requirement: 任务回滚 API

系统 SHALL 提供 `POST /api/tasks/{id}/rollback`，请求体为 `{ "versionNo": N }`。该端点 MUST 查找 `task_def_version` 中对应快照，将快照中的 `name/type/content/datasourceId/targetDatasourceId/paramsJson/timeoutSec/retryMax/priority/description` 写回 `task_def`，并置 `has_draft_change=1`。MUST NOT 改变 `current_version_no` 或 `status`。若 `versionNo` 不存在 MUST 返回 404。

#### Scenario: 成功回滚任务
- **WHEN** 用户 POST `/api/tasks/1/rollback` body `{ "versionNo": 2 }`
- **THEN** 系统将 v2 快照内容写回 `task_def`，`has_draft_change=1`，`status` 和 `current_version_no` 不变

#### Scenario: 回滚不存在的版本
- **WHEN** 用户 POST `/api/tasks/1/rollback` body `{ "versionNo": 999 }`
- **THEN** 系统返回 HTTP 404

### Requirement: 工作流回滚 API

系统 SHALL 提供 `POST /api/workflows/{id}/rollback`，请求体为 `{ "versionNo": N }`。该端点 MUST 查找 `workflow_def_version` 中对应快照，将 `dag_snapshot_json` 解析后写回 `workflow_node` / `workflow_edge`（按 node_key 对账），并将工作流配置字段写回 `workflow_def`，置 `has_draft_change=1`。MUST NOT 改变 `current_version_no` 或 `status`。

#### Scenario: 成功回滚工作流
- **WHEN** 用户 POST `/api/workflows/1/rollback` body `{ "versionNo": 1 }`
- **THEN** 系统将 v1 DAG 快照写回 nodes/edges，`has_draft_change=1`，版本号不变

### Requirement: 工作流详情含版本历史

系统 SHALL 将 `GET /api/workflows/{id}` 的响应结构升级为 `WorkflowDetail { workflow: WorkflowDef, versions: WorkflowDefVersion[] }`，与任务 `TaskDetail` 对称。`versions` MUST 按 `version_no DESC` 排序。

#### Scenario: 获取工作流详情含版本列表
- **WHEN** 用户 GET `/api/workflows/1`
- **THEN** 系统返回 `{ "workflow": {...}, "versions": [v3, v2, v1] }` 结构

### Requirement: 右侧栏 Tab 布局（任务与工作流）

任务编辑子 Tab 右侧栏 MUST 拆分为 [配置 | 版本历史] 两个 tab，默认激活"配置"。工作流画布子 Tab MUST 新增右侧栏，同样为 [配置 | 版本历史] 两个 tab。切换 tab MUST NOT 卸载另一 tab 的组件（CSS 隐藏而非条件渲染），保持表单状态不丢失。右侧栏宽度不变（320px），tab 标签使用紧凑文字标签样式。

#### Scenario: 任务编辑子 Tab 右侧栏 tab 切换
- **WHEN** 用户在任务编辑子 Tab 点击右侧栏"版本历史"tab
- **THEN** 右侧栏内容切换为版本历史列表，配置 tab 内容隐藏但状态保留

#### Scenario: 工作流画布子 Tab 右侧栏 tab 切换
- **WHEN** 用户在工作流画布子 Tab 点击右侧栏"版本历史"tab
- **THEN** 右侧栏内容切换为版本历史列表

#### Scenario: 配置 tab 状态不丢失
- **WHEN** 用户在配置 tab 修改了名称但未保存，切换到版本历史 tab 后切回
- **THEN** 配置 tab 中名称字段保留修改后的值

### Requirement: 工作流配置面板

工作流画布子 Tab 右侧栏"配置"tab MUST 展示并允许编辑以下工作流级属性：名称、描述、调度类型（cron/事件）、cron 表达式、优先级。保存操作复用现有 `PUT /api/workflows/{id}`。

#### Scenario: 编辑工作流名称
- **WHEN** 用户在工作流画布右侧栏"配置"tab 修改名称并保存
- **THEN** 系统调用 `PUT /api/workflows/{id}` 更新名称，成功后清除脏标记

#### Scenario: 编辑工作流调度配置
- **WHEN** 用户修改 cron 表达式并保存
- **THEN** 系统更新工作流调度配置，标记未发布改动

