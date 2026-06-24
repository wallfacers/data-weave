## ADDED Requirements

### Requirement: 手动运行范围（子图）

`POST /api/workflows/{id}/run` SHALL 接受 `scope`（`FULL` 默认 / `TO_NODE` / `DOWNSTREAM`）与 `targetNodeKey`（`scope` 非 `FULL` 时必填）。`WorkflowTriggerService.trigger` MUST 按已发布版本快照的边计算节点闭包，仅物化子集节点为 `task_instance`：`FULL` 物化全部节点（现状）；`TO_NODE` 物化 target 及其全部前驱闭包；`DOWNSTREAM` 物化 target 及其全部后继闭包。子集外节点 MUST NOT 生成 `task_instance`。`ONLY_NODE`（仅本节点脱离依赖单跑）SHALL 复用 `POST /api/tasks/{taskId}/run` 的孤立单任务实例路径（不建 `workflow_instance`、不触发上下游）。手动触发的实例（`FULL`/`TO_NODE`/`DOWNSTREAM`）MUST 忽略跨周期依赖，同周期依赖在子图内遵守。运行范围属写操作，MUST 经 `GatedActionService` 闸门与 `agent_action` 审计。接口 MUST 返回子图实例的 `workflowInstanceId`（或 `ONLY_NODE` 的孤立 `instanceId`）供前端订阅事件/日志流。

#### Scenario: 运行到本节点含上游闭包

- **WHEN** 用户对节点 N 选择 `scope=TO_NODE` 运行
- **THEN** 系统创建一个 `workflow_instance`，仅物化 N 及其全部前驱节点，N 的上游按同周期依赖就绪

#### Scenario: 运行下游含后继闭包

- **WHEN** 用户对节点 N 选择 `scope=DOWNSTREAM` 运行
- **THEN** 系统创建一个 `workflow_instance`，仅物化 N 及其全部后继节点

#### Scenario: 单独运行复用孤立实例

- **WHEN** 用户对一个 TASK 节点选择 `ONLY_NODE` 运行
- **THEN** 前端调用 `POST /api/tasks/{taskId}/run`，创建孤立 `task_instance`（无 `workflow_instance`），不触发上下游

#### Scenario: 手动子图实例忽略跨周期

- **WHEN** 用户对一个开启自依赖的工作流以 `TO_NODE` 运行
- **THEN** 子图实例不检查跨周期依赖，节点仅按同周期就绪

#### Scenario: 默认 FULL 向后兼容

- **WHEN** 用户不带 `scope` 调用 `POST /api/workflows/{id}/run`
- **THEN** 行为等价于 `scope=FULL`，物化全部节点，与改动前一致

#### Scenario: 运行范围经闸门与审计

- **WHEN** 用户发起任意 `scope` 的运行
- **THEN** 写操作经 `GatedActionService` 裁决并落 `agent_action` 审计，默认 L1 直执行，规则可收紧至 L2 审批
