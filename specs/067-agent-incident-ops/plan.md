# Implementation Plan: 任务失败智能运维——Agent 自动诊断处置闭环 + 监督席指挥中心

**Branch**: `067-agent-incident-ops` | **Date**: 2026-07-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/067-agent-incident-ops/spec.md`

## Summary

任务实例失败（周期/手动/实时）后由 master 内嵌的**有界运维 Agent 管线**自动完成「巡检开单 → 采证 → LLM 分型诊断 → 确定性梯度处置（重跑/调资源/检查点续跑自动；改代码提案人审）→ 验证收口/升级人工」闭环；LLM 通道复用 053 `lineage_agent_config` + 协议适配器（新增通用 `LlmChatClient` 与 `ops_enabled` 开关）；全部写动作经既有 `GatedActionService`/`PolicyEngine`/`agent_action` 审批审计。前端新增 `supervision` 一等视图并设为工作区默认首屏：战况播报横幅 + Agent 活动直播流（SSE 快照+直播：思考态/工具 chips/流式打字）+ 下钻事故线程（自由对话 + 结构化审批按钮）。全部技术裁决见 [research.md](research.md)（R1–R13）。

## Technical Context

**Language/Version**: 后端 Java 25 + Spring Boot 4.0（WebFlux, Jackson 3）；前端 TypeScript / Next.js 16（App Router, Turbopack）+ React 19

**Primary Dependencies**: 复用为主，零新增第三方依赖——LLM 外呼走 JDK `java.net.http.HttpClient`（053 模式）；SSE 走 Reactor Sinks + `EventBus`(Redis pub/sub)；前端 shadcn/ui(base) + hugeicons + `useEventSource` + `DwScroll` + DataTable 原语

**Storage**: PostgreSQL（默认）/ H2（profile=h2，DDL 双方言兼容）：schema `0.18.0 → 0.19.0`（4 新表 + 3 列变更，见 [data-model.md](data-model.md)）；Redis（EventBus 直播频道 `dw:incident:evt:{projectId}`、LogBus 证据读取）

**Testing**: JUnit 5 + AssertJ + WebTestClient（JWT via JwtTestSupport；H2 独立库名防串台）；LLM 外呼以假端点/桩适配器测编排；前端 vitest + Playwright 浏览器门；调度红线回归（真并发 dispatch）

**Target Platform**: Linux server（多 master 对等部署，巡检 CAS 单赢防重）；浏览器（桌面）

**Project Type**: Web 应用（Spring Boot 多模块后端 + Next.js 前端两独立项目）

**Performance Goals**: 失败→事故 ≤1 sweep 周期（30s）；诊断 ≤5min P95（SC-001）；Agent 步骤→feed 呈现 ≤2s（SC-009）；对话首 token ≤5s P90（SC-011）

**Constraints**: 调度内核零侵入（不改状态机/CAS/锁序，纯巡检观察，SC-008）；LLM 降级永不抛、明文密钥不进日志（053 惯例）；自动处置次数上限硬护栏（防循环，SC-007）；风暴限流 `storm-max-inflight`；i18n 三规则 + LLM 叙述按 agent locale 原文存储

**Scale/Scope**: 单租户数百任务、失败风暴数十并发开单量级；前端 1 个新一等视图（首屏）+ 4 组件簇；后端约 6 服务 + 4 表 + 1 控制器；不做经验库/影响评估/playbook 引擎（Out of Scope）

## Constitution Check

*GATE: Phase 0 前评估，Phase 1 设计后复核——复核已完成，结论不变。*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. Files-First | ✅ PASS | 修复提案发布 = `writeTaskVersionSnapshot` 新版本快照，`dw pull` 取回 `*.task.yaml`；新增 `resources` 节进文件契约（pull/push 往返完整，R6/R7） |
| II. Server is Source of Truth | ✅ PASS | 提案发布走 push 同一程序化入口 + 基线陈旧校验（`base_version_no` 不符即 STALE）；无双向同步/合并 |
| III. Two-Legged Debugging | ✅ PASS | 不触碰 CLI 本地运行时与 TEST 提交链路 |
| IV. AI Lives in the Local Agent | ⚠️ **记录在案的偏差**（见 Complexity Tracking） | 服务端新增有界运维 Agent 管线；authoring 底线不破（修复走「带证据的变更提案」经审批与版本快照，非 authoring 界面）；观测与调度内核零损伤 |
| V. Reuse the Kernel | ✅ PASS | 调度器/版本快照/执行器零改写；闸门+审计全复用（新增 policy_rules 种子与 executor 分支）；SSE/EventBus/LogBus 复用；053 LLM 通道复用 |

**原则 IV 偏差裁决**：宪法 1.2.0 的原则 IV 写于「拆除服务端 authoring AI（chat cockpit/workhorse）」语境；已确认的北极星方向文档（2026-07-03，§4/§7.1）明确重划边界——authoring 永远走本地 Skill+CLI（不变），**运维动作（诊断/重跑/修复提案）属 Trust 层，允许常驻服务端**。053 已确立服务端有界 LLM 通道先例。本特性严守三条不可让渡内核的等价物：① 不新增任何 authoring 界面/工具；② 全部写动作过闸门留审计、改代码默认人审；③ 调度内核与观测零侵入。**随本特性应提交宪法修订案**（原则 IV 重定义为「authoring AI 归位本地；运维编队按方向文档 §4 允许服务端有界存在」，MAJOR bump），修订未批准前本偏差以 Complexity Tracking 记录 + 用户于 spec 阶段确认的方向文档为依据。**本偏差已由用户于 2026-07-13 显式批准**（/speckit-analyze C1 裁决：批准偏差、直接实施），满足 Governance「实现前记录+批准」要求；T042 宪法修订案照常作为 Phase 7 文本收口。

## Project Structure

### Documentation (this feature)

```text
specs/067-agent-incident-ops/
├── plan.md              # 本文件
├── research.md          # Phase 0（R1–R13 全裁决）
├── data-model.md        # Phase 1（4 新表/3 列变更/状态机/校验）
├── quickstart.md        # Phase 1（7 端到端场景 + 回归门）
├── contracts/
│   ├── incident-api.md  # REST + 闸门种子 + MCP 面
│   └── sse-live-feed.md # 直播流事件契约（持久化/瞬态两层）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令产物）
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/src/main/
│   ├── java/.../interfaces/IncidentController.java        # REST + SSE stream
│   ├── java/.../application/mcp/McpToolRegistry.java      # +query_incidents/incident_reverify
│   └── resources/schema.sql                                # 0.19.0：incident* 4 表 + task_def.resources_json
│                                                           #   + task_def_version.resources_json
│                                                           #   + lineage_agent_config.ops_enabled + policy 种子
├── dataweave-master/src/main/java/.../
│   ├── domain/incident/                                    # Incident/Message/Proposal/Briefing + 枚举 + 直播事件
│   ├── application/incident/                               # Sweeper/AgentService/EvidenceCollector/
│   │                                                       #   ConversationService/BriefingService/QueryService
│   ├── application/lineage/agent/LlmChatClient.java        # 新增通用多轮客户端（复用两协议适配器）
│   ├── application/{TaskService,ProjectSyncService}.java   # 资源节文件契约 + 程序化发布复用（微改）
│   ├── application/DefaultPlatformActionExecutor.java      # +incident_* action 分支
│   └── infrastructure/incident/                            # 4 个 Repository
├── dataweave-worker/src/main/java/.../                     # DispatchCommand+resources 传播；
│                                                           #   Spark/Flink/SeaTunnel/DataX 执行器内存/核数映射
cli/                                                        # task.yaml 契约新增可选 resources 节（pull/diff/push 透传）
frontend/
├── lib/workspace/{views.ts,registry.tsx,nav-groups.ts}     # +supervision 视图注册；DEFAULT_VIEWS[0]=supervision
├── components/workspace/views/supervision-view.tsx         # 指挥中心主视图
├── components/workspace/views/supervision/
│   ├── briefing-banner.tsx                                 # 战况横幅 + 接班报告展开
│   ├── live-feed.tsx                                       # 直播流（DwScroll+过滤+待处理置顶区）
│   ├── incident-thread.tsx                                 # 线程：消息流/chips/流式打字/思考态
│   └── chat-composer.tsx                                   # 对话输入 + 结构化裁决按钮
└── messages/{zh-CN,en-US}.json                             # +supervision 命名空间 + views.supervision
```

**Structure Decision**: 沿用两项目分离 + 后端四模块 DDD 分层。事故域完整落 `dataweave-master`（domain/application/infrastructure），REST/SSE 入口在 `dataweave-api` interfaces——与 053/060/062 的既有切分完全一致。worker 仅接收 resources 透传做引擎映射，不感知事故域。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 原则 IV：服务端存在 AI 推理调用（有界运维 Agent 管线） | 用户核心诉求 = 失败后无人在场时自动诊断处置；本地 agent 无法 7×24 值守服务端故障流；方向文档 §4 已确认 Trust 层编队常驻服务端 | ① 纯本地 agent 轮询（拒绝：违背「无需人工介入」目标，开发者机器不常在线）；② 外挂 agent 进程（方向文档目标形态，v1 拒绝：新部署单元/更宽 MCP 面/进程管理成本，作为后续演进）；③ 无 LLM 纯规则引擎（拒绝：分型与修复生成本质需要推理，规则库=Out of Scope 的 playbook 特性） |
| `task_def`/`task_def_version`/文件契约 同时加 `resources_json`（跨 master/worker/cli 三面） | FR-006 资源自愈的前置缺口：任务现无任何资源声明字段，无处可调 | 只改引擎参数字符串（拒绝：无结构化护栏，Agent 直接改 content 易写坏且无法与「改代码需人审」区分风险等级） |

## Phase 2 展望（/speckit-tasks 的切分依据，非本命令产物）

US1（Sweeper+采证+诊断+DIAG_UNAVAILABLE 降级）→ US2（梯度处置+资源列全链+提案/审批/回滚+验证收口+防循环）→ US3（指纹前置+升级+复验）→ US4（supervision 视图+SSE 直播+对话+播报+首屏切换）。每 US 独立可测（quickstart 场景 1-7 一一对应）；调度红线回归与 H2/PG 双库、浏览器门为横切收口任务。
