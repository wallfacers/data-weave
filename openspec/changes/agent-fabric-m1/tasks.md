## 1. 数据模型：审计四表 + 策略规则（agent-audit / policy-engine）

- [ ] 1.1 `schema.sql` 新增 `agent_session` / `agent_run` / `agent_step` / `agent_action` / `policy_rules` DDL（PG/H2 兼容，公共审计列约定），`data.sql` 种子含初始分级规则（对照运维动作全景表，宁严勿松）
- [ ] 1.2 master 领域层：五表实体 + 仓储 + 基础查询服务（按 run 回放步骤序列）
- [ ] 1.3 Docker PostgreSQL 实库验证 DDL 无报错、种子自洽；`./mvnw install -DskipTests` 全模块 SUCCESS

## 2. PolicyEngine 与审批单（policy-engine）

- [ ] 2.1 `PolicyEngine`：数据驱动分级（policy_rules 匹配）+ 资源归属判定（目标实例/任务是否本平台）+ 环境（dev/prod）与数量阈值抬升
- [ ] 2.2 命令串安全解析：首命令前缀定基础等级；重定向/命令分隔/`$()`/反引号强制升 L2（单测覆盖管道不升级、注入升级、L4 拒绝）
- [ ] 2.3 审批单生命周期：PENDING 创建（落 agent_action）→ approve/reject 接口 → 批准后平台侧按票据执行 → EXPIRED 超时任务；L3 批准需回输对象名二次确认
- [ ] 2.4 `applyFix` 迁入闸门（缺口③）：四种修复动作经 PolicyEngine + agent_action 留痕（记录操作者），删除直接执行路径
- [ ] 2.5 单测：分级矩阵、归属抬升、审批三终态、applyFix 留痕；`./mvnw -q -pl dataweave-master compile` + 测试全绿

## 3. worker exec 端点（worker-exec）

- [ ] 3.1 worker：受控执行端点（白名单前缀配置化、超时 60s、输出截断 64KB、返回 exitCode/stdout/stderr）
- [ ] 3.2 master：节点在线校验 + 派发转发 + agent_action 落库（命令/节点/结果摘要）
- [ ] 3.3 单测：白名单外拒绝、超时终止、离线节点报错、注入命令拒绝路径

## 4. MCP Server 与平台工具（mcp-tool-server）

- [ ] 4.1 Spike：官方 MCP Java SDK（WebFlux transport）在 Spring Boot 4/Jackson 3 下可用性；不可用则手写最小子集（initialize/tools/list/tools/call + Streamable HTTP），当日定案
- [ ] 4.2 MCP 端点 + Bearer 认证 filter（401 路径测试）
- [ ] 4.3 工具注册中心：查询工具（task/instance/lineage/metric/diagnosis）复用 master 领域服务；写工具（task rerun 等）+ `node_exec` + `approve_and_execute` 全部经 PolicyEngine 入口，无绕过路径
- [ ] 4.4 工具输出截断 + 完整输出引用存档；MCP Inspector（或等价客户端）手验 tools/list 与 tools/call 全通

## 5. 前端：上下文注入 + 审批卡片（agent-bridge，收口缺口①）

- [ ] 5.1 agent-rail → AgentChat 传递页面上下文 `{module, pathname, taskId/instanceId/nodeId}`，经 HttpAgent forwardedProps 或消息前缀送达后端（实测 CopilotKit v2 能力择优，补 cockpit 5.5）
- [ ] 5.2 mock 模式 IntentRouter 消费上下文：失败实例页问「为什么挂了」无需复述对象（测试）
- [ ] 5.3 审批卡片组件：渲染 `dataweave.approval` CUSTOM 事件（摘要/等级/批准/拒绝），决策后调审批接口并自动追加说明消息
- [ ] 5.4 `pnpm typecheck` 零错误

## 6. 桥接层与双模式（agent-bridge）

- [ ] 6.1 `agent.mode=mock|workhorse` 配置开关（默认 mock），AguiController 按模式路由；mock 路径行为与既有测试完全不变
- [ ] 6.2 桥接层：对话 ⇄ workhorse 会话映射（agent_session），建会话带 instructions/metadata，消息转发带上下文段
- [ ] 6.3 SSE 消费与落库：模型文本流 → AG-UI TEXT_MESSAGE_CONTENT 增量；tool_call_start/done → agent_step；permission_resolved → decision+source
- [ ] 6.4 stub workhorse SSE server 集成测试：两模式 AG-UI 事件序列同套断言（WebTestClient）

## 7. dw CLI 骨架（dw-cli）

- [ ] 7.1 `cli/` Go 模块：`dw task list|show|instances|rerun`、`dw logs cat`、`--json`、`--help` 子命令树；token 配置调 master REST
- [ ] 7.2 `dw task rerun` 走 PolicyEngine 留痕（与 agent 同闸验证）；CLI 构建脚本 + 二进制不入 git

## 8. 诊断域测试补课（缺口②）

- [ ] 8.1 `DiagnosisService` 测试：上下文采集内容、按 taskInstanceId 幂等
- [ ] 8.2 `applyFix` 四动作测试：RERUN / MIGRATE_NODE（落最空闲节点）/ RERUN_MORE_MEMORY / CAP_NODE_WEIGHT，含诊断置 RESOLVED 与（2.4 后的）闸门路径

## 9. workhorse 真机联调与验收（前置：workhorse 侧 support-dataweave-headless-integration 落地）

- [ ] 9.1 部署配置交付：workhorse config（preset_rules 放行 `dataweave__*`、default_allowed_tools 移除内置副作用工具、providers anthropic/openai）+ mcp.json（DataWeave 端点 + Bearer）+ docker compose 增 workhorse service
- [ ] 9.2 端到端：右舷「建一个每天凌晨跑的订单汇总任务并发布」→ agent 多步工具调用完成 → 审计回放可见全部 step
- [ ] 9.3 端到端：触发 L2 操作 → 审批卡片 → 批准 → 平台执行 → agent 续做；拒绝与超时路径各验一次
- [ ] 9.4 Browser Verification Gate：Playwright 实跑（输入框渲染、console 无 error、流式收发、审批卡片交互），产物写 `tmp/` 验后清理
- [ ] 9.5 同步 CLAUDE.md（agent.mode 说明、workhorse 运行方式、MCP 端点）；tasks.md 勾选与实际一致
