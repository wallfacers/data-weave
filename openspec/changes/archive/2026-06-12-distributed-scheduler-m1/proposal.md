# Proposal: distributed-scheduler-m1

## Why

DataWeave 的数据模型（两级状态机、版本快照、`run_mode=TEST`、`worker_nodes` 心跳表）已为任务调度铺好路，但执行层是空的：调度器服务为 TODO，master→worker 是同 JVM 进程内方法调用，日志只有 `task_instance.log` 一个 4000 字符字段，前端实例状态一次性 fetch 不会动。没有真实的任务下发能力，平台的"用 Agent 编织数据"就只是查询玩具。本变更交付分布式调度内核——面向中大型企业、十万级实例/天开箱即用、百万级升级路径预埋。

## What Changes

- **分布式调度内核**：多 master 完全对等（无选主），PG `SKIP LOCKED` 认领 + 乐观 CAS 状态推进；事件驱动为主（提交/完成/槽位释放即触发，毫秒级下发）、5s 轮询兜底；资源不足停 WAITING 等事件唤醒。死锁防御为硬性不变量（永不等锁、固定锁序、短事务、发布时 DAG/跨流环检测、等待不占资源）。
- **优先级与软抢占**：workflow 级优先级 + aging 防饥饿；`preemptible` 任务可被高优任务抢占回炉（`PREEMPTED` 不耗 attempt）；`SchedulingPolicy` 接缝将"打分/选节点"与正确性内核分离，为后续 AI 智能调度预留接管点。
- **任务下发网络化**：双部署模式 `all-in-one`（默认，单 JVM + H2 + 内存总线，克隆即跑）/ `distributed`（master×N + worker×M 独立进程 + PG/Redis/MinIO）。`(instance_id, attempt)` 幂等下发、租约对账、worker incarnation epoch 重启宣告（失败可知：`failure_reason=WORKER_RESTART/LOST`）、SIGTERM 优雅 drain。
- **执行双路径**：调度任务走新 `TaskExecutor`（不设白名单，信任链=发布审查）；Agent `node_exec` 维持白名单老路。cron 例行触发不过闸门；人/Agent 发起的运行（TEST/手动/rerun）经 `GatedActionService`，TEST 默认 L1 留痕直执行、`policy_rules` 可收紧。
- **失败恢复**：断点恢复（成功节点保留终态跳过，从失败节点续跑整条流）+ 整流重跑（重置全部节点后恢复，同一套机制）。
- **单任务测试运行**：TEST 模式下发草稿内容（非已发布版本），每 worker 预留 TEST 槽（可配）防被例行任务饿死，不污染运维统计。
- **实时日志与状态流**：每 task_instance 一个 Redis Stream，任一 master SSE 转发，前端 EventSource 滚屏（同 AI token 流体验，断线带 offset 续传）；workflow 状态变化同范式推送。日志真相 worker 本地落盘，任务结束归档 MinIO，`task_instance.log` 只存尾部摘要。
- **四层指标体系**：调度性能（调度延迟 p50/p99/p999、空抢率、队列深度/最长等待者）、资源执行（槽位利用率、租约回收次数）、管道健康（日志端到端延迟、兜底命中比）、业务 SLA（biz_date 数据就绪基线）。指标即百万级升级的触发判据。
- **BREAKING**：核心表主键自增改 UUIDv7（时间有序，百万级分区/归档前提）；master/worker 进程间契约新增（worker exec 端点、心跳携带 epoch 与运行中实例）。
- docker-compose 新增 MinIO；Redis 从占位转为实际使用（事件唤醒 + 日志管道；Redis 全丢退化为轮询，零任务丢失）。

## Capabilities

### New Capabilities

- `scheduler-core`: 事件驱动调度内核——多 master 对等认领、优先级/aging/软抢占、WAITING 排队与唤醒、cron 触发幂等（唯一约束 + misfire 策略）、死锁防御不变量、`SchedulingPolicy` 接缝。
- `task-dispatch`: master→worker 任务下发与执行——双部署模式、幂等下发、租约对账、epoch 重启宣告、`TaskExecutor` 执行双路径、优雅停机、状态回报。
- `workflow-recovery`: 任务流失败恢复——断点恢复、整流重跑、节点级重试语义、平台注入 `biz_date + attempt` 幂等钥匙的责任边界。
- `task-test-run`: 单任务测试运行——草稿内容下发、闸门分级（默认 L1）、TEST 预留槽、与正式统计隔离。
- `realtime-streams`: 实时日志与状态流——Redis Stream 管道、SSE 端点、前端滚屏视图、断线续传、MinIO 归档与历史日志读取。
- `scheduler-metrics`: 调度可观测性——四层指标定义、采集与查询接口、升级触发判据。

### Modified Capabilities

- `worker-fleet`: 心跳上报新增 incarnation epoch 与运行中实例列表（租约续约）；离线判定/epoch 变化联动该节点任务的失败回收。

## Impact

- **backend/dataweave-master**：新增调度内核（认领循环、事件调度、policy、恢复服务）、Redis EventBus/LogBus 接缝、指标采集；schema 迁移（UUIDv7、priority/lease/epoch/failure_reason 等字段、cron 触发唯一约束）。
- **backend/dataweave-worker**：独立进程启动入口（distributed 模式）、exec HTTP 端点、`TaskExecutor` 真实执行、日志按行采集与本地落盘、归档上传、优雅停机。
- **backend/dataweave-api**：`WorkerNodeExecGateway` 按模式切换（进程内/WebClient）、SSE 端点（日志流/状态流）、指标查询接口；TEST/手动/rerun 接入 `GatedActionService`。
- **frontend**：实例日志滚屏视图（EventSource）、任务流状态实时刷新、指标看板；`useApi` 一次性 fetch 模式补充 SSE 订阅。
- **基础设施**：docker-compose 新增 MinIO；Redis 转实际使用；`dw logs cat` 后端实现接归档存储。
- **依赖**：新增 S3 客户端（MinIO SDK 或 AWS SDK S3）、Spring Data Redis（或 Lettuce 直连）。
