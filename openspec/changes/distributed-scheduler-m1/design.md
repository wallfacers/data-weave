# Design: distributed-scheduler-m1

## Context

数据模型已就位（`workflow_def/instance` + `task_def/instance` 两级状态机、版本快照、`run_mode=TEST`、`worker_nodes` 心跳表），但执行层为空：调度器是 TODO，master→worker 是同 JVM 进程内调用（`WorkerNodeExecGateway` 注释已预告网络化方向），日志仅 `task_instance.log` 4000 字符字段，Redis 在 docker-compose 占位未用，前端实例状态一次性 fetch。

约束：开发态零依赖铁律（H2、克隆即跑）；DDD 四层依赖方向；副作用必经 `PolicyEngine` 闸门、无绕过路径；指标口径表已有的版本化惯例；目标中大型企业，十万级实例/天开箱即用、百万级丝滑升级。

本设计经 brainstorming 压力测试，四个关键决策（失败恢复范围、TEST 闸门策略、部署形态、变更切分）已与用户确认。

## Goals / Non-Goals

**Goals:**

- 任务流下发 + 单任务测试运行下发，资源充足毫秒级（"0 延迟"），不足则排队等待事件唤醒
- 多 master 多 worker 分布式，任务状态零丢失（master 崩溃、worker 重启、网络抖动均可恢复且失败可知）
- 任务与数据库两级死锁的系统性防御（不变量，非补丁）
- 优先级 + aging 防饥饿 + 软抢占，最大化资源利用（work-conserving）；调度决策与正确性内核分离，为 AI 智能调度预留接管点
- 实时日志/状态 SSE 流（前端滚屏体验同 AI token 流），日志归档对象存储
- 四层可观测指标体系，兼作百万级升级的触发判据
- 断点恢复 + 整流重跑

**Non-Goals:**

- 硬抢占（kill 任意运行中任务）—— 仅软抢占 `preemptible` 任务
- 资源向量装箱（CPU/内存维度调度）—— v1 按任务槽数，留给 AI policy 阶段
- backfill 补数功能本身（仅预留 `preemptible` 语义）
- 多租户配额/公平性调度
- 日志全文检索（Loki/ES）—— 归档只支持按实例读取
- AI 调度策略实现 —— 本变更只交付 `SchedulingPolicy` 接缝

## Decisions

### D1. 多 master 完全对等，PG 为唯一真相源（vs leader 选举 / Redis 队列）

所有 master 无差别，靠 `SELECT … FOR UPDATE SKIP LOCKED` 从 DB 认领待调度实例。不引入 ZK/etcd（与零依赖铁律冲突、运维重）；不让 Redis 当真相源（与"状态不能丢"矛盾）。**分工纲领：DB 保正确性，Redis 保实时性** —— Redis 全部丢失时系统退化为秒级轮询，正确性无损。PG 与 H2 2.x 均支持 `SKIP LOCKED`。

### D2. "0 延迟" = 事件驱动快路径 + 轮询兜底

- **快路径（毫秒级）**：实例提交、任务完成（释放槽位 + 解锁 DAG 下游）、worker 心跳恢复、重试定时器到点 —— 即刻触发一轮调度。本机事件进程内直达；跨 master 经 Redis pub/sub（频道 `dw:wake`）广播唤醒，醒来后照常 `SKIP LOCKED` 抢，抢不到说明别人干了。
- **兜底（默认 5s，可配）**：全量扫 WAITING/DISPATCHED，捞事件丢失与 master 宕机留下的漏网之鱼。事件全丢时最坏延迟 = 轮询间隔，零任务丢失。
- 等待队列 = DB 里的 WAITING 状态行，不引入额外队列组件。

### D3. 死锁防御不变量（硬性，代码评审红线）

DB 级三纪律 —— 全部是"不等锁就不会死锁"：

1. **认领只用 `SKIP LOCKED`**，永不等待行锁；
2. **状态推进全用乐观 CAS**：`UPDATE … SET state=? WHERE id=? AND state=?`，影响行数 0 即让步，无先读后写锁窗口；
3. **锁序固定 + 短事务**：跨两级状态固定先 task 后 workflow；事务内只做状态落库，HTTP 下发等副作用一律在事务提交后。心跳 upsert 单行单键，不与调度事务交叉。

