# Research: Weft 子特性 E —— MCP 工具重塑

**Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

落在真实 `McpToolRegistry` / `McpAuthFilter` / `GatedActionService` / `PolicyEngine` 上。

## E1 — MCP 租户身份(隔离的根因,FR-007 关键难点)⚠️

**现状**:`McpAuthFilter` 只校验**单一静态共享 token**(`mcp.auth.token`),**MCP 完全无租户概念**;`McpContext` 仅有 `args()`/`locale()`,无 tenantId。这才是 `query_*` 用 `findAll()` 跨租户的根因 —— 不是"忘了过滤",是"MCP 根本没有租户身份"。

**Decision**: 给 MCP token **绑定租户/用户身份**。MVP 用配置注入:`mcp.auth.token` + `mcp.auth.tenant-id` + `mcp.auth.user-id`;`/mcp` 分发工具前,从该身份**设置 `TenantContext`**(ThreadLocal,与 JWT 路径同源),工具内一切查询/写入自然按 `TenantContext.tenantId()` 限定。`McpContext` 增 `tenantId()/userId()` 暴露给 handler。

**Rationale**:
- 契合 Weft「开发者本地 AI agent 操作平台」模型 —— 一个开发者 = 一个租户 token,身份随 token 配置而定,真隔离即时生效。
- 复用既有 `TenantContext` 机制,工具内只需把 `findAll()` 换 `findByTenantId(...)`,改动收敛。
- 写工具的 `ActionRequest.actor/actorSource` 已是 "agent"/"AGENT",补 tenant/user 即闭环审计归属。

**Future**: 多租户 MCP 场景下,把单 token 升级为 **token→(tenant,user) 注册表**(token registry);MVP 的 config-bound 是其退化单行。

**⚠️ 需用户知会**:这把 MCP 鉴权从"单一共享密钥"扩为"带身份的密钥",属 E 的最大设计决策;若用户希望另一种身份模型(如 token 注册表先行 / 每调用传 projectId 并校验),在此调整。

**Alternatives rejected**:
- 工具参数传 tenantId/projectId 但不绑 token → 单共享 token 下任何调用方可冒充任意租户,**非真隔离**,否决。
- 维持单 token 不加身份、只给新工具加显式 projectId → 既有 query_* 漏洞不闭环,违 FR-007。

## E2 — 工具集重塑清单(FR-001/002/002a)

**Decision**:

| 工具 | 处置 | 说明 |
|---|---|---|
| `create_task` | **移除** | 内联 SQL 直建+上线,绕过文件表示违原则 I;定义写入一律走 `project_push`(clarify 裁定) |
| `batch_*`(任务批量创建类) | **移除/重塑** | 同上,若属内联创建则移除 |
| `query_task_definitions` `query_task_instances` `query_fleet` `query_metric` `query_lineage` | **保留 + 补租户隔离** | `findAll()` → 按 `TenantContext` 过滤(E5) |
| `task_rerun` `node_exec` `approve_and_execute` | **保留**(已过闸门) | 写工具维持闸门;`node_exec` 命令串安全解析不弱化(FR-009) |
| `project_pull` `project_push` `project_diff` | **新增** | 复用 C `ProjectSyncService`(E3/E4) |
| `instance_logs`(读运行日志快照) | **新增** | FR-001「读取日志」;复用 `OpsService` 日志读取,tenant-scoped |

**Rationale**: 守 spec「至少覆盖列出/读取+提交+触发+读日志 + 写必过闸门 + 无残留 AI 工具 + 全隔离」。逐一去留依现存注册实况(已读 `registerTools()`)。

## E3 — `project_push` 风险自适应定级(FR-006)

