# Contract — Pull

`POST /api/projects/{projectId}/pull`

把项目装配为文件集返回(US1 / FR-001/002/003)。

## Request
- Path: `projectId` (Long)
- Body: 无(或空)。
- Auth: JWT;`TenantContext.tenantId()` 须 == project.tenantId,否则拒。

## Response — `ApiResponse<PullResult>`
```json
{
  "code": 0,
  "data": {
    "projectId": 42,
    "bundle": { "files": {
      "project.yaml": "formatVersion: 1\ncode: analytics\n...",
      "tags.yaml": "formatVersion: 1\ntags:\n- ...",
      "orders/orders_etl.task.yaml": "formatVersion: 1\nname: 订单 ETL\ntype: SQL\ndatasource: warehouse_main\n...",
      "orders/orders_etl.sql": "INSERT INTO ...",
      "orders/daily_orders.flow.yaml": "formatVersion: 1\n..."
    }},
    "baseline": "a1b2c3d4e5f6a7b8",
    "fileCount": 5
  },
  "message": "success",
  "errorCode": null
}
```

## Rules
- `bundle` = `FileContract.serialize(projectExport)`(文件格式 100% 由 B 决定)。
- 文件中数据源仅以**逻辑名**出现,**0 凭据**(FR-003/SC-005)。
- 空项目:仍返回 project/tags 骨架,不报错(US1-4)。
- `baseline`:对该 project 当前定义算的不透明修订令牌(D4)。

## Errors(`ApiResponse.err` + HTTP 200,业务码在 code/errorCode)
| 场景 | errorCode |
|------|-----------|
| 项目不存在 | `project.not_found` |
| 越权(跨租户/无权) | `project.access_denied` |

## Acceptance
- AS1:含任务/任务流/标签的项目 pull → files 完整可读,目录=类目层级。
- AS2:绑定数据源的任务 → 文件含 `datasource: <name>`,无 host/port/user/pwd。
- AS3:无权项目 pull → `project.access_denied`,不泄定义。
- AS4:空项目 pull → 返回骨架,不报错。