任务级三防御：发布时 DAG 拓扑环检测（有环拒绝发布）；创建跨流依赖时全局环检测；**"等待不占资源"** —— 依赖判定发生在 WAITING 阶段不占槽，任务仅在真正可运行时被派发占槽，"持有并等待"条件不成立。

### D4. cron 触发防重：护栏表唯一约束（vs 选主 / 分布式锁）

每个 master 都扫 cron、都尝试触发；触发前先 INSERT 护栏表 `cron_fire(workflow_id, scheduled_fire_time)`（UNIQUE 复合键），撞键即忽略 —— 谁插入成功这次触发归谁，零协调。不在 `workflow_instance` 上加唯一约束（会误伤同 biz_date 的多次手动触发），且护栏表方案 PG/H2 同构（不依赖部分索引）。misfire 策略可配：`fire_once`（默认，恢复后补一次）/ `skip`。

### D5. `SchedulingPolicy` 接缝：分数与正确性分离（AI 接管点）

```
待调度实例 ──▶ SchedulingPolicy.score(instance)  → 有效优先级
候选节点   ──▶ SchedulingPolicy.place(inst, nodes) → 目标 worker
                       │ 只产分数和选择，不碰状态
              调度内核（CAS 状态机 / 幂等下发 / 租约）← 不可替换
```

v1 默认实现：有效优先级 = workflow 级声明优先级 + 等待时长 aging（防饥饿）；节点选择 = least-loaded。未来 AI 经 MCP 调优先级或提供 policy hint —— **AI 只能影响分数，影响不了正确性**，算错最坏是调度不优，不会丢任务/死锁/重复执行（与 PolicyEngine 闸门思想同构）。

优先级粒度：v1 仅 workflow 级（实例继承可覆盖），节点级留给 AI 阶段。TEST 运行天然高优先。

### D6. 软抢占语义

- `preemptible` 标记在 workflow 定义（默认 false），实例继承可覆盖；典型用途为补数类可重跑任务。
- 高优任务无槽时可 kill `preemptible` 运行中任务：实例置 `PREEMPTED` → 回 WAITING 重排，**不消耗 attempt**（否则被抢三次即终态失败，不合理）。
- 不做通用硬抢占：shell 任务腰斩的副作用清理是无底洞，收益配不上复杂度。
- work-conserving：有空槽且有可运行任务就派，绝不为未来的高优任务空等；唯一例外是每 worker 预留 1 个 TEST 槽（可配 0 关闭）。

### D7. 状态不丢三层防线 + epoch 重启宣告

1. **写前置**：先落 `state=DISPATCHED, worker_node_code, lease_expire_at, attempt` 再发 HTTP，调用失败 CAS 回 WAITING 重派；
2. **幂等下发**：worker 按 `(task_instance_id, attempt)` 去重，master 重试/双 master 竞态不会重复执行；
3. **租约对账**：心跳携带"运行中实例 ids"续租；任一 master 兜底轮询发现租约过期 → CAS 置 FAILED（`failure_reason=WORKER_LOST`）按 `retry_max` 重派。

worker 重启：每次启动生成 incarnation（启动纪元号），心跳携带；master 发现某节点 incarnation 变化 → 该节点全部 RUNNING/DISPATCHED 实例 CAS 置 FAILED（`failure_reason=WORKER_RESTART`）。认知前提：shell 子进程随 worker 死，重启后无物可恢复，唯一正确动作是快速确定地宣告死亡 + 按策略重试。配套：SIGTERM 优雅停机（拒新任务 → drain 运行中任务至超时 → 上报）；平台向任务执行环境注入 `biz_date + attempt` 作为任务侧幂等钥匙（责任边界：平台给钥匙，任务自己保证重跑不重复写数）。

### D8. 状态机扩展

`task_instance`：`NOT_RUN →(上游就绪) WAITING →(认领) DISPATCHED →(worker 回报) RUNNING → SUCCESS | FAILED | STOPPED`；新增 `PREEMPTED →(回炉) WAITING`。`workflow_instance` 聚合规则沿用既有两级状态矩阵，新增 **FAILED → RUNNING 再入**（恢复运行）。

