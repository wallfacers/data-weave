# Freshness API Contracts
# Feature: 041-freshness-redesign
# Date: 2026-07-03

## 1. GET /api/freshness/dashboard — 概览区数据

### Request

```
GET /api/freshness/dashboard?projectId={projectId}

Query Parameters:
  projectId   Long    可选（优先 TenantContext，回退查询参数）
```

### Response

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "summary": {
      "total": 178,
      "fresh": 128,
      "aging": 35,
      "stale": 12,
      "never": 3
    },
    "trend": {
      "totalDelta": 5,
      "freshDelta": 12,
      "agingDelta": -3,
      "staleDelta": 0
    },
    "snapshotDate": "2026-07-03"
  }
}
```

### Field Notes

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| summary.total | int | no | 总任务数 |
| summary.fresh | int | no | FRESH 档位任务数 |
| summary.aging | int | no | AGING 档位任务数 |
| summary.stale | int | no | STALE 档位任务数 |
| summary.never | int | no | NEVER 档位任务数 |
| trend | object | **yes** | 无前一天快照时为 null |
| trend.totalDelta | int | no | 总任务数日环比（绝对值） |
| trend.freshDelta | int | no | FRESH 数量日环比 |
| trend.agingDelta | int | no | AGING 数量日环比 |
| trend.staleDelta | int | no | STALE 数量日环比 |
| snapshotDate | string | no | 当前分布数据日期 |

### Error Cases

| Scenario | HTTP Status | code | message |
|----------|-------------|------|---------|
| projectId 缺失 | 400 | project.required | — |
| 首次使用（无快照） | 200 | 0 | trend=null |

---

## 2. GET /api/freshness — 表格数据（扩展）

### Request

```
GET /api/freshness?projectId={projectId}&tiers={tiers}&taskName={taskName}&sort={sort}&page={page}&size={size}

Query Parameters:
  projectId   Long      可选（优先 TenantContext）
  tiers       String    可选，逗号分隔：FRESH,AGING,STALE,NEVER
  taskName    String    可选，模糊搜索
  sort        String    默认 worst_first | best_first
  page        int       默认 1（1-based）
  size        int       默认 20（最大 200）
```

### Response

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "taskId": 42,
        "name": "用户行为日志同步",
        "workflowName": "日志采集工作流",
        "scheduleType": "CRON",
        "scheduleHuman": "每天 06:00",
        "tier": "AGING",
        "lastSuccessAt": "2026-07-02T22:00:00Z",
        "ageHours": 8,
        "trend7Days": [4, 4, 3, 3, 2, 2, 1]
      }
    ],
    "total": 178,
    "page": 0,
    "size": 20
  }
}
```

### FreshnessRow Fields (Extended)

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| taskId | Long | no | 任务 id |
| name | String | no | 任务名 |
| workflowName | String | **yes** | 所属工作流名（无关联时为 null） |
| scheduleType | String | **yes** | MANUAL / CRON / DEPENDENCY |
| scheduleHuman | String | **yes** | 人读调度周期 |
| tier | String | no | FRESH / AGING / STALE / NEVER |
| lastSuccessAt | String | **yes** | ISO-8601 UTC instant（NEVER 为 null） |
| ageHours | Long | **yes** | 距今小时数（NEVER 为 null） |
| trend7Days | int[] | no | 最近 7 天档位数值（0-7 个元素） |

### trend7Days 约定

- 数组长度 0-7，按日期升序（index 0 = 最早）
- 数值映射：4=FRESH, 3=AGING, 2=STALE, 1=NEVER
- 新建任务（无快照）→ 空数组 `[]`
- 某天快照缺失 → 跳过该天（不补零/不插值）

---

## 3. AssetSubscription — 新鲜度告警订阅（复用）

### Subscribe

```
POST /api/assets/subscribe
Content-Type: application/json

{
  "targetType": "task",
  "targetId": 42,
  "changeFilter": "freshness"
}
```

### Unsubscribe

```
DELETE /api/assets/subscribe?targetType=task&targetId=42&changeFilter=freshness
```

### List Subscriptions

```
GET /api/assets/subscriptions?targetType=task
→ [ { "targetId": 1, "targetName": "...", "changeFilter": "freshness" }, ... ]
```

**Note**: 复用现有 `AssetSubscription` API，不新增端点。

---

## 4. Internal: FreshnessSnapshotJob（无 HTTP 端点）

每天 02:00 由 `@Scheduled` 触发，内部流程：

1. 获取 PG advisory lock `freshness_snapshot`
2. 枚举所有 (tenant_id, project_id)
3. 对每个 (tenant, project):
   a. 执行 FreshnessService 聚合查询
   b. INSERT INTO freshness_task_daily ... ON CONFLICT DO NOTHING
   c. 按 tier 聚合 → INSERT INTO freshness_daily_snapshot ... ON CONFLICT DO NOTHING
4. DELETE FROM freshness_task_daily WHERE snapshot_date < NOW() - 90 days
5. DELETE FROM freshness_daily_snapshot WHERE snapshot_date < NOW() - 90 days
6. 释放 advisory lock
