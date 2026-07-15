# Contract: SSE / REST 消息载荷扩展

## GET {SSE_BASE}/api/incidents/stream（既有端点，payload 微扩）

`message` 事件 `data.message`（即 `IncidentMessage` 序列化）新增字段：

```json
{
  "id": "…", "incidentId": "…", "seq": 42,
  "kind": "HUMAN_SAY",
  "content": "……",
  "payloadJson": null,
  "actor": "zhangsan",
  "actorName": "张三",        // 新增；Agent/system/存量消息为 null
  "createdAt": "2026-07-14T10:00:00"
}
```

- 事件类型集合**不变**（snapshot/incident/message/briefing/thinking/chip/delta/end…）；打断收尾复用既有收尾事件语义（带 streamId）。
- 打断产生的 AGENT_SAY：`payloadJson` 含 `"interrupted": true`，前端据此渲染「已打断」标记。

## GET /api/incidents/{id}/messages?afterSeq&limit（既有端点）

历史消息条目同步携带 `actorName`（同上结构）。分页、排序、去重语义不变。

## 前端类型对齐

`lib/supervision/types.ts` 的 `Message` 增加 `actorName?: string`；`use-incident-stream.ts`/`store.ts` 透传，不参与去重键（仍为 seq）。