断点恢复：成功节点保留 SUCCESS 终态跳过，失败/未跑节点重新入队，从失败点续跑整条流；整流重跑 = 重置全部节点状态后走同一恢复机制（同一套实现）。

### D9. 执行双路径与闸门策略

- **调度任务**：新 `TaskExecutor`（worker 侧），不设命令白名单 —— 信任链 = 任务内容经过发布流程审查。`ControlledCommandExecutor` 白名单维持原状，只服务 Agent `node_exec` 诊断命令。
- **闸门**：cron 例行触发不过 `PolicyEngine`（信任链挂在发布审查上，否则数万定时任务卡审批 = 调度死亡）；人/Agent 发起的运行（TEST/手动触发/rerun/恢复/抢占 kill）构造 `ActionRequest` 经 `GatedActionService` —— TEST 默认 L1（留痕直执行），企业可经 `policy_rules` 抬到 L2 审批。TEST 是全系统唯一"未发布内容上 worker"的口子，必须留 `agent_action` 痕。

### D10. 双部署模式（配置切换，同一套代码)

| | `all-in-one`（默认） | `distributed`（生产） |
|---|---|---|
| 进程 | 单 JVM（master+worker 同进程） | master×N + worker×M 独立进程 |
| 存储 | H2 | PostgreSQL |
| 总线 | 内存（`Sinks.Many`） | Redis pub/sub + Stream |
| 归档 | 本地文件目录 | MinIO（S3 API） |
| 下发 | 进程内直调（现有 Gateway 接缝） | WebClient → worker exec 端点 |
| master 数 | 1 | N（对等） |

三个接缝接口配置驱动：`EventBus`（memory|redis）、`LogBus`（memory|redis）、`LogArchiveStorage`（file|s3）。`WorkerNodeExecGateway` 按 `scheduler.mode` 切实现。进程间内部调用（exec 下发、状态回报）以共享 token 鉴权（同 `mcp.auth.token` 惯例，配置 `cluster.auth.token`）。

### D11. 实时日志与状态流

```
worker 执行                     任一 master                      前端
stdout/stderr 按行/批 ─XADD─▶ Redis Stream ─XREAD─▶ SSE 端点 ─▶ EventSource 滚屏
        │                    (每实例一个 key,            (Last-Event-ID 断线续传)
        │ 同时本地落盘全量      dw:log:{instanceId},
        │ 任务结束             TTL/maxlen 防爆)
        ▼
LogArchiveStorage 归档: logs/{biz_date}/{instance_id}/{attempt}.log
task_instance.log 只存尾部摘要（失败快速定位）
```

- 每 task_instance 一个 Stream：天然多消费者（多浏览器同看）、offset 续传（刷新不丢行）、任一 master 可服务 SSE —— 适配多 master 无粘性要求。
- workflow 状态流同范式：`dw:evt:{workflowInstanceId}` 事件流 → SSE，前端看 DAG 节点逐个变绿。
- 日志真相在 worker 本地文件，Stream 只是实时管道；历史日志/`dw logs cat` 走归档读取。
- 前端在 `useApi` 一次性 fetch 之外补充 EventSource 订阅 hook，滚屏组件复用对话流的视觉范式。

### D12. UUIDv7 主键迁移（BREAKING，百万级前提）

实例类核心表主键自增改 UUIDv7（时间有序、跨库友好、归档不撞键）。这是百万级升级"第一刀"（分区/归档）的前提，现在不做以后伤筋动骨。开发态 H2 重建即可；schema.sql 直改 + 既有 mock 数据生成同步调整。

### D13. 百万级升级路径（预埋不实现）

