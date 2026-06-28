# Data Model: Weft 子特性 E —— MCP 工具重塑

**Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

E 不新增 DB 表。以下为 MCP 上下文扩展、闸门 seed、工具契约。

## 1. MCP 身份上下文（E1）

`McpContext` 扩展(现有仅 `args()`/`locale()`):

| 字段 | 来源 | 用途 |
|---|---|---|
| `tenantId()` | `McpAuthFilter` 由 token 解析的身份(MVP:`mcp.auth.tenant-id`) | 所有读写按租户限定 |
| `userId()` | `mcp.auth.user-id` | 写工具 ActionRequest actor 归属 + 审计 |

`McpController` 分发工具前 `TenantContext.set(tenantId, userId, …)`,`finally` 清理。所有 query_*/project_* 经 `TenantContext.tenantId()` 限定。

**身份缺失/无效 token** → `McpAuthFilter` 401(既有);身份配置缺失 → 启动期校验失败或工具返回 `mcp.tenant_required` 错误码。

## 2. `policy_rules` seed（E3,数据驱动定级)

| actionType | 基础 level | 触发 |
|---|---|---|
| `PROJECT_PUSH` | **L1** | project_push 且 diff.removed 为空且非 force → 直通+审计 |
| `PROJECT_PUSH_DESTRUCTIVE` | **L2** | project_push 且 diff.removed 非空 **或** force=true → 审批挂起 |

PolicyEngine 仍可在此基础上按归属/环境抬升(不下降)。**PolicyEngine 代码零改**。

## 3. 写工具 ActionRequest（E4,复用既有结构)

```text
ActionRequest{
  toolName   = "project_push"
  actionType = "PROJECT_PUSH" | "PROJECT_PUSH_DESTRUCTIVE"  // 工具按 diff 选
  targetType = "PROJECT"
  targetId   = <projectId>
  command    = <push payload 编码: files + baseline + force>
  actor      = "agent"
  actorSource= "AGENT"
  summary    = "推送项目 #<id> 定义（新增N/更新M/删除K）"
}
```

`gatedActionService.submit(req, ctx.locale())` → `GateResult{outcome: EXECUTED|PENDING_APPROVAL|REJECTED, …}`。`DefaultPlatformActionExecutor` 在 EXECUTED(L0/L1)或审批通过后回调:解码 payload → `ProjectSyncService.push(projectId, tenantId, userId, PushCommand)`(`@Transactional` 全有或全无)。

## 4. 工具清单与隔离/闸门矩阵

| 工具 | 读/写 | 隔离 | 闸门 | 底层 |
|---|---|---|---|---|
| `project_pull` | 读 | tenant | — | C `ProjectSyncService.pull` |
| `project_diff` | 读 | tenant | — | C `ProjectSyncService.diff` |
| `project_push` | 写 | tenant | **风险自适应 L1/L2** | C push（经执行器） |
| `instance_logs` | 读 | tenant | — | `OpsService` 日志读取 |
| `query_task_definitions` | 读 | tenant(**回补**) | — | repo `findByTenantId`(原 findAll) |
| `query_task_instances` | 读 | tenant(**回补**) | — | 同上 |
| `query_fleet`/`query_metric`/`query_lineage` | 读 | tenant(**回补**) | — | 域服务 tenant-scoped |
| `task_rerun` | 写 | tenant | 既有(TASK_RERUN) | 既有 |
| `node_exec` | 写 | tenant | 既有(NODE_EXEC,命令安全解析不弱化) | 既有 |
| `approve_and_execute` | 读 | tenant | — | 读审批单状态(不自批) |
| ~~`create_task`~~ | — | — | — | **移除**(改走 project_push) |
| ~~`batch_*` 内联创建~~ | — | — | — | **移除/重塑** |

## 5. 错误码（FR-010,双 bundle 对齐)

| code | 场景 |
|---|---|
| `mcp.tenant_required` | MCP 身份缺失/未配置 |
| (复用 C)`project.sync.*` | push 校验失败/未知数据源/删除 ONLINE 引用 |
| (复用)`gate.rejected`/`gate.pending` | 闸门反馈,按 agent locale |

## 6. 状态转移（project_push)

1. AI 调 `project_push{projectId, files, baseline, force}`。
2. 工具 `diff` 计算 removed → 选 actionType。
3. `submit` → PolicyEngine 定级:
   - L1 → 执行器回调 push → 覆盖+快照+审计 → 返回 created/updated/deleted+newBaseline。
   - L2 → `PENDING_APPROVAL`,**不执行 push**,定义 0 落库,返回审批单 id;人工在审批入口批准后执行。
   - 校验失败 → 透传 C 错误码,不部分落库。
4. 全程留 agent_action 审计。
