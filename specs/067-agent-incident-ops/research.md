# Phase 0 Research: 067 任务失败智能运维

**Date**: 2026-07-13 · 所有 NEEDS CLARIFICATION 在此收敛为决策。侦察依据：backend/frontend 只读探查（2026-07-13）。

## R1. 运维 Agent 的运行形态：master 内嵌编排管线（非外挂进程）

- **Decision**: 运维 Agent 以 **master 内的有界编排管线**（`IncidentAgentService`）实现：Java 代码编排「采证 → 诊断 → 决策 → 行动 → 验证」固定流水线，仅「诊断」「修复生成」「对话」「战况综述」四个点外呼云 LLM；无自由工具循环、无常驻推理进程。
- **Rationale**: ① 用户明确要求复用 053 血缘 Agent 配置——053 已确立「master 内有界 LLM 通道」先例（`LlmAgentClient` + 协议适配器 + 降级永不抛）；② 方向文档 §4.4 的外挂进程（Claude Agent SDK）需要新部署单元 + 更宽 MCP 面，v1 收益不成比例；③ 方向文档 §4.2 判断 3：第一版编队要小、固定流水线。决策=分型到动作的映射用**确定性 Java 代码**，LLM 只做分型判断与文本生成——可测、可控、成本最低。
- **Alternatives considered**: 外挂 Claude Agent SDK 进程（方向文档目标形态，作为后续演进保留；v1 拒绝：新部署单元/认证面/进程管理成本）；LLM 自由工具循环（拒绝：不可测、成本不可控、v1 不需要）。
- **Constitution 张力**: 原则 IV「服务端无 AI 大脑」——见 plan.md Complexity Tracking 的正式偏差记录。

## R2. 失败感知：巡检 Sweeper + 唯一开单约束（不复用信号桥）

- **Decision**: 新增 `IncidentSweeper`（`@Scheduled(fixedDelay)`，模式同 `StuckInstanceSweeper`）扫描 `task_instance` 中 `state IN ('FAILED','SUSPENDED')` 且无未收口事故的实例。开单防重（多 master）：`incident.open_key` 列 = 开着时为 `task_def_id`、收口时置 NULL，配 `UNIQUE(tenant_id, open_key)`——insert 单赢即认领，赢者本机线程池继续处理；同任务后续失败归并进既有开单（追加 message + 关联实例）。
- **Rationale**: ① 066 明确「Agent 自建巡检发现故障，不复用信号桥」，且失败路径现已无任何告警发布残留（仅 `readiness_signal` outbox 与 UI SSE 事件）；② 巡检对调度内核**零侵入**（SC-008 红线）：不改 `InstanceStateMachine`/`WorkerReportService` 任何一行；③ FAILED 是 `RetryService` 烧完业务重试后的终态——Agent 天然不与业务重试打架；SUSPENDED 是 060 infra 重派超限的非终态，正需要智能介入。唯一约束用「NULL 可重复」语义规避 H2 不支持 partial index 的方言坑（两库兼容）。
- **Alternatives considered**: 状态机终态处插桩发事件（拒绝：违背 066 决策 + 侵入调度内核）；EventBus 订阅 `dw:evt:*`（拒绝：那是 per-workflow-instance UI 频道，非全局故障流，且 pub/sub 无持久化会丢）。

## R3. LLM 通道：复用 053 配置与协议适配器，新增通用 chat 客户端

- **Decision**: 复用 `lineage_agent_config` 表（协议/端点/模型/密钥/超时/限频）与 `AnthropicProtocolAdapter`/`OpenAiProtocolAdapter`；新增 **`LlmChatClient`**（与 `LlmAgentClient` 并列）：支持多轮 messages + system 提示 + 结构化 JSON 输出约定，适配器补一个 `buildChatRequest(cfg, messages)` 入口。诊断输出为结构化 JSON（分型/置信度/证据行引用/建议），解析失败即降级 UNKNOWN。运维启停：`lineage_agent_config` 加 `ops_enabled` 列（默认 0），与血缘用途独立开关；限频用**独立令牌桶**（新配置键 `ops.incident.llm-rate-per-min`，默认 30），与血缘富化的桶分离，防失败风暴与 push 高峰互相饿死；审计思路沿用（新表 `incident` 侧自带过程留痕，不复用 `lineage_agent_call`）。
- **Rationale**: 用户显式要求复用；053 的加密（`DatasourceEncryptor`）、降级（永不抛、明文 key 不进日志）、限频均为硬约束的现成实现。表名含 `lineage` 是历史命名，改名收益低于迁移噪声（单一权威 schema.sql 无迁移脚本，改名会断存量数据），保留并注释语义扩展。
- **Alternatives considered**: 新建 `ops_agent_config` 表（拒绝：用户要求复用一份配置，双表必然漂移）；表改名 `ai_agent_config`（拒绝：断存量、全链路改名噪声大）。

