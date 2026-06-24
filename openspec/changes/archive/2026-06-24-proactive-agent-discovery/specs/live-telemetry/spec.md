## ADDED Requirements

### Requirement: 节点指标真实采集

`HeartbeatReporter` SHALL 采集 worker 进程/主机的真实资源指标（CPU、系统 load、内存占用），替换当前硬编码常量，并随心跳上报写入 `worker_nodes`。

#### Scenario: 心跳带真指标

- **WHEN** worker 发送一次心跳
- **THEN** `worker_nodes` 中该节点的 cpu/mem/load 反映采集到的真实数值，而非固定常量

### Requirement: 并发任务数主端聚合

master 端 SHALL 按 `worker_node_code` 聚合 `task_instance` 中处于 DISPATCHED/RUNNING 的计数，得出真实 `concurrentTasks`，作为诊断证据来源，不再单纯信任 worker 上报值。

#### Scenario: 真实争抢计数

- **WHEN** 某节点同时承载多个运行中实例
- **THEN** 诊断证据中的 `concurrentTasks` 等于 master 端按该节点聚合的运行中实例数

### Requirement: 失败 history 真实统计

诊断证据中的 `history` SHALL 由近 7 天该 `task_id` × 该 `worker_node_code` 的 FAILED 实例计数真实得出，写入 Finding evidence，替换硬编码。

#### Scenario: 历史失败次数为真

- **WHEN** 为某失败实例生成证据
- **THEN** `history` 字段等于近 7 天同任务同节点的实际失败次数

### Requirement: 测试期故障注入脚本

系统 SHALL 提供可重跑的测试期脚本，用于在测试/demo 环境制造真实的失败素材（FAILED 实例 + 含 OOM 堆栈的日志 + 拉高目标节点指标），喂给主动发现链路。该脚本 SHALL NOT 作为运行时组件存在，且在生产 profile 下不加载，以免污染真实采集。

#### Scenario: 脚本造真实故障

- **WHEN** 测试期运行故障注入脚本
- **THEN** 数据库出现真实的 FAILED 实例与对应节点高负载指标，巡检器据此能产出真证据 Finding

#### Scenario: 生产不加载

- **WHEN** 以生产 profile 启动
- **THEN** 故障注入脚本/组件不参与运行，真实采集不被注入数据污染
