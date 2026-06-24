# instance-lifecycle Specification

## Purpose
TBD - created by archiving change task-core-capabilities. Update Purpose after archive.
## Requirements
### Requirement: Pause workflow instance
The system SHALL support pausing a running workflow instance via `POST /api/ops/instances/{id}/pause`. The system SHALL set `WorkflowInstance.state='PAUSED'` and mark all `NOT_RUN` `TaskInstance` entries for this workflow as `PAUSED`. Currently `RUNNING` task instances SHALL be allowed to complete naturally.

#### Scenario: Pause a running workflow
- **WHEN** a user sends `POST /api/ops/instances/101/pause` for a workflow with `state='RUNNING'`
- **THEN** the workflow state becomes `PAUSED`, all `NOT_RUN` task instances become `PAUSED`, and the response is HTTP 200.

#### Scenario: Pause a non-running workflow
- **WHEN** a user sends `POST /api/ops/instances/101/pause` for a workflow with `state='SUCCESS'`
- **THEN** the system returns HTTP 409 with message "Only RUNNING instances can be paused".

### Requirement: Resume workflow instance
The system SHALL support resuming a paused workflow instance via `POST /api/ops/instances/{id}/resume`. The system SHALL set `WorkflowInstance.state='RUNNING'`, set all `PAUSED` `TaskInstance` entries back to `NOT_RUN`, and resume execution from the next unexecuted node.

#### Scenario: Resume a paused workflow
- **WHEN** a user sends `POST /api/ops/instances/101/resume` for a workflow with `state='PAUSED'`
- **THEN** the workflow state becomes `RUNNING`, paused task instances become `NOT_RUN`, and execution continues.

#### Scenario: Resume a non-paused workflow
- **WHEN** a user sends `POST /api/ops/instances/101/resume` for a workflow with `state='RUNNING'`
- **THEN** the system returns HTTP 409 with message "Only PAUSED instances can be resumed".

### Requirement: Kill workflow instance
The system SHALL support forcefully terminating a workflow instance via `POST /api/ops/instances/{id}/kill`. The system SHALL set `WorkflowInstance.state='STOPPED'`, set all non-terminal `TaskInstance` entries (`NOT_RUN`, `RUNNING`, `PAUSED`, `WAITING`) to `STOPPED`, and set `finished_at` to the current timestamp.

#### Scenario: Kill a running workflow
- **WHEN** a user sends `POST /api/ops/instances/101/kill`
- **THEN** the workflow and all active task instances become `STOPPED`, `finished_at` is set, and HTTP 200 is returned.

#### Scenario: Kill a terminal workflow
- **WHEN** a user sends `POST /api/ops/instances/101/kill` for a workflow with `state='SUCCESS'`
- **THEN** the system returns HTTP 409 with message "Cannot kill a terminal instance".

### Requirement: Pause task instance
The system SHALL support pausing an individual task instance via `POST /api/ops/task-instances/{id}/pause`. Only `NOT_RUN` task instances SHALL be pausable. The task instance state SHALL become `PAUSED`.

#### Scenario: Pause a not-run task instance
- **WHEN** a user sends `POST /api/ops/task-instances/201/pause` for a task with `state='NOT_RUN'`
- **THEN** the task instance state becomes `PAUSED` and HTTP 200 is returned.

### Requirement: Resume task instance
The system SHALL support resuming an individual paused task instance via `POST /api/ops/task-instances/{id}/resume`. The task instance state SHALL become `NOT_RUN`.

#### Scenario: Resume a paused task instance
- **WHEN** a user sends `POST /api/ops/task-instances/201/resume` for a task with `state='PAUSED'`
- **THEN** the task instance state becomes `NOT_RUN` and HTTP 200 is returned.

### Requirement: Kill task instance
The system SHALL support forcefully terminating an individual task instance via `POST /api/ops/task-instances/{id}/kill`. Only non-terminal task instances SHALL be killable. The state SHALL become `STOPPED` and `finished_at` SHALL be set.

