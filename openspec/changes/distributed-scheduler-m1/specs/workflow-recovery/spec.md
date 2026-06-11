# workflow-recovery Delta

## ADDED Requirements

### Requirement: 断点恢复

失败的工作流实例 SHALL 支持"恢复运行"：已 SUCCESS 的节点保留终态并跳过，FAILED/STOPPED/未运行节点重新置为可调度，从失败点继续推进整条流直至完成。恢复 SHALL 使 `workflow_instance` 从 FAILED 状态重新进入 RUNNING。恢复操作属于人为发起，MUST 经 `GatedActionService` 闸门并留痕。

#### Scenario: 从失败节点续跑

- **WHEN** 一条 50 节点的流在第 30 个节点失败，用户发起恢复运行
- **THEN** 前 29 个 SUCCESS 节点不重跑，第 30 个节点重新执行，成功后下游按 DAG 继续推进

#### Scenario: 恢复后再次失败可再恢复

- **WHEN** 恢复运行后另一节点失败，用户再次发起恢复
- **THEN** 第二次恢复同样跳过全部已成功节点，从新失败点继续

### Requirement: 整流重跑

工作流实例 SHALL 支持"整流重跑"：将该实例全部节点状态重置后走与断点恢复同一套推进机制，从 DAG 起点完整重跑。整流重跑 MUST 经闸门并留痕。

#### Scenario: 整流重跑覆盖全部节点

- **WHEN** 用户对一个已结束（无论成败）的实例发起整流重跑
- **THEN** 所有节点（含原 SUCCESS 节点）重新执行一遍

### Requirement: 节点级自动重试

任务实例失败时 SHALL 按其任务定义的 `retry_max` 与退避策略自动重试，每次重试递增 `attempt` 并产生独立的日志与留痕；重试耗尽后实例进入终态 FAILED 并向上聚合工作流状态。`PREEMPTED` 回炉 MUST NOT 计入重试次数。

#### Scenario: 重试耗尽终态失败

- **WHEN** retry_max=2 的任务连续失败 3 次（首跑 + 2 次重试）
- **THEN** 实例终态 FAILED，attempt=3，工作流实例聚合为 FAILED

#### Scenario: 每次 attempt 日志独立

- **WHEN** 查看一个重试过 2 次的实例
- **THEN** 可分别查看 attempt 1/2/3 的完整日志
