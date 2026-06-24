# cron-scheduler Specification

## Purpose
TBD - created by archiving change task-core-capabilities. Update Purpose after archive.
## Requirements
### Requirement: Periodic cron scanning
The system SHALL run a `CronScheduler` component that executes every 60 seconds. On each tick, it SHALL query `workflow_def` for rows where `schedule_type='CRON'`, `status='ONLINE'`, and `deleted=0`, then evaluate whether each workflow's next fire time (based on `cron` expression and `last_fire_time`) has been reached.

#### Scenario: Workflow fires on schedule
- **WHEN** a workflow has `cron='0 0 8 * * ?'` (daily at 8:00), `last_fire_time='2026-06-10 08:00:00'`, and the current time is `2026-06-11 08:01:00`
- **THEN** the scheduler detects the fire time has passed and triggers the workflow.

#### Scenario: Workflow not yet due
- **WHEN** a workflow has `cron='0 0 8 * * ?'`, `last_fire_time='2026-06-11 08:00:00'`, and the current time is `2026-06-11 10:00:00`
- **THEN** the scheduler does NOT trigger the workflow (already fired today).

#### Scenario: Workflow outside schedule window
- **WHEN** a workflow has `schedule_start='2026-07-01 00:00:00'` and the current time is `2026-06-11 08:00:00`
- **THEN** the scheduler skips this workflow (not yet in effective window).

#### Scenario: Workflow past schedule end
- **WHEN** a workflow has `schedule_end='2026-06-01 00:00:00'` and the current time is `2026-06-11 08:00:00`
- **THEN** the scheduler skips this workflow (past effective window).

### Requirement: Trigger workflow execution

When the scheduler determines a workflow should fire, it SHALL: (1) create a `WorkflowInstance` with `trigger_type='CRON'`, `state='RUNNING'`, `biz_date` set to the previous day (`yyyy-MM-dd`); (2) set `total_tasks` to the count of `workflow_node` entries for this workflow; (3) materialize a `TaskInstance` for each node — real `TASK` nodes at `state='WAITING'`, `VIRTUAL` nodes materialized directly to `state='SUCCESS'` (zero-load) — and hand them to the `SchedulerKernel` DAG readiness gate, where downstream nodes unlock as their upstream predecessors reach `SUCCESS` per edge `strength` (execution order is governed by the DAG gate, NOT node creation order); (4) update `workflow_def.last_fire_time` to the current timestamp. CRON instances SHALL honor cross-cycle dependencies: a node with an enabled `workflow_dependency` (`biz_date >= earliest_biz_date`) MUST additionally wait for the depended node's previous-cycle instance (`biz_date` offset by `date_offset`) to reach `SUCCESS`; a node whose `biz_date` is before `earliest_biz_date` is exempt (first-cycle bootstrap).

#### Scenario: Trigger a workflow with 3 nodes

- **WHEN** the scheduler fires a workflow that has 3 `workflow_node` entries (2 real TASK, 1 VIRTUAL)
- **THEN** the system creates 1 `WorkflowInstance` (with `total_tasks=3`), 2 real `TaskInstance` at `state='WAITING'` and 1 VIRTUAL at `state='SUCCESS'`, then the DAG readiness gate dispatches runnable nodes as their predecessors succeed (not in creation order)

#### Scenario: Prevent duplicate firing

- **WHEN** a workflow fires at 08:00 and `last_fire_time` is updated to `08:00:00`
- **THEN** the next scheduler tick at 08:01 does NOT trigger the same workflow again.

#### Scenario: CRON instance honors cross-cycle self-dependency

- **WHEN** a CRON-fired instance node has a self-dependency (`date_offset=LAST_DAY`) with `earliest_biz_date <= today`, and its previous-day instance is not yet `SUCCESS`
- **THEN** the node stays `WAITING` until the previous-day instance reaches `SUCCESS`, then becomes runnable

#### Scenario: First-cycle exempt from cross-cycle check

- **WHEN** a CRON-fired instance node's `biz_date` is before its cross-cycle dependency's `earliest_biz_date`
- **THEN** the node skips the cross-cycle check and is runnable per same-cycle dependencies only

### Requirement: Schedule type support
The system SHALL support three `schedule_type` values: `MANUAL` (only triggered by user/Agent), `CRON` (triggered by scheduler), `DEPENDENCY` (triggered by upstream workflow completion — Phase B does not implement dependency evaluation; these workflows are skipped by the scheduler).

#### Scenario: Manual workflow not triggered by scheduler
- **WHEN** a workflow has `schedule_type='MANUAL'`
- **THEN** the scheduler skips this workflow regardless of cron expression.

#### Scenario: Dependency workflow not triggered by scheduler
- **WHEN** a workflow has `schedule_type='DEPENDENCY'`
- **THEN** the scheduler skips this workflow (dependency evaluation is Phase P1).

### Requirement: Scheduler logging
The scheduler SHALL log each tick's activity: number of workflows scanned, number triggered, any errors encountered. Logs SHALL be written to the application log (SLF4J) at INFO level.

#### Scenario: Normal tick logged
- **WHEN** the scheduler completes a tick that scanned 5 workflows and triggered 1
- **THEN** the system logs: `[CronScheduler] tick: scanned=5, triggered=1`.

#### Scenario: Error during trigger logged
- **WHEN** a workflow trigger fails (e.g., database error)
- **THEN** the system logs the error at ERROR level with the workflow ID and continues processing other workflows (does not crash).

### Requirement: CronScheduler startup and shutdown
The `CronScheduler` SHALL be a Spring-managed bean that starts automatically when the application starts. It SHALL be gracefully shut down when the application stops (no mid-execution triggers are interrupted).

#### Scenario: Application startup
- **WHEN** the Spring Boot application starts
- **THEN** the `CronScheduler` bean is initialized and begins its periodic scanning.

#### Scenario: Application shutdown
- **WHEN** the application receives a shutdown signal
- **THEN** the scheduler stops accepting new triggers and waits for in-progress triggers to complete.

