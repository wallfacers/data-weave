# API Contract: GET /api/ops/summary

**Feature**: 040-ops-summary-date-filter | **Date**: 2026-07-02

## Endpoint

```
GET /api/ops/summary
```

## Request Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `projectId` | `Long` | no | TenantContext default | 项目 ID（036 项目隔离） |
| `bizDate` | `String` | **no** (新增) | none (全量) | 业务日期，格式 `yyyy-MM-dd`。传入时仅统计该日期的任务实例；不传时统计全部（向后兼容） |

## Response

### Success (200)

```json
{
  "code": 0,
  "data": {
    "total": 42,
    "success": 35,
    "failed": 3,
    "running": 4,
    "failedInstances": [
      {
        "id": "uuid...",
        "taskDefName": "ods_user_sync",
        "state": "FAILED",
        "bizDate": "2026-07-02",
        "failureReason": "TIMEOUT",
        "...": "..."
      }
    ]
  }
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `total` | `int` | 任务实例总数（`run_mode=NORMAL`，受 `bizDate` 过滤） |
| `success` | `int` | `state=SUCCESS` 的数量 |
| `failed` | `int` | `state=FAILED` 的数量 |
| `running` | `int` | `state=RUNNING` 的数量 |
| `failedInstances` | `TaskInstance[]` | 失败实例清单（同样受 `bizDate` 过滤） |

### Error Responses

| Code | Condition |
|------|-----------|
| 200 + `data=null` | 后端未启动时的 fallback（与现有行为一致） |
| 400 | `bizDate` 格式非法（非 `yyyy-MM-dd`） |

### Example

```bash
# 统计 2026-07-02 的任务实例
curl -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/summary?projectId=1&bizDate=2026-07-02"

# 统计全部（向后兼容，行为不变）
curl -H "Authorization: Bearer $DW_TOKEN" \
  "http://localhost:8000/api/ops/summary?projectId=1"
```

## Unchanged Endpoint

`GET /api/ops/eta-summary` — 不改。SLA 风险保持全局视角，不受 `bizDate` 影响。