## R4. 诊断证据包与分型

- **Decision**: 证据包 = ① 实例日志尾部（`LogBus.read` 取尾 N 行 + `task_instance.log`/`error_message`/`exit_code`/`failure_reason`，超长做头尾采样截断，预算 ~32KB）；② 任务定义（`task_def.content`、type、datasource 绑定）；③ 近 10 次运行历史（state/exit_code/duration/business_attempt）；④ 实时任务附 `external_job_handle` 与 checkpoint 可用性。分型枚举：`TRANSIENT / RESOURCE / CODE / UPSTREAM_DATA / CONFIG_CREDENTIAL / UNKNOWN`。凭据类先走**确定性前置指纹**（authentication failed / access denied / password 等多语言模式 → 直接 CONFIG_CREDENTIAL，免 LLM 误判）；其余交 LLM 结构化判定。
- **Rationale**: 全部证据源已有现成读取口（LogBus Redis Stream、task_def、task_instance），零新采集设施；确定性指纹打底符合方向文档 §4.3「playbook 打底、LLM 兜长尾」的成本分层思想（完整 playbook 引擎超范围，仅此一个硬指纹）。
- **Alternatives considered**: 血缘上下游纳入证据包（延后：US 无强需求，属影响评估范畴，Out of Scope 已划走）；全量日志入 prompt（拒绝：成本与上下文溢出）。

## R5. 行动梯度与闸门接线

- **Decision**: 分型 → 动作的确定性映射：`TRANSIENT` → 自动重跑（复用 `OpsService.rerunInstance`）；`RESOURCE` → 调资源 + 重跑（见 R6）；`CODE` → 生成修复提案（默认 L3 人审）；`UPSTREAM_DATA`/`CONFIG_CREDENTIAL` → 直接升级 NEEDS_HUMAN；`UNKNOWN` → 至多 1 次试探重跑后升级。全部写动作以 `ActionRequest`（actorSource=AGENT，actor=`ops-agent`）经 `GatedActionService.submit` 提交；`policy_rules` 新增种子：`incident_rerun`/`incident_adjust_resources`/`incident_reverify`/`incident_resume_checkpoint` = L1，`incident_publish_fix` = L3。审批复用 `agent_action` PENDING_APPROVAL 生命周期 + `ApprovalController`（`DefaultPlatformActionExecutor` 增加对应 action 分支）。防循环：`incident.auto_action_count` 上限（`ops.incident.max-auto-actions`，默认 3），超限强制 NEEDS_HUMAN。人工介入检测：动作执行前 CAS 校验实例当前状态（复用既有 CAS 语义），状态不符即让位记录。
- **Rationale**: 闸门/审批/审计全为现成内核（宪法原则 V）；L1/L3 划分即方向文档 §4.3 的梯度表；CAS 前置校验天然解决人机并发。
- **Alternatives considered**: 自建审批表（拒绝：`agent_action` 生命周期即审批单，重复建设）；L2 信任升格机制（Out of Scope，另行立项）。

## R6. 资源调整：task_def 新增 `resources_json` + worker 引擎级映射

- **Decision**: `task_def` 加 `resources_json` 列（如 `{"memoryMb":4096,"cpuCores":2}`，NULL=引擎默认），随版本快照与 pull/push 文件契约（`*.task.yaml` 增可选 `resources` 节）走；`DispatchCommand` 传播到 worker，各执行器**尽力映射**：Spark（driver/executor memory/cores 提交参数）、Flink（taskmanager 内存）、SeaTunnel/DataX（JVM -Xm*）优先覆盖；Shell/Python/SQL v1 不强制（记录说明）。Agent 的 RESOURCE 处置 = 经闸门修改 `resources_json`（护栏：`ops.incident.memory-cap-mb` 默认 16384、单次调幅 ≤2 倍）+ 生成新版本快照 + 重跑。
- **Rationale**: 侦察确认 task_def 现无任何资源字段——这是 FR-006 的前置缺口，必须补列；放 task_def（而非仅 params_json 里约定）才能被护栏结构化校验、被文件契约显式承载（宪法原则 I：可 diff 可审查）。OOM 高发地正是引擎类任务，v1 覆盖引擎类即覆盖主要价值。
- **Alternatives considered**: 只改引擎参数字符串（拒绝：无结构化护栏、各引擎语法不一 Agent 易写坏）；容器级 cgroup 限额（拒绝：当前 worker 为裸子进程模型，超范围）。

