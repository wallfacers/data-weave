# data-backfill Specification

## Purpose
TBD - created by archiving change data-ops-center. Update Purpose after archive.
## Requirements
### Requirement: 补数据实例生成

系统 SHALL 支持按 任务/工作流 × 日期区间 [dateStart, dateEnd] 生成补数据实例,经 `POST /api/ops/backfill`。每个 bizDate × 目标任务生成一条 `run_mode='BACKFILL'` 实例,`$bizdate` 参数按对应日期注入,并落一条 `backfill_run` 父记录。

#### Scenario: 单任务三天区间生成
- **WHEN** 提交 `{ targetType: "task", targetId: 10, dateStart: "2026-06-20", dateEnd: "2026-06-22", includeDownstream: false, parallelism: 2 }`
- **THEN** 生成 3 条 BACKFILL 实例(每天一条),各自 `$bizdate` 为对应日期,返回 `backfill_run` 记录,outcome 反映闸门裁决

#### Scenario: bizDate 参数正确注入避免无日志
- **WHEN** 补数据实例被执行
- **THEN** `$bizdate` 已解析为该实例对应日期(非缺省),不出现「bizDate 缺省致 CAS-FAILED 无日志」

#### Scenario: 写操作经闸门
- **WHEN** 提交补数据请求
- **THEN** 经 `GatedActionService` 裁决;未配策略时按默认等级裁决(L2→PENDING_APPROVAL 时不立即生成,返回待批)

### Requirement: 补数据依赖编排与并发度

工作流目标补数据 SHALL 物化整张 DAG（每 bizDate 一个 workflow_instance），同一 bizDate 内的上下游就绪由既有调度就绪门（上游 SUCCESS 才认领）自然串行,无需额外编排。`parallelism` M1 记录于 `backfill_run`,跨 bizDate 的运行并发由全局调度/worker 容量自然约束;硬节流(同时最多 N 个 bizDate)推迟 M2(见 design 开放问题③)。

#### Scenario: 同日期上下游串行(工作流目标)
- **WHEN** 对含上下游链的工作流发起补数据
- **THEN** 每个 bizDate 物化整张 DAG,同 bizDate 内下游节点在上游同日期节点 SUCCESS 后才被认领(复用既有就绪门)

#### Scenario: 并发度记录于批次
- **WHEN** 以 parallelism=2 发起补数据
- **THEN** `backfill_run.parallelism` 记为 2(供展示与 M2 节流),M1 不硬约束跨 bizDate 并发

### Requirement: 补数据 run 进度查询

系统 SHALL 提供补数据 run 列表与单 run 详情查询(`GET /api/ops/backfill`、`GET /api/ops/backfill/{runId}`),进度(total/success/failed/running)由子实例状态聚合得出。

#### Scenario: run 列表带聚合进度
- **WHEN** 调用 `GET /api/ops/backfill`
- **THEN** 返回各 run 的 total/success/failed/running 与 state(RUNNING/SUCCESS/FAILED/PARTIAL)

#### Scenario: run 详情下钻子实例
- **WHEN** 调用 `GET /api/ops/backfill/{runId}`
- **THEN** 返回 run 元信息 + 其全部子实例(InstanceRow),可继续下钻日志

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

