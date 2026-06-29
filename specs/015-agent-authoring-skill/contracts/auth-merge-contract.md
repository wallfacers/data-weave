# Contract: dw 认证合并

## CLI 侧

- 所有 dw 命令（`task`/`logs`/`pull`/`push`/`diff`/`run`）MUST 仅用单一凭据：`DW_TOKEN` → `Authorization: Bearer <token>`。
- 移除 `main.go` 手拼 `X-DW-Token`，统一经 `cli/client` 注入。

## 服务端侧

- `/api/cli/*`、`/api/projects/*`、`/api/ops/*`、`/api/tasks/*` MUST 接受统一 Bearer 凭据。
- **过渡兼容**: 服务端 `/api/cli/*` 在过渡期 MUST 同时接受旧 `X-DW-Token`（双接受），避免老 CLI 立即失效。
- 认证合并 MUST NOT 改动：PolicyEngine L0–L4 授权、审计落库、任一端点的请求/响应体契约与路径（FR-015/FR-028）。

## 验证

- 后端 `@ActiveProfiles("h2")` 测试：统一 Bearer 凭据对 CLI 端点与项目/ops 端点均 200；缺/错凭据 401/403 语义不变。
- 写操作仍触发 PolicyEngine + agent_action 审计（回归断言）。
