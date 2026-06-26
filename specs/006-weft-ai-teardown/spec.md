# Feature Specification: 子特性 A —— 服务端 AI 拆除(Server-Side AI Teardown)

**Feature Branch**: `006-weft-ai-teardown`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Weft 子特性 A:删除服务端 AI —— chat cockpit/AG-UI/workhorse/IntentRouter/主动发现(Inspector→Finding→Diagnosis→FindingAction→AgentNotifier)/OpsAlertService,瘦身前后端;保留运行态观测与写闸门(agent_action);前端移除左侧聊天台、Workspace 占满;新建 workspace_snapshot 表迁移 tab 快照;更名 Weft。"

## 概述

这是 Weft 转型的**子特性 A**,总纲见 `specs/005-weft-pivot/spec.md`,治理见 `.specify/memory/constitution.md`(原则 IV「AI Lives in the Local Agent」)。A 是**纯拆除**:把服务端内置的 AI 整套从前后端移除,净化环境,为后续 B–E 腾地方。A **不新增**业务能力,**不动**存量任务/任务流定义数据(那是 B/C 的 clean slate),也**不重塑** MCP 工具(那是 E)。

A 的成功定义:服务端 AI 痕迹清零 + 后端编译/启动正常 + 前端类型检查/构建/浏览器渲染通过 + **运行态观测与写闸门毫发无损**。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 服务端 AI 代码与端点被干净移除 (Priority: P1)

平台维护者移除服务端 AI 整套 —— AG-UI 端点与编排、workhorse 桥接、IntentRouter、主动发现链路(Inspector→Finding→Diagnosis→FindingAction→AgentNotifier)、运维巡检告警(OpsAlertService) —— 后端编译通过、可启动,代码与依赖无活跃残留。

**Why this priority**: 这是 A 的主体。AI 代码不清零,转型就没真正发生。P1。

**Independent Test**: 拆除后 `./mvnw -pl <module> compile` 零错、服务可启动;`POST /agui`、`GET /ops/supervisor`、`GET /ops/inspect` 返回 404;全仓库搜索 AguiController/IntentRouter/WorkhorseBridge/Inspector/FindingService/AgentNotifier 等无活跃引用。

**Acceptance Scenarios**:

1. **Given** 含服务端 AI 的后端,**When** 执行拆除并编译,**Then** 编译零错、服务正常启动。
2. **Given** 拆除后的服务,**When** 请求 `/agui`,**Then** 返回 404(端点已不存在)。
3. **Given** 拆除后的代码库,**When** 搜索被删类名,**Then** 无任何活跃 import/bean 注入残留。

---

### User Story 2 - 运行态观测与写闸门毫发无损 (Priority: P1)

拆除**不得**误伤非 AI 能力:ops 概览、metrics、运行日志流、DAG 实例视图必须照常;写闸门(PolicyEngine/GatedActionService/ApprovalService + `agent_action` 表)必须照常 —— 经 MCP/REST/CLI 的写操作仍留痕、审批流仍工作。

**Why this priority**: 这是拆除的安全边界。已知 `OpsController` 把 workhorse 探活端点与观测端点混置,删错会连带弄坏观测;`agent_action` 表名带 "agent" 但实为通用写闸门留痕,误删会摧毁治理。P1。

**Independent Test**: 拆除后 `GET /api/ops/metrics`、日志流、DAG 实例事件流均 200 且数据正常;经 MCP/REST 触发一次写操作,确认 `agent_action` 落一条记录、审批流可走完。

**Acceptance Scenarios**:

1. **Given** 拆除后的服务,**When** 访问 ops/metrics/日志/DAG 实例端点,**Then** 全部正常返回。
2. **Given** 拆除后的写闸门,**When** 经 MCP 提交一个写操作,**Then** `agent_action` 表新增留痕、策略判级与审批逻辑不变。
3. **Given** `OpsController`,**When** 逐端点裁剪,**Then** `/supervisor`、`/inspect` 被删而观测端点保留。

---

### User Story 3 - 前端聊天台移除,首页变查看控制台 (Priority: P1)

开发者打开首页,不再有左侧聊天驾驶舱;右侧多 tab Workspace 占满全宽,首页成为纯运维/查看控制台(ops/metrics/日志/DAG/类目树等 tab)。Workspace 的 tab 快照仍被持久化(改由新的 `workspace_snapshot` 后端表承载)。

**Why this priority**: 前端不清零,聊天残留会报错且与新定位冲突。P1,与后端拆除同等关键。

**Independent Test**: `pnpm typecheck` + `pnpm build` 通过;浏览器打开首页:无左侧聊天栏、Workspace 占满、无 console 报错;打开几个 tab、刷新后 tab 状态仍在(快照持久化生效)。

**Acceptance Scenarios**:

