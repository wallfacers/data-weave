## Context

DataWeave 的 Agent 现为规则 mock：`IntentRouter` 关键词分支 + `MockLlmClient`，单步、无规划能力。最终目标「AI 替代数开+运维」要求：真 LLM 多步工具循环、可审计、可审批、可跨机器排障。选定 workhorse-agent（Go，headless agent server，MCP host，anthropic/openai 双协议）为大脑；其侧配套变更 `support-dataweave-headless-integration` 已立项（MCP host 接线、tool 字段 glob、审批事件生命周期 `permission_request/permission_resolved`、会话 instructions/metadata、`default_allowed_tools` 生效），事件 JSON 契约以该变更 design.md 为准。

大数据运维动作面分析结论（探索阶段已定）：读写比约 6:4，写动作 80% 是可逆例行处置（kill 本平台 app、重跑、unlock、msck…）。**分级维度不是读/写，而是爆炸半径 × 可逆性 × 资源归属 × 环境**；L1/L2 分界常取决于资源归属（kill 的 app 是否本平台提交），只有平台查得到——故细粒度审批必须在 DataWeave 侧。

硬约束（CLAUDE.md）：DDD 依赖方向；AG-UI 事件序列完整 + SCREAMING_SNAKE_CASE；Spring Boot 4 / Jackson 3；改 domain 后 `./mvnw install`；触及右舷/AG-UI 必过 Browser Verification Gate。

## Goals / Non-Goals

**Goals:**
- workhorse 大脑接入，AG-UI 体验不变（右舷流式对话），`agent.mode=mock` 保零依赖回退。
- 平台工具经 MCP 一份实现多出口；副作用全部收口到平台工具（审计无洞）。
- L0–L4 分级 + 异步审批单；审计四表全链路留痕、可回放。
- 收口 cockpit 三缺口：逐消息上下文注入、诊断域测试、修复操作入闸。
- `dw` CLI 骨架与 worker exec 端点（兜底排障通道）。

**Non-Goals:**
- 引擎连接器（yarn/hive/flink/kafka 工具与 `dw` 引擎子命令）——M3。
- 真实调度执行内核、MinIO 统一存储、SSE 实时日志——M2。
- Agent 驱动的真 RCA（DiagnosisAnalyzer 仍 mock）——M4。
- workhorse 自身的代码改动（在其仓库变更内完成，这里只交付部署配置）。
- 多副本 workhorse / 高可用——单实例 127.0.0.1 即可。

## Decisions

### D1: workhorse-agent 做大脑，不自研 loop，不用 LangChain4j/AgentScope
loop/压缩/sub-agent/权限/SSE 续传 workhorse 全有且经差距分析核实；Java 侧重造是浪费；AgentScope 引入第三运行时语言。**集成面 = MCP（工具）+ REST/SSE（会话）两条标准协议**，耦合最小。Alternative：LangChain4j 嵌入 Spring——保单进程但重造全部 agent 基建，弃。

### D2: 双闸门权限——workhorse 粗闸全放行，DataWeave PolicyEngine 细闸裁决
workhorse 侧 `preset_rules: {tool: "dataweave__*", pattern: "**", decision: allow_permanent}`，其 prompt 只兜内置工具。所有分级判定在 MCP 工具执行入口处由 PolicyEngine 完成（需要查资源归属/环境，只有平台能做）。**Why**：避免同一动作被问两次（审批疲劳）；归属判定 workhorse 永远做不了。Alternative：workhorse 侧逐工具规则细配——表达不了归属维度，弃。

### D3: 分级矩阵 L0–L4（policy-engine spec 的核心需求）
- L0 只读 → 直接执行，审计。
- L1 可逆例行写（kill **本平台提交的** app、重跑、unlock、msck、savepoint 触发）→ 直接执行 + 审计 + 右舷通知。
- L2 高影响（kill 非本平台 app、强制置成功、批量 >N、prod 环境的 L1）→ 审批单。
- L3 不可逆（drop table、delete topic、绕 Trash 删除）→ 审批单 + 二次确认（回输对象名）。
- L4 禁止 → 永久拒绝。
分级规则数据驱动（`policy_rules` 表：工具/命令前缀 + 条件 + 等级），不硬编码。

### D4: 审批是异步两段式，批准后平台侧直接执行
L2/L3 时工具立即返回 `PENDING_APPROVAL {approvalId, summary, level}`，agent 把它转述给用户；`AguiOrchestrator` 同步发 CUSTOM `dataweave.approval` 渲染审批卡片。用户点批准 → `POST /api/approvals/{id}/approve` → **平台直接执行动作**（票据里有完整命令/参数，不回 LLM 之手）→ 右舷自动追加一条用户消息「审批单 #42 已批准并执行：…」让 agent 继续。**Why**：不占工具调用连接傻等；执行不经 LLM 避免参数被改写；对话语义连贯。Alternative：同步 hang 住等审批——超时管理复杂、断线即丢，弃。

### D5: 审计四表，`agent_step.tool_use_id` 与 workhorse 事件天然 join
- `agent_session(id, conversation_id, workhorse_session_id, mode, created_*…)`
- `agent_run(id, session_id, trigger, user_message, state, started/finished…)`
- `agent_step(id, run_id, tool_use_id, tool_name, input_json, output_ref, truncated, duration_ms…)`
- `agent_action(id, step_id, policy_level, action_type, target_type/target_id, command, approval_status[NONE/PENDING/APPROVED/REJECTED/EXPIRED], approved_by/at, executed_at, result_json…)`
桥接层消费 workhorse SSE `tool_call_start/done`、`permission_resolved{decision,source}` 落库；审批单即 `agent_action` 行的生命周期，不另设表。长输出按引用存（M2 接 MinIO 前先落本地文件路径）。

