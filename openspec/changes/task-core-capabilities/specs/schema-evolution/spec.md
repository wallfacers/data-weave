## ADDED Requirements

### Requirement: task_def new fields
The `task_def` table SHALL include the following new columns: `priority INTEGER DEFAULT 5` (execution priority, 0=highest, 9=lowest), `description VARCHAR(512)` (task description for display), `owner_id BIGINT` (task owner for notifications). All new columns SHALL be nullable with sensible defaults.

#### Scenario: Create task with priority
- **WHEN** a task is created with `priority=1`
- **THEN** the `task_def.priority` column stores 1.

#### Scenario: Default priority
- **WHEN** a task is created without specifying `priority`
- **THEN** the `task_def.priority` column defaults to 5.

### Requirement: task_instance new fields and type change
The `task_instance` table SHALL include: `exit_code INTEGER` (process exit code), `error_message VARCHAR(2000)` (structured error summary). The `log` column SHALL be changed from `VARCHAR(4000)` to `TEXT`. The `state` column SHALL accept the new value `PAUSED`.

#### Scenario: Record exit code on task completion
- **WHEN** a task completes with exit code 0
- **THEN** `task_instance.exit_code` stores 0.

#### Scenario: Record error message on failure
- **WHEN** a task fails with an error
- **THEN** `task_instance.error_message` stores a summary of the error (up to 2000 chars).

### Requirement: workflow_def new fields
The `workflow_def` table SHALL include: `last_fire_time TIMESTAMP` (last scheduler trigger time, for preventing duplicate triggers), `priority INTEGER DEFAULT 5` (workflow execution priority), `timeout_sec INTEGER` (workflow-level timeout in seconds).

#### Scenario: Scheduler updates last_fire_time
- **WHEN** the scheduler triggers a workflow at `2026-06-11 08:00:00`
- **THEN** `workflow_def.last_fire_time` is set to `2026-06-11 08:00:00`.

### Requirement: workflow_instance progress counters
The `workflow_instance` table SHALL include: `total_tasks INTEGER DEFAULT 0` (total nodes in workflow), `completed_tasks INTEGER DEFAULT 0` (nodes completed successfully), `failed_tasks INTEGER DEFAULT 0` (nodes that failed). The `state` column SHALL accept the new value `PAUSED`.

#### Scenario: Progress tracking during execution
- **WHEN** a workflow with 5 nodes has 3 completed and 1 failed
- **THEN** `total_tasks=5`, `completed_tasks=3`, `failed_tasks=1`.

### Requirement: worker_nodes capacity fields
The `worker_nodes` table SHALL include: `max_concurrent_tasks INTEGER DEFAULT 10` (maximum concurrent tasks per node), `node_group VARCHAR(64)` (logical worker group name).

#### Scenario: Worker node with group assignment
- **WHEN** a worker node registers with `node_group='high-cpu'`
- **THEN** `worker_nodes.node_group` stores `'high-cpu'`.

### Requirement: task_def_version snapshot sync
The `task_def_version` table SHALL include `priority INTEGER` and `description VARCHAR(512)` columns to match the corresponding fields in `task_def`. When a version snapshot is created during publish, these fields SHALL be copied from the current `task_def`.

#### Scenario: Version snapshot includes new fields
- **WHEN** a task with `priority=1` and `description='核心GMV计算'` is published
- **THEN** the created `task_def_version` row includes `priority=1` and `description='核心GMV计算'`.

### Requirement: Fix WorkflowDependency.dateOffset type
The `WorkflowDependency` Java entity's `dateOffset` field SHALL be changed from `Integer` to `String` to match the `workflow_dependency.date_offset VARCHAR(32)` column and the seed data values (`'CURRENT_DAY'`, `'LAST_DAY'`).

#### Scenario: Persist date offset string
- **WHEN** a `WorkflowDependency` is saved with `dateOffset='CURRENT_DAY'`
- **THEN** the value is persisted correctly without type conversion errors.

### Requirement: Fix AlertRule and NotificationChannel createdBy type
The `AlertRule` and `NotificationChannel` Java entities' `createdBy` and `updatedBy` fields SHALL be changed from `String` to `Long` to match the `BIGINT` column type in the schema.

#### Scenario: Persist alert rule with Long createdBy
- **WHEN** an `AlertRule` is saved with `createdBy=1L`
- **THEN** the value is persisted correctly without type conversion errors.

### Requirement: Create WorkflowDefVersion entity
A `WorkflowDefVersion` Java entity class SHALL be created to map to the `workflow_def_version` table, enabling programmatic access via Spring Data JDBC repositories.

#### Scenario: Query workflow version history
- **WHEN** a `WorkflowDefVersionRepository` queries by `workflowId`
- **THEN** the system returns a list of `WorkflowDefVersion` entities ordered by `version_no DESC`.

### Requirement: H2 and PostgreSQL compatibility
All DDL changes SHALL be compatible with both H2 (development) and PostgreSQL (production). `ALTER COLUMN ... TYPE TEXT` syntax SHALL be used for type changes. All new columns SHALL have DEFAULT values or be nullable.

#### Scenario: Migration on H2
- **WHEN** the application starts with H2 profile
- **THEN** `schema.sql` applies all DDL changes without errors.

#### Scenario: Migration on PostgreSQL
- **WHEN** the migration SQL is executed on PostgreSQL
- **THEN** all ALTER TABLE statements succeed without errors.
