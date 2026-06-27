# Feature Specification: Weft 子特性 E —— MCP 工具重塑

**Feature Branch**: `010-weft-mcp-tools`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Weft 子特性 E:MCP 工具重塑,本地 AI 编程 agent 经 MCP 操作平台,所有写操作过 PolicyEngine 写闸门+审计"

## 概要

E 把平台能力以 **MCP 工具**形式暴露给开发者本地的 AI 编程 agent(Claude Code / Codex 等),让 AI 经 MCP 操作 Weft,取代 A 阶段拆除的服务端 AI 大脑。承接总纲(005)**FR-014/FR-015**,落地宪法**原则 IV「AI 归位本地」+ 原则 V「内核复用」**。

E 复用既有 `/mcp` JSON-RPC server 框架与 `McpToolRegistry` 注册机制,复用 **C(ProjectSyncService)** 提供项目同步类工具,复用 **PolicyEngine L0–L4 写闸门 + agent_action 审计**。E 不新建 MCP 协议层、不绕过写闸门。

**核心范式对齐**:任务即代码。E 让本地 AI agent 能「读平台状态 → 经 MCP 把本地文件定义推回 → 触发受控运行 → 读日志诊断」,所有写操作一律过闸门,不因来源是 AI 而放行。

依赖 **C**(项目同步工具底层调 `ProjectSyncService`)。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 本地 AI 经 MCP 读平台状态 (Priority: P1)

开发者本地 AI agent 经 `/mcp` 调用只读工具,查询任务/工作流定义、运行实例与状态、运行日志、血缘、指标快照,以理解平台当前态并辅助开发者诊断。只读工具受租户/项目隔离,免写闸门。

**Why this priority**: AI 要操作平台必先能"看见"平台。只读是一切 MCP 协作的地基,且无副作用风险,优先级最高。

**Independent Test**: 用 Bearer 令牌经 `/mcp` `tools/call` 调每个只读工具,断言返回与对应 REST/域服务同源数据;断言越权令牌/跨租户查询被拒。

**Acceptance Scenarios**:

1. **Given** 合法 Bearer 令牌, **When** `tools/list`, **Then** 返回重塑后的工具清单,不含任何已拆除的服务端 AI 专用工具(如 query_diagnosis)。
2. **Given** 合法令牌, **When** 调 task/workflow/实例/日志/血缘/指标只读工具, **Then** 返回与平台 REST/域服务同源的数据。
3. **Given** 越权或跨租户访问, **When** 调只读工具, **Then** 被隔离约束拒绝,返回可定位错误,无数据泄漏。

### User Story 2 - 本地 AI 经 MCP 把定义推回服务器 (Priority: P1)

开发者本地 AI agent 在改完本地文件定义后,经 MCP `project_push` 工具(底层复用 C 的 `ProjectSyncService.push`)把项目定义幂等覆盖回服务器并生成版本快照;`project_pull`/`project_diff` 工具支持 AI 读取与差异预览。该写操作**必经 PolicyEngine 写闸门**并留 agent_action 审计;触达高风险等级时进入审批挂起(PENDING_APPROVAL),不静默落库。

**Why this priority**: 这是 E 与 C 的接缝,也是"AI 归位本地"范式的闭环 —— AI 能像开发者一样把任务即代码推回治理。是 E 区别于"只读看板"的核心价值。

**Independent Test**: 经 MCP 调 `project_push` 推一份定义,断言:① 走了 `GatedActionService.submit` → PolicyEngine(可在审计表查到 agent_action);② 低风险直接落库 + 生成快照;③ 构造高风险触发 PENDING_APPROVAL,断言未落库直到批准;④ 越权项目被拒。

**Acceptance Scenarios**:

