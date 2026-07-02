# API Contract: 任务流列表查询

**Feature**: 039-workflow-list-fields | **Date**: 2026-07-02

> 既有端点的**增量扩展**（向后兼容：新 query 参数均可选、响应 items 增字段）。鉴权 / 项目隔离 / 信封结构不变。

## 端点

- `GET /api/ops/periodic-workflows`（scheduleType=CRON）
- `GET /api/ops/manual-workflows`（scheduleType=MANUAL）

两者共享 `OpsService#queryWorkflows`，仅 `scheduleType` 不同。

## Query 参数（🆕 = 本 feature 新增，均可选）

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 名称模糊（LIKE %?%） |
| `hasDraftChange` | int | 否 | 周期端点专有 |
| `recentResult` | string | 否 | SUCCESS / FAILED / NEVER |
| `catalogNodeId` | long | 否 | |
| `createdBy` | long | 否 | |
| `projectId` | long | 否 | 项目隔离 |
| `page` | int | 否 | 默认 1（1-based） |
| `size` | int | 否 | 默认 20，上限 200 |
| 🆕 `priorityTier` | string | 否 | `high`(priority 0–2) / `normal`(3–9)；缺省=不限 |
| 🆕 `sort` | string | 否 | `<field>:<dir>`，如 `priority:desc`；field 白名单仅 `priority`；缺省=按 id |

> 前端 `sort` 经 `toQueryParams` 序列化为 `sort=priority:desc`；后端 `OpsController` 解析为 `sortField`/`sortDir` 传入 `WorkflowQuery`。

## 响应（`Page` 信封；items 增字段标 🆕）

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": 12,
        "name": "每日ETL",
        "description": "每日凌晨抽取",
        "cron": "0 30 1 * * ?",
        "status": "ONLINE",
        "currentVersionNo": 3,
        "hasDraftChange": 0,
        "lastFireTime": "2026-07-02T01:30:00",
        "priority": 1,
        "timeoutSec": 3600,
        "updatedAt": "2026-07-01T10:00:00",
        "updatedBy": 5,
        "catalogNodeId": null,
        "recentTriggerResult": "SUCCESS",
        "nextTriggerTime": "2026-07-03T01:30:00"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

- `nextTriggerTime` 🆕：ISO 字符串；null = 未回填（首轮回填前）或手动流（MANUAL 无 cron）。

## 契约不变项

- 信封结构 `{ code, data: { items, total, page, size } }`（`code===0` 表成功）不变。
- 鉴权 Bearer token + 项目隔离（`projectId`）不变。
- 纯查询，**无写操作** → 不经 PolicyEngine gate、无 `agent_action` 审计。
- 失败仍返回 `code !== 0` + `message`，前端按既有 `fetchWorkflowPage` 归一为空结果（不报错）。
