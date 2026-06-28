# API Contracts: 运营中心实例列表切换与 DAG 查看

**Feature**: 003-instance-dag-viewer
**Base URL**: `/api/ops`

---

## 1. GET /api/ops/workflow-instances

List workflow instances with filtering and pagination.

### Request

```
GET /api/ops/workflow-instances?state=RUNNING&triggerType=CRON&bizDateFrom=2026-06-01&bizDateTo=2026-06-26&page=1&size=20
```

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| state | String | - | Single state value |
| stateIn | String | - | CSV, e.g. "FAILED,STOPPED" |
| triggerType | String | - | CRON, MANUAL, BACKFILL |
| workflowId | Long | - | Filter by specific definition |
| bizDate | String | - | Exact match |
| bizDateFrom | String | - | Range start (inclusive) |
| bizDateTo | String | - | Range end (inclusive) |
| startedAtFrom | String | - | ISO timestamp |
| startedAtTo | String | - | ISO timestamp |
| page | int | 1 | 1-based |
| size | int | 20 | 1-200 |

### Response

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "018f4a2c-...",
        "workflowId": 5,
        "workflowName": "每日数据同步",
        "state": "RUNNING",
        "bizDate": "20260626",
        "priority": 5,
        "triggerType": "CRON",
        "totalTasks": 8,
        "completedTasks": 3,
        "failedTasks": 0,
        "startedAt": "2026-06-26T09:00:00",
        "finishedAt": null,
        "durationMs": null
      }
    ],
    "total": 42,
    "page": 1,
    "size": 20
  }
}
```

---

## 2. GET /api/ops/workflow-instances/{id}/dag

Get instance-level DAG with runtime node states.

### Request

```
GET /api/ops/workflow-instances/018f4a2c-.../dag
```

### Response

```json
{
  "success": true,
  "data": {
    "workflowInstanceId": "018f4a2c-...",
    "workflowName": "每日数据同步",
    "workflowVersionNo": 3,
    "triggerType": "CRON",
    "state": "RUNNING",
    "bizDate": "20260626",
    "nodes": [
      {
        "nodeKey": "import_users",
        "taskName": "导入用户数据",
        "taskId": 42,
        "taskInstanceId": "018f4a2d-...",
        "state": "SUCCESS",
        "attempt": 0,
        "startedAt": "2026-06-26T09:00:00",
        "finishedAt": "2026-06-26T09:01:30",
        "durationMs": 90000,
        "posX": 100.0,
        "posY": 200.0,
        "nodeType": "TASK"
      },
      {
        "nodeKey": "transform_data",
        "taskName": "数据转换",
        "taskId": 43,
        "taskInstanceId": "018f4a2e-...",
        "state": "RUNNING",
        "attempt": 0,
        "startedAt": "2026-06-26T09:01:31",
        "finishedAt": null,
        "durationMs": null,
        "posX": 300.0,
        "posY": 200.0,
        "nodeType": "TASK"
      }
    ],
    "edges": [
      {
        "fromNodeKey": "import_users",
        "toNodeKey": "transform_data",
        "strength": "NORMAL"
      }
    ]
  }
}
```

### Error Cases

| Status | Condition |
|--------|-----------|
| 404 | WorkflowInstance not found |
| 404 | DAG snapshot missing (workflow_def_version.dag_snapshot_json is null — legacy instances) |

---

## 3. GET /api/ops/task-instances/{id}/resolved-code

Get the actual executed script/code with parameters resolved.

### Request

```
GET /api/ops/task-instances/018f4a2d-.../resolved-code
```

### Response

```json
{
  "success": true,
  "data": {
    "taskInstanceId": "018f4a2d-...",
    "rawContent": "#!/bin/bash\necho 'Loading data for ${yyyyMMdd}...'\nmysqldump -h ${host} -d ${db}",
    "resolvedContent": "#!/bin/bash\necho 'Loading data for 20260626...'\nmysqldump -h 10.0.1.50 -d analytics",
    "unresolvedPlaceholders": [],
    "runMode": "NORMAL",
    "isOverride": false,
    "taskType": "SHELL"
  }
}
```

### With Unresolved Placeholders

```json
{
  "success": true,
  "data": {
    "taskInstanceId": "018f4a2d-...",
    "rawContent": "SELECT * FROM ${table} WHERE dt=${yyyyMMdd}",
    "resolvedContent": "SELECT * FROM ${table} WHERE dt=20260626",
    "unresolvedPlaceholders": ["${table}"],
    "runMode": "NORMAL",
    "isOverride": false,
    "taskType": "SQL"
  }
}
```

### Error Cases

| Status | Condition |
|--------|-----------|
| 404 | TaskInstance not found |
| 400 | Task type has no textual content (e.g., purely config-driven task) |

---

## 4. GET /api/ops/task-instances/{id}/resolved-config

Get the runtime configuration with parameters resolved.

### Request

```
GET /api/ops/task-instances/018f4a2d-.../resolved-config
```

### Response

```json
{
  "success": true,
  "data": {
    "taskInstanceId": "018f4a2d-...",
    "taskType": "SHELL",
    "timeoutSeconds": 300,
    "retryStrategy": "FIXED(3,60s)",
    "resourceLimit": "cpu=2,mem=1024Mi",
    "rawParamsJson": "{\"host\":\"10.0.1.50\",\"db\":\"${dbName}\"}",
    "resolvedParamsJson": "{\"host\":\"10.0.1.50\",\"db\":\"analytics\"}",
    "unresolvedPlaceholders": [],
    "runMode": "NORMAL",
    "isOverride": false,
    "taskVersionNo": 3
  }
}
```

### Test Run with Override

```json
{
  "success": true,
  "data": {
    "taskInstanceId": "018f4a2d-...",
    "taskType": "SHELL",
    "timeoutSeconds": 600,
    "retryStrategy": "FIXED(5,30s)",
    "resourceLimit": "cpu=4,mem=2048Mi",
    "rawParamsJson": "{\"host\":\"10.0.1.50\",\"db\":\"analytics\"}",
    "resolvedParamsJson": "{\"host\":\"10.0.1.50\",\"db\":\"analytics\"}",
    "unresolvedPlaceholders": [],
    "runMode": "TEST",
    "isOverride": true,
    "originalParamsJson": "{\"host\":\"\${host}\",\"db\":\"\${dbName}\"}",
    "originalTimeoutSeconds": 300,
    "taskVersionNo": 3
  }
}
```

### Error Cases

| Status | Condition |
|--------|-----------|
| 404 | TaskInstance not found |
