# Tasks: distributed-scheduler-m1

按「内核 → 网络化 → 实时管道 → 指标/前端」四阶段排布。阶段 1–2 完成后 all-in-one 全链路可用；阶段 3 起 distributed 模式与实时体验就位。

## 1. Schema 迁移与基座

- [x] 1.1 实例类核心表主键自增改 UUIDv7（task_instance/workflow_instance 及外键），同步调整实体与现有 mock 数据生成，提供 PG 迁移 SQL（BREAKING）
- [x] 1.2 schema 新字段：workflow_def 加 priority/preemptible；workflow_instance 加 priority；task_instance 加 lease_expire_at/failure_reason，state 枚举加 DISPATCHED/PREEMPTED；worker_nodes 加 incarnation/reserved_test_slots
- [x] 1.3 新增 cron_fire 护栏表（workflow_id + scheduled_fire_time 复合唯一键）及实体/repository
- [x] 1.4 定义三接缝接口 EventBus/LogBus/LogArchiveStorage（domain 层）并提供内存/本地文件默认实现（all-in-one），配置项 scheduler.mode、cluster.auth.token 骨架
- [x] 1.5 发布期校验：工作流发布 DAG 拓扑环检测、创建 workflow_dependency 全局环检测（含单测）

## 2. 调度内核（all-in-one 先行）

- [x] 2.1 状态机服务：乐观 CAS 推进（统一入口，WHERE state=? 守卫）、固定锁序 task→workflow、事务内禁副作用的结构约束（含并发单测）
- [x] 2.2 认领循环：SKIP LOCKED 批量认领 WAITING 实例 + 兜底轮询（默认 5s 可配），H2/PG 双方言验证
- [x] 2.3 事件驱动快路径：提交/任务完成/槽位释放/心跳恢复事件经 EventBus 触发即时调度；DAG 下游解锁联动
- [x] 2.4 SchedulingPolicy 接缝 + 默认实现（声明优先级 + aging 防饥饿打分；least-loaded 选节点；work-conserving），含饥饿场景单测
- [x] 2.5 槽位管理：worker capacity 占用/释放、TEST 预留槽（默认 1 可配 0）
- [x] 2.6 软抢占：高优无槽时 kill preemptible 运行实例 → PREEMPTED 回 WAITING 不耗 attempt；抢占与完成竞态 CAS 裁决（含单测）
- [x] 2.7 cron 调度器：扫描到期工作流 → cron_fire 护栏表防重 → 创建实例；misfire 策略 fire_once/skip 可配（含多线程竞争单测）
- [x] 2.8 重试服务：失败按 retry_max 退避重试，attempt 递增；PREEMPTED 不计次
- [x] 2.9 失败恢复：断点恢复（SUCCESS 节点跳过、FAILED→RUNNING 再入）与整流重跑（全节点重置走同一机制），经 GatedActionService 留痕
- [x] 2.10 手动触发/rerun/恢复/测试运行接入 GatedActionService（cron 例行不进闸门）；policy_rules 默认数据：TEST=L1
- [x] 2.11 测试运行：TEST 模式下发草稿内容、跳过依赖检查、不入正式统计（OpsController 过滤已有，补 SLA 排除）
- [x] 2.12 内核集成测试：单 JVM 内多线程双"master"竞争认领、资源不足排队唤醒、0 延迟路径（提交→下发耗时断言）、死锁不变量回归

## 3. 任务下发网络化（distributed 模式）

