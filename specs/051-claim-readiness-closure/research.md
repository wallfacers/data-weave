# Research: 认领就绪态物化 + 性能链收口

Phase 0 决策汇总。本 feature 的关键决策已在 spec 的 `/speckit-clarify`（4 项）与 brainstorming 阶段收敛；此处固化为 Decision/Rationale/Alternatives。

## R1 — 就绪态一致性模型：最终一致 + 异步维护 + 对账兜底

- **Decision**: 上游/跨周期满足方到达放行终态时，仅在**完成事务内**提交自身状态 + append 一条廉价 outbox 信号；下游 unmet 递减/重算**异步、批量、离峰**进行，不进完成事务关键路径。周期性对账在有界窗口内收敛残留漂移。就绪滞后 p99 < 3s。
- **Rationale**: 把扇出写挪出完成/聚合热路径（046 已点名 `computeAndUpdate` 聚合是下一瓶颈，强一致会加剧它）；锁更干净（完成事务不触碰下游行，天然不违反不变量③）；吞吐更稳。代价是就绪态有界滞后，但不重新引入 O(WAITING) 扫描，SC-001 仍成立。
- **Alternatives**: ① 强一致（下游递减与上游终态同事务）——正确性可证明但把扇出写压在最热路径、且需在上游事务内触碰下游行，逼近违反锁顺序不变量③，与优化目标逆向。② 认领时校验（物化仅作提示）——认领路径仍逐候选校验，O(1) 打折扣。

## R2 — 维护机制：信号触发的 scoped 重算（非递减账本）

- **Decision**: Maintainer 消费信号 → 解析受影响的**下游扇出集 D** → 对每个 `d∈D` 按**权威状态重算** `unmet_deps`（复用现有上游/跨周期就绪语义，作用域限 D）→ CAS 落库。不做"直接 -1 递减 + 幂等账本"。
- **Rationale**: 重算是**幂等且构造上无漂移**——每次读权威 pred/上周期状态，重投递/崩溃重放结果一致，**无需去重账本**、无需清理、上游重跑回退（FR-007）天然被覆盖（权威态变了，重算把 unmet 加回）。成本由"完成节点的扇出宽度"界定（DAG 通常上游数很少，每下游多读几行），而非 WAITING 规模。把非幂等的"递减"换成幂等的"重算"，用极小读换掉账本/去重复杂度。
- **Alternatives**: ① 递减 + 账本 `(signal_id, downstream_id)` 去重——递减最省读，但宽 DAG 下账本行=扇出量、要清理、重跑要额外 +1 回滚，非幂等本质靠账本兜。② 无信号周期增量对账扫最近完成——"扫最近完成"本身是活儿，且要够频才能 p99<3s，逼近高频扫描。

## R3 — 物化载体：`task_instance.unmet_deps` 列（非独立表）

- **Decision**: 在 `task_instance` 加列 `unmet_deps INT NOT NULL DEFAULT 0`；认领候选索引 `idx_task_instance_claim` 扩为含 `unmet_deps`；认领查询加 `AND unmet_deps=0`。
- **Rationale**: 就绪态是实例的固有属性，co-located 最自然；PK 定位的重算 CAS 更新最便宜；认领候选索引扩一列即走 seek，无需 join。契合「内核复用」——最小结构改动。
- **Alternatives**: 独立 `task_readiness(instance_id, unmet_count)` 表——与 task_instance 热行解耦，但认领要多一次 join/查、维护两处生命周期，复杂度不划算。

## R4 — 信号通道：事务型 outbox 表 `readiness_signal`

- **Decision**: 新表 `readiness_signal`（TERMINAL/RESET 两 kind）。完成/reset 事务内 INSERT 一行（与 `casTaskTerminal` 同事务）；Maintainer 用 `FOR UPDATE SKIP LOCKED` 批量领取未处理信号、处理后标记 `processed=1`。EventBus 的 `wake()` 仍用于即时唤醒一轮认领。
- **Rationale**: 事务型 outbox = **no-loss 构造保证**（信号与完成同提交 → 崩溃不丢、重启续处理）；SKIP LOCKED 领取让多 master 各处理各的、即便重复处理也因重算幂等而安全。EventBus 是 best-effort pub/sub（易丢），不足以承载 no-loss，故用持久 outbox 承正确性、EventBus 承时效唤醒。
- **Alternatives**: 纯 EventBus 承载递减——pub/sub 丢消息则漂移，只能靠对账兜，且对账要够频 → 逼近 O(变更) 高频扫描。

## R5 — 下游扇出集 D 的解析

- **Decision**: 给定完成/重置实例 U（node N，wf-instance WI，bizDate B）：
  - **同 DAG**：`workflow_edge WHERE from_node_id=N` 的后继 node → 同 WI 的 `task_instance(WAITING)`。
  - **跨周期**：`workflow_dependency WHERE depend_node_id=N AND enabled=1` 的后继 `(workflow_id, node_id, date_offset, earliest_biz_date)` → 逆算受影响 bizDate `B'`（满足 `offsetBizDate(B', date_offset)==B`）→ `task_instance(workflow_node_id=node_id, biz_date=B', WAITING)`，含首周期豁免过滤。
  - 对每个 `d∈D` 重算 `unmet_deps` = 未满足上游 edge 数（STRONG=pred SUCCESS / WEAK=pred 终态）+ 未满足跨周期依赖数。
