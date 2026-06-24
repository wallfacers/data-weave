## MODIFIED Requirements

### Requirement: DAG 整图读写

系统 SHALL 支持以「整图」为单位读写工作流 DAG。读 DAG（GET `/api/workflows/{id}/dag`）MUST 返回该工作流全部未删除的 `workflow_node`（含 `node_key`、`node_type`、`task_id`、`name`、`pos_x`、`pos_y`）与 `workflow_edge`（含端点 `node_key` 及 `strength`）。整图保存（PUT `/api/workflows/{id}/dag`）MUST 在单事务内按 `node_key` 对账节点、按 `(from_node_key,to_node_key)` 对账边：客户端有而库无→新增，两侧都有→更新（含 `strength`），库有而客户端无→软删（`deleted=1`）。边缺省 `strength` MUST 按 `STRONG` 处理（行为等价改动前）。发布快照（`dag_snapshot_json`）MUST 冻结每条边的 `strength`，使回滚能还原边强度。保存成功 SHALL 置 `has_draft_change=1`。

#### Scenario: 整图保存新增与软删

- **WHEN** 用户 PUT DAG，其中包含一个新 `node_key` 且省略了某个已存在的 `node_key`
- **THEN** 系统新增该新节点、软删被省略的节点及其关联边，其余节点按提交内容更新坐标/名称/类型/task 绑定

#### Scenario: TASK 节点必须绑定任务

- **WHEN** 保存的图中存在 `node_type=TASK` 但 `task_id` 为空的节点
- **THEN** 系统拒绝保存并返回校验错误，提示该节点未绑定任务

#### Scenario: VIRTUAL 节点不绑任务

- **WHEN** 保存的图中存在 `node_type=VIRTUAL` 的节点
- **THEN** 系统接受其 `task_id` 为空，正常持久化

#### Scenario: 乐观锁冲突

- **WHEN** 用户提交的 `workflow_def.version` 与库中当前 version 不一致
- **THEN** 系统拒绝保存并返回 409，提示工作流已被他人修改需刷新

#### Scenario: 边强度随整图读写与快照

- **WHEN** 用户 PUT 一条边并指定 `strength=WEAK`，随后发布
- **THEN** 该边 `strength=WEAK` 持久化并冻结进 `dag_snapshot_json`；回滚到旧版本时该边强度随快照还原

## ADDED Requirements

### Requirement: 跨周期依赖配置

系统 SHALL 提供跨周期依赖（`workflow_dependency`）的配置读写：`GET /api/workflows/{id}/dependencies`、`POST /api/workflows/{id}/dependencies`、`DELETE /api/workflows/{id}/dependencies/{depId}`。每条依赖含 `node_id`（本节点）、`depend_workflow_id`、`depend_node_id`、`date_offset`（`CURRENT_DAY`/`LAST_DAY`）、`earliest_biz_date`（可空）、`enabled`。系统 MUST 允许自依赖（`depend_workflow_id=workflow_id` 且 `depend_node_id=node_id`），不视为环；对非自指依赖 MUST 做全局跨流环检测，成环拒绝并提示路径。所有写 MUST 经 `GatedActionService` 闸门并留痕。跨周期依赖作为调度属性，编辑即随 `workflow_def` 当前态生效，MUST NOT 纳入 DAG 版本快照（与 `cron`/`schedule_type` 同生命周期）。

#### Scenario: 配置自依赖

- **WHEN** 用户为本节点 N 创建自依赖（`depend_node_id=N`、`date_offset=LAST_DAY`、`earliest_biz_date=2026-06-20`）
- **THEN** 系统保存该 `workflow_dependency`，不再报 `workflow.graph.self_dependency`

#### Scenario: 跨流依赖成环拒绝

- **WHEN** 用户创建一条会使全局工作流依赖图成环的跨工作流依赖
- **THEN** 系统拒绝并提示环路路径

#### Scenario: 依赖写经闸门

- **WHEN** 用户增、删、改跨周期依赖
- **THEN** 写操作经 `GatedActionService` 裁决并落 `agent_action` 审计

#### Scenario: 跨周期依赖编辑即生效不进快照

- **WHEN** 用户编辑一条跨周期依赖后未发布即触发周期
- **THEN** 该依赖按 `workflow_def` 当前态即时生效，且不出现在任何 `dag_snapshot_json` 中