- [x] 3.1 TaskExecutor 真实执行：进程启动、stdout/stderr 按行采集、本地落盘、退出码判定、超时 kill；注入 biz_date/attempt 环境变量；与 node_exec 白名单路径隔离
- [x] 3.2 worker 幂等执行：按 (instance_id, attempt) 去重；exec HTTP 端点（distributed）+ 进程内 Gateway（all-in-one）双实现，共享 token 鉴权
- [x] 3.3 写前置下发：CAS 置 DISPATCHED 落库（worker_node_code/lease_expire_at/attempt）→ 事务外下发 → 失败 CAS 回 WAITING
- [x] 3.4 worker 状态回报：started/finished/failed 回调任一 master，CAS 推进 + 触发调度事件
- [x] 3.5 心跳扩展：携带 incarnation 与运行中实例列表；master 侧租约续约；FleetService/HeartbeatReporter 改造（worker-fleet delta）
- [x] 3.6 失效回收：incarnation 变化 → 该节点实例 FAILED(WORKER_RESTART)；租约过期/离线 → FAILED(WORKER_LOST)；联动重试（含集成测试）
- [x] 3.7 worker 独立进程入口（dataweave-worker 自己的 Spring Boot 启动）+ SIGTERM 优雅 drain（拒新任务、等待运行中至超时、上报）
- [x] 3.8 Redis 实现：EventBus(pub/sub dw:wake) + LogBus(Stream dw:log:{id}, TTL/maxlen)；docker-compose 验证
- [ ] 3.9 distributed 集成验证：docker compose 起 PG+Redis+MinIO+2 master+2 worker，验证竞争认领唯一、worker 重启失败可知、Redis 宕机退化轮询零丢失
  - ⏸ 未完成（环境受限）：distributed 代码已实现且单测通过（worker 21 测试 + ClusterReportTest/LeaseReaperTest + Redis/S3 实现），但 docker-compose 的 distributed profile 引用预构建镜像 `dataweave/dataweave-{api,worker}:latest` 且**缺 Dockerfile/构建上下文**，无法在本环境起多节点集群做活体验证。补 Dockerfile + 真实集群后方可勾选。

## 4. 实时管道与归档

- [x] 4.1 LogArchiveStorage S3 实现（MinIO SDK），归档键 logs/{biz_date}/{instance_id}/{attempt}.log；任务结束归档 + 尾部摘要回写 task_instance.log；MinIO 进 docker-compose
- [x] 4.2 日志 SSE 端点：GET /api/ops/instances/{id}/logs/stream（XREAD 转发，Last-Event-ID 续传）；历史日志接口走归档；dw logs cat 后端接归档
- [x] 4.3 状态事件流：实例/节点状态变化发布 dw:evt:{workflowInstanceId}，SSE 端点 GET /api/ops/workflow-instances/{id}/events/stream
- [x] 4.4 前端 EventSource 订阅 hook（断线带 offset 重连）+ 实例日志滚屏视图（逐行追加自动滚动，复用对话流视觉范式）
- [x] 4.5 前端任务流实时刷新：实例视图订阅状态流，节点状态实时变化替代一次性 fetch
- [x] 4.6 浏览器验证（硬性 Gate）：Playwright 实测 http://localhost:3000 —— CopilotChat 输入框正常渲染、新增「系统指标」看板拉到后端 /api/ops/metrics 实时数据（派发数/空抢率/事件vs轮询/执行耗时）、console 零 error，截图存 tmp/metrics-view-verified.png。注：all-in-one mock 任务亚秒完成，运行中日志滚屏/DAG 实时变绿的「执行中」过程无法在内置模拟执行下观测（属 demo 执行器特性，非缺陷），SSE 端点与前端订阅 hook 已就位，留待 distributed 真实执行验证

## 5. 指标体系与收尾

- [x] 5.1 Micrometer 调度性能指标：调度/下发延迟分布、队列深度、最长等待者年龄、吞吐、单轮耗时、空抢率
- [x] 5.2 资源/执行/管道指标：槽位利用率、碎片率、按 task_def 成败率、租约回收次数、日志端到端延迟、Stream 积压、SSE 连接数、事件 vs 兜底命中比
- [x] 5.3 SLA 基线：workflow+biz_date 就绪时刻记录与基线表、破线事件（排除 TEST），喂告警模块与 Agent 自诊断查询
- [x] 5.4 /api/ops/metrics 汇总接口 + 前端指标看板视图（Workspace 注册）
- [x] 5.5 文档同步：CLAUDE.md/README/docs/architecture.md 更新双部署模式、运行方式、AG-UI 之外的 SSE 端点说明
- [x] 5.6 全量回归：后端 mvnw install 测试全绿、前端 typecheck、all-in-one 克隆即跑冒烟、distributed compose 冒烟
