## Why

今天的驾驶舱是「真引擎 + 死燃料」：诊断引擎（DiagnosisAnalyzer/applyFix 闸门）是真的，但它读到的节点指标、失败 history、今日同步量都是 `data.sql` 死种子；Agent 只会被动应答，**从不主动发现问题**；失败巡检、自动诊断、左栏主动播报这条链路根本不存在。结果是看板很漂亮，但"打开就知道数据到哪一步、哪里出了问题"只是摆设。

更深的限制在前端：聊天用 CopilotKit v2，它**无法在用户未发起 run 时把消息注入聊天记录**——这堵死了"Agent 主动开口"。要让平台自己发现问题并开口说话，必须自己掌管消息存储。

这次要把驾驶舱从"真引擎+死燃料"升级成「**真引擎 + 活燃料 + 会主动发现的 Agent**」，并把"主动发现"做成**通用框架**：任何模块（任务失败、数据质量、SLA、血缘断裂…）写一个巡检器就能接入，产出统一的"发现(Finding)"，统一走举手台 + 主动播报 + 闸门修复。这是本平台相对"人盯看板"竞品的核心差异点——**平台自己发现、举手、并能动手**。

## What Changes

- **替换聊天底座**：拆掉 CopilotKit v2，自研多会话聊天台（参照隔壁 `workhorse/workhorse-assistant` 的 `MessagePart`/`ChatRuntime` 模型），自己掌管消息存储 → 主动播报、闸门审批内联、多会话三件事从"难题"变"自然结果"。这是对 CLAUDE.md「Chat uses CopilotKit v2 only」硬门的**有意偏离**，理由如上，随本次同步改门。
- **通用主动发现框架**：`Inspector` SPI（可插拔）→ 统一 `Finding` 模型 → `InspectorScheduler`（定时兜底 + 失败事件加速 + `announced` 去重）→ 落库。首发 `TaskFailureInspector`（内部复用现有 `DiagnosisService`，不重写诊断）。
- **真推通道**：新增持久 SSE `GET /api/agent/stream`，发现产生即推送；自有聊天台订阅后把"我发现 X 失败了，根因…，要我处理吗"追加进会话。
- **举手台通用化**：从只渲染 `TaskDiagnosis` 改为渲染通用 `Finding[]`，一键修复经现有 `GatedActionService` 闸门。
- **L1 真采集**：`HeartbeatReporter` 采真实 cpu/mem/load（替换硬编码）；master 端按 `worker_node_code` 聚合真实并发任务数；近 7 天失败 history 真实统计。让诊断证据从死种子变真数据。
- **多会话**：会话 store（`runtimes: Map`）+ 侧栏 + 后端持久化（增/列/删/历史重水合）。
- **故障注入（测试期脚本）**：提供脚本在测试/demo 期造真实 OOM/资源争抢失败实例，喂给上面的链路。非运行时组件、不在 prod 加载。

## Capabilities

### New Capabilities
- `proactive-discovery`: 通用主动发现框架——Inspector SPI、统一 Finding 模型、巡检调度与去重、Findings 查询/修复 API。
- `agent-notification-stream`: 服务端→客户端持久 SSE 推送通道与 AgentNotifier，承载主动播报与 Finding 推送。
- `agent-chat-shell`: 自研多会话聊天台（替换 CopilotKit），自有消息存储、AG-UI 流消费、permission 内联、会话持久化。
- `live-telemetry`: L1 真实采集——节点真指标、并发任务聚合、失败 history 统计；含测试期故障注入脚本。

### Modified Capabilities
- `self-diagnosis`: 从纯被动（用户问才诊断）增加**主动**路径——巡检器自动对未诊断的 FAILED 实例触发 `diagnoseInstance`，并把结果映射为 `Finding`。

## Impact

- **前端（🅱 工作包）**：移除 `@copilotkit/*` 聊天依赖与 `agent-rail.tsx` 现有实现；新增自有聊天台、多会话 store、举手台通用化、`/api/agent/stream` 订阅。改 CLAUDE.md 前端栈门。
- **后端（🅰 工作包）**：新增 `finding` 表 + domain/repo；`Inspector` SPI + `TaskFailureInspector` + `InspectorScheduler` + `AgentNotifier`；新增 `/api/agent/stream`、`/api/findings*`、`/api/agent/sessions*` 端点；`HeartbeatReporter`/`FleetService`/`DiagnosisService` 改造；`schema.sql`/`data.sql`；测试脚本。
- **协议**：新增持久 SSE 通道（AG-UI `/agui` 契约不变）。
- **接缝**：两个工作包仅经冻结的 HTTP/SSE 契约耦合，文件物理隔离（🅰 只动 `backend/`+sql+scripts，🅱 只动 `frontend/`），可完全并行。
