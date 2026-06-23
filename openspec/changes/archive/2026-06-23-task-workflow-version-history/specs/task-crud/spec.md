## ADDED Requirements

### Requirement: Rollback task to historical version

The system SHALL support rolling back a task to a historical version via `POST /api/tasks/{id}/rollback` with body `{ "versionNo": N }`. The system MUST look up the `task_def_version` snapshot for the given `versionNo` and write its fields (`name`, `type`, `content`, `datasourceId`, `targetDatasourceId`, `paramsJson`, `timeoutSec`, `retryMax`, `priority`, `description`) back to `task_def`. The system MUST set `has_draft_change=1`. The system MUST NOT change `current_version_no` or `status`. If the `versionNo` does not exist, the system MUST return HTTP 404. The rollback operation MUST pass through `GatedActionService` and leave an audit trail.

#### Scenario: Rollback task to a historical version
- **WHEN** a user sends `POST /api/tasks/1/rollback` with `{ "versionNo": 2 }`
- **THEN** the system writes v2 snapshot fields back to `task_def`, sets `has_draft_change=1`, `current_version_no` and `status` remain unchanged

#### Scenario: Rollback to non-existent version
- **WHEN** a user sends `POST /api/tasks/1/rollback` with `{ "versionNo": 999 }`
- **THEN** the system returns HTTP 404

#### Scenario: Rollback via Agent (MCP tool)
- **WHEN** the Agent invokes the `rollback_task` MCP tool
- **THEN** the system rolls back via `GatedActionService` (L1 gate) and returns the updated task
