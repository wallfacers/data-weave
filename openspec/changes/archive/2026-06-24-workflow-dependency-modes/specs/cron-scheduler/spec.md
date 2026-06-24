## MODIFIED Requirements

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
