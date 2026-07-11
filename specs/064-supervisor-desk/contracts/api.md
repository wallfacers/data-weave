# API Contracts: 监督席

**Feature**: 064-supervisor-desk
**Date**: 2026-07-11

## Modified Endpoints

### GET /api/events — 新增 incidentOnly 参数

**Change**: 新增可选查询参数 `incidentOnly`

| Param | Type | Default | Description |
|---|---|---|---|
| `incidentOnly` | `boolean` | `false` | `true` = 仅返回已关联工单的信号（`health_event.ref_id` 存在于 `incident.source_ref_id` 且工单未 CLOSED） |

**Response**: 不变 — `{ items: HealthEvent[], total: number }`

**Implementation**: 后端 `EventCenterService` SQL 添加可选 JOIN：
```sql
-- 当 incidentOnly=true 时
SELECT he.* FROM health_event he
INNER JOIN incident inc ON he.ref_id = inc.source_ref_id
  AND inc.state IN ('OPEN','MITIGATING','RESOLVED','SUPPRESSED')
  AND inc.deleted = 0
WHERE he.tenant_id = ? AND he.deleted = 0
ORDER BY he.last_occurred_at DESC
```

### GET /api/incidents — 响应新增字段

**Change**: 响应 `IncidentCard` 新增两个字段

```diff
{
  "active": [{ ... }],
  "recentResolved": [{ ... }],
  "activeCount": 5,
  "recentResolvedCount": 3
}
```

每个 `IncidentCard` 新增：
```json
{
  "healByType": "TASK_SUCCESS",   // NEW - nullable
  "healByRefId": "100"            // NEW - nullable
}
```

### GET /api/incidents/{id} — 同上

`IncidentDetail.incident` 同样新增 `healByType`/`healByRefId` 字段。

### POST /api/incidents/{id}/rerun — NO CHANGE

### POST /api/incidents/{id}/suppress — NO CHANGE

### POST /api/incidents/{id}/notes — NO CHANGE

## Unchanged Endpoints

- `GET /api/incidents/history` — unchanged
- `POST /api/incidents/{id}/unsuppress` — unchanged
- `POST /api/approvals/{actionId}/approve|reject` — unchanged
- `GET /api/events/subscriptions` — unchanged
- `POST /api/events/subscriptions` — unchanged
- `DELETE /api/events/subscriptions/{id}` — unchanged
- `GET /api/alert/channels` — unchanged

## Internal Contracts (No REST surface)

### Signal Hook: openOrAttach 签名变更

**Current**: `signature = "T:" + taskId + ":" + failureClass` (failureClass ∈ {TIMEOUT, EXIT_NONZERO, WORKER_RESTART, WORKER_LOST, UNKNOWN})

**New**: `signature = "T:" + taskId + ":" + failureReason` (failureReason = raw signal value, e.g. `EXIT_CODE_-1`)

**Current**: No heal condition stored.

**New**: On INSERT, also set `heal_by_type` / `heal_by_ref_id`:
- TASK_FAILED → `heal_by_type='TASK_SUCCESS'`, `heal_by_ref_id=taskId`
- TASK_TIMEOUT → `heal_by_type='TASK_SUCCESS'`, `heal_by_ref_id=taskId`
- NODE_OFFLINE → `heal_by_type='NODE_ONLINE'`, `heal_by_ref_id=nodeCode`
- SLA_BREACH → `heal_by_type=null`, `heal_by_ref_id=null` (no auto-heal for SLA)

### Signal Hook: healByTask 精确匹配

**Current**: `WHERE source_kind='TASK' AND source_ref_id=? AND state IN ('OPEN','MITIGATING')`

**New**: `WHERE heal_by_type=? AND heal_by_ref_id=? AND state IN ('OPEN','MITIGATING')`

调用方 `IncidentHealListener.onTaskSucceeded` 改为传 `('TASK_SUCCESS', taskId)`.
