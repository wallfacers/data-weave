# task-crud Specification

## Purpose
TBD - created by archiving change task-core-capabilities. Update Purpose after archive.
## Requirements
### Requirement: Create task as draft
The system SHALL allow creating a task definition in `DRAFT` status via `POST /api/tasks`. The request body SHALL include `name`, `type`, `content`, and optional fields (`datasourceId`, `targetDatasourceId`, `paramsJson`, `timeoutSec`, `retryMax`, `priority`, `description`, `ownerId`). The response SHALL return the created task with `status=DRAFT`, `currentVersionNo=0`, `hasDraftChange=1`.

#### Scenario: Create a SQL task draft
- **WHEN** a user sends `POST /api/tasks` with `{ "name": "日活计算", "type": "SQL", "content": "SELECT count(*) FROM users WHERE dt='${bizdate}'" }`
- **THEN** the system creates a `task_def` row with `status=DRAFT`, `currentVersionNo=0`, and returns HTTP 201 with the full task object.

#### Scenario: Create task with missing required fields
- **WHEN** a user sends `POST /api/tasks` without `name` or `type`
- **THEN** the system returns HTTP 400 with a validation error message.

#### Scenario: Create task via Agent (MCP tool)
- **WHEN** the Agent invokes the `create_task` MCP tool
- **THEN** the system creates the task via `GatedActionService` (L1 gate) and returns the created task.

### Requirement: Search tasks with pagination and filters
The system SHALL support searching tasks via `GET /api/tasks` with query parameters: `keyword` (name fuzzy match), `type` (exact match), `status` (exact match), `startTime` / `endTime` (created_at range, format `yyyy-MM-dd HH:mm:ss`), `page` (0-based, default 0), `size` (default 20). The response SHALL include `content` (array of tasks), `totalElements`, `totalPages`, `page`, `size`.

#### Scenario: Search by keyword
- **WHEN** a user sends `GET /api/tasks?keyword=GMV&page=0&size=20`
- **THEN** the system returns tasks whose `name` contains "GMV" (case-insensitive), paginated.

#### Scenario: Filter by status and time range
- **WHEN** a user sends `GET /api/tasks?status=ONLINE&startTime=2026-06-01 00:00:00&endTime=2026-06-11 23:59:59`
- **THEN** the system returns ONLINE tasks created within the specified time range.

#### Scenario: Default pagination
- **WHEN** a user sends `GET /api/tasks` with no parameters
- **THEN** the system returns the first page (page=0) of up to 20 tasks, ordered by `created_at DESC`.

### Requirement: Get task detail
The system SHALL support retrieving a single task's full detail via `GET /api/tasks/{id}`. The response SHALL include all task fields plus version history (list of `task_def_version` entries for this task).

#### Scenario: Get existing task
- **WHEN** a user sends `GET /api/tasks/1`
- **THEN** the system returns the task with all fields and version history, ordered by `version_no DESC`.

#### Scenario: Get non-existent task
- **WHEN** a user sends `GET /api/tasks/9999`
- **THEN** the system returns HTTP 404.

#### Scenario: Get soft-deleted task
- **WHEN** a user sends `GET /api/tasks/{id}` for a task with `deleted=1`
- **THEN** the system returns HTTP 404 (soft-deleted tasks are invisible).

### Requirement: Update task in draft status
The system SHALL allow updating a task via `PUT /api/tasks/{id}` only when the task is in `DRAFT` status. Updatable fields: `name`, `type`, `content`, `datasourceId`, `targetDatasourceId`, `paramsJson`, `timeoutSec`, `retryMax`, `priority`, `description`, `ownerId`. On update, `has_draft_change` SHALL be set to 1. `updated_at` and `updated_by` SHALL be refreshed.

#### Scenario: Update a draft task
- **WHEN** a user sends `PUT /api/tasks/1` with `{ "name": "日活计算v2", "content": "SELECT ..." }` for a DRAFT task
- **THEN** the system updates the fields, sets `has_draft_change=1`, and returns the updated task.

#### Scenario: Update an online task
- **WHEN** a user sends `PUT /api/tasks/1` for a task with `status=ONLINE`
- **THEN** the system returns HTTP 409 Conflict with message "Task must be offline before editing".