#### Scenario: Kill a running task instance
- **WHEN** a user sends `POST /api/ops/task-instances/201/kill` for a task with `state='RUNNING'`
- **THEN** the task instance state becomes `STOPPED`, `finished_at` is set, and HTTP 200 is returned.

### Requirement: Instance state machine integrity
The system SHALL enforce the following state transitions. Any invalid transition SHALL return HTTP 409.

Valid transitions:
- `NOT_RUN` → `RUNNING` (execution starts)
- `NOT_RUN` → `PAUSED` (manual pause)
- `RUNNING` → `SUCCESS` (execution completes)
- `RUNNING` → `FAILED` (execution fails)
- `RUNNING` → `STOPPED` (manual kill)
- `PAUSED` → `NOT_RUN` (resume)
- `PAUSED` → `STOPPED` (kill while paused)
- `FAILED` → `RUNNING` (rerun)

#### Scenario: Invalid state transition rejected
- **WHEN** a task instance is in `SUCCESS` state and a pause is attempted
- **THEN** the system returns HTTP 409 with a descriptive error message.

### Requirement: Frontend instance operation buttons
The frontend SHALL display operation buttons for each instance row in the ops panel:
- `RUNNING` instances: [Pause] [Kill] [View Log]
- `PAUSED` instances: [Resume] [Kill] [View Log]
- `FAILED` instances: [Rerun] [Diagnose] [View Log]
- `SUCCESS` instances: [Rerun] [View Log]
- `STOPPED` instances: [View Log]

#### Scenario: Running instance shows pause and kill
- **WHEN** the ops panel displays a workflow instance with `state='RUNNING'`
- **THEN** the row shows [Pause] [Kill] [View Log] buttons.

#### Scenario: Paused instance shows resume and kill
- **WHEN** the ops panel displays a workflow instance with `state='PAUSED'`
- **THEN** the row shows [Resume] [Kill] [View Log] buttons.

### Requirement: MCP tools for instance operations
The system SHALL expose the following MCP tools via `McpToolRegistry`: `pause_instance`, `resume_instance`, `kill_instance`. All write tools SHALL go through `GatedActionService` at L1 gate level.

#### Scenario: Agent pauses an instance via MCP
- **WHEN** the Agent invokes `pause_instance` with `instanceId=101`
- **THEN** the system pauses the workflow instance via `GatedActionService` and returns the result.

### Requirement: 实例置成功状态转移

实例状态机 SHALL 支持 `set-success` 转移:从 FAILED/STOPPED/RUNNING/PREEMPTED 经乐观 CAS(`WHERE state=?`)推进为 SUCCESS,并触发下游 WAITING 实例的就绪重算与唤醒;NOT_RUN/WAITING 不可置成功。事务内只落状态,唤醒/下发在事务外。

#### Scenario: 失败态置成功
- **WHEN** 对 FAILED 实例执行 set-success
- **THEN** CAS 推进为 SUCCESS,下游 WAITING 被唤醒重评

#### Scenario: 运行态置成功不破坏不变量
- **WHEN** 对 RUNNING 实例 set-success
- **THEN** 状态在事务内 CAS 落 SUCCESS,`dw:wake` 唤醒在事务外发出,不持锁等待

#### Scenario: 非运行态拒绝
- **WHEN** 对 NOT_RUN/WAITING 实例 set-success
- **THEN** 拒绝,状态不变

### Requirement: 实例批量操作语义

系统 SHALL 支持对一组实例批量执行 rerun/kill/set-success,每个实例独立经状态机转移与闸门裁决,逐项汇总结果,部分待批不影响其余项执行。

#### Scenario: 批量逐项独立裁决
- **WHEN** 批量 set-success 一组实例,部分命中 L2 策略
- **THEN** 命中项 PENDING_APPROVAL,其余 EXECUTED,各自状态转移互不阻塞

