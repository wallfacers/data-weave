# ops-instance-management Specification

## Purpose
TBD - created by archiving change data-ops-center. Update Purpose after archive.
## Requirements
### Requirement: 周期实例多维筛选与分页

系统 SHALL 提供按 `runMode`、`state`、`taskId`、`bizDate` 组合筛选并分页查询实例的能力,经 `GET /api/ops/instances`。

#### Scenario: 按状态筛选失败实例
- **WHEN** 调用 `GET /api/ops/instances?state=FAILED&runMode=NORMAL&page=0&size=20`
- **THEN** 返回 `{ code, data: { items, total, page, size } }`,`items` 仅含 state=FAILED 且 runMode=NORMAL 的实例,按 id 降序

#### Scenario: 按业务日期与任务联合筛选
- **WHEN** 调用 `GET /api/ops/instances?taskId=10&bizDate=2026-06-23`
- **THEN** 返回该任务在该 bizDate 的实例集合,字段含 id/state/runMode/startedAt/finishedAt/durationMs

#### Scenario: 空结果分页
- **WHEN** 筛选条件无匹配
- **THEN** 返回 `items: []`、`total: 0`,不报错

### Requirement: 实例批量操作

系统 SHALL 提供对一组实例 id 批量执行 `rerun`/`kill`/`set-success` 的能力,经 `POST /api/ops/instances/batch`,每个实例独立走 `GatedActionService` 闸门并汇总结果。

#### Scenario: 批量重跑返回逐项结果
- **WHEN** 提交 `{ ids: [a, b, c], op: "rerun" }`
- **THEN** 返回 `{ code, data: { requested: 3, accepted, results: [{ id, outcome, approvalId? }] }, outcome }`,每项 outcome ∈ EXECUTED|PENDING_APPROVAL|REJECTED

#### Scenario: 部分实例被闸门拦截
- **WHEN** 批量操作中部分实例命中 L2/L3 策略
- **THEN** 这些项 outcome=PENDING_APPROVAL 并带 approvalId,其余 EXECUTED,整体不因部分待批而失败

#### Scenario: 非法 op 拒绝
- **WHEN** 提交未知 op
- **THEN** 返回业务错误码,不执行任何实例

### Requirement: 置成功(set-success)

系统 SHALL 允许将非成功态实例(FAILED/STOPPED/RUNNING/PREEMPTED)经乐观 CAS 推进为 SUCCESS,并唤醒其下游 WAITING 实例;NOT_RUN/WAITING 不允许置成功。

#### Scenario: 失败实例置成功并唤醒下游
- **WHEN** 对 state=FAILED 的实例调用 set-success
- **THEN** 该实例 CAS 推进为 SUCCESS,且依赖它的下游 WAITING 实例被重新评估就绪并唤醒

#### Scenario: 拒绝对未运行实例置成功
- **WHEN** 对 state=WAITING 或 NOT_RUN 的实例置成功
- **THEN** 拒绝并返回业务错误(无运行事实不可置成功)

#### Scenario: CAS 竞争失败不误置
- **WHEN** 置成功时实例状态已被并发改动(CAS `WHERE state=?` 不命中)
- **THEN** 不写入 SUCCESS,返回冲突,状态保持一致

