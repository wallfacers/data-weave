## 1. 数据模型：审计四表 + 策略规则（agent-audit / policy-engine）

- [x] 1.1 `schema.sql` 新增 `agent_session` / `agent_run` / `agent_step` / `agent_action` / `policy_rules` DDL（PG/H2 兼容，公共审计列约定），`data.sql` 种子含初始分级规则（对照运维动作全景表，宁严勿松）
- [x] 1.2 master 领域层：五表实体 + 仓储 + 基础查询服务（按 run 回放步骤序列）
- [x] 1.3 Docker PostgreSQL 实库验证 DDL 无报错、种子自洽；`./mvnw install -DskipTests` 全模块 SUCCESS

## 2. PolicyEngine 与审批单（policy-engine）

- [x] 2.1 `PolicyEngine`：数据驱动分级（policy_rules 匹配）+ 资源归属判定（目标实例/任务是否本平台）+ 环境（dev/prod）与数量阈值抬升
- [x] 2.2 命令串安全解析：首命令前缀定基础等级；重定向/命令分隔/`$()`/反引号强制升 L2（单测覆盖管道不升级、注入升级、L4 拒绝）
- [x] 2.3 审批单生命周期：PENDING 创建（落 agent_action）→ approve/reject 接口 → 批准后平台侧按票据执行 → EXPIRED 超时任务；L3 批准需回输对象名二次确认
- [x] 2.4 `applyFix` 迁入闸门（缺口③）：四种修复动作经 PolicyEngine + agent_action 留痕（记录操作者），删除直接执行路径
- [x] 2.5 单测：分级矩阵、归属抬升、审批三终态、applyFix 留痕；`./mvnw -q -pl dataweave-master compile` + 测试全绿

## 3. worker exec 端点（worker-exec）

- [x] 3.1 worker：受控执行端点（白名单前缀配置化、超时 60s、输出截断 64KB、返回 exitCode/stdout/stderr）
- [x] 3.2 master：节点在线校验 + 派发转发 + agent_action 落库（命令/节点/结果摘要）
- [x] 3.3 单测：白名单外拒绝、超时终止、离线节点报错、注入命令拒绝路径

## 4. MCP Server 与平台工具（mcp-tool-server）

- [x] 4.1 Spike：官方 MCP Java SDK（WebFlux transport）在 Spring Boot 4/Jackson 3 下可用性；不可用则手写最小子集（initialize/tools/list/tools/call + Streamable HTTP），当日定案 —— **定案：手写最小子集**（不引 SDK，协议面小可控；见 McpController）
- [x] 4.2 MCP 端点 + Bearer 认证 filter（401 路径测试）
- [x] 4.3 工具注册中心：查询工具（task/instance/lineage/metric/diagnosis）复用 master 领域服务；写工具（task rerun 等）+ `node_exec` + `approve_and_execute` 全部经 PolicyEngine 入口，无绕过路径
- [x] 4.4 工具输出截断 + 完整输出引用存档；MCP Inspector（或等价客户端）手验 tools/list 与 tools/call 全通（McpEndpointTest 等价客户端 8 例全绿）

## 5. 前端：上下文注入 + 审批卡片（agent-bridge，收口缺口①）

- [x] 5.1 agent-rail → AgentChat 传递页面上下文 `{module, pathname, taskId/instanceId/nodeId}`，经 CopilotKitProvider `properties` → forwardedProps.dataweave 送达后端（浏览器实测 `/agui` body 含 instanceId）
- [x] 5.2 mock 模式 IntentRouter 消费上下文：失败实例页问「为什么挂了」无需复述对象（IntentRouterIntentTest 覆盖 + 浏览器实测 forwardedProps 透传）
- [x] 5.3 审批卡片组件：渲染 `dataweave.approval` CUSTOM 事件（摘要/等级/批准/拒绝），决策后调审批接口并自动追加说明消息（组件 + agent.subscribe 订阅；live e2e 见 9.3 待 workhorse）
- [x] 5.4 `pnpm typecheck` 零错误

## 6. 桥接层与双模式（agent-bridge）

- [x] 6.1 `agent.mode=mock|workhorse` 配置开关（默认 mock），AguiController 按模式路由；mock 路径行为与既有测试完全不变
- [x] 6.2 桥接层：对话 ⇄ workhorse 会话映射（agent_session），建会话带 instructions/metadata，消息转发带上下文段
- [x] 6.3 SSE 消费与落库：模型文本流 → AG-UI TEXT_MESSAGE_CONTENT 增量；tool_call_start/done → agent_step；permission_resolved → decision+source
- [x] 6.4 stub workhorse SSE server 集成测试：两模式 AG-UI 事件序列同套断言（WebTestClient）

## 7. dw CLI 骨架（dw-cli）

- [x] 7.1 `cli/` Go 模块：`dw task list|show|instances|rerun`、`dw logs cat`、`--json`、`--help` 子命令树；token 配置调 master REST
- [x] 7.2 `dw task rerun` 走 PolicyEngine 留痕（与 agent 同闸验证：CLI 重跑实测 EXECUTED L1）；CLI 构建脚本 + 二进制不入 git

