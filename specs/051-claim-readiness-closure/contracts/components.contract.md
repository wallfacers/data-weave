# Component Contracts: 就绪态物化组件

本 feature 无对外 HTTP/MCP/CLI 接口（就绪态由调度内核自维护）。契约是 master application 层内部组件的行为契约——每个组件"做什么、怎么用、依赖什么"，可独立测。

## ReadinessInitializer — 物化时算 unmet_deps 初值（权威重算，非朴素计数）

- **做什么**：给定一批新物化的 task_instance（携 workflow_instance_id / workflow_node_id / workflow_id / biz_date / workflow_trigger），按**权威状态**算每个的 `unmet_deps` 初值。
- **契约**：
  - `unmet_deps = 未满足上游 edge 数 + 未满足适用跨周期依赖数`——**只计未满足的**，不是依赖总数。等价于对新实例调 `ReadinessRecompute` 的单实例重算（复用同一权威判定：上游门 STRONG=pred SUCCESS / WEAK=pred 终态；跨周期门=上周期 SUCCESS 计数 + 首周期豁免 + 非 CRON 计 0）。
  - **关键**：物化时上游/上周期实例**可能已终态**（跨周期上周期常已 SUCCESS；乱序/延迟物化下 DAG 内上游也可能已 SUCCESS/FAILED）。这些已满足依赖 MUST 不计入初值——否则无后续信号触发重算（满足方早已终态、信号已消费），实例卡 unmet>0 直到 Reconciler 兜底，破坏 SC-005 就绪滞后 p99<3s。
  - 无未满足上游 edge 且无未满足适用跨周期依赖 → `0`（直通就绪）。
  - 缺 workflow_instance_id/workflow_node_id（单跑/占位实例）→ 上游门计 0。
- **依赖**：`workflow_edge`、`workflow_dependency`、`task_instance`（只读）；`ReadinessRecompute`（复用权威判定，避免逻辑双写）。
- **落点**：`WorkflowTriggerService` 物化 task_instance 处（初值随 INSERT 写入，或紧随其后同事务 UPDATE）。
- **可测**：宽 DAG（N 未完成上游 → N）；**上游已 SUCCESS 时初值扣减**（如 A 已完成再物化 B → B 初值不计 A）；跨周期上周期已 SUCCESS → 初值不计该跨周期依赖；首周期豁免（初值不计豁免依赖）；无未满足依赖直通（0）；非 CRON 忽略跨周期。

## ReadinessSignalWriter — 完成/reset 事务内 append 信号

- **做什么**：满足方实例到达放行终态（或被 rerun/reset）时，在**同事务**内 INSERT 一条 `readiness_signal`。
- **契约**：
  - `casTaskTerminal(→SUCCESS/FAILED)` 成功后、**同一事务内** append `kind=TERMINAL` 信号（快照 workflow_id/workflow_instance_id/workflow_node_id/biz_date）。
  - 上游 rerun/reset（SUCCESS→WAITING 等回退）→ append `kind=RESET` 信号。
  - 单行写，**不做任何下游扇出写**（保持完成事务短、不锁下游行 → 不变量③④）。
- **依赖**：`ReadinessSignalRepository`（infrastructure）。
- **落点**：`WorkerReportService.reportSuccess/reportFailure` 的终态提交事务内；rerun/reset 路径（RecoveryService/BackfillService 相关）。
- **可测**：终态提交则信号必在（同事务原子性）；完成事务回滚则信号不存在；写信号不触碰下游 task_instance 行。

## ReadinessMaintainer — sweeper：信号 → scoped 重算 → wake

- **做什么**：周期轮询未处理信号，解析受影响下游集 D，对 D scoped 重算 unmet_deps，标记信号已处理，唤醒一轮认领。
- **契约**：
  - 每 `scheduler.readiness.maintainer.interval`（默认 ~1s）跑一批：`SELECT ... WHERE processed=0 ORDER BY id LIMIT batch FOR UPDATE SKIP LOCKED`。
  - 对每条信号解析 D（见 `ReadinessRecompute` 的 D 解析）→ 调 `ReadinessRecompute.recompute(D)` → CAS `UPDATE task_instance SET unmet_deps=? WHERE id=?`。
  - 处理完 `UPDATE readiness_signal SET processed=1, processed_at=? WHERE id IN(...)`（在处理事务内）。
  - 有下游从 `>0` 变 `0`（新就绪）→ `wake()`（EventBus WAKE_CHANNEL）促一轮认领。
  - **幂等**：重复处理同信号 → 重算同值，安全（崩溃重放/多 master 各领各的均安全）。
