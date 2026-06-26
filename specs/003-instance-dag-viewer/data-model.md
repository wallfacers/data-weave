# Data Model: 运营中心实例列表切换与 DAG 查看

**Feature**: 003-instance-dag-viewer
**Date**: 2026-06-26

## New DTOs (Read-Only Views — No Schema Changes)

All entities below are read projections. No new tables, no schema migrations.

### WorkflowInstanceRow

Task flow instance list row.

| Field | Type | Source |
|-------|------|--------|
| id | UUID | workflow_instance.id |
| workflowId | Long | workflow_instance.workflow_id |
| workflowName | String | workflow_def.name (correlated subquery) |
| state | String | workflow_instance.state |
| bizDate | String | workflow_instance.biz_date |
| priority | Integer | workflow_instance.priority |
| triggerType | String | workflow_instance.trigger_type |
| totalTasks | Integer | workflow_instance.total_tasks |
| completedTasks | Integer | workflow_instance.completed_tasks |
| failedTasks | Integer | workflow_instance.failed_tasks |
| startedAt | String (ISO) | workflow_instance.started_at |
| finishedAt | String (ISO) | workflow_instance.finished_at |
| durationMs | Long (nullable) | computed: finishedAt - startedAt |

### WorkflowInstanceQuery

Filter parameters for workflow instance list.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| state | String | No | Single value |
| stateIn | String | No | CSV multi-select |
| triggerType | String | No | CRON/MANUAL/BACKFILL |
| workflowId | Long | No | Filter by specific definition |
| bizDate | String | No | Exact match |
| bizDateFrom | String | No | Range start |
| bizDateTo | String | No | Range end |
| startedAtFrom | String | No | ISO timestamp range start |
| startedAtTo | String | No | ISO timestamp range end |
| page | int | Yes | Base-0 (service) / Base-1 (API) |
| size | int | Yes | 1-200 |

### InstanceDagNode

A DAG node with runtime state overlay.

| Field | Type | Source |
|-------|------|--------|
| nodeKey | String | workflow_def_version.dag_snapshot_json → nodes[].key |
| taskName | String | task_def.name |
| taskId | Long | task_instance.task_id |
| taskInstanceId | UUID | task_instance.id |
| state | String | task_instance.state |
| attempt | Integer | task_instance.attempt |
| startedAt | String (ISO) | task_instance.started_at |
| finishedAt | String (ISO) | task_instance.finished_at |
| durationMs | Long (nullable) | computed |
| nodeType | String | "TASK" |
| posX | Double | dag_snapshot_json → nodes[].x |
| posY | Double | dag_snapshot_json → nodes[].y |

### InstanceDagEdge

| Field | Type | Source |
|-------|------|--------|
| fromNodeKey | String | dag_snapshot_json → edges[].from |
| toNodeKey | String | dag_snapshot_json → edges[].to |
| strength | String | dag_snapshot_json → edges[].strength |

### InstanceDagView

Full instance DAG response.

| Field | Type |
|-------|------|
| workflowInstanceId | UUID |
| workflowName | String |
| workflowVersionNo | Integer |
| triggerType | String |
| state | String |
| bizDate | String |
| nodes | List\<InstanceDagNode\> |
| edges | List\<InstanceDagEdge\> |

### ResolvedCodeView

Result of parameter resolution for "actual code" tab.

| Field | Type | Notes |
|-------|------|-------|
| taskInstanceId | UUID | |
| rawContent | String | Original template (before resolution) |
| resolvedContent | String | After parameter substitution |
| unresolvedPlaceholders | List\<String\> | Placeholders that could not be resolved |
| runMode | String | NORMAL/TEST/BACKFILL |
| isOverride | boolean | True if TEST mode content_override used |
| taskType | String | SHELL/SQL/SPARK/etc. |

## Existing Entities (Referenced, Not Modified)

| Entity | Table | Key Fields Used |
|--------|-------|----------------|
| WorkflowInstance | workflow_instance | id, workflowId, workflowVersionNo, state, triggerType, bizDate, totalTasks, completedTasks, failedTasks, startedAt, finishedAt, priority |
| TaskInstance | task_instance | id, workflowInstanceId, taskId, state, attempt, startedAt, finishedAt, bizDate, runMode, contentOverride, paramsOverride, taskVersionNo |
| WorkflowDefVersion | workflow_def_version | dag_snapshot_json (JSON column with nodes + edges) |
| TaskDefVersion | task_def_version | content (script/SQL template), params_json |
| TaskDef | task_def | name, content, params_json |

## State Values (Both Instance Types)

```
NOT_RUN → WAITING → DISPATCHED → RUNNING → SUCCESS | FAILED | STOPPED
                                              ↘ PREEMPTED → WAITING
PAUSED (可插入在 WAITING 之后)
SKIPPED (frozen nodes)
```

DAG node visual mapping: FAILED/STOPPED = red, RUNNING = blue+animated, SUCCESS = green, WAITING/DISPATCHED = yellow, SKIPPED = grey, PREEMPTED = orange, PAUSED = purple, NOT_RUN = light grey.