## R7. 修复提案与代码回流

- **Decision**: 提案 = 新表 `incident_proposal`（proposed_content 全量新内容 + base_version_no + 证据 JSON + 状态机 PENDING→APPROVED→PUBLISHED→VERIFIED/VERIFY_FAILED→ROLLED_BACK）。批准执行 = 程序化调 `TaskService.writeTaskVersionSnapshot` + `publish`（与 push 同一入口，天然生成版本快照、`dw pull` 可取回，remark 注明 incident 溯源）。验证失败自动回滚 = 用 base 版本内容再发一版（版本号只进不退，回滚也是新快照）。基线冲突防护：发布前校验 `current_version_no == base_version_no`，不符（人在提案挂起期间改了任务）即提案作废并在线程说明。
- **Rationale**: 复用 push 的同一程序化入口（侦察确认 `writeTaskVersionSnapshot` 可直调）保证宪法原则 I/II：服务端零漂移、修复即新快照、pull 即文件。全量内容而非 diff：与 push 幂等覆盖语义一致，避免 patch 应用失败类故障。
- **Alternatives considered**: Agent 直接开 repo PR（方向文档理想形态；拒绝：服务端无 repo 凭据与工作副本，超范围）；存 diff（拒绝：应用失败面、与幂等覆盖语义冲突）。

## R8. 实时通道：EventBus pub/sub + SSE（快照 + 直播）

- **Decision**: 新频道 `dw:incident:evt:{projectId}`（Redis pub/sub）承载直播流事件（事故开立/状态变更/Agent 步骤/工具动作点亮/流式文本分片/思考态起止）。SSE 端点 `GET /api/incidents/stream`（项目域）：先发 DB 快照（近 N 条开着的事故 + 最新消息），再桥接订阅——同 `workflowEventsStream` 的 Sinks 模式。细粒度直播事件（分片/思考态）**只走通道不持久化**；语义完整消息（诊断结论、动作、对话轮次）落 `incident_message` 后同时广播。对话回复流式：LLM SSE 分片 → 逐段 publish → 完成后整条落库。
- **Rationale**: 快照+订阅是仓库已验证模式（DAG 流）；前端已有 `useEventSource`（直连 `SSE_BASE`、Last-Event-ID 续传）。分片不落库控制存储噪声，重放时以完整消息呈现（SC-005 可还原性依赖持久化消息即可满足）。
- **Alternatives considered**: WebSocket（拒绝：仓库无先例，SSE 已够单向直播 + POST 上行）；每分片落库（拒绝：写放大严重）。

## R9. 战况播报与接班报告

- **Decision**: 新表 `incident_briefing`（每项目最新一行，UNIQUE(tenant_id, project_id)）：`summary_line`（LLM 一句话综述）+ `stats_json`（处理中/待审批/需人工/已解决计数，SQL 实时算）+ `report_md`（完整接班报告，LLM 生成）。触发：事故开立/收口/升级时防抖重生成（`ops.incident.briefing-debounce-ms` 默认 60s）；数字随 `GET /api/incidents/briefing` 实时查询（保证 SC-010 数字零不一致——数字永远来自 SQL，LLM 只写叙述）。
- **Rationale**: 「数字 SQL、叙述 LLM」分离是 SC-010 一致性的结构保证；防抖控成本。
- **Alternatives considered**: 纯前端拼数字无叙述（拒绝：Q4 已裁决要智能播报）；定时轮询生成（拒绝：无事件时空烧模型）。

## R10. 对话（FR-016）：上下文填充式多轮，无开放工具循环

