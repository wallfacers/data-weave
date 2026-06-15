## ADDED Requirements

### Requirement: 虚拟节点零负载执行

工作流节点 SHALL 携带 `node_type`（`TASK`/`VIRTUAL`，默认 `TASK`）。触发物化（`WorkflowTriggerService`）时，`node_type=VIRTUAL` 的节点 MUST 直接生成 `state=SUCCESS` 的 `task_instance`（`started_at=finished_at` 为物化时刻，不设 `task_id`），不进入 `WAITING`、不下发给 worker、不占用任何调度槽。虚拟节点 MUST 作为 DAG 拓扑的起始/汇聚锚点正常参与下游 readiness 解锁（下游的 `pred.state<>'SUCCESS'` 检查对虚拟节点自然放行）。虚拟节点 SHALL 计入 `workflow_instance` 的 `total_tasks` 与 `completed_tasks`。

#### Scenario: 虚拟节点物化即成功
- **WHEN** 工作流触发，某节点 `node_type=VIRTUAL`
- **THEN** 系统为其创建 `state=SUCCESS` 的 task_instance，不下发给任何 worker、不占槽

#### Scenario: 虚拟起始节点解锁下游
- **WHEN** 一个 `VIRTUAL` 起始节点指向多个 `TASK` 下游节点
- **THEN** 物化后虚拟节点即为 SUCCESS，其全部下游 TASK 节点的入边前驱判定通过，进入可运行状态

#### Scenario: 实例计数自洽
- **WHEN** 工作流含 N 个节点（其中 M 个虚拟）全部成功
- **THEN** `total_tasks=N`、`completed_tasks=N`，计数包含虚拟节点
