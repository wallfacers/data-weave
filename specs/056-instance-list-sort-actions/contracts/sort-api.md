# API Contracts: 实例列表排序

## 1. GET /api/ops/workflow-instances — 新增 sort 参数

**Existing endpoint** with added `sort` query parameter.

### Request

```
GET /api/ops/workflow-instances?projectId=1&page=1&size=20&sort=scheduledFireTime:desc
```

| Param | Type | Required | Description |
|---|---|---|---|
| `sort` | String | No | Format: `{field}:{dir}`. Omit for default priority-based sort. |

Valid sort fields:
- `scheduledFireTime`
- `bizDate`
- `startedAt`
- `finishedAt`
- `durationMs`

Valid sort directions: `asc`, `desc`

### Behavior

- **Without `sort`**: Existing priority-tier sort (FAILED/STOPPED first, then RUNNING, then rest) + `id DESC`
- **With `sort=field:dir`**: Sorted by specified field, then `id DESC` as tiebreaker
- **With invalid field**: Falls back to default priority sort (no error)
- **Null values**: Always `NULLS LAST` regardless of direction

### Example

```bash
curl "http://localhost:8000/api/ops/workflow-instances?projectId=1&page=1&size=5&sort=startedAt:desc"
```

Response shape unchanged from existing `WorkflowInstanceRow[]`.

---

## 2. GET /api/ops/instances — 新增 sort 参数

**Existing endpoint** with added `sort` query parameter.

### Request

```
GET /api/ops/instances?projectId=1&page=1&size=20&sort=bizDate:asc
```

Same `sort` parameter format as workflow-instances above.

Valid sort fields:
- `scheduledFireTime` (via JOIN from workflow_instance)
- `bizDate`
- `startedAt`
- `finishedAt`
- `durationMs`

### Behavior

Same as workflow-instances. task_instance 的 `scheduled_fire_time` 通过 LEFT JOIN `workflow_instance` 获取，可直接排序。

---

## 3. Frontend toQueryParams serialization

The frontend `DataTable` component already serializes sort into the `sort=field:dir` format:

```typescript
// data-table.ts (existing)
if (query.sort) qs.set("sort", `${query.sort.field}:${query.sort.dir}`)
```

Both `WorkflowInstancesPanel` and `PeriodicInstancesPanel` pass through the fetcher transparently — no frontend contract changes needed for the API layer.