- **Decision**: `POST /api/incidents/{id}/chat` 收人话，`IncidentConversationService` 组 prompt = 系统提示（角色/边界/可提议动作清单）+ 事故证据包 + 线程历史（截断预算）→ `LlmChatClient` 流式回复。Agent 可在回复中带结构化动作提议块（JSON），服务端解析后照常经闸门执行并追加 ACTION 消息——**LLM 无直接执行权，永远是「提议→服务端校验→闸门」**。v1 不提供开放式工具调用（如现场查任意表）；追加排查上下文限于预置证据源（可按指令重新拉日志更长尾部/运行历史）。
- **Rationale**: 保住「只有编排层有行动权」（方向文档 §4.2 判断 2）与审计单点；上下文填充覆盖绝大多数追问场景，成本与安全面可控。
- **Alternatives considered**: 工具循环（MCP 自调用）（拒绝：v1 复杂度/安全面爆炸，留待外挂编队演进）。

## R11. 前端形态落位

- **Decision**: 新一等视图 `supervision`（监督席指挥中心）：`views.ts` ViewType + `VIEW_META` + `registry.tsx` + `nav-groups.ts`（ops 组首位）+ `DEFAULT_VIEWS` 改为 `["supervision", ...]`（FR-018 首屏）。组件树 `components/workspace/views/supervision-view.tsx` + `supervision/`：`briefing-banner`（战况横幅+展开报告）、`live-feed`（直播流，DwScroll + 状态/任务过滤 + 待处理置顶区）、`incident-thread`（下钻线程：消息流/工具链 chips/流式打字/思考态）、`chat-composer`（对话输入 + 结构化按钮：批准/驳回/复验/标记已处理）。SSE 走既有 `useEventSource`（直连 SSE_BASE、token/projectId query）。i18n 新顶层命名空间 `supervision` + `views.supervision`（双 bundle 同步）。动效遵循 DESIGN 克制基调：呼吸感/流式打字均 `motion-safe:` 前缀 + `prefers-reduced-motion` 降级静态；实现前过 reuse-first checklist（DataTable/DwScroll/Badge/TabStrip 复用，无聊天组件残留需自建消息气泡与工具 chip，同改动回填 DESIGN 公共组件目录）。
- **Rationale**: 全部机制点（视图注册双表、DEFAULT_VIEWS、SSE hook、DwScroll 硬规则）经侦察确认；聊天 UI 无残留可复用（CopilotKit 拆净），自建量可控。
- **Alternatives considered**: 挂 OpsView 新 tab（Q5 已否）；SidePanel 承载线程（保留为后续增强，v1 线程在视图内下钻，避免双容器状态同步）。

## R12. schema 版本与配置键

- **Decision**: schema `0.18.0 → 0.19.0`：新表 `incident` / `incident_message` / `incident_proposal` / `incident_briefing`；`task_def` + `resources_json`；`lineage_agent_config` + `ops_enabled`。配置键（application.yml，`ops.incident.*`）：`sweep-interval-ms`(30000)、`max-auto-actions`(3)、`memory-cap-mb`(16384)、`resource-step-factor-max`(2.0)、`briefing-debounce-ms`(60000)、`llm-pool-size`(2)、`evidence-log-tail-lines`(400)、`storm-max-inflight`(5)、`llm-rate-per-min`(30，独立令牌桶)。
- **Rationale**: 单一权威 DDL 惯例；巡检/池化参数对齐既有 sweeper/enricher 命名风格。

## R13. i18n 与 Agent 文本语言

- **Decision**: 系统骨架文案（状态名、按钮、错误码）走三规则（前端 next-intl / 后端 `Messages.get` / `BizException`）；**LLM 生成的叙述**（诊断说明、播报、对话）按项目 agent locale 生成（prompt 指定输出语言 = `task_instance.locale` 兜底 zh-CN），存储原文不做二次翻译。错误码新增 `incident.*` 域（如 `incident.not_found`、`incident.agent_disabled`、`incident.proposal_stale`）。
- **Rationale**: 与 053/既有约定一致；LLM 文本翻译存储成本不值。

## 决议汇总（对照 spec 的开放点）

| 开放点 | 决议 |
|---|---|
| Agent 运行形态 | R1 master 内嵌有界管线 |
| 失败感知机制 | R2 巡检 Sweeper + open_key 唯一约束 |
| 模型配置复用方式 | R3 复用表 + `ops_enabled` + `LlmChatClient` |
| 资源调整落点 | R6 `task_def.resources_json` + 引擎映射 |
| 修复回流路径 | R7 程序化 writeTaskVersionSnapshot+publish |
| 实时直播通道 | R8 EventBus pub/sub + SSE 快照直播 |
| 对话边界 | R10 上下文填充式，无工具循环 |
| 首屏切换 | R11 `DEFAULT_VIEWS[0]="supervision"` |
