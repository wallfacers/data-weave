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

