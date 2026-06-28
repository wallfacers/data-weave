# API Contract Changes: 工作流实例运维操作

**Date**: 2026-06-26

## 新增端点

### 1. POST /api/ops/task-instances/{id}/rerun

重跑单个失败/停止/成功的任务实例。

**Request**: 无 body
**Response**:
```json
{
  "code": 0,
  "data": {
    "success": true,
    "instanceId": "uuid",
    "newState": "WAITING"
  }
}
```

**Error codes**:
- `task.not_terminal` — 任务未处于终态，无法重跑
- `task.not_found` — 任务实例不存在

---

### 2. POST /api/ops/task-instances/{id}/set-success

将单个任务实例强制标记为成功。

**Request**: 无 body
**Response**:
```json
{
  "code": 0,
  "data": {
    "success": true,
    "instanceId": "uuid",
    "newState": "SUCCESS"
  }
}
```

**Error codes**:
- `task.set_success_invalid_state` — 当前状态不允许置成功 (仅 FAILED/STOPPED/RUNNING/PREEMPTED 可操作)
- `task.not_found` — 任务实例不存在

---

## 修改的端点

### 3. GET /api/ops/workflow-instances — 响应新增 env 字段

**变更**: `WorkflowInstanceRow` 新增 `env` 字段

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "uuid",
        "workflowName": "...",
        "triggerType": "CRON",
        "env": "PROD",          // NEW
        "state": "RUNNING",
        "bizDate": "2026-06-26",
        "totalTasks": 10,
        "completedTasks": 5,
        "failedTasks": 0,
        "startedAt": "...",
        "duration": "..."
      }
    ],
    "total": 100,
    "page": 1,
    "size": 20
  }
}
```

### 4. GET /api/ops/workflow-instances/{id} — 响应新增 env 字段

**变更**: `WorkflowInstanceDetail` 新增 `env` 字段

```json
{
  "code": 0,
  "data": {
    "id": "uuid",
    "workflowId": 123,
    "env": "PROD",            // NEW
    "state": "FAILED",
    "bizDate": "2026-06-26",
    "tasks": [...]
  }
}
```

### 5. GET /api/ops/instances — 响应新增 env 字段

**变更**: `InstanceRow` 新增 `env` 字段（透传自所属 workflow_instance）

### 6. POST /api/ops/instances/batch — 新增 100 上限校验

**变更**: 请求 `ids` 数组长度超过 100 时返回错误

**Error response**:
```json
{
  "code": 2,
  "msg": "批量操作最多支持 100 个实例，当前选中了 150 个",
  "data": null
}
```

**Error code**: `batch.too_many`

### 7. 所有写操作端点 — 新增 DEV 环境限制

以下端点对 `env=DEV` 的实例返回错误（停止除外）：
- `POST /api/ops/instances/{id}/rerun` → `workflow.dev_limited`
- `POST /api/ops/instances/{id}/recover` → `workflow.dev_limited`
- `POST /api/ops/instances/{id}/pause` → `workflow.dev_limited`
- `POST /api/ops/instances/{id}/resume` → `workflow.dev_limited`
- `POST /api/ops/task-instances/{id}/rerun` → `task.dev_limited`
- `POST /api/ops/task-instances/{id}/set-success` → `task.dev_limited`
- `POST /api/ops/instances/batch` → 过滤 DEV 实例或全部拒绝

### 8. POST /api/ops/instances/{id}/rerun — 新增状态守卫

**变更**: `RecoveryService.rerunAll()` 对非终态实例返回错误

**Error response**:
```json
{
  "code": 2,
  "msg": "仅终态（SUCCESS/FAILED/STOPPED）实例可以重跑，当前状态为 RUNNING",
  "data": null
}
```

**Error code**: `workflow.not_terminal`

---

## 请求/响应格式约定

- 成功: `{ "code": 0, "data": {...} }`
- 错误: `{ "code": 2, "msg": "本地化错误消息", "data": null }`
- 认证: `Authorization: Bearer <jwt>` (所有端点)
- 分页: `{ "items": [...], "total": N, "page": N, "size": N }`
