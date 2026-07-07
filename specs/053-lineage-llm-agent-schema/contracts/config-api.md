# Contract: 血缘 AI Agent 配置 REST API

基址：`/api/lineage/agent-config`（JWT + `X-Project-Id`/`?projectId=`，经 `ProjectScope.require` 成员校验，与既有项目隔离一致）。统一响应 `200 + {code, message, data}`。

## GET `/api/lineage/agent-config`
取当前项目配置（无则 `data=null`）。`data.apiKey` **脱敏**为 `sk-…{末4位}`，绝不回明文。

响应 `data`：
```json
{ "id":1, "protocol":"OPENAI", "baseUrl":"https://...", "model":"...",
  "apiKeyMasked":"sk-…a1b2", "enabled":true,
  "timeoutMs":30000, "rateLimitPerMin":60, "maxColumns":2000 }
```

## PUT `/api/lineage/agent-config`
创建或更新（每项目一条，upsert）。请求：
```json
{ "protocol":"ANTHROPIC|OPENAI", "baseUrl":"...", "model":"...",
  "apiKey":"sk-...(明文，仅写入时传；缺省=不改)", "enabled":true,
  "timeoutMs":30000, "rateLimitPerMin":60, "maxColumns":2000 }
```
- `apiKey` 存在 → 加密覆盖 `api_key_enc`；缺省/空 → 保留原密文（PATCH null vs 缺失语义）。
- `enabled=true` 时 `baseUrl`+`model` 必填，否则 `lineage_agent.config_incomplete`。
- `protocol` 非法 → `lineage_agent.protocol_invalid`。

## POST `/api/lineage/agent-config/test`
用当前（或请求体）配置发一次最小探活外呼，返回连通性 + 延迟。请求体可携带明文 apiKey 覆盖测试；不落库、不落日志明文。

响应 `data`：`{ "ok":true, "latencyMs":820, "note":"..." }`；失败 `ok:false` + 本地化 `note`。

## 错误码（i18n 规则③，backend `<domain>.<semantic>`）
- `lineage_agent.config_incomplete`
- `lineage_agent.protocol_invalid`
- `lineage_agent.test_failed`
- `lineage_agent.forbidden`（非项目成员）

## 审计只读（可选）
GET `/api/lineage/agent-config/calls?taskDefId=` → 最近外呼审计（`lineage_agent_call`，脱敏），供治理查看（FR-021）。
