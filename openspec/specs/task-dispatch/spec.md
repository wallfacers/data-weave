# task-dispatch Specification

## Purpose
TBD - created by archiving change distributed-scheduler-m1. Update Purpose after archive.
## Requirements
### Requirement: 双部署模式

系统 SHALL 支持配置切换的两种部署模式：`all-in-one`（默认：单 JVM、H2、内存总线、本地文件归档、进程内直调，克隆即跑零外部依赖）与 `distributed`（master×N + worker×M 独立进程、PostgreSQL、Redis 总线、MinIO 归档、HTTP 下发）。两种模式 MUST 共用同一套调度内核代码，仅经 `EventBus`、`LogBus`、`LogArchiveStorage`、`WorkerNodeExecGateway` 四个接缝切换实现。distributed 模式下进程间内部调用 MUST 以共享 token 鉴权。

#### Scenario: all-in-one 克隆即跑

- **WHEN** 在无 Docker、无外部服务的环境以默认配置启动后端
- **THEN** 任务流下发、测试运行、实时日志全链路可用

#### Scenario: distributed 多进程

- **WHEN** 以 distributed 模式启动 2 个 master 与 2 个 worker
- **THEN** 任务被分派到独立 worker 进程执行，状态正确回流

### Requirement: 幂等下发

master 下发前 MUST 先以 CAS 将实例置为 DISPATCHED 并落库 `worker_node_code`、`lease_expire_at`、`attempt`，落库成功后才发起下发调用；下发调用失败 SHALL CAS 回 WAITING 重新调度。worker MUST 按 `(task_instance_id, attempt)` 对下发请求去重——重复收到同一键的请求 SHALL 幂等返回而不重复执行。

#### Scenario: 下发网络失败自动重派

- **WHEN** master 落库 DISPATCHED 后对 worker 的 HTTP 调用超时
- **THEN** 实例回到 WAITING 并被重新调度到可用节点，不丢失

#### Scenario: 重复下发去重

- **WHEN** worker 收到两次 `(instance_id=X, attempt=1)` 的下发请求
- **THEN** 任务仅执行一次，第二次请求幂等返回

### Requirement: 租约对账

运行中实例 SHALL 持有租约，由所属 worker 心跳携带的"运行中实例列表"续约。任一 master 的兜底轮询发现租约过期的 RUNNING/DISPATCHED 实例 SHALL 将其 CAS 置为 FAILED（`failure_reason=WORKER_LOST`），并按 `retry_max` 决定重派或终态。

#### Scenario: worker 失联任务回收

- **WHEN** 某 worker 网络中断超过租约时长
- **THEN** 其上运行中实例被标记 FAILED（WORKER_LOST）并按重试策略重派，状态可在前端看到

### Requirement: worker 重启宣告（incarnation epoch）

worker MUST 在每次进程启动时生成新的 incarnation 标识并随心跳上报。master 检测到某节点 incarnation 变化时，SHALL 将该节点上全部 RUNNING/DISPATCHED 实例 CAS 置为 FAILED（`failure_reason=WORKER_RESTART`），按重试策略处理。失败原因 MUST 持久化并对用户可见。

#### Scenario: worker 重启后任务失败可知

- **WHEN** 一个正在执行 3 个任务的 worker 进程重启
- **THEN** 3 个实例均被标记 FAILED 且 failure_reason=WORKER_RESTART，重试策略生效，用户可在实例详情看到失败原因

### Requirement: 优雅停机

worker 收到 SIGTERM 时 SHALL 停止接收新任务、等待运行中任务完成（可配 drain 超时）、上报最终状态后退出。drain 超时未完成的任务按 worker 重启语义处理。

#### Scenario: 计划内重启零失败

- **WHEN** 对一个运行中任务剩余 30 秒、drain 超时 60 秒的 worker 发送 SIGTERM
- **THEN** 任务正常完成并回报 SUCCESS 后进程退出，无失败实例

### Requirement: 执行双路径

worker SHALL 提供独立于 `ControlledCommandExecutor` 的 `TaskExecutor` 执行调度任务，不应用命令白名单（信任链为任务内容已经发布流程审查）。Agent `node_exec` 诊断命令 MUST 继续走白名单路径，两条路径不得混用。`TaskExecutor` SHALL 向任务执行环境注入 `biz_date` 与 `attempt`，作为任务侧实现幂等的钥匙。

#### Scenario: 调度任务执行任意已发布脚本

- **WHEN** 一个内容为白名单外命令的已发布 SHELL 任务被调度执行
- **THEN** TaskExecutor 正常执行该脚本，不被白名单拦截

#### Scenario: 幂等钥匙注入

- **WHEN** 实例第 2 次重试（attempt=2，biz_date=2026-06-10）执行
- **THEN** 任务进程环境中可读取到 biz_date 与 attempt 值

### Requirement: 例行触发与人为发起的闸门边界

cron 例行触发产生的任务执行 MUST NOT 经过 `PolicyEngine` 闸门（信任链挂在工作流发布审查上）。由人或 Agent 主动发起的运行（手动触发、rerun、测试运行、恢复运行、抢占终止）MUST 构造 `ActionRequest` 经 `GatedActionService` 分级裁决并留 `agent_action` 痕，无绕过路径。

#### Scenario: cron 触发不卡审批

- **WHEN** cron 到点触发包含 100 个节点的工作流
- **THEN** 全部任务直接进入调度，无审批单产生

#### Scenario: Agent 发起 rerun 留痕

- **WHEN** Agent 经 MCP 工具对失败实例发起 rerun
- **THEN** 产生 ActionRequest 经闸门裁决，agent_action 表有完整留痕

