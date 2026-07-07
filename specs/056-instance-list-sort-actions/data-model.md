# Data Model: 实例列表排序 + 操作按钮状态化

本特性不涉及新表或数据迁移。

## Sort Field Mapping（白名单）

排序字段名 → 数据库列名映射，用于 `OpsService` 动态 ORDER BY：

| Sort Field (API) | DB Column (workflow_instance) | DB Column (task_instance) | Null Handling |
|---|---|---|---|
| `scheduledFireTime` | `wi.scheduled_fire_time` | — (task 无此列) | NULLS LAST |
| `bizDate` | `wi.biz_date` | `ti.biz_date` | NULLS LAST |
| `startedAt` | `wi.started_at` | `ti.started_at` | NULLS LAST |
| `finishedAt` | `wi.finished_at` | `ti.finished_at` | NULLS LAST |
| `durationMs` | `wi.duration_ms` | `ti.duration_ms` | NULLS LAST |

**Note**: task_instance 没有 `scheduled_fire_time` 列（scheduled_fire_time 在 workflow_instance 层面）。任务实例面板的 scheduledFireTime 通过 JOIN workflow_instance 获取。当前 `queryInstances` 已 LEFT JOIN `workflow_instance` 取 `scheduled_fire_time`，可直接用于排序。

## Instance State → Action Availability Matrix

| State | 重跑 (Rerun) | 恢复 (Recover) | 停止 (Stop) |
|---|---|---|---|
| RUNNING | ❌ | ❌ | ✅ |
| DISPATCHED | ❌ | ❌ | ✅ |
| SUCCESS | ✅ | ❌ | ❌ |
| FAILED | ✅ | ✅ | ✅ |
| PREEMPTED | ✅ | ✅ | ✅ |
| STOPPED | ✅ | ❌ | ❌ |
| NOT_RUN | ❌ | ❌ | ✅ |
| WAITING | ❌ | ❌ | ✅ |
| PAUSED | ❌ | ❌ | ✅ |

## Contract Changes

### OpsContracts.InstanceQuery (master)

```diff
+ String sortField,   // nullable, e.g. "bizDate"
+ String sortDir       // nullable, "asc" | "desc"
```

### OpsContracts.WorkflowInstanceQuery (master)

```diff
+ String sortField,
+ String sortDir
```

### api.dto.InstanceQuery (API module)

```diff
+ @RequestParam(required = false) String sortField,
+ @RequestParam(required = false) String sortDir
```

Actual controller parsing is via single `sort` param in `field:dir` format (consistent with existing `periodic-workflows` pattern).

## Frontend Sort Persistence

```
URL: /?open=ops&sort=scheduledFireTime:desc

DataTable initialState:
  sort = parseSortFromURL(searchParams) ?? { field: "scheduledFireTime", dir: "desc" }
```
