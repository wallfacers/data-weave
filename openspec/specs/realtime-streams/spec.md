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