### Requirement: Soft delete task
The system SHALL support soft-deleting a task via `DELETE /api/tasks/{id}`. The system SHALL set `deleted=1` instead of physically removing the row. An `ONLINE` task MUST be offlined before deletion.

#### Scenario: Delete a draft task
- **WHEN** a user sends `DELETE /api/tasks/1` for a DRAFT task
- **THEN** the system sets `deleted=1` and returns HTTP 204.

#### Scenario: Delete an online task
- **WHEN** a user sends `DELETE /api/tasks/1` for a task with `status=ONLINE`
- **THEN** the system returns HTTP 409 Conflict with message "Task must be offline before deletion".

#### Scenario: Deleted task excluded from search
- **WHEN** a user searches tasks via `GET /api/tasks`
- **THEN** tasks with `deleted=1` SHALL NOT appear in results.

### Requirement: Publish task to online
The system SHALL support publishing a draft task via `POST /api/tasks/{id}/publish`. Publishing SHALL: (1) set `status=ONLINE`, (2) increment `current_version_no`, (3) create an immutable `task_def_version` snapshot, (4) set `has_draft_change=0`.

#### Scenario: Publish a draft task
- **WHEN** a user sends `POST /api/tasks/1/publish`
- **THEN** the system sets `status=ONLINE`, creates version snapshot (e.g., version_no=1), and returns the updated task.

#### Scenario: Publish with remark
- **WHEN** a user sends `POST /api/tasks/1/publish` with `{ "remark": "修复 GMV 计算口径" }`
- **THEN** the version snapshot includes `remark="修复 GMV 计算口径"`.

#### Scenario: Publish an already online task
- **WHEN** a user sends `POST /api/tasks/1/publish` for a task with `status=ONLINE` and `has_draft_change=0`
- **THEN** the system returns HTTP 409 with message "No draft changes to publish".

### Requirement: Offline a task
The system SHALL support offlining an online task via `POST /api/tasks/{id}/offline`. The system SHALL set `status=DRAFT`. Existing task instances are not affected.

#### Scenario: Offline an online task
- **WHEN** a user sends `POST /api/tasks/1/offline`
- **THEN** the system sets `status=DRAFT` and returns the updated task.

#### Scenario: Offline an already offline task
- **WHEN** a user sends `POST /api/tasks/1/offline` for a task with `status=DRAFT`
- **THEN** the system returns HTTP 409 with message "Task is already offline".

### Requirement: Unified date format
All `LocalDateTime` fields serialized in API responses SHALL use the format `yyyy-MM-dd HH:mm:ss` (e.g., `"2026-06-11 14:30:00"`). Input parameters accepting datetime SHALL also accept this format. This SHALL be configured globally via Jackson `Jackson2ObjectMapperBuilderCustomizer`.

#### Scenario: API response date format
- **WHEN** a user retrieves a task via `GET /api/tasks/1`
- **THEN** `createdAt` and `updatedAt` fields are formatted as `"2026-06-11 14:30:00"`.

#### Scenario: Search with date range input
- **WHEN** a user sends `GET /api/tasks?startTime=2026-06-01 00:00:00&endTime=2026-06-11 23:59:59`
- **THEN** the system correctly parses the datetime strings and filters accordingly.

### Requirement: Frontend task management UI
The frontend SHALL provide a task management interface within `task-flow-view` including: (1) a search bar with keyword input, type/status dropdowns, date range picker, and "New Task" button; (2) a paginated task list table with columns: name, type, status badge, priority, created_at (formatted `yyyy-MM-dd HH:mm:ss`), and action menu (view/edit/delete/publish/offline); (3) a side drawer (Sheet) for viewing and editing task details.

#### Scenario: Search and view tasks
- **WHEN** a user opens the task management view
- **THEN** the system displays a search bar, a paginated task list, and each row has an action menu.

#### Scenario: Edit a draft task via drawer
- **WHEN** a user clicks "Edit" on a DRAFT task row
- **THEN** the system opens a side drawer with a form pre-filled with the task's current values. The user can modify fields and save.

#### Scenario: Create a new task via drawer
- **WHEN** a user clicks "+ New Task"
- **THEN** the system opens a side drawer with an empty form. After filling in and clicking "Save Draft", the task is created with `status=DRAFT`.

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

