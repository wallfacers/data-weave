# Contract — Push

`POST /api/projects/{projectId}/push`

摄入文件集,校验 + 幂等覆盖 + 生成版本快照(US2 / FR-004..009 + FR-016/017)。

## Request — `PushCommand`
```json
{
  "files": { "project.yaml": "...", "orders/orders_etl.task.yaml": "...", "orders/orders_etl.sql": "..." },
  "baseline": "a1b2c3d4e5f6a7b8",
  "force": false,
  "expectedFileCount": 5,
  "remark": "本地改了 ETL 超时"
}
```
- Auth: JWT;隔离同 pull。

## Response — `ApiResponse<PushResult>`
```json
{
  "code": 0,
  "data": {
    "projectId": 42,
    "created": { "task": 1 },
    "updated": { "task": 1, "workflow": 1 },
    "deleted": {},
    "snapshots": [
      { "entityType": "TASK", "entityId": 200, "name": "订单 ETL", "versionNo": 4 },
      { "entityType": "WORKFLOW", "entityId": 300, "name": "每日订单流", "versionNo": 2 }
    ],
    "newBaseline": "f9e8d7c6b5a40312"
  },
  "message": "success", "errorCode": null
}
```

## Rules(顺序即语义,事务内)
1. **校验全前置**(任一失败整单拒绝、零落库,FR-005/006):
   - `FileContract.deserialize` → `warnings` 非空即拒(缺必填/悬挂引用/类型错,B 已检)。
   - 完整性:`files.size()`/`expectedFileCount` 不符即拒(FR-017)。
   - 并发:`baseline` ≠ 当前修订 且 `force=false` → 拒(D4)。
   - datasource 逻辑名解析:项目内不存在即拒(FR-007)。
   - 删除候选在线引用守卫:被 ONLINE workflow 引用即拒(D6)。
2. **落库**(D2 三态对账:upsert + 软删);workflow node/edge 整体重建。
3. **建快照**:对 insert/update 的 task/workflow 调 D1 状态中立内核;**不**晋级 ONLINE(Q1/FR-009)。
4. 落 `agent_action` 审计;返回新 `baseline`。

## Errors
| 场景 | errorCode | 备注 |
|------|-----------|------|
| 项目不存在 | `project.not_found` | |
| 越权 | `project.access_denied` | |
| 定义无效/不完整 | `project.sync.invalid` | message 含文件+字段+原因(可定位) |
| 基线陈旧 | `project.sync.stale` | 提示先 pull/看 diff;可 force |
| 未知数据源名 | `project.sync.unknown_datasource` | 指明 task + ds 名 |
| 删除被在线引用 | `project.sync.delete_referenced` | 指明 task + workflow |
| 文件截断/数不符 | `project.sync.incomplete` | |

## Acceptance
- AS1:改任务脚本+超时 push → 更新+新快照,其它项目不受影响。
- AS2:缺必填/脚本 push → `project.sync.invalid` 可定位,服务器**保持 push 前状态**(全有或全无)。
- AS3:未知数据源名 → `project.sync.unknown_datasource`,不落库。
- AS4:push 后再 pull 干净目录 → round-trip 语义等价。
- AS5:无权项目 push → `project.access_denied`。
- AS6:删除本地缺失定义 → 软删+保留快照;被在线引用 → `project.sync.delete_referenced` 整单拒。
- AS7(并发):陈旧 baseline 非 force → `project.sync.stale`;force 或最新 → 正常。
