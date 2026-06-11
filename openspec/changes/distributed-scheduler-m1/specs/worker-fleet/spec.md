# worker-fleet Delta

## MODIFIED Requirements

### Requirement: 心跳与资源指标上报

worker 节点 SHALL 周期性上报心跳与资源指标（CPU 使用率、内存使用率、磁盘使用率、系统 load），写入 `worker_nodes`。心跳 MUST 同时携带本进程的 incarnation 标识（每次进程启动重新生成）与当前运行中的任务实例 id 列表（用于实例租约续约）。超过心跳超时阈值未上报的节点 SHALL 被标记为「离线」。

#### Scenario: 周期上报刷新指标

- **WHEN** worker 上报一次心跳
- **THEN** 该节点的资源指标与最近心跳时间被更新

#### Scenario: 心跳超时判离线

- **WHEN** 某节点超过心跳超时阈值未上报
- **THEN** 系统将其状态标记为「离线」

#### Scenario: 心跳续约运行中实例

- **WHEN** worker 心跳携带运行中实例 id 列表
- **THEN** 这些实例的租约到期时间被刷新

## ADDED Requirements

### Requirement: 节点失效联动任务回收

master 检测到节点失效信号时 SHALL 联动回收其上任务：节点 incarnation 变化（进程重启）或节点判离线/租约过期（失联），该节点上全部 RUNNING/DISPATCHED 实例 SHALL 被 CAS 置为 FAILED 并记录失败原因（`WORKER_RESTART` / `WORKER_LOST`），按各自重试策略处理。

#### Scenario: 节点离线任务不悬挂

- **WHEN** 某节点被判离线时仍有 2 个 RUNNING 实例
- **THEN** 两个实例被标记 FAILED（WORKER_LOST）并进入重试流程，不会永久停留在 RUNNING
