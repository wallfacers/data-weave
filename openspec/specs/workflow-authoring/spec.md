# workflow-authoring Specification

## Purpose
工作流定义的编排能力：草稿工作流的全生命周期 CRUD、以「整图」为单位的 DAG 读写、以及把当前 DAG 冻结为不可变版本快照的发布流程。所有写操作经 `GatedActionService` 闸门并留痕。
## Requirements
### Requirement: 工作流定义 CRUD

系统 SHALL 提供工作流定义的完整 CRUD 能力。用户 MUST 能创建草稿工作流、按名称/状态分页搜索、查看详情、编辑（仅 `DRAFT` 或 `has_draft_change` 态可改调度配置）、软删除、下线。创建的工作流初始 `status=DRAFT`、`current_version_no=0`、`has_draft_change=1`。所有写操作 MUST 经 `GatedActionService` 闸门并留痕。

`GET /api/workflows/{id}` 的响应结构 MUST 为 `WorkflowDetail { workflow: WorkflowDef, versions: WorkflowDefVersion[] }`，`versions` 按 `version_no DESC` 排序，与任务 `TaskDetail` 对称。

#### Scenario: 创建草稿工作流
- **WHEN** 用户 POST `/api/workflows` 提交名称与调度配置
- **THEN** 系统创建一条 `workflow_def`，`status=DRAFT`、`current_version_no=0`、`has_draft_change=1`，返回新 id

#### Scenario: 分页搜索工作流
- **WHEN** 用户 GET `/api/workflows?keyword=&status=&page=0&size=20`
- **THEN** 系统返回匹配的分页结果（content + totalElements + totalPages）

#### Scenario: 软删除工作流
- **WHEN** 用户 DELETE `/api/workflows/{id}`
- **THEN** 系统将该工作流 `deleted=1`，后续搜索/读取不再返回，且不物理删除其历史版本

#### Scenario: 获取工作流详情含版本列表
- **WHEN** 用户 GET `/api/workflows/1`
- **THEN** 系统返回 `{ "workflow": {...}, "versions": [v3, v2, v1] }` 结构

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

