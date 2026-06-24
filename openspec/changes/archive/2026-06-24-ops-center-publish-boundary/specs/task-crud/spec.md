## MODIFIED Requirements

### Requirement: Offline a task
The system SHALL support offlining an online task via `POST /api/tasks/{id}/offline`. The system SHALL set `status=DRAFT`. Existing task instances are not affected.

A task that is referenced by any `ONLINE` workflow (i.e. appears as a `TASK` node whose `task_id` matches in a `workflow_def` with `status=ONLINE`, per its live DAG nodes) MUST NOT be offlined. The system MUST reject such an offline request with HTTP 409 and a localized error (code `task.referenced_by_online_workflow`) naming the referencing workflow(s). This mirrors the "ONLINE workflow cannot be deleted" invariant: a task running under a production workflow cannot be pulled out from under it. To offline the task, the operator MUST first offline every referencing workflow or remove the corresponding node from each workflow's DAG.

#### Scenario: Offline an online task
- **WHEN** a user sends `POST /api/tasks/1/offline` for a task not referenced by any ONLINE workflow
- **THEN** the system sets `status=DRAFT` and returns the updated task.

#### Scenario: Offline an already offline task
- **WHEN** a user sends `POST /api/tasks/1/offline` for a task with `status=DRAFT`
- **THEN** the system returns HTTP 409 with message "Task is already offline".

#### Scenario: Offline a task referenced by an online workflow
- **WHEN** a user sends `POST /api/tasks/1/offline` for a task that is a `TASK` node in an `ONLINE` workflow
- **THEN** the system rejects with HTTP 409 (code `task.referenced_by_online_workflow`), names the referencing workflow(s), and leaves the task `ONLINE`

#### Scenario: Offline allowed after referencing workflow offlined
- **WHEN** the only ONLINE workflow referencing task 1 is offlined, then a user sends `POST /api/tasks/1/offline`
- **THEN** the system sets `status=DRAFT` and returns the updated task
