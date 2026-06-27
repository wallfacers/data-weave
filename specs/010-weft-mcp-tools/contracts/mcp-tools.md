# Contract: MCP 工具面（子特性 E）

`/mcp` JSON-RPC（`initialize` / `tools/list` / `tools/call`,Bearer `mcp.auth.token`)。E 后:身份带租户、写过闸门、读受隔离。

## 身份（E1）

- `McpAuthFilter` 校验 Bearer token,并解析绑定身份(MVP:`mcp.auth.tenant-id`/`mcp.auth.user-id`)。
- `McpController` 分发前 `TenantContext.set(tenantId,userId)`;`finally` clear。
- 缺 token → 401;缺身份配置 → `mcp.tenant_required`。

## 只读工具（免闸门,tenant-scoped）

| 工具 | 入参 | 返回 | 隔离 |
|---|---|---|---|
| `project_pull` | `projectId` | 文件集 `{files,baseline,fileCount}` | 仅本租户项目,越权拒 |
| `project_diff` | `projectId, files, baseline?` | `{added,modified,removed,stale}` | 同上;**零写入** |
| `instance_logs` | `instanceId` | 日志快照行 | 仅本租户实例 |
| `query_task_definitions` | — | 任务定义列表 | **回补**:`findByTenantId`(原 findAll 跨租户) |
| `query_task_instances` | `state?` | 实例列表 | **回补** tenant |
| `query_fleet`/`query_metric`/`query_lineage` | … | 同既有 | **回补** tenant |
| `approve_and_execute` | `approvalId` | 审批单状态/结果(不自批) | tenant |

## 写工具（过 PolicyEngine 闸门 + agent_action 审计）

### `project_push`（FR-003/005/006）

- **入参**:`projectId, files{path→content}, baseline?, force?, remark?`
- **流程**:① `ProjectSyncService.diff` 算 removed → 选 actionType `PROJECT_PUSH`(L1)/`PROJECT_PUSH_DESTRUCTIVE`(L2,含删除或 force);② `submit` 闸门;③ L1 直执行 push、L2 `PENDING_APPROVAL`(0 落库)、L4 拒;④ 执行经 `DefaultPlatformActionExecutor` → `ProjectSyncService.push`(`@Transactional`)。
- **返回**:EXECUTED → `{created,updated,deleted,snapshots,newBaseline}`;PENDING → 审批单 id;校验失败 → `project.sync.*` 错误码,不部分落库。
- **越权**:跨租户项目拒(FR-007)。

### `task_rerun` / `node_exec`（保留)

- 维持既有 `ActionRequest → submit` 闸门;`node_exec` 命令串安全解析(重定向/分隔/子命令 → ≥L2)不弱化(FR-009)。tenant-scoped。

## 移除

- `create_task`(内联 SQL 直建+上线)→ 定义写入一律 `project_push`(FR-002a)。
- `batch_*` 内联创建类 → 移除/重塑。
- 确认无 A 阶段残留 AI 工具(query_diagnosis 等)。

## 不变量(SC)

| 不变量 | SC |
|---|---|
| `tools/list` 0 残留 AI 工具、0 create_task | SC-001 |
| 写 100% 留审计;含删除/force push 100% PENDING 且批前 0 落库;纯增改 100% L1 | SC-002 |
| 越权/跨租户(含 query_*)100% 拒 | SC-003 |
| project_push(MCP)与 C 直调同语义 | SC-004 |
| 只读与 REST 同源口径 | SC-005 |
| ops/metrics/日志/DAG 不回归 | SC-006 |

## 复用端点/服务（E 零修改)

| 复用 | 来源 |
|---|---|
| `ProjectSyncService.pull/push/diff` | C(008) |
| `GatedActionService`/`PolicyEngine`/`policy_rules`/agent_action | 既有 |
| `DefaultPlatformActionExecutor`(+PROJECT_PUSH case) | 既有 |
| `TenantContext` / `OpsService` 日志读取 | 既有 |