1. **Given** AI 持合法令牌 + 有权项目, **When** `project_push`(低风险), **Then** 经写闸门通过,幂等覆盖项目定义并为受影响 task/workflow 生成快照,留 agent_action 审计。
2. **Given** `project_push` 触达 L2/L3 风险等级, **When** 提交, **Then** 返回 PENDING_APPROVAL,定义不落库,直至人工在审批入口批准。
3. **Given** 一份无效/不完整定义, **When** `project_push`, **Then** 以可定位错误拒绝(透传 C 的校验),不部分落库。
4. **Given** AI 调 `project_pull`/`project_diff`, **Then** 返回文件集 / 差异预览(只读),供 AI 决策。
5. **Given** 越权项目, **When** 任意 project_* 工具, **Then** 被隔离约束拒绝。

### User Story 3 - 本地 AI 经 MCP 触发受控运行与诊断 (Priority: P2)

开发者本地 AI agent 经 MCP 触发任务重跑 / 受控节点命令等写操作以协助排障,并读取运行日志闭环诊断。所有写操作经同一写闸门 + 审计,高风险进审批;只读诊断(日志/实例状态)免闸门。

**Why this priority**: 让 AI 不只是改定义,还能参与运行态排障闭环。依赖只读(US1)与闸门(US2)已就位,优先级次之。

**Independent Test**: 经 MCP 调 `task_rerun`/`node_exec`,断言走闸门 + 审计;调日志/实例只读工具断言闭环诊断数据可得;断言所有写操作越权被拒。

**Acceptance Scenarios**:

1. **Given** AI 持合法令牌, **When** `task_rerun` 一个实例, **Then** 经写闸门触发重跑并留审计,高风险进审批。
2. **Given** `node_exec` 受控命令, **When** 命令串触发安全解析升级(重定向/分隔符/子命令), **Then** 按既有 PolicyEngine 规则升级等级/拒绝,不放行危险命令。
3. **Given** AI 读运行日志/实例状态, **Then** 返回与 ops 观测同源的数据,完成诊断闭环。

### Edge Cases

- 已拆除的服务端 AI 专用工具(query_diagnosis 等)残留注册 → `tools/list` MUST 不出现,且无活跃 handler。
- `project_push` 经 MCP 与经 CLI(D)推同一项目 → 两者均过同一写闸门 + 走 C 同一 `ProjectSyncService`,语义一致,后到者按乐观并发/基线处理。
- AI 反复触发同一高风险写 → 每次独立生成 ActionRequest + 审批单,不复用旧批准绕闸门。
- MCP 令牌缺失/过期 → `/mcp` 401,工具不可达。
- 写工具在审批挂起期间被 AI 轮询 → 经 `approve_and_execute` 类工具读状态,批准在平台审批入口完成,MCP 不自批。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 平台 MUST 经 `/mcp` 向本地 AI agent 暴露重塑后的工具集,至少覆盖:列出/读取定义、读取运行实例与状态、读取日志、读取血缘/指标(只读),提交定义、触发运行(写)。
- **FR-002**: `tools/list` MUST NOT 含任何 A 阶段已拆除的服务端 AI 专用工具;残留注册 MUST 被移除或重塑为符合任务即代码范式的工具。
- **FR-003**: E MUST 新增 `project_push` 工具,底层复用 C 的 `ProjectSyncService.push`,把本地文件定义幂等覆盖回服务器并生成版本快照。
- **FR-004**: E MUST 新增 `project_pull` 与 `project_diff` 只读工具,底层复用 C 的 `ProjectSyncService.pull/diff`,供 AI 读取文件集与差异预览。
- **FR-005**: 所有经 MCP 的**写操作**(project_push、task_rerun、node_exec 等)MUST 经既有写闸门链路 `ActionRequest → GatedActionService.submit → PolicyEngine`,并留 agent_action 审计;MUST NOT 因来源是 AI agent 而绕过。
- **FR-006**: 触达 L2/L3 风险等级的写操作 MUST 进入审批挂起(PENDING_APPROVAL),在人工批准前 MUST NOT 落库/执行;L4 MUST 拒绝;L0/L1 直接执行。
- **FR-007**: 所有 MCP 工具(读与写)MUST 受租户/项目隔离约束;越权 MUST 被拒,无跨租户数据泄漏。
- **FR-008**: 只读工具 MUST 复用既有域服务/REST 同源数据,MUST NOT 复制并行查询逻辑导致口径漂移。
- **FR-009**: `node_exec` 的命令串安全解析(白名单前缀 + 重定向/分隔符/子命令升级)MUST 沿用既有 PolicyEngine 规则,E 不弱化。
- **FR-010**: 每个工具 MUST 提供 name + JSON Schema + handler 注册(沿用 `McpToolRegistry.registerTools()` 机制);工具描述等后端生成文案 MUST 走 `Messages.get`(按 agent locale)。
- **FR-011**: `/mcp` MUST 沿用既有 Bearer(`mcp.auth.token`)鉴权;缺失/无效令牌 MUST 拒绝。
- **FR-012**: E MUST NOT 损伤运行态观测(ops/metrics/日志/DAG 实例)与调度内核;MUST NOT 重新引入服务端 AI(chat/AG-UI/workhorse/IntentRouter/findings)。

