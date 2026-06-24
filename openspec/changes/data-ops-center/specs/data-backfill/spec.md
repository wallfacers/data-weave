## ADDED Requirements

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

系统 SHALL 在 `includeDownstream=true` 时,使同一 bizDate 内的下游补数据实例依赖同日期上游实例的就绪;`parallelism` 控制跨 bizDate 的并发提交上限。

#### Scenario: 同日期上下游串行
- **WHEN** includeDownstream=true 且目标含上下游链
- **THEN** 同一 bizDate 内下游实例在上游同日期实例成功后才就绪(复用既有就绪判定)

#### Scenario: 跨日期受并发度限制
- **WHEN** parallelism=2 且区间 5 天
- **THEN** 同时最多 2 个 bizDate 的链在跑,其余排队

### Requirement: 补数据 run 进度查询

系统 SHALL 提供补数据 run 列表与单 run 详情查询(`GET /api/ops/backfill`、`GET /api/ops/backfill/{runId}`),进度(total/success/failed/running)由子实例状态聚合得出。

#### Scenario: run 列表带聚合进度
- **WHEN** 调用 `GET /api/ops/backfill`
- **THEN** 返回各 run 的 total/success/failed/running 与 state(RUNNING/SUCCESS/FAILED/PARTIAL)

#### Scenario: run 详情下钻子实例
- **WHEN** 调用 `GET /api/ops/backfill/{runId}`
- **THEN** 返回 run 元信息 + 其全部子实例(InstanceRow),可继续下钻日志
