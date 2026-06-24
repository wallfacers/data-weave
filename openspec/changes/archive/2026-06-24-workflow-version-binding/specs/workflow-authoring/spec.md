## MODIFIED Requirements

### Requirement: 工作流发布

系统 SHALL 支持工作流发布（POST `/api/workflows/{id}/publish`）。发布前 MUST 调用 `WorkflowGraphValidator.validateWorkflowDagAcyclic` 校验 DAG 无环；有环 MUST 拒绝发布并返回面向用户的错误（含环路节点）。发布 SHALL 将当前 DAG 冻结为 `dag_snapshot_json`（含 nodes、edges 及各 TASK 节点的 `current_version_no`）写入新的 `workflow_def_version`，并 `current_version_no++`、`has_draft_change=0`、`status=ONLINE`。

发布生成的 `dag_snapshot_json` SHALL 是该工作流运行期的**唯一真相源（规定性快照）**：周期与正式手动运行从此快照物化拓扑与各节点 task 版本（见 `workflow-version-binding` 能力），而非描述性历史记录。发布动作即「晋级到生产」——发布后任务再发新版不自动流入运行，须重新发布（重新晋级）方采纳。

#### Scenario: 无环 DAG 发布成功
- **WHEN** 用户发布一个无环工作流
- **THEN** 系统生成新版本快照、`current_version_no` 自增、`has_draft_change=0`、`status=ONLINE`

#### Scenario: 有环 DAG 拒绝发布
- **WHEN** 用户发布一个存在环路的工作流
- **THEN** 系统拒绝发布并返回错误，提示环路节点路径，工作流版本号不变

#### Scenario: 发布快照成为运行真相源
- **WHEN** 用户发布工作流后该工作流被周期触发
- **THEN** 运行实例的拓扑与各节点 task 版本取自本次发布的快照，与发布后任务的最新草稿/新发布版无关