1. **Given** 拆除后的前端,**When** 类型检查与构建,**Then** 均通过。
2. **Given** 首页,**When** 浏览器渲染,**Then** 无聊天台、Workspace 占满全宽、控制台无报错。
3. **Given** 用户打开若干 tab 并刷新,**When** 页面重载,**Then** tab 快照经 `workspace_snapshot` 恢复。

---

### User Story 4 - 数据与配置净化 (Priority: P2)

AI 专属数据表与配置被清理:删除 `agent_session`/`agent_run`/`agent_step` 及 finding/diagnosis 相关表(workspace_state 先迁入新 `workspace_snapshot` 表);删除 `agent.mode` 配置、workhorse 的 WebClient 接线、docker-compose 的 `workhorse` profile、`deploy/workhorse/`;更新 CLAUDE.md 更名 Weft 并移除 AG-UI/chat/workhorse/findings 条目。

**Why this priority**: 数据/配置残留也是"环境污染"。P2,因为它依赖 Story 1/3 的代码先删。

**Independent Test**: 启动时 schema 不再含被删表;配置中无 `agent.mode`/workhorse;CLAUDE.md 无被删能力的引用且定位已是 Weft。

**Acceptance Scenarios**:

1. **Given** 拆除后,**When** 应用启动建表,**Then** 不再创建 agent_session/run/step、finding、diagnosis 等表,但 `agent_action`/policy/审批表仍在。
2. **Given** Workspace,**When** 持久化 tab 快照,**Then** 写入新的 `workspace_snapshot` 表(非 agent_session)。
3. **Given** 配置与文档,**When** 检查,**Then** 无 `agent.mode`/workhorse profile/deploy/workhorse;CLAUDE.md 定位为 Weft、无被删能力条目。

---

### Edge Cases

- **`agent_action` 误删**:它名带 "agent" 但由写闸门 `GatedActionService.submit()` 为**所有**写操作(MCP/REST/CLI)留痕、并承载审批单,MUST 保留;删它会摧毁治理与审批。
- **`OpsController` 端点混置**:`/supervisor`(workhorse 探活)、`/inspect`(OpsAlertService)与观测端点同居一个 Controller,MUST **逐端点裁剪**,不可整体删该 Controller。
- **`workspace_state` 迁移时序**:必须**先**建 `workspace_snapshot` 表并切换读写路径,**再**删 `agent_session` 表,避免中间态丢 tab 快照。
- **MCP 工具悬挂引用**:删除 `DiagnosisService` 等会导致 `query_diagnosis` 等 MCP 工具注册悬挂,MUST 同步剪掉这些工具注册(框架与其余工具保留)。
- **mock/workhorse 双模式**:现有 `agent.mode=mock|workhorse` 分支,两条都要拆;拆除后不得残留任一模式的 bean 接线导致上下文起不来。
- **测试连带**:被删能力的现有测试 MUST 一并删除/改写,否则编译/测试失败。

## Requirements *(mandatory)*

### Functional Requirements

#### 后端拆除
- **FR-A01**: MUST 删除 AG-UI 整套:`AguiController`(/agui 端点)、`AguiOrchestrator`、`AguiEvents`、`IntentRouter`。
- **FR-A02**: MUST 删除 workhorse 桥接:`WorkhorseBridge`、`WorkhorseHealth`、`WorkhorseSupervisor` 及其 WebClient 接线。
- **FR-A03**: MUST 删除主动发现整串:`Inspector`/`TaskFailureInspector`/`InspectorScheduler`、`FindingService`/`FindingActionService`/`Finding`、`DiagnosisService`、`AgentNotifier`、`AgentStreamController`、`AgentSessionService`。
- **FR-A04**: MUST 删除运维巡检告警 `OpsAlertService` 及其暴露端点。
- **FR-A05**: MUST 同步剪掉因上述删除而悬挂的 MCP 工具注册(如 `query_diagnosis`);MUST NOT 删除 MCP 框架本身(`McpController`/`McpToolRegistry`/`McpAuthFilter`)。

#### 保留与逐端点裁剪
- **FR-A06**: MUST 保留写闸门内核:`PolicyEngine`/`GatedActionService`/`ApprovalService`,以及 `agent_action`/`policy_rules`/审批表;经 MCP/REST/CLI 的写操作 MUST 仍写 `agent_action`、审批流 MUST 不变。
- **FR-A07**: MUST 保留运行态观测:ops 概览、metrics、运行日志流、DAG 实例视图;`OpsController` MUST **逐端点裁剪**(删 `/supervisor`、`/inspect`,保留观测端点),MUST NOT 整体删该 Controller。