- **依赖**：`ReadinessSignalRepository`、`ReadinessRecompute`、EventBus。
- **可测**：信号→受影响下游 unmet 正确重算→wake；SKIP LOCKED 下多消费者不重复推进（或重复但幂等）；处理后 processed=1。
- **时效**：Maintainer 间隔 + 批量 → 就绪滞后 p99 < 3s（SC-005）。

## ReadinessRecompute — scoped 重算核心（幂等）

- **做什么**：给定满足方实例 U 或直接给定下游集 D，按**权威状态**重算每个 `d∈D` 的 unmet_deps。
- **契约**：
  - **D 解析**（给定 U：node N / wf-instance WI / bizDate B）：
    - 同 DAG：`workflow_edge WHERE from_node_id=N` → 后继 node → `task_instance(workflow_instance_id=WI, workflow_node_id∈后继, state=WAITING)`。
    - 跨周期：`workflow_dependency WHERE depend_node_id=N AND enabled=1 AND deleted=0` → 后继 `(workflow_id, node_id, date_offset, earliest_biz_date)` → 逆算 `B'`（`offsetBizDate(B', date_offset)==B`）→ `task_instance(workflow_node_id=node_id, biz_date=B', state=WAITING)`（首周期豁免过滤）。
  - **重算**（每个 d）：`unmet = 未满足上游 edge 数 + 未满足适用跨周期依赖数`，复用 049 `batchUpstreamReady`（STRONG=pred SUCCESS / WEAK=pred 终态）与 048 `batchCrossCycleReady`（上周期 SUCCESS 计数 + 首周期豁免）语义，作用域限 D。
  - **幂等/无漂移**：纯读权威态的确定性函数；同输入同输出，可安全重放。
- **依赖**：`workflow_edge`、`workflow_dependency`、`task_instance`（只读）；`offsetBizDate` 工具（含逆向）。
- **可测**：STRONG/WEAK 语义；跨周期逆偏移正确；双跑同值（幂等）；上游 rerun 后重算把 unmet 加回（回退）。

## ReadinessReconciler — 低频有界对账自愈

- **做什么**：低频审计一部分实例，检出 unmet_deps 漂移并自愈。
- **契约**：
  - 每 `scheduler.readiness.reconciler.interval`（默认 ~60s）跑：取"WAITING 且 unmet_deps>0 且 updated_at 停留超阈值"或滚动窗内一批实例 → `ReadinessRecompute` 重算 → 与库值不一致则 CAS 修正 + `readiness_drift_corrected++`。
  - **有界**：单轮扫描量有上界（定向/窗口），不做全表 O(WAITING) 扫。
  - **上线一次性初始化**（R9/§6=A）：启动时或首轮对 WAITING 全量算初值；配置开关 `scheduler.readiness.materialized=false` 时门住认领 `unmet_deps=0` 过滤，初始化完成后置真。
- **依赖**：`ReadinessRecompute`、`SchedulerMetrics`、配置开关。
- **可测**：注入漂移（手工改 unmet_deps 或删信号）→ 一轮内检出并自愈；开关 false 时认领不启用 unmet 过滤。

## SchedulerKernel.selectRunnable — 认领路径简化

- **契约变更**：
  - `selectRunnable` 加 `AND unmet_deps=0`（NORMAL/BACKFILL；TEST 无就绪门不变）。
  - **删除** `batchUpstreamReady` + `batchCrossCycleReady` 调用与 `collectReady` 窗口游标翻窗兜底；候选即就绪 → 单窗直接 assign。
  - `FOR UPDATE SKIP LOCKED` 单线程 claim 不变（不变量①）；CAS 状态推进不变（不变量②）。
- **可测**：只认领 unmet_deps=0 的实例；unmet_deps>0 不被认领；无 Java 就绪门调用；崩溃 idempotency 回归（casDispatchBatch WHERE state=WAITING 恰好一次）。
