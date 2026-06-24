# manual-run-trigger Specification

## Purpose
TBD - created by archiving change data-development-ide. Update Purpose after archive.
## Requirements
### Requirement: 任务手动触发正式实例

系统 SHALL 提供 `POST /api/tasks/{id}/run`，按任务发布态分流，不再对未发布任务直接拒绝：

- **已发布（ONLINE）任务**：起一个 `run_mode=NORMAL` 的**正式** task_instance，经现有调度/执行链下发，跑**已发布版本快照**（`task_version_no=current_version_no`）。该实例"正式、计入统计"由 `run_mode=NORMAL` 判定（与 OpsService/WorkflowStateService 现有口径一致），MUST 计入正式运维统计（实例列表、失败清单、SLA），与 cron/依赖触发的实例同源同质。
- **未发布/草稿（DRAFT）任务**：起一个 `run_mode=TEST` 的**测试** task_instance（行为遵循 `task-test-run`），MUST NOT 计入正式统计。

触发 MUST NOT 影响该任务的 cron 定时计划（不改 next_fire、不补历史）。任务侧 MUST NOT 新增 `trigger_type` 列（task_instance 现有 `run_mode` 字段已足够区分 NORMAL/TEST）。接口 MUST 在闸门放行后返回新建实例的 `instanceId`，供前端订阅日志流。

#### Scenario: 已发布任务起正式实例

- **WHEN** 用户对一个已发布任务 `POST /api/tasks/{id}/run`
- **THEN** 系统创建一个 `run_mode=NORMAL` 的正式 task_instance 并下发，返回其 `instanceId`，该实例计入正式统计

#### Scenario: 未发布任务起测试实例（不再拒绝）

- **WHEN** 用户对一个仅有草稿、从未发布的任务 `POST /api/tasks/{id}/run`
- **THEN** 系统不再返回 409，而是创建一个 `run_mode=TEST` 的测试 task_instance 并下发，返回其 `instanceId`，该实例不计入正式统计

#### Scenario: 手动触发不动 cron 计划

- **WHEN** 一个带 cron 调度的任务被手动触发一次
- **THEN** 该任务的下一次 cron 触发时间与计划不受影响，仅多出一个对应模式的实例

### Requirement: 工作流手动触发正式实例

系统 SHALL 提供 `POST /api/workflows/{id}/run`，对一个**已上线**工作流即时起一个正式 workflow_instance。实现 MUST **薄包装现成的** `WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)`（`workflow_instance.trigger_type` 列已存在，传 `"MANUAL"`），不另起平行触发服务。实例创建与下发 MUST 遵守既有调度死锁防御不变量（认领用 SKIP LOCKED、状态推进用乐观 CAS、锁序 task→workflow、事务内只落库 HTTP 下发在事务外）。接口 MUST 返回 `workflowInstanceId`，供前端订阅 DAG 事件流给节点变色。

#### Scenario: 手动起正式工作流实例

- **WHEN** 用户对一个已上线工作流 `POST /api/workflows/{id}/run`
- **THEN** 系统按当前发布版本的 DAG 创建一个 `trigger_type=MANUAL` 的 workflow_instance 并交由 `SchedulerKernel` 调度，返回 `workflowInstanceId`

#### Scenario: 手动工作流实例可被事件流观测

- **WHEN** 手动触发的工作流实例开始执行
- **THEN** `/api/ops/workflow-instances/{id}/events/stream` 推送其节点状态变迁事件

#### Scenario: 未上线工作流拒绝手动正式触发

- **WHEN** 用户对一个无已发布版本的工作流请求手动正式运行
- **THEN** 系统拒绝并提示需先发布上线

### Requirement: 手动触发经闸门与审计

任务/工作流的手动触发属人为/Agent 发起的写操作，MUST 经 `GatedActionService.submit` → `PolicyEngine` 裁决并落 `agent_action` 审计，无绕过路径。无论分流为 NORMAL 还是 TEST 实例均 MUST 经闸门。默认分级 SHALL 为 L1（留痕后直执行，不建审批单）；分级规则 MUST 数据驱动（`policy_rules` 表），允许企业按类型/运行模式收紧（如 MANUAL+SHELL 或 TEST+SHELL 抬至 L2 审批）。

#### Scenario: 默认 L1 直执行

- **WHEN** 用户手动触发一个 SQL 任务（默认规则，无论已发布或草稿）
- **THEN** 系统留 `agent_action` 痕后立即下发，无审批等待，返回 `instanceId`

#### Scenario: 规则收紧后需审批

- **WHEN** 企业在 `policy_rules` 将 MANUAL+SHELL 配为 L2 后，用户手动触发一个 shell 任务
- **THEN** 系统建审批单并返回 `PENDING_APPROVAL`，批准后才下发，全程留痕

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