先算术祛魅：100 万实例/天 ≈ 平均 11.6/s、峰值百级 —— `SKIP LOCKED` 单表扛几千 TPS，调度吞吐不是瓶颈；先爆的是 `task_instance` 表膨胀（3.65 亿行/年）。四刀依序、每刀独立：① PG 按 biz_date 分区 + 冷数据归档 MinIO（纯 DBA 操作，代码零改动 —— 依赖 D12 预埋）；② master 按 workflow_id hash 分片认领（配置开关，消灭空抢）；③ 心跳/槽位上收 Redis 异步刷 PG；④ 日志管道 Redis Stream → Kafka。**空抢率、队列深度、兜底命中比等指标即"何时动第几刀"的判据。**

### D14. 四层指标体系（Micrometer + SLA 基线表）

| 层 | 关键指标 | 回答的问题 |
|---|---|---|
| 调度性能 | 调度延迟 p50/p99/p999（可运行→DISPATCHED）、下发延迟（DISPATCHED→启动）、队列深度、**最长等待者年龄**（饥饿检测）、dispatch/s、一轮调度耗时、DAG 聚合耗时、**SKIP LOCKED 空抢率** | "0 延迟"是否兑现 |
| 资源执行 | 槽位利用率（全局/节点）、资源碎片率、成功/重试/超时率（按 task_def 维度）、心跳延迟、**租约过期回收次数** | 容量与健康 |
| 管道健康 | 日志端到端延迟（目标 <500ms = "滚屏像 token 流"的量化定义）、Stream 积压、SSE 连接数/重连率、**事件唤醒 vs 兜底命中比**（兜底占比升高 = 事件通道在漏，最佳预警） | 实时体验是否真实时 |
| 业务 SLA | 每 workflow 按 biz_date 的数据就绪时刻 vs 历史基线（破线预警，喂告警模块与 Agent 自诊断）、DAG 关键路径时长 | 数据中台特色，调度系统一般没有 |

技术实现：Micrometer 注册 + actuator 暴露；SLA 基线落 DB 表（完成时聚合写入）；`/api/ops/metrics` 提供前端看板查询。

## Risks / Trade-offs

- [Redis pub/sub 不保证送达] → 兜底轮询兜住正确性，最坏延迟 = 轮询间隔；"兜底命中比"指标监控事件通道健康。
- [双模式两套总线实现都要测] → 接缝接口窄（publish/subscribe/append/read），内存实现同时是单测替身；distributed 路径以 docker compose 集成测试覆盖。
- [TEST 跑未发布草稿是安全口子] → 默认 L1 留痕 + `policy_rules` 数据驱动可收紧到 L2；TEST 槽预留限制爆炸半径。
- [软抢占 kill 时机与状态回报竞态]（kill 到达时任务恰好完成）→ CAS 推进天然裁决：先到先得，PREEMPTED 与 SUCCESS 互斥转换，后到的 CAS 失败即放弃。
- [worker 本地日志随节点丢失]（归档前磁盘损毁）→ 接受：实时段 Stream 仍可见尾部，摘要已在 DB；全量丢失概率低、代价可承受。
- [UUIDv7 迁移触碰所有实例表外键] → 开发态 H2 重建零成本；尚无生产数据，此刻是最后的廉价窗口（这正是把它放进 v1 的理由）。
- [aging 参数失调可能导致优先级失效]（aging 过快 = 人人平等）→ 参数可配 + 最长等待者年龄指标可观测，policy 接缝便于后续调优。

## Migration Plan

1. schema 迁移（UUIDv7、新字段、`cron_fire` 护栏表）—— 开发态 H2 重建，PG 提供迁移 SQL；
2. all-in-one 模式先行交付（内核 + 双路径执行 + 内存总线 + 文件归档），CI/克隆即跑不破；
3. distributed 模式随后（worker 独立入口 + WebClient Gateway + Redis/MinIO），docker compose 起全套验证多 master 多 worker;
4. 前端 SSE 视图最后接（管道就位后）；
5. 回滚策略：`scheduler.mode=all-in-one` 即退回单进程；调度器整体可由配置开关禁用，退回现状（仅查询平台）。

## Open Questions

- TEST 槽预留数默认值（暂定每 worker 1 槽）是否需要按 worker 规格分档 —— 实现期看真实容量数据再定。
- SLA 基线算法 v1 用近 N 天分位数还是简单移动平均 —— 实现期定，不影响 schema（基线表存计算结果）。
