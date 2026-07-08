# API Contract: System Settings — AI Agent Config (Global Singleton)

**Feature**: 057-system-settings | **Base**: `/api/settings/agent-config` | **Auth**: JWT + admin（同 `/api/users`；前端按 `project:manage` 过滤可见性）

> 配置由 053 的 `/api/lineage/agent-config`（按项目）迁此：去 `projectId`，改为 **tenant 全局单例**。凭据加密存储、脱敏返回（复用 053 机制）。`apiKey` 缺省 = 不改。

## GET `/api/settings/agent-config`
取当前租户的全局 AI Agent 配置（未配置 → `data=null`）。
- Response `200`: `{ "code":0, "data": AgentConfigVO | null }`

## PUT `/api/settings/agent-config`
创建或更新全局配置（每租户一条 upsert）。
- Request: `UpsertRequest`
- Response `200`: `{ "code":0, "data": AgentConfigVO }`
- **校验**：`protocol ∈ {ANTHROPIC, OPENAI}`；`enabled=true` 时 `baseUrl`(http/https) + `model` 必填；`apiKey` null/空 = 保留旧密文。
- **错误码**：`lineage_agent.protocol_invalid` / `lineage_agent.config_incomplete`（复用 053）。

## POST `/api/settings/agent-config/test`
用当前填入（或覆盖既有）的配置发一次最小探活外呼；**不落库、不落日志明文**。
- Request: `UpsertRequest`
- Response `200`: `{ "code":0, "data": TestResult }`

## GET `/api/settings/agent-config/calls?taskDefId=&limit=`
最近 N 条外呼审计（脱敏，按 `created_at` 倒序；可按 `task_def_id` 过滤；`limit≤200`）。**保留项目/任务溯源**（FR-011）。
- Response `200`: `{ "code":0, "data": CallRecord[] }`

---

## Schemas

```jsonc
// AgentConfigVO（GET/PUT 响应；apiKeyMasked=null 表示未配置 key）
{
  "id": 1,
  "protocol": "OPENAI",            // ANTHROPIC | OPENAI
  "baseUrl": "https://api.example.com/v1",
  "model": "gpt-4o-mini",
  "apiKeyMasked": "sk-…abcd",      // string | null
  "enabled": true,
  "timeoutMs": 30000,
  "rateLimitPerMin": 60,
  "maxColumns": 2000
}

// UpsertRequest（PUT/POST 请求；apiKey 留空=不改）
{
  "protocol": "OPENAI",
  "baseUrl": "https://api.example.com/v1",
  "model": "gpt-4o-mini",
  "apiKey": "sk-xxxx",             // 可省略/空 = 保留旧密文
  "enabled": true,
  "timeoutMs": 30000,              // 可省略=默认 30000
  "rateLimitPerMin": 60,           // 可省略=默认 60
  "maxColumns": 2000               // 可省略=默认 2000
}

// TestResult
{ "ok": true, "latencyMs": 842, "note": null }
{ "ok": false, "latencyMs": 0, "note": "auth failed" }   // note 脱敏，无明文 key

// CallRecord（审计；保留 projectId/taskDefId 溯源）
{
  "id": 99,
  "tenantId": 1,
  "projectId": 7,                 // 触发该次外呼的任务所属项目
  "configId": 1,                  // → 全局 lineage_agent_config.id
  "protocol": "OPENAI",
  "taskDefId": 123,               // 可空
  "latencyMs": 1100,
  "status": "SUCCESS",            // SUCCESS | DEGRADED | REJECTED
  "edgesEmitted": 4,
  "note": null,                   // 降级/拒收原因摘要（脱敏）
  "createdAt": "2026-07-08T12:00:00Z"
}
```

## 改动对照（053 → 057）
| 项 | 053（按项目） | 057（全局） |
|---|---|---|
| 路径 | `/api/lineage/agent-config` | `/api/settings/agent-config` |
| 作用域参数 | `?projectId=` + `ProjectScope.require` | 无（`TenantContext.tenantId()`）|
| 配置粒度 | 每项目一条 | 每租户一条（全局单例）|
| 审计溯源 | 按 project | 仍按 project/task（不变）|
| 鉴权 | project 成员 | admin（同 `/api/users`）|
