# worker-fleet Specification

## Purpose

定义 worker 机器集群的注册、心跳与资源指标上报、离线判定，以及集群机器观测视图与 Agent 机器状态查询。

## Requirements

### Requirement: Worker 机器注册

`dataweave-worker` SHALL 在启动时向 master 注册其机器节点（节点标识、主机名/地址、规格容量），注册信息 SHALL 持久化到 `worker_nodes` 表。重复注册同一节点 SHALL 为幂等更新而非新增。

#### Scenario: 启动注册

- **WHEN** 一个 worker 进程启动
- **THEN** 该节点出现在 `worker_nodes` 中，状态为「在线」

#### Scenario: 重注册幂等

- **WHEN** 同一节点标识再次注册
- **THEN** 更新既有记录而不产生重复行

### Requirement: 心跳与资源指标上报

worker 节点 SHALL 周期性上报心跳与资源指标（CPU 使用率、内存使用率、磁盘使用率、系统 load），写入 `worker_nodes`。超过心跳超时阈值未上报的节点 SHALL 被标记为「离线」。

#### Scenario: 周期上报刷新指标

- **WHEN** worker 上报一次心跳
- **THEN** 该节点的资源指标与最近心跳时间被更新

#### Scenario: 心跳超时判离线

- **WHEN** 某节点超过心跳超时阈值未上报
- **THEN** 系统将其状态标记为「离线」

### Requirement: 集群机器观测视图

前端「集群机器 / 资源监控」SHALL 展示各 worker 节点的在线状态与资源指标（CPU/内存/磁盘/load）。master SHALL 提供查询机器列表与单节点详情的接口；Agent SHALL 能经 AG-UI 以 CUSTOM 事件返回机器状态用于右舷展示。

#### Scenario: 列表呈现节点状态

- **WHEN** 用户进入「集群机器」页
- **THEN** 看到所有节点及其在线状态、CPU/内存/磁盘/load 指标

#### Scenario: Agent 返回机器状态结构化结果

- **WHEN** 用户在右舷问「机器现在压力大吗」
- **THEN** 后端经 AG-UI CUSTOM 事件返回机器状态结构化结果，右舷渲染为节点状态视图