## 8. 诊断域测试补课（缺口②）

- [x] 8.1 `DiagnosisService` 测试：上下文采集内容、按 taskInstanceId 幂等
- [x] 8.2 `applyFix` 四动作测试：RERUN / MIGRATE_NODE（落最空闲节点）/ RERUN_MORE_MEMORY / CAP_NODE_WEIGHT，含诊断置 RESOLVED 与（2.4 后的）闸门路径

## 9. workhorse 真机联调与验收

> **阻塞已解除（2026-06-17 复查）**：workhorse 侧 `support-dataweave-headless-integration` 已归档落地（MCP host 接线、tool glob、`permission_request/permission_resolved` 生命周期、会话 instructions/metadata、default_allowed_tools 均已实现）；六平台二进制已编好（`workhorse-agent/dist/`）。**workhorse-agent 零改动**（OpenCode 式通用大脑，约束不接受宿主特定改动）。
> **复查确认的事实**：续做机制 = 前端追加消息触发新 run（D4 / agent-bridge「批准续做」Scenario），与 workhorse turn-based 模型一致（其工具调用同步有界、不能阻塞等审批，唯一续做入口即 POST 新 user_message）。前后端续做接线已就位并 stub 验过（前端 `agent-chat.tsx` 决策后 append+runAgent；后端 `ApprovalService` 执行）——**仅差真 LLM key + 真 sidecar 的活体验证**。
> **镜像缺口**：docker-compose 的 `image: dataweave/workhorse-agent` 悬空（仓库无该镜像/Dockerfile）。真机验证先走 9.6 本机直跑二进制；跨端托管与 Dockerfile 收口见独立变更 `dataweave-managed-sidecar`。

- [x] 9.1 部署配置交付：workhorse config（preset_rules 放行 `dataweave__*`、default_allowed_tools 移除内置副作用工具、providers anthropic/openai）+ mcp.json（DataWeave 端点 + Bearer）+ docker compose 增 workhorse service（profile）—— `deploy/workhorse/`
- [~] 9.2 端到端：查询类多步工具调用已真机验通（2026-06-17，Qwen via 百炼）：浏览器问「查询当前所有任务定义」→ agent 调 MCP `query_task_definitions` → 渲染表格「共 3 个任务，均 ONLINE」；sidecar 日志见建会话/发消息/流式 turn（dur 5.4s）。**写类多步「建任务并发布」待补验**
- [~] 9.3 L2 审批闭环已真机验通（2026-06-17，API 级）：`kill_instance` → PolicyEngine 判 **L2** → `PENDING_APPROVAL`+approvalId（即 `dataweave.approval` 卡片数据源）；批准 #100 → 平台执行 → 实例 **RUNNING→STOPPED**；拒绝 #101 → **REJECTED** 不执行；agent_action 审计两单 L2 + approved_by。**真机修复的 bug**：`DefaultPlatformActionExecutor` 漏实现 `KILL_INSTANCE`（及 PAUSE/RESUME_INSTANCE）→ 批准后报 `unsupported_action`（非闸门路径走 handler 直调 opsService，闸门路径靠 executor 重放却没这些 case）。已补三个 case（注入 `ObjectProvider<OpsService>` 打破 OpsService→DiagnosisService→GatedActionService→Executor 循环依赖）+ 5 个单测（113/113 master 绿）。**残留**：`UPDATE_TASK`/`DELETE_TASK`（默认 L2，仅 DRAFT）executor 仍未实现，列入后续。**未做**：浏览器实跑审批卡片渲染 + agent 活体续做（playwright MCP 中断；续做机制=前端 append+runAgent，已在 9.2 证明同款会话发消息可起新 turn）；超时(30min TTL)路径未验
- [x] 9.6 本机非 docker 跑通 sidecar：用当前源码重编的 `workhorse-agent/dist/workhorse-agent` + `deploy/workhorse/config.local.yaml`（gitignore，含真 key）跑通；后端 `--agent.mode=workhorse`。**真机踩坑记录**：① dist 旧二进制不认新 config 字段 → 须从当前源码 `build.sh` 重编；② 8787 被 Windows 宿主进程占用（WSL 镜像网络共享端口）→ 改 8799 + 后端 `--agent.workhorse.base-url`；③ **应用跑成 servlet/Tomcat 栈导致 reactive `CorsWebFilter` 失效、`/agui` 预检无 CORS 头** → 须强制 WebFlux（根因 sibling 模块泄漏 webmvc+tomcat 进 classpath）→ **已固化 `spring.main.web-application-type: reactive` 到 application.yml**（不再依赖启动参数）
- [x] 9.4 Browser Verification Gate：Playwright 实跑（输入框渲染 ✅、console 无 error ✅、流式收发 ✅ fleet 表、forwardedProps 上下文注入 ✅ 含 instanceId），产物写 `tmp/` 验后清理（审批卡片 live 交互属 9.3 阻塞项）
- [x] 9.5 同步 CLAUDE.md（agent.mode 说明、workhorse 运行方式、MCP 端点、dw CLI）；tasks.md 勾选与实际一致