**Decision**: **两个 actionType 数据驱动,PolicyEngine 零改**:
- `seed policy_rules`:`PROJECT_PUSH` 基础级 **L1**;`PROJECT_PUSH_DESTRUCTIVE` 基础级 **L2**。
- `project_push` 工具 submit 前先 `ProjectSyncService.diff` 计算 added/modified/**removed**;若 `removed` 非空 **或** `force=true` → actionType 取 `PROJECT_PUSH_DESTRUCTIVE`(L2 审批挂起),否则 `PROJECT_PUSH`(L1 直通+审计)。

**Rationale**:
- PolicyEngine 已是数据驱动(按 actionType 查 policy_rules 定基础级)+ 归属/环境/数量抬升;用**两条 seed 规则**实现风险自适应,**不动 PolicyEngine 代码**,最干净。
- 与 C 删除守卫语义同调(删除=高风险);整次 push 一个闸门决策,对齐 C 的 `@Transactional` 全有或全无。

**Alternatives rejected**:
- 给 PolicyEngine 加"破坏性 push"专用抬升逻辑 → 改内核、非数据驱动,否决。
- 一律 L2 → AI 闭环每次卡人审,体验差(clarify 已否)。

## E4 — `project_push` 执行接线(FR-005)

**Decision**: 写工具构造 `ActionRequest{toolName="project_push", actionType=…, targetType="PROJECT", targetId=projectId, command=<push payload 编码>, actor="agent", actorSource="AGENT"}` → `gatedActionService.submit(req, locale)`。在平台动作执行器(`DefaultPlatformActionExecutor`,已有 TEST_RUN 等 case)**新增 `PROJECT_PUSH`/`PROJECT_PUSH_DESTRUCTIVE` case**,L0/L1 直执行或审批通过后回调 → 解码 payload → `ProjectSyncService.push(projectId, tenantId, userId, PushCommand)`。

**Rationale**:
- 复用既有"闸门裁决 → 执行器执行"链路(create_task 把 content 编进 command 同款),不旁路。
- push payload(files+baseline+force)随 `ActionRequest.command` 携带(内存态,bundle 量级可接受);执行器内 push 仍 `@Transactional` 全有或全无。

**风险**:bundle 大时 command 字段大 —— MVP 可接受;若需要,payload 可走临时存储引用,留实现期评估。

## E5 — 只读隔离回补(FR-007)

**Decision**: 所有 query_* 工具把 `repository.findAll()` 替换为按 `TenantContext.tenantId()` 过滤的查询(`findByTenantId…`);新增读工具(project_pull/diff、instance_logs)同样 tenant-scoped。越权/跨租户 100% 被拒(SC-003)。

**Rationale**: E1 建立 tenant 身份后,隔离回补即"把 findAll 改 findByTenant";触及既有工具是 E 必做范围(clarify 裁定)。需确认相关 repository 有 `findByTenantId` 或补之(增量,不改既有签名)。

## E6 — i18n(FR-010)

**Decision**: 工具 name 稳定(英文标识);工具**描述**与闸门反馈(approval reason/pending/rejected)走 `Messages.get(key, agentLocale)`(后端生成文案,按 agent locale),与既有 `gateText`/`gatedActionService` 一致。新增错误码 `<domain>.<semantic>`(如 `mcp.tenant_required`)双 bundle 对齐。

## 复用点汇总(零修改)

| 复用 | 来源 | E 用法 |
|---|---|---|
| `ProjectSyncService` pull/push/diff | C(008) | project_* 工具底层 |
| `McpToolRegistry`/`McpTool`/`McpContext`/`/mcp` JSON-RPC | 既有 | 增删工具、扩 tenantId |
| `GatedActionService`/`PolicyEngine`/`policy_rules`/agent_action | 既有 | 写工具闸门+审计 |
| `DefaultPlatformActionExecutor` | 既有 | 新增 PROJECT_PUSH case |
| `TenantContext` | 既有(JWT 路径) | MCP 分发前注入身份 |
| `OpsService` 日志/实例读取 | 既有 | instance_logs 只读工具 |

## 新增点(本期)

- MCP 身份:`McpAuthFilter`/`McpContext` 扩 tenant/user;分发前设 `TenantContext`。
- 工具:移除 create_task/batch 内联创建;新增 project_pull/push/diff + instance_logs;query_* 补隔离。
- seed:`policy_rules` 两条(PROJECT_PUSH / PROJECT_PUSH_DESTRUCTIVE)。
- 执行器:`DefaultPlatformActionExecutor` PROJECT_PUSH case。
