# scheduler-core Specification

## Purpose
TBD - created by archiving change distributed-scheduler-m1. Update Purpose after archive.
## Requirements
### Requirement: 多 master 对等认领

调度器 SHALL 支持多个 master 实例同时运行且完全对等（无 leader 选举）。待调度实例的认领 MUST 使用 `SELECT … FOR UPDATE SKIP LOCKED`，同一实例 SHALL 仅被一个 master 认领成功。PostgreSQL 是调度状态的唯一真相源；Redis 不可用时调度 SHALL 退化为轮询驱动而不丢失任何任务。

#### Scenario: 双 master 竞争认领

- **WHEN** 两个 master 同时对同一批 WAITING 实例发起认领
- **THEN** 每个实例恰好被一个 master 认领，无重复下发、无锁等待

#### Scenario: Redis 完全不可用

- **WHEN** Redis 宕机期间提交新的工作流实例
- **THEN** 实例在兜底轮询周期内被正常调度执行，无任务丢失

### Requirement: 事件驱动调度与轮询兜底

调度器 SHALL 以事件驱动为主：实例提交、任务完成（释放槽位并解锁 DAG 下游）、worker 心跳恢复、重试定时器到点 MUST 立即触发一轮调度；跨 master 事件经事件总线广播唤醒。同时 SHALL 以可配置间隔（默认 5 秒）全量轮询 WAITING/DISPATCHED 实例作为兜底。资源充足时，实例从可运行到下发至 worker SHALL 在毫秒级完成；资源不足时实例 SHALL 停留在 WAITING 状态等待唤醒，不占用任何槽位。

#### Scenario: 资源充足毫秒级下发

- **WHEN** 提交一个实例且存在空闲槽位的在线 worker
- **THEN** 实例在一次 DB 事务加一次下发调用内到达 worker，无轮询等待

#### Scenario: 资源不足排队等待

- **WHEN** 所有 worker 槽位占满时提交实例
- **THEN** 实例停留在 WAITING；当任一任务完成释放槽位时，该实例被事件立即唤醒调度

### Requirement: 死锁防御不变量

调度器实现 MUST 遵守以下不变量：认领只用 `SKIP LOCKED` 且永不等待行锁；状态推进一律使用乐观 CAS（`UPDATE … WHERE id=? AND state=?`），影响行数为 0 时让步；跨两级状态更新固定先 task 后 workflow 的顺序；事务内只做状态落库，HTTP 下发等副作用 MUST 在事务提交之后执行。任务层面：工作流发布时 MUST 做 DAG 拓扑环检测（有环拒绝发布）；创建跨流依赖时 MUST 做全局依赖环检测；依赖等待 MUST 不占用执行槽位。

#### Scenario: 有环 DAG 拒绝发布

- **WHEN** 用户发布一个包含 A→B→C→A 环路的工作流
- **THEN** 发布被拒绝并提示环路所在节点

#### Scenario: 跨流依赖成环被拦截

- **WHEN** 创建一条会使全局工作流依赖图成环的 `workflow_dependency`
- **THEN** 创建被拒绝并提示环路路径

#### Scenario: CAS 竞态让步

- **WHEN** 两个 master 同时尝试推进同一实例的状态
- **THEN** 仅一方 CAS 成功，另一方让步且不产生锁等待或重复副作用

### Requirement: cron 触发幂等与 misfire 策略

每个 master SHALL 独立扫描 cron 到期的工作流；触发前 MUST 先向护栏表 `cron_fire(workflow_id, scheduled_fire_time)`（复合唯一键）插入记录，插入冲突即放弃本次触发。所有 master 同时停机错过触发点后，恢复时 SHALL 按可配置 misfire 策略处理：`fire_once`（默认，补触发一次）或 `skip`。

#### Scenario: 多 master 同时扫到同一 cron 触发点

- **WHEN** 三个 master 同时检测到同一工作流的同一触发点
- **THEN** 恰好产生一个 workflow_instance

