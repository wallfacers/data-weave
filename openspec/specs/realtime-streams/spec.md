# realtime-streams Specification

## Purpose
TBD - created by archiving change distributed-scheduler-m1. Update Purpose after archive.
## Requirements
### Requirement: 任务日志实时管道

worker 执行任务时 SHALL 按行/批将 stdout/stderr 写入日志总线（distributed 模式为每实例一个 Redis Stream，带 TTL/maxlen 上限；all-in-one 模式为内存总线），同时全量落盘 worker 本地文件。任一 master SHALL 能从日志总线读取并经 SSE 端点转发，无 master 粘性要求。

#### Scenario: 日志逐行实时可见

- **WHEN** 一个运行中任务持续输出日志
- **THEN** 前端在亚秒级延迟内逐行收到新日志

#### Scenario: 任一 master 可服务日志流

- **WHEN** 任务由 master-1 调度，浏览器的 SSE 连接落在 master-2
- **THEN** 日志流正常推送，内容一致

### Requirement: 前端日志滚屏与断线续传

前端 SHALL 提供任务实例实时日志视图：经 EventSource 订阅日志 SSE，逐行追加滚屏展示（体验同 AI 输出 token 流）。SSE 连接 MUST 支持 Last-Event-ID（Stream offset）断线续传——刷新或重连后从断点继续，不丢失已产生的行。

#### Scenario: 滚屏追加

- **WHEN** 用户打开运行中实例的日志视图
- **THEN** 日志逐行流入并自动滚动，无需手动刷新

#### Scenario: 刷新不丢行

- **WHEN** 用户在任务运行中途刷新页面重新连接
- **THEN** 已输出的日志完整回放，并继续接收新行

### Requirement: 工作流状态实时流

workflow_instance 与其下 task_instance 的每次状态变化 SHALL 发布到事件流，前端经 SSE 订阅后实时刷新任务流视图（节点状态逐个变化），范式与日志流一致。

#### Scenario: DAG 节点状态实时变化

- **WHEN** 一条流的某节点从 RUNNING 变为 SUCCESS、下游节点开始 RUNNING
- **THEN** 前端任务流视图在亚秒级内反映两个节点的状态变化，无需轮询

### Requirement: 日志归档与历史读取

任务实例结束（含每次 attempt 结束）时，worker SHALL 将全量日志经 `LogArchiveStorage` 归档（distributed 模式为 MinIO/S3，键为 `logs/{biz_date}/{instance_id}/{attempt}.log`；all-in-one 模式为本地目录），并将尾部摘要回写 `task_instance.log`。历史日志查询（前端与 `dw logs cat`）SHALL 从归档读取，与日志所在 worker 节点解耦。

#### Scenario: 已结束实例读归档

- **WHEN** 用户查看一个昨天结束的实例日志
- **THEN** 完整日志从归档存储返回，即使原执行节点已下线

#### Scenario: 实时段过期不影响历史

- **WHEN** Redis Stream 中该实例的实时日志已过 TTL 被清理
- **THEN** 历史日志仍可从归档完整读取

### Requirement: 日志流按终态关闭并携带实例终态

任务实例进入终态（SUCCESS/FAILED/STOPPED）时，`/api/ops/instances/{id}/logs/stream` SHALL 关闭流，并在关闭前 emit 的 `end` 事件中携带实例终态结果。日志流的 **live 路径**（连接时实例仍在运行）SHALL 周期性查询实例 `state`；检出终态时 SHALL 先排空日志总线尾部日志（避免丢尾），再 emit 一个 `data` 为 `{"state": "<InstanceStates 终态值>"}` 的 `end` 事件并 complete 流。「连接时实例已终态」的快照路径 SHALL 在回放归档日志后 emit 同样携带 `state` 的 `end` 事件。`end` 事件的 `event` 名 MUST 保持为 `end` 不变（仅 `data` 由空字符串丰化为 JSON state 对象），以保持对仅按 `event === "end"` 判定结束的客户端的非破坏性。前端解析 `end.data` 时 MUST 容错处理空或非 JSON 的旧负载。

#### Scenario: 实时观看运行至终态关闭并携带结果

- **WHEN** 用户在实例仍处于 RUNNING 时连接日志流，任务随后进入 SUCCESS 终态
- **THEN** 流在检出终态后先排空日志总线尾部，emit 一个 `event: "end"` 且 `data` 为 `{"state":"SUCCESS"}` 的事件，随后关闭流、停止轮询

#### Scenario: 连接时已终态携带结果

- **WHEN** 用户连接一个已处于 FAILED 终态的实例日志流
- **THEN** 流回放归档日志后 emit 一个 `event: "end"` 且 `data` 为 `{"state":"FAILED"}` 的事件

#### Scenario: 失败与已终止终态分别携带

- **WHEN** 任务终态为 FAILED 或 STOPPED
- **THEN** `end.data.state` 分别为 `"FAILED"` 或 `"STOPPED"`

#### Scenario: 仅按事件名判定结束的客户端不受影响

- **WHEN** 一个客户端仅以 `event === "end"` 判定流结束、不解析 `data`
- **THEN** 在 `end.data` 由空丰化为 JSON state 后，其结束判定与日志回放行为保持不变（非破坏性契约增强）

#### Scenario: 运行中不关闭流

- **WHEN** 实例持续处于 RUNNING 且不断输出日志
- **THEN** 日志流持续推送 `log` 事件、不 emit `end`、不关闭，直到实例进入终态

