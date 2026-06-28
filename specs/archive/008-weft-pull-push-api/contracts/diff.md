# Contract — Diff Preview

`POST /api/projects/{projectId}/diff`

push 前的只读差异预览(US3 / FR-010/011)。

## Request
同 `PushCommand` 的 `files`(+ 可选 `baseline`);**忽略** `force`/`remark`。

## Response — `ApiResponse<DiffPreview>`
```json
{
  "code": 0,
  "data": {
    "added":    [ { "entityType": "TASK", "identity": "orders/new_task", "displayName": "新任务" } ],
    "modified": [ { "entityType": "TASK", "identity": "orders/orders_etl", "displayName": "订单 ETL" } ],
    "removed":  [ { "entityType": "WORKFLOW", "identity": "old_flow", "displayName": "废弃流" } ],
    "stale": true
  },
  "message": "success", "errorCode": null
}
```

## Rules
- 用 D2 的身份键三态对账,但**只读**:计算 added/modified/removed,**不写任何库**(FR-011)。
- `modified` 判定:身份匹配且序列化后内容(经 B)有别。
- `stale`:当前 `baseline` 是否已落后(D4),仅提示,不阻断。
- 本地与服务器完全一致 → 三列皆空(US3-2)。

## Errors
| 场景 | errorCode |
|------|-----------|
| 项目不存在 | `project.not_found` |
| 越权 | `project.access_denied` |
| 文件无法解析 | `project.sync.invalid` |

## Acceptance
- AS1:本地有增/改/删各一 → 三列与实际一致。
- AS2:完全一致 → 空差异。
- AS3:diff 执行后服务器定义 0 变化(只读)。
