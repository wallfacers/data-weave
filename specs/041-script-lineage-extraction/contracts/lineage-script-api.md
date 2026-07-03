# API Contracts: 041-script-lineage-extraction

前缀沿用 `LineageGraphController` 的 `/api/lineage`。所有端点受 JWT + `X-Project-Id` 项目隔离（036 约定）；写端点额外要求项目管理权限（`ProjectScope.require`），并经 GatedActionService 门禁。错误一律 `BizException(code, args)`。

## 1. POST /api/lineage/corrections — 提交人工修正（FR-007）

```jsonc
// Request
{
  "action": "CONFIRM" | "REMOVE" | "REVOKE",   // REVOKE=撤销既有裁决
  "taskDefId": 123,
  "direction": "READ" | "WRITE",
  "tableKey": "1|10.0.0.5|5432|dw|dw.orders",   // 与图节点 id 同构
  "columnKey": null                              // 可选，字段级边
}
// Response 200（L1 直通执行后）
{
  "actionId": 456,                // agent_action.id（审计锚点）
  "status": "EXECUTED",
  "correction": { "id": 7, "status": "CONFIRMED", "operator": "admin", "createdAt": "…Z" }
}
```

- 门禁映射：`CONFIRM→LINEAGE_EDGE_CONFIRM`、`REMOVE→LINEAGE_EDGE_REMOVE`、`REVOKE→LINEAGE_CORRECTION_REVOKE`，policy_rules seed 均 L1。
- 错误码：`lineage.edge_not_found`（语义键在图中无对应边且无既有裁决）、`lineage.correction_conflict`（并发 UPSERT 冲突）、`project.permission_denied`。
- REMOVE 生效 = 立即从图删该边 + 落抑制行；CONFIRM = 边 confidence 升 CONFIRMED + 落确认行；REVOKE = 删裁决行 +（REMOVED 撤销时）触发该任务血缘重抽取回图。
- 幂等：同键同 action 重复提交返回既有裁决（200，不新建 agent_action 执行）。

## 2. GET /api/lineage/tasks/{taskDefId}/hints — 未解析提示（FR-006）

```jsonc
// Response 200
{ "items": [ { "id": 1, "kind": "DYNAMIC_TABLE", "scriptHint": "L42: df.to_sql(table_name, …)",
               "versionNo": 3, "createdAt": "…Z" } ] }
```

## 3. GET /api/lineage/tasks/{taskDefId}/corrections — 当前生效裁决列表

```jsonc
// Response 200
{ "items": [ { "id": 7, "direction": "WRITE", "tableKey": "…", "columnKey": null,
               "status": "REMOVED", "operator": "admin", "createdAt": "…Z" } ] }
```

## 4. 既有查询端点的响应扩展（非破坏）

`GET /api/lineage/tables/{id}/upstream|downstream`、`/columns/{id}/…`、`/graph` 的 `LineageGraph.edges[]`（FlowEdgeView）新增可选字段：

```jsonc
{ "from": "…", "to": "…", "taskDefId": 12,
  "confidence": "UNVERIFIED",          // 原字段，脚本推断边=UNVERIFIED；此前 FLOWS_TO 恒空，现填值
  "source": "SCRIPT_INFERRED",         // 新增：SQL_PARSE 族原值 | SCRIPT_SQL | SCRIPT_INFERRED | SCRIPT_MODEL
  "humanState": "CONFIRMED",           // 新增：人工确认态；无裁决时省略（NON_NULL）
  "modelVersion": "…@v1"               // 新增：仅 SCRIPT_MODEL 边（NON_NULL 省略）
}
```

向后兼容：新字段可省略；旧客户端忽略未知字段。

## 5. 推理 sidecar 内部契约（ml/lineage-extractor/serve，平台内网调用，非公网 API）

### POST /extract

```jsonc
// Request（ModelExtractor → sidecar）
{ "taskType": "PYTHON", "content": "<脚本源码 ≤4000 chars>" }
// Response 200
{ "modelVersion": "weft-lineage-extractor-1.5b@v1",
  "reads":  [ { "table": "ods.users", "columns": null } ],
  "writes": [ { "table": "dw.users_clean", "columns": ["id", "name"] } ] }
// 无法抽取 → reads/writes 均空数组（200）；服务异常 → 5xx；平台侧超时 2s 即弃
```

- 确定性：温度 0，同版本模型同输入必须同输出（FR-013）。
- 平台侧校验后才转边：JSON schema + 每个 `table` 值可在 `content` 中文本定位（防幻觉，FR-012）；校验拒收计入降级留痕。
- `GET /health` 探活：ModelExtractor 启动与周期探活失败 → `supports()` false 整体旁路。

## 6. 边详情面板数据流（前端）

1. 点击边 → 已有 `FlowEdgeView`（含 taskDefId/source/confidence/humanState）本地渲染，无新请求；
2. 面板内并行拉 `GET /tasks/{taskDefId}/hints` 与 `/corrections`；
3. 修正按钮（权限 `can(...)` 门禁）→ `POST /corrections` → 成功后局部刷新当前子图（复用现有 fetchDownstream 流程，不整页刷新）。