### D6: 页面上下文逐消息注入（修 cockpit 缺口①）
上下文随导航变化，是消息级不是会话级。前端：agent-rail 把 `{module, pathname, taskId/instanceId/nodeId}` 传入 AgentChat → `HttpAgent` forwardedProps（或消息前置上下文段，实现时按 CopilotKit v2 实际能力择优，与 cockpit 5.5 同一实现）。后端：mock 模式 `IntentRouter` 直接消费；workhorse 模式桥接层拼成消息前缀块 `[上下文] 模块=/ops 实例=#100`。workhorse 零改动。

### D7: node_exec 的命令安全解析放 PolicyEngine（R9 落位）
worker exec 端点只认白名单前缀（`dw `、`tail `、`grep `、`df `、`free `、`jstat `…，配置化）；PolicyEngine 在派发前解析命令串：首命令前缀决定基础等级，含重定向（`>` `>>`）、命令分隔（`;` `&&` `||`）、`$()`/反引号 → 强制升 L2。**Why**：workhorse 不懂 `dw` 语义，平台懂；解析一处实现，CLI 人用、agent 用同闸。

### D8: MCP server 优先官方 MCP Java SDK，留手写降级
官方 `io.modelcontextprotocol` SDK 有 WebFlux transport；但 Spring Boot 4/Jackson 3 兼容性未验证。先 spike：SDK 可用则用；不可用则手写最小子集（Streamable HTTP：POST JSON-RPC `initialize/tools/list/tools/call` + SSE GET），协议面小、可控。Bearer 鉴权用 WebFlux filter，token 配置于双方配置文件。

### D9: `dw` CLI 用 Go 单二进制、薄壳调 REST、自己不做权限
worker 机零依赖分发；所有子命令带身份（token）打 master REST，PolicyEngine 统一裁决，人/agent 同闸同审计。目录 `cli/`，独立构建（Makefile/goreleaser 后续），不入 Maven reactor。M1 仅 `dw task list|show|instances|rerun`、`dw logs cat <instanceId>`、`--json`。

### D10: agent.mode 双模式，mock 是一等公民
`agent.mode=mock|workhorse`（默认 mock）。mock：现 IntentRouter 路径原样；workhorse：AguiController 把消息转发桥接层。CI 与零依赖开发永远跑 mock；workhorse 模式的集成测试用 stub SSE server。**Why**：保住「克隆即跑」的开发体验，真大脑是增配不是替换。

## Risks / Trade-offs

- [workhorse 侧变更未落地（阻断）] → 任务排序：先做不依赖它的部分（审计表、PolicyEngine、上下文注入、dw CLI、worker exec、MCP server）；桥接联调任务显式标注前置条件；MCP host 未接线前可用 MCP Inspector 等客户端先验 server 端。
- [MCP Java SDK 与 Spring Boot 4 不兼容] → D8 已留手写降级路径，spike 放任务最前。
- [审批后自动追加消息的对话一致性]（用户批准时 agent run 已结束）→ 追加消息触发新 run，上下文段携带审批结果，workhorse 会话历史保证连续。
- [node_exec 是高危面] → 白名单前缀 + PolicyEngine 升级解析 + 全量审计 + 测试必须覆盖注入/分隔符拒绝路径；默认仅 dev 环境放开 L1 直执行。
- [长输出膨胀审计表] → output 超阈值落文件存引用；M2 统一迁 MinIO。
- [双模式分叉导致行为漂移] → AG-UI 事件契约（序列、CUSTOM 结构）两模式共用同一 `AguiOrchestrator` 出口，WebTestClient 测试两模式同套断言。

## Migration Plan

1. 审计四表 + `policy_rules` DDL/种子 → 领域层（master）→ `install`。
2. PolicyEngine + 审批单生命周期 + applyFix 入闸（缺口③）→ 单测（含拒绝/超时路径）。
3. worker exec 端点（白名单+审计）→ 单测覆盖注入拒绝。
4. MCP server spike → 平台工具注册（查询类 + node_exec + approve 续做）→ MCP Inspector 手验。
5. 前端上下文注入（缺口①，与 cockpit 5.5 同实现）+ 审批卡片组件 → typecheck。
6. 桥接层（会话映射、SSE 落库、消息转发）+ `agent.mode` 开关 → stub 集成测试。
7. 补诊断域测试（缺口②）。
8. workhorse 真机联调（待其变更落地）：部署配置（preset_rules/default_allowed_tools/mcp.json）+ 端到端 + Browser Verification Gate。
9. 回滚：`agent.mode=mock` 一键回退；新表不影响既有路径。

## Open Questions

- CopilotKit v2 注入逐消息上下文的最优 API（forwardedProps vs 消息前缀）——实现 5.5 时实测定。
- MCP Java SDK 兼容性——spike 后定（D8 已备降级）。
- workhorse 进程的运维方式（systemd / docker compose 服务）——联调阶段定，倾向 compose 加一个 service。
- `policy_rules` 种子的初始分级清单粒度——实现时与运维动作全景表对照逐条定，宁严勿松。