- **Rationale**: 复用 049 `batchUpstreamReady` 与 048 `batchCrossCycleReady` 的既有语义，仅把作用域从"全体候选"缩到"受影响 D"；逆偏移是既有 `offsetBizDate` 的反向应用。D 有界于扇出宽度。
- **Alternatives**: 全量重扫上游门——即 049 现状，正是要消除的 O(WAITING) 退化源。

## R6 — 对账 Reconciler：低频有界审计

- **Decision**: `ReadinessReconciler` 低频（默认 ~60s，`scheduler.readiness.reconciler.interval`）跑有界审计——重算"WAITING 且 `unmet_deps>0` 停留超阈值"或滚动窗内实例的 unmet_deps，检出漂移则 CAS 自愈并出 `readiness_drift_corrected` 指标。
- **Rationale**: 正常路径（outbox + 重算幂等）已构造无漂移；对账只兜 bug/手工改库/torn-state 的残留，故低频有界即可，不在时效路径上。停留超阈值的定向审计避免全表扫。
- **Alternatives**: 高频全量对账——O(WAITING) 且高频，与优化目标冲突。

## R7 — 认领路径简化

- **Decision**: `selectRunnable` 加 `AND unmet_deps=0`；**删除** `batchUpstreamReady` + `batchCrossCycleReady` + `collectReady` 的窗口游标翻窗兜底（049 引入的饿死缓解 hack）。候选即就绪 → 单窗即可，无需 Java 就绪门、无需翻窗。TEST 路径（无就绪门）逻辑不变。
- **Rationale**: 物化后候选本身已就绪，Java 就绪门与窗口游标失去存在理由，删之简化热路径、消除窗口饿死风险来源。
- **Alternatives**: 保留 Java 门做双保险——冗余、且保留了退化路径，违背"彻底解"。

## R8 — 四不变量保持论证

- **①** 认领仍单线程 `FOR UPDATE SKIP LOCKED`，仅加 `unmet_deps=0` 过滤。
- **②** 状态 CAS 不变；`unmet_deps` 由 Maintainer/Reconciler 落**权威重算值**（并发两 master 算同值 → 收敛一致；信号 SKIP LOCKED 领取避重复处理）。
- **③** Maintainer 读 `workflow_edge`/`workflow_dependency`（无锁 SELECT）、按 PK 改 `task_instance`（自己的事务）；**完成事务只 INSERT 信号、不锁下游行** → 无跨表锁、无反向锁顺序。
- **④** `unmet_deps` 维护是独立事务，不碰 dispatch 路径；状态仍事务内持久化、下发仍事务外。

## R9 — 上线回填（spec §6 决策 = A）

- **Decision**: 部署时对**已存在的 WAITING 实例**做一次性 `unmet_deps` 全量初始化（Reconciler 启动全量 pass 或等价 backfill），用配置开关 `scheduler.readiness.materialized` **门住认领的 `unmet_deps=0` 过滤**直到初始化完成，避免默认 0 把老实例误判就绪。
- **Rationale**: `unmet_deps DEFAULT 0` 会让部署前的 WAITING 老实例默认就绪 → 误认领。一次性全量初始化 + 开关门是稳的、可观测的上线路径；这些是调度运行态实例，不适用 constitution「存量不予考虑」（那条针对定义数据）。
- **Alternatives**: ① 哨兵 `-1`=未初始化，认领 `=0` 天然排除 → Reconciler 逐步回填——更懒但认领会暂时漏老实例直到回填。② 不管——直接误认领，排除。

## R11 — 物化初值必须权威计算（analyze C1 修订）

- **Decision**: `ReadinessInitializer` 用**权威重算**算初值（复用 `ReadinessRecompute` 单实例入口），只计**未满足**依赖，而非依赖总数/edge 计数。
- **Rationale**: 物化时上游/上周期实例常已终态——跨周期上周期通常已 SUCCESS；乱序/延迟物化下 DAG 内上游也可能已完成。朴素计数把已满足依赖计入 unmet，而满足方早已终态、信号已消费，**不会再触发重算** → 实例卡 unmet>0 直到 Reconciler 兜底，破坏 SC-005（就绪滞后 p99<3s，跨周期尤甚）。权威重算天然扣除已满足者，物化即得正确初值。
- **Alternatives**: 朴素 edge/dep 计数——快但对预满足依赖过计，靠对账兜底、时效退化，排除。
- **Impact**: FR-002 措辞、contracts ReadinessInitializer、tasks T009（暴露单实例重算入口）/T011（复用它）/T019（加"上游已 SUCCESS 初值扣减"回归）已同步。

## R10 — 压测与验证口径（复用既有基线）

- **Decision**: 复用 045/046 cron-stress（1000wf `*/2s`、可人为堆 WAITING）与 `docker kill` fault-injection；慢任务档需 rebuild worker image 真跑 sleep 占槽。崩溃注入按三连欠原口径（046 T017 / 048 T013 / 049 T013）真跑多 master docker。指标沿用既有 + 新增 `readiness_*`（不可变、只增）。
- **Rationale**: 与前序 spec 同口径 → 可比、不新造标准；真跑（非仅单测）才闭合三连欠与 slot_util 容量证据。
- **Alternatives**: 仅单测级 idempotency——覆盖不了崩溃恢复与真实 slot 占用，无法闭合欠账。
