# Contract: 打断 Agent 输出轮次

## POST /api/incidents/{id}/agent/cancel（新增）

**鉴权**: Bearer JWT + `X-Project-Id`（既有过滤器）。

**请求**: 无 body。

**行为**：
1. 构造 `ActionRequest`：`toolName="incident_agent_cancel"`、`actionType="INCIDENT_AGENT_CANCEL"`、`actor=<username（服务端认定）>`、`actorSource="UI"`，经 `GatedActionService.submit` 过闸；
2. `policy_rules` 种子行 `('TOOL','incident_agent_cancel',…,'L0')` ⇒ 直接执行 + `agent_action` 审计留痕（**绝不产生 PENDING_APPROVAL**——否则停止能力失效，属契约级断言）；
3. 执行体：置位该 incident 的取消句柄；`LlmChatClient` 读循环检查点退出；已产出部分内容以 `AGENT_SAY` 落库（`payload_json.interrupted=true`）并发送既有收尾事件（带 streamId，前端清空 delta 缓冲）。

**响应**：

```json
{ "code": 0, "data": { "cancelled": true } }   // 有在途轮次并已打断
{ "code": 0, "data": { "cancelled": false } }  // 无在途轮次（幂等成功，非错误）
```

**竞态语义**：cancel 与轮次自然完成竞争时先落库者胜；两种结局都以最终落库消息为唯一真相，不出现「已打断」与完整回复并存。

**错误**: incident 不存在 → 既有 `incident.not_found` 类错误码；未认证 → 401。

## 审计断言（测试锚点）

- 每次调用产生一条 `agent_action` 记录（无论 cancelled true/false）。
- 记录的 outcome 为直执行（L0），前端无需按 `PENDING_APPROVAL` 分流。
