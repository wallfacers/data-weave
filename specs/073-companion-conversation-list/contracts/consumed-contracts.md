# Consumed Contracts（本特性消费的既有后端契约 — 零新增/零变更）

本特性为纯前端改造，**不新增、不修改任何后端端点、SSE 事件或数据库 schema**。以下列出所依赖的既有契约（071/070 已交付并冻结），仅作消费声明与回归边界，供实现与评审核对「未越界」。

## REST（`/api/companion`，authFetch + `X-Project-Id`，统一 `{code,message,data}`）

| 端点 | 方法 | 用途 | 客户端 |
|---|---|---|---|
| `/messages?reportId&before&limit` | GET | **会话历史加载**（全局或按问题），时间序返回 `MessageView[]` | `fetchMessages()`（已存在）|
| `/reports?status&limit` | GET | 问题列表离线补看，返回 `ReportView[]` | `fetchReports()`（已存在）|
| `/reports/{id}/close` | POST | 项目级共享关闭 | `closeReport()`（已存在）|
| `/reports/{id}/read` | POST | 标记已读 | `readReport()`（已存在）|
| `/chat` | POST | 发送消息 `{content, reportId?}`（走 PolicyEngine 写闸门）| `sendChat()`（已存在）|
| `/chat/cancel` | POST | 打断当前流 | `cancelChat()`（已存在）|

## SSE（`/api/companion/stream?projectId&token`，直连 SSE_BASE 绕 Next rewrite）

| 事件 | 数据 | 前端消费 |
|---|---|---|
| `snapshot` | `{state, briefing, reports}` | 初始化 state/briefing/reports（**本特性另在挂载后 `fetchMessages` 补历史**）|
| `state` | `{state, reason?}` | 机器人形态 |
| `report` | `{type:"created"\|"closed", report}` | 问题列表增量（closed → 若命中锚定则回落）|
| `briefing` | `Briefing` | 顶部概况 |
| `message` | `MessageView` | 追加会话线程（按 id 去重）|
| `delta` | `{messageId, chunk}` | 流式追加 |
| `end` | `{messageId, interrupted}` | 流式结束/中断标记 |

## 回归边界（评审核对项）

- 后端目录 `dataweave-*/src` **零 diff**；`schema.sql` 版本不变。
- 不新增/改动 SSE 事件类型、REST 端点签名、`{code,message,data}` 契约。
- 项目隔离（`X-Project-Id` / SSE `projectId+token` / TenantContext）沿用，不放宽。
- sidecar 凭据仍只在后端配置，前端零接触（constitution IV sidecar 例外面约束①）。