#### 前端拆除
- **FR-A08**: MUST 删除前端聊天台:`components/agent-rail.tsx`、`agent-chat.tsx`、`components/chat/`(整目录)、`lib/chat/`(整目录)、`components/cockpit/`(findings-rail/diagnosis-card/fix-actions/fleet-card)。
- **FR-A09**: AppShell MUST 移除左栏,Workspace MUST 占满全宽;首页渲染 MUST 无聊天残留入口、浏览器 console MUST 无报错。
- **FR-A10**: MUST NOT 残留 `@copilotkit/*`、`@ag-ui/client` 或聊天相关依赖/路由。

#### 数据与配置
- **FR-A11**: MUST 新建轻量 `workspace_snapshot` 表(按用户键)承载 Workspace tab 快照,并把原 `AgentAuditService` 的 workspace_state 读写迁至新的 `WorkspaceSnapshotService`;迁移 MUST 先建新表/切路径再删旧表。
- **FR-A12**: MUST 删除 AI 专属表 `agent_session`/`agent_run`/`agent_step` 及 finding/diagnosis 相关表;MUST 保留 `agent_action`/policy/审批表。
- **FR-A13**: MUST 删除 `agent.mode` 配置、docker-compose 的 `workhorse` profile、`deploy/workhorse/` 目录;拆除后 mock 与 workhorse 两模式的 bean 接线 MUST 无残留(上下文可正常启动)。

#### 文档与范围边界
- **FR-A14**: MUST 更新 `CLAUDE.md`:更名 Weft、定位改"任务即代码平台"、移除 AG-UI/chat/workhorse/IntentRouter/findings 相关条目(constitution 标的 ⚠ 项)。
- **FR-A15**: A MUST NOT 删除或迁移存量任务/任务流定义数据(FR-019 的 clean slate 留 B/C);A 期间系统 MUST 仍可启动并展示运行态。
- **FR-A16**: 被删能力的现有测试 MUST 一并删除/改写;MUST 新增回归测试:`/agui`、`/ops/supervisor`、`/ops/inspect` 已 404,而 ops/metrics/日志/DAG 端点仍 200,写闸门仍写 `agent_action`。

### Key Entities

- **workspace_snapshot(新表)**:承载前端 Workspace 的 tab 快照;按用户(或会话)键;后端透明存储其序列化 JSON,替代原 `agent_session.workspace_state`。
- **agent_action(保留表)**:写闸门对所有副作用操作的留痕 + 审批单载体;非 AI 专属,A 必保留。
- **被删表**:`agent_session`/`agent_run`/`agent_step`(AI 会话/运行/步骤)、`finding`/diagnosis 相关表(主动发现产物)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-A01**: 拆除后,全仓库不存在 AguiController/AGUI 端点/IntentRouter/WorkhorseBridge/Inspector/FindingService/DiagnosisService/AgentNotifier/OpsAlertService 的活跃代码与依赖(搜索命中为 0,注释/历史除外)。
- **SC-A02**: 后端 `./mvnw -pl <changed-module> compile` 零错,服务可启动;前端 `pnpm typecheck` 与 `pnpm build` 通过。
- **SC-A03**: 运行态观测端点(ops/metrics/日志流/DAG 实例)100% 保留可用;`/agui`、`/ops/supervisor`、`/ops/inspect` 均 404。
- **SC-A04**: 经 MCP/REST 的写操作 100% 仍写入 `agent_action` 且审批流不变(抽样核对命中)。
- **SC-A05**: 浏览器打开首页:无聊天台、Workspace 占满、无 console 报错;打开 tab→刷新后快照经 `workspace_snapshot` 恢复。
- **SC-A06**: 应用启动不再建 agent_session/run/step、finding、diagnosis 表;`agent_action`/policy/审批表仍在;配置无 `agent.mode`/workhorse;CLAUDE.md 定位为 Weft。

## Assumptions

- **MCP 框架保留**:`McpController`/`McpToolRegistry`/`McpAuthFilter` 与 AG-UI/workhorse 完全正交(已代码勘查确认),A 只剪悬挂工具注册,框架留给 E 重塑。
- **OpsAlertService 无保留价值**:经确认其失败巡检告警对新定位无用,整体删除(含 `/ops/inspect`),不另开替代通道。
- **Workspace 快照保留服务端持久化**:选择新建 `workspace_snapshot` 表(而非退回 localStorage),保留跨设备能力;表结构细节实现时定。
- **存量定义数据 clean slate 不在 A**:存量任务/任务流定义数据删除属 B/C(FR-019),A 保持系统可演示。
- **并行实现**:A 的实现计划将拆为多个互不阻塞的工作流(按前端/后端模块切分)+ 一个收口集成验证 pass,以便由多个 Claude Code CLI agent 并发执行;并行边界须保证各工作流落地后整体编译/启动仍绿(详见后续 plan)。
- **工作位置**:A 在 git worktree `/home/wallfacers/project/dw-weft`(分支 `005-weft-pivot` 基线,特性 `006-weft-ai-teardown`)内推进。
