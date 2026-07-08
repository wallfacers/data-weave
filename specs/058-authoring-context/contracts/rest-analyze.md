# Contract: REST 端点（AuthoringContextController）

租户+项目隔离沿用既有 JWT/`X-Project-Id`；纯读；无状态 analyze 零持久化。全部返回统一 envelope（`{code,data,message}`）。

## POST /api/authoring-context/analyze —— 工作副本（无状态）

对未 push 的本地工作副本分析。**零写入、不改图谱**（FR-004）。

**Request**
```json
{
  "drafts": [
    { "taskName": "dw_user_daily", "type": "SQL", "content": "INSERT INTO dw.user_daily SELECT * FROM ods.user",
      "datasourceRef": "ds-1", "targetDatasourceRef": "ds-1" }
  ],
  "target": "dw_user_daily",          // 分析焦点草稿（缺省=drafts[0]）
  "depth": 3,                          // 遍历深度，调用方自决；缺省=默认多跳
  "include": ["context","deps","reuse","diagnostics"]  // 能力子集；P1 仅 context/deps
}
```

**Response 200**（`data`）
```json
{
  "context": { "taskRef":"dw_user_daily", "reads":[...], "writes":[...],
               "columnLineage":[...], "datasourceSchema":{...},
               "depthUsed":3, "truncated":[...], "partial":[...] },
  "deps": { "upstream":[...], "downstream":[...] },
  "reuse": [ ... ],          // include 含 reuse 时（P2）
  "diagnostics": [ ... ]     // include 含 diagnostics 时（P3）
}
```

**约束**
- `drafts` 内跨任务依赖先在草稿集内解析，再回退服务端已 push 图谱；同名任务草稿覆盖已 push 版本（FR-019）。
- 任一事实源不可用 → `context.partial` 标注缺失，HTTP 仍 200（FR-005）。
- 越权租户/项目 → 拒绝（既有隔离错误码）。

## GET /api/authoring-context/{taskDefId} —— 已 push 任务

对已 push 任务返回同结构 `data`（`drafts` 换为服务端定义）。Query：`?depth=&include=`。

## 与既有端点关系

- **复用** `LineageQueryService` 的 upstream/downstream/neighborhood/columnUpstream/impact/downstreamTaskLevels（不新写图查）。
- **不改** 既有 `/api/lineage/*`（`LineageGraphController`）。本端点是面向创作的**编排聚合**层。
