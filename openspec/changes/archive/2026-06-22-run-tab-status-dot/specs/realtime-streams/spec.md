## ADDED Requirements

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
