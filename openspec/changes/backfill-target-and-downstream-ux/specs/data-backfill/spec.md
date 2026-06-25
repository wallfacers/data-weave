## ADDED Requirements

### Requirement: 下游沿血缘展开与子集选择

补数据 SHALL 让「下游」真正生效:对 task 目标,系统沿**血缘**(基于既有 `FlowEdge` 的读写关系,可跨 workflow)解析下游任务集合,用户可选定其中**子集**一并补数据。补数据请求体 SHALL 携带显式 `downstreamTaskIds: number[]`(空数组=只补目标自身);最终补数据目标为 `[目标自身] ∪ downstreamTaskIds`,对每个 (目标 × bizDate) 各生成一条 BACKFILL 实例。下游任务的同 bizDate 上下游就绪由既有调度就绪门(上游 SUCCESS 才认领)自然串行,不引入新编排。`includeDownstream` 布尔字段保留作向后兼容(true 且无显式集合=全下游)。

下游解析 SHALL 复用 `LineageGraphService`,不新增数据表:从目标任务的产出表出发,沿 `FlowEdge(fromTableId→toTableId, taskDefId)` BFS,收集读取这些表的下游任务并去重去自身。

#### Scenario: 选定下游子集一并补数据
- **WHEN** 提交 `{ targetType: "task", targetId: 10, dateStart: "2026-06-20", dateEnd: "2026-06-20", downstreamTaskIds: [11, 12] }`
- **THEN** 为任务 10、11、12 各生成该 bizDate 的 BACKFILL 实例(共 3 条),同 bizDate 内 11/12 在其上游同日期实例 SUCCESS 后才被认领

#### Scenario: 空下游集合只补自身
- **WHEN** 提交的 `downstreamTaskIds` 为空数组(或字段缺失)
- **THEN** 仅为目标自身生成 BACKFILL 实例,行为与历史「只补目标」一致(向后兼容)

#### Scenario: 下游集合受血缘约束
- **WHEN** 解析任务 10 的下游
- **THEN** 返回集合为沿 `FlowEdge` 可达的、读取任务 10 产出表(及其级联产出)的下游任务,去重并排除任务 10 自身;血缘缺边时集合可能不完整,不做隐式补全

### Requirement: 下游影响范围预览

系统 SHALL 提供下游预览端点 `GET /api/ops/backfill/downstream-preview?targetType=task&targetId=`,返回目标任务的下游任务列表(id/名称/路径/层级),供前端在提交前以可勾选树/列表呈现影响范围。前端 SHALL 默认**不全选**下游,由用户显式勾选,避免误触发全图补数据。

#### Scenario: 预览返回下游任务列表
- **WHEN** 调用 `GET /api/ops/backfill/downstream-preview?targetType=task&targetId=10`
- **THEN** 返回任务 10 的下游任务列表(每项含 id/名称/路径/层级),其集合与下游解析逻辑一致

#### Scenario: 无下游时返回空
- **WHEN** 目标任务没有血缘下游
- **THEN** 预览返回空列表,前端提示「无下游」,用户仍可仅补目标自身

### Requirement: 补数据影响面参与闸门分级

补数据请求经 `GatedActionService` 时,`ActionRequest` SHALL 携带 `affectedTargetCount`(= 1 + 选定下游数),作为 `PolicyEngine` 数据驱动分级的信号,使大批量补数据可经 `policy_rules` 升级审批等级。

#### Scenario: 影响面计入闸门请求
- **WHEN** 提交含 N 个下游的补数据请求
- **THEN** 闸门 `ActionRequest` 的 `affectedTargetCount` 为 N+1,`PolicyEngine` 据现有策略裁决 outcome(EXECUTED/PENDING_APPROVAL/REJECTED)
