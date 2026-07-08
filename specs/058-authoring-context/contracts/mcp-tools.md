# Contract: MCP 只读工具（McpToolRegistry）

均为**只读查询**（无写、不过策略闸门，因无副作用）；每个 handler `requireTenant(ctx)`；复用 `AuthoringContextService`（与 CLI/REST 同一后端能力，SC-006）。**新增并存，不改旧工具签名**（research D4）。

## query_authoring_context

- **desc**: 返回某任务的创作上下文（读写表→上下游 + 表/列血缘 + 数据源 schema，租户+项目隔离）。
- **schema**: `{ taskId: string(req), depth?: integer, include?: string[] }`
- **handler**: `requireTenant` → `AuthoringContextService.context(tenant, project, taskId, depth)`。

## query_task_deps

- **desc**: 返回任务依赖视图（声明 DAG + 推导血缘，带 origin）。
- **schema**: `{ taskId: string(req) }`

## query_reuse_candidates（P2）

- **desc**: 返回与目标任务写表目标重叠的复用候选（确定性排序）。
- **schema**: `{ taskId: string(req) }`

## query_lineage_diagnostics（P3）

- **desc**: 返回任务的一致性诊断（悬空上游/列契约破坏/重复定义/依赖背离），建议性。
- **schema**: `{ taskId: string(req) }`

## query_lineage（既有，升级说明）

- **保留**旧签名与语义（指标 code→SQL→物理表），兼容既有调用方。
- 表/列级血缘的新面由上述新增工具承载——这即 FR-015「修漂移」的落地方式（新增覆盖，而非删旧/破坏）。

## 注意

- MCP 工具仅接受**已 push 任务**（`taskId`）；未 push 工作副本的无状态分析走 **CLI/REST analyze**（MCP 不承载本地文件集）。
- 严守宪法 IV：这些是**查询**工具，绝不引入创作/写能力；创作仍只走 CLI/Skill + push 闸门。
