## Why

当前 Agent 是规则 mock（`IntentRouter` 关键词路由 + `MockLlmClient`），既不会思考也不能多步自治，而「AI 替代数开+运维角色」的最终目标要求一个真 LLM 大脑驱动全平台工具。同时，Agent 将拥有整个数据团队的操作权限——**审计、凭证隔离、分级审批不是后期治理功能，而是接入真大脑前必须就位的地基**。workhorse-agent 侧的对接变更（`support-dataweave-headless-integration`：MCP host 接线、tool glob 规则、审批事件生命周期、会话 instructions/metadata、headless 工具画像）已立项，本变更是 DataWeave 侧的对应工程。

另纳入 agent-native-cockpit 复查发现的三个真缺口：① 页面上下文只显示在右舷头部、未注入对话（design D3 的产品灵魂断链）；② 诊断域核心逻辑（DiagnosisService 采集幂等、applyFix 四动作）无测试；③ 修复执行无身份无审计。三者都与本变更的桥接/审计能力同源，一并收口。

## What Changes

- **Agent 大脑替换**：接入 workhorse-agent（headless server，anthropic/openai 双协议）。`dataweave-api` 新增桥接层：AG-UI 会话 ⇄ workhorse 会话（SSE 事件映射、流式透传）。保留 `IntentRouter` 作为 `agent.mode=mock` 的零依赖回退（开发态/CI 不依赖 LLM key 与 workhorse 进程）。
- **DataWeave MCP Server**：在 `dataweave-api` 暴露 MCP Streamable HTTP 端点（Bearer 认证），注册平台工具（任务/实例/血缘/指标/诊断的查询与操作）+ `node_exec`（worker 机受控执行，兜底诊断通道）。工具实现复用 master 既有领域服务，一份实现多出口。
- **PolicyEngine + 审批单**：副作用操作按 L0–L4 分级（爆炸半径 × 可逆性 × 资源归属 × 环境）。L0/L1 直接执行+审计；L2/L3 创建审批单返回 `PENDING_APPROVAL`，AG-UI 渲染审批卡片，人批后 agent 调 `approve_and_execute` 续做；L4 永久拒绝。**既有 `applyFix` 修复操作迁入同一闸门**（收口缺口③）。
- **审计落库**：新增 `agent_session` / `agent_run` / `agent_step` / `agent_action` 四表。桥接层消费 workhorse SSE（`tool_call_start/done`、`permission_resolved` 含 source）全量落库，可回放、可沉淀回归评测集。
- **页面上下文逐消息注入**（收口缺口①）：右舷把 `pathname + taskId/instanceId/nodeId` 随每条用户消息送达后端；mock 模式下 `IntentRouter` 消费，workhorse 模式下拼入消息上下文段。
- **`dw` CLI 骨架**：Go 单二进制，`dw task list|show|instances|rerun`、`dw logs cat`、`--json`，调 master REST。引擎子命令（yarn/hive/flink/kafka）留 M3 长出。
- **worker exec 端点**：worker 新增受控命令执行接口（白名单前缀 + 超时 + 输出截断 + 全量审计），supply `node_exec` 工具。
- **补诊断域测试**（收口缺口②）：DiagnosisService 采集幂等、applyFix 四动作的 JUnit + AssertJ 覆盖。

## Capabilities

### New Capabilities

- `mcp-tool-server`: DataWeave 平台 MCP Streamable HTTP 端点——工具注册中心（平台 CRUD 工具 + node_exec）、Bearer 认证、工具结果结构与截断约定。
- `policy-engine`: 副作用分级（L0–L4）、资源归属与环境判定、审批单生命周期（创建/批准/拒绝/超时/执行）、`approve_and_execute` 工具、修复操作（applyFix）纳入闸门。
- `agent-audit`: agent_session/agent_run/agent_step/agent_action 四表契约、workhorse SSE 事件落库映射、审批决议留痕（decision + source + 审批人）、回放查询接口。
- `agent-bridge`: AG-UI ⇄ workhorse 会话桥——会话建立与映射、SSE 事件转 AG-UI 事件、审批卡片交互、逐消息页面上下文注入、`agent.mode=mock|workhorse` 双模式切换。
- `worker-exec`: worker 受控命令执行端点——白名单前缀、超时与输出截断、执行审计、与 node_exec 工具的契约。
- `dw-cli`: dw 命令行骨架——子命令树（task/logs）、--json 输出、身份与 master REST 调用约定。

### Modified Capabilities

<!-- base specs 仍为空（dataweave-mvp / agent-native-cockpit 未归档），cockpit 缺口的规范性收口
     以本变更新 capability 承载：缺口①→agent-bridge、缺口③→policy-engine。
     缺口②为测试债，不产生 spec 变化，仅入 tasks。 -->

## Impact

- **后端**：`dataweave-api` 新增 MCP server 端点、桥接层、PolicyEngine、审计仓储；`dataweave-master` 增审计四表领域 + applyFix 接入闸门；`dataweave-worker` 增 exec 端点；`schema.sql`/`data.sql` 增四表 DDL 与种子。
- **前端**：右舷上下文注入（agent-rail → agent-chat → HttpAgent forwardedProps/消息附文）；审批卡片组件（渲染 `dataweave.approval` CUSTOM 事件、批准/拒绝回传）。
- **新仓库目录**：`cli/`（Go，dw 二进制）——独立构建，不进 Maven reactor。
- **AG-UI 协议**：新增 CUSTOM 事件 `dataweave.approval`（审批单）+ 既有事件序列不变；前后端同步改，过 Browser Verification Gate。
- **外部依赖（阻断项）**：workhorse-agent 的 `support-dataweave-headless-integration` 变更需先落地（MCP host 接线是硬前置；tool glob/审批事件增强影响体验但可降级）。workhorse 部署配置（preset_rules 放行 `dataweave__*`、default_allowed_tools 移除内置副作用工具）属本变更交付物。
- **运行形态**：新增进程 workhorse-agent（127.0.0.1 绑定，dataweave-api 为唯一客户端）；`agent.mode=mock` 时一切照旧零依赖。
- **Gate**：触及 AG-UI/右舷 → Browser Verification Gate 必过；新表 → PostgreSQL 实库验证；安全敏感（node_exec/审批）→ 测试必须覆盖拒绝路径。
