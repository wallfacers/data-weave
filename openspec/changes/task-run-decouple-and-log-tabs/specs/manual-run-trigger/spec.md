## MODIFIED Requirements

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

### Requirement: 手动触发经闸门与审计

任务/工作流的手动触发属人为/Agent 发起的写操作，MUST 经 `GatedActionService.submit` → `PolicyEngine` 裁决并落 `agent_action` 审计，无绕过路径。无论分流为 NORMAL 还是 TEST 实例均 MUST 经闸门。默认分级 SHALL 为 L1（留痕后直执行，不建审批单）；分级规则 MUST 数据驱动（`policy_rules` 表），允许企业按类型/运行模式收紧（如 MANUAL+SHELL 或 TEST+SHELL 抬至 L2 审批）。

#### Scenario: 默认 L1 直执行

- **WHEN** 用户手动触发一个 SQL 任务（默认规则，无论已发布或草稿）
- **THEN** 系统留 `agent_action` 痕后立即下发，无审批等待，返回 `instanceId`

#### Scenario: 规则收紧后需审批

- **WHEN** 企业在 `policy_rules` 将 MANUAL+SHELL 配为 L2 后，用户手动触发一个 shell 任务
- **THEN** 系统建审批单并返回 `PENDING_APPROVAL`，批准后才下发，全程留痕
