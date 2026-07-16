# Contracts: Workhorse HTTP + MCP

## 1. Workhorse HTTP API(sidecar 容器 `:8300`)

沿用 071(support-dataweave-headless-integration),**本 feature 不改协议**,仅收紧访问控制。

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/v1/sessions` | `{agent_type, instructions, ephemeral}` | `201 {id, agentType, status, ...}` |
| POST | `/v1/sessions/{id}/stream` | `{type:"user_message", content}` | `202` |
| GET | `/v1/sessions/{id}/stream` | SSE 订阅 | `reasoning_delta` / `assistant_text_delta` / `assistant_text_done` / `interrupted` / `error` |
| POST | `/v1/sessions/{id}/cancel` | — | 打断当前轮 |
| DELETE | `/v1/sessions/{id}` | — | 删会话 |
| GET | `/health` | — | `200`(探活,**豁免 auth/origin**) |

### 访问控制(本 feature 收紧点)

- **认证**:除 `/health` 外,`/v1/*` 与 `/debug/*` 须 `Authorization: Bearer <COMPANION_BRAIN_TOKEN>`(`auth.enabled`)。无 token → `401`。
- **跨域**:除 `/health`、`/ui` 外,`/v1/*` 须 `Origin` 在 `allowed_origins` 白名单;缺失或非白名单 → `403 origin_forbidden`。
- 两个独立中间件(bearer + origin),都要过。

## 2. MCP server: dataweave(workhorse → 后端 `/mcp`)

| 项 | 值 |
|---|---|
| url | `http://dataweave-master:8000/mcp`(服务名,docker network 内) |
| 认证 | `Authorization: Bearer <mcp.auth.token>`(`McpAuthFilter`,绑定 tenant=1 / user=1) |
| 协议 | MCP Streamable HTTP(JSON-RPC over HTTP) |
| 工具 | `dataweave__query_*`(只读查询)/ `dataweave__instance_logs` 等;写工具(`task_rerun`/`rerun_*`/`submit_backfill` 等)经平台 PolicyEngine 写闸门 |

### 调用语义

- workhorse 启动时连 `/mcp`,加载 `dataweave__*` 工具注册表。
- 巡检:`WorkhorseBrainClient.buildPatrolPrompt` 把 `project_id` 注入提示;workhorse 调 `dataweave__query_*` 时 `project_id` 作工具参数传;tenant 由 mcp-token 绑定(=1)。
- 写动作:workhorse 调 `dataweave__` 写工具 → 后端 PolicyEngine 裁决(L1 直执 / L2·L3 审批 / L4 拒)+ `agent_action` 审计;sidecar 不绕闸门。