### Key Entities *(include if feature involves data)*

- **MCP Tool**:一个工具的 name + JSON Schema + handler;分只读(免闸门)与写(过闸门)两类。
- **ActionRequest / Gate Result**:写工具提交的受控动作请求及闸门裁决(通过/挂起/拒绝),复用既有结构。
- **agent_action 审计记录**:每次写操作的审计行(来源、工具名、动作类型、裁决),复用既有 4 张审计表机制。
- **Project Sync 调用**:project_pull/push/diff 工具对 C `ProjectSyncService` 的调用与其文件集/结果。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `tools/list` 返回的工具 100% 属于重塑后清单,0 个已拆除 AI 专用工具残留。
- **SC-002**: 经 MCP 的写操作 100% 留下 agent_action 审计,0 旁路;构造的 L2/L3 写 100% 进 PENDING_APPROVAL 且批准前 0 落库。
- **SC-003**: 越权/跨租户 MCP 调用 100% 被拒,0 数据泄漏。
- **SC-004**: `project_push` 经 MCP 与经 C 服务直调对同一输入产生语义等价结果(同走 ProjectSyncService),round-trip 一致。
- **SC-005**: 只读工具数据与对应 REST 端点同源,口径 0 漂移(抽样比对)。
- **SC-006**: E 落地后 ops/metrics/日志/DAG 实例端点行为不回归(回归测试全绿)。

## Assumptions

- 复用既有 `/mcp` JSON-RPC(initialize/tools.list/tools.call)+ Bearer 框架,E 只增删工具,不改协议层。
- 复用 C 的 `ProjectSyncService`(pull/push/diff)作为 project_* 工具底层;C 已落地且零修改。
- 复用既有 `GatedActionService`/`PolicyEngine`/agent_action 审计;写闸门规则数据驱动(policy_rules 表),E 不硬编码绕过。
- 工具集的精确边界(保留/重塑/新增哪些)在 plan 阶段依现存 `McpToolRegistry` 实况裁定;本 spec 约束"至少覆盖 + 必过闸门 + 无残留 AI 工具"。
- A 阶段已删除大部分服务端 AI;E 负责确认 MCP 侧无残留并补齐任务即代码范式工具。

## Dependencies

- **C(008)pull/push/diff** 的 `ProjectSyncService`:project_* 工具底层。**零修改复用**。
- **既有 `/mcp` server + `McpToolRegistry`**(`backend/dataweave-api/.../mcp/`)。
- **既有 PolicyEngine / GatedActionService / agent_action 审计 / policy_rules**。
- **既有域服务 / 观测端点**(只读工具同源)。
- 与 **D** 的交叉面:`project_push` 与 D 的 `dw push` 共用 C 同一 `ProjectSyncService` 与同一写闸门 —— 须保证语义一致,不各自实现。

## Out of Scope

- 服务端 AI 任何重建(chat/AG-UI/workhorse/IntentRouter/findings)。
- 新的 MCP 协议层 / 鉴权体系。
- CLI 与本地 runtime(D)。
- C 的 `ProjectSyncService` 任何修改(仅调用)。
- PolicyEngine 规则的弱化或绕过通道。