#### Scenario: 停机恢复补触发

- **WHEN** 全部 master 停机 10 分钟错过一个触发点后恢复，misfire 策略为 `fire_once`
- **THEN** 该触发点被补触发一次且仅一次

### Requirement: 优先级、aging 与调度策略接缝

工作流定义 SHALL 携带优先级（实例继承、可在触发时覆盖）。调度排序 SHALL 由 `SchedulingPolicy` 接口决定：默认实现为有效优先级 = 声明优先级 + 等待时长 aging（防饥饿），节点选择为 least-loaded。调度内核（状态机推进、幂等下发、租约）MUST 与策略实现分离——替换策略实现 MUST NOT 影响正确性保证。调度 SHALL 遵循 work-conserving：存在空槽与可运行任务时必须派发，不为未来任务空等（TEST 预留槽除外）。

#### Scenario: 高优先级先行

- **WHEN** 队列中同时存在优先级 8 和优先级 3 的可运行实例且仅剩一个槽位
- **THEN** 优先级 8 的实例先被下发

#### Scenario: aging 防饥饿

- **WHEN** 一个低优先级实例等待时长持续增长
- **THEN** 其有效优先级随等待时间上浮，最终获得调度而不被无限饿死

### Requirement: 软抢占

实例 SHALL 支持 `preemptible` 标记（继承自工作流定义，默认 false）。当高优先级实例无槽可用且存在运行中的 `preemptible` 实例时，调度器 SHALL 终止该实例并将其置为 `PREEMPTED` 状态、回 WAITING 重排，且 MUST NOT 消耗其 attempt 次数。非 `preemptible` 的运行中任务 MUST NOT 被强制终止。抢占终止与任务自然完成的竞态 SHALL 由 CAS 裁决（先到先得）。

#### Scenario: 高优任务抢占补数任务

- **WHEN** 高优先级实例到达且唯一槽位被 `preemptible` 实例占用
- **THEN** 该实例被终止并置 PREEMPTED 回队（attempt 不变），高优实例获得槽位

#### Scenario: 抢占与完成竞态

- **WHEN** 抢占指令到达 worker 时任务恰好已回报成功
- **THEN** 实例终态为 SUCCESS，抢占方 CAS 失败后放弃，不产生状态覆盖

### Requirement: 虚拟节点零负载执行

工作流节点 SHALL 携带 `node_type`（`TASK`/`VIRTUAL`，默认 `TASK`）。触发物化（`WorkflowTriggerService`）时，`node_type=VIRTUAL` 的节点 MUST 直接生成 `state=SUCCESS` 的 `task_instance`（`started_at=finished_at` 为物化时刻，不设 `task_id`），不进入 `WAITING`、不下发给 worker、不占用任何调度槽。虚拟节点 MUST 作为 DAG 拓扑的起始/汇聚锚点正常参与下游 readiness 解锁（下游的 `pred.state<>'SUCCESS'` 检查对虚拟节点自然放行）。虚拟节点 SHALL 计入 `workflow_instance` 的 `total_tasks` 与 `completed_tasks`。

#### Scenario: 虚拟节点物化即成功
- **WHEN** 工作流触发，某节点 `node_type=VIRTUAL`
- **THEN** 系统为其创建 `state=SUCCESS` 的 task_instance，不下发给任何 worker、不占槽

#### Scenario: 虚拟起始节点解锁下游
- **WHEN** 一个 `VIRTUAL` 起始节点指向多个 `TASK` 下游节点
- **THEN** 物化后虚拟节点即为 SUCCESS，其全部下游 TASK 节点的入边前驱判定通过，进入可运行状态

#### Scenario: 实例计数自洽
- **WHEN** 工作流含 N 个节点（其中 M 个虚拟）全部成功
- **THEN** `total_tasks=N`、`completed_tasks=N`，计数包含虚拟节点

