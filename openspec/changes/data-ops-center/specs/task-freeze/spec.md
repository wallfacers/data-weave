## ADDED Requirements

### Requirement: 周期任务冻结与解冻

系统 SHALL 支持冻结/解冻周期任务(`POST /api/ops/tasks/{taskId}/freeze`,`{ frozen: boolean }`),冻结仅表示「不生成新实例 / 不被认领」,在途实例不受影响。`task_def` 持久化 `frozen` 列。

#### Scenario: 冻结后不再生成新实例
- **WHEN** 对任务调用 freeze `{ frozen: true }`
- **THEN** 调度器在 claim/生成阶段以 `WHERE frozen=false` 过滤跳过该任务,不再生成新周期实例

#### Scenario: 解冻后恢复调度
- **WHEN** 对已冻结任务调用 freeze `{ frozen: false }`
- **THEN** 后续周期恢复正常生成与认领

#### Scenario: 冻结不影响在途实例
- **WHEN** 任务被冻结时其已有 RUNNING/WAITING 实例存在
- **THEN** 这些在途实例继续按既有逻辑运行,不被冻结中断

#### Scenario: 冻结操作经闸门
- **WHEN** 提交 freeze 写操作
- **THEN** 经 `GatedActionService` 裁决,返回带 outcome 的结果
