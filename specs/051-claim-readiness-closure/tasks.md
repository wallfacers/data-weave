---
description: "Task list for 051 认领就绪态物化 + 性能链收口"
---

# Tasks: 认领就绪态物化 + 性能链收口

**Input**: Design documents from `specs/051-claim-readiness-closure/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 包含（CLAUDE.md「无测试=未完成」；US2/US3 本身即验证故事）。

**Organization**: 按 user story 切分——US1（P1）就绪态物化核心=MVP → US2（P2）崩溃注入+四不变量收口 → US3（P3）慢任务 slot_util。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- 路径基于 plan.md：仅动 `backend/dataweave-master/`（application `readiness/` 子包 + 改 `SchedulerKernel`/`WorkflowTriggerService`/`WorkerReportService`）+ `backend/dataweave-api/.../schema.sql`

---

## Phase 1: Setup（共享地基）

**Purpose**: schema 变更 + 子包骨架 + 配置 + 指标注册，US1/US2/US3 都依赖。

- [ ] T001 改 `backend/dataweave-api/src/main/resources/schema.sql`：`task_instance` 加列 `unmet_deps INTEGER NOT NULL DEFAULT 0`（version 列后）；顶部 DROP 段加 `DROP TABLE IF EXISTS readiness_signal;`（契约见 contracts/schema.contract.md C1）
- [ ] T002 改 `schema.sql`：`idx_task_instance_claim` 扩列为 `(state, run_mode, deleted, unmet_deps, updated_at)`（C2）
- [ ] T003 改 `schema.sql`：新增 `readiness_signal` 表 + `idx_readiness_signal_pending (processed, id)`（C3）
- [ ] T004 改 `schema.sql`：`schema_version` 插 `0.10.0` 行 + 文件头注释追加一行；DB 行/文件头/项目版本三处同步（C4）
- [ ] T005 [P] 新建子包 `backend/dataweave-master/src/main/java/com/dataweave/master/application/readiness/`（空骨架类 + package-info），并在 `backend/dataweave-master/src/main/java/com/dataweave/master/infrastructure/ReadinessSignalRepository.java` 建接口骨架
- [ ] T006 [P] 在 `SchedulerMetrics.java` 注册 `readiness_*` 指标（`readiness_signal_lag`/`readiness_maintain_batch`/`readiness_signal_pending`/`readiness_drift_corrected`/`readiness_recompute_scope`/`unmet_ready_candidates`）——只增不改（contracts/metrics.contract.md）
- [ ] T007 [P] 配置键：`scheduler.readiness.maintainer.interval`(~1s)、`.maintainer.batch`、`.reconciler.interval`(~60s)、`.reconciler.window`、开关 `scheduler.readiness.materialized`——加到 master 配置类 + `application.yml` 默认值

**Checkpoint**: schema H2/PG 双跑建表成功，子包/配置/指标就位。

---

## Phase 2: Foundational（阻塞所有故事的前置）

**Purpose**: 信号仓储 + scoped 重算核心——被 Maintainer/Reconciler 共用，US1 直接依赖。

- [ ] T008 实现 `infrastructure/ReadinessSignalRepository.java`：INSERT 信号（`GeneratedKeyHolder` 取自增主键，勿用 H2 旧 `CALL IDENTITY()`）；`FOR UPDATE SKIP LOCKED` 批量领取 `processed=0`；批量标记 `processed=1, processed_at`（data-model.md §2）
- [ ] T009 实现 `application/readiness/ReadinessRecompute.java`：① D 解析（同 DAG `workflow_edge from_node_id` 后继 + 跨周期 `workflow_dependency depend_node_id` 反查）；**逆偏移策略（U1）**——不假定 `offsetBizDate` 可逆，用**正向枚举候选 bizDate 匹配** `offsetBizDate(B',offset)==B`（或证明 offset 简单可逆再直算），加测试覆盖月末/工作日等非平凡 offset ② 对 D 按权威状态重算 `unmet_deps`（**抽取** 049 batchUpstreamReady STRONG/WEAK + 048 batchCrossCycleReady 首周期豁免语义为可复用单元，作用域限 D，同时供 Initializer/Reconciler 复用）——纯读、确定性、幂等（contracts/components.contract.md）。另暴露**单实例重算**入口供 T011 Initializer 复用
- [ ] T010 [P] 契约测试 `ReadinessSchemaContractTest.java`：H2/PG 建表 + 列/索引/表存在 + `schema_version=0.10.0` + 认领查询与信号 SKIP LOCKED 领取可执行（contracts/schema.contract.md 契约测试段）

**Checkpoint**: 信号读写 + scoped 重算可独立测；DDL 契约双库绿。

---

## Phase 3: User Story 1 - 就绪判定 O(1)、与 WAITING 规模脱钩（P1）🎯 MVP

**Goal**: 物化 unmet_deps + 认领只取 unmet_deps=0 + 异步维护，就绪判定与堆积脱钩、claim 跟上物化。
**Independent Test**: 1000wf `*/2s` 堆 WAITING 50万+，单轮就绪判定耗时与堆积无关；就绪滞后 p99<3s；WAITING 稳态不涨。

### 实现

- [ ] T011 [US1] 实现 `application/readiness/ReadinessInitializer.java`：物化时按**权威状态**算 `unmet_deps` 初值——**只计未满足依赖**（复用 T009 ReadinessRecompute 单实例重算，勿朴素计数）。**C1 关键**：物化时上游/上周期实例可能已终态（跨周期上周期常已 SUCCESS、乱序物化下 DAG 内上游也可能已完成），已满足者不计入，否则无信号触发、卡就绪滞后直到对账。首周期豁免不计，无未满足依赖→0（FR-002）
- [ ] T012 [US1] 接入 `WorkflowTriggerService.java` 物化路径：新 task_instance 随 INSERT 写 `unmet_deps` 初值（同事务）
- [ ] T013 [US1] 实现 `application/readiness/ReadinessSignalWriter.java`：终态/reset 事务内 append `readiness_signal`（TERMINAL/RESET，单行不扇出）
- [ ] T014 [US1] 接入 `WorkerReportService.java`：`casTaskTerminal(→SUCCESS/FAILED)` 同事务内调 SignalWriter 写 TERMINAL 信号；rerun/reset 路径写 RESET 信号（不触碰下游行——护不变量③④）
- [ ] T015 [US1] 实现 `application/readiness/ReadinessMaintainer.java`：`@Scheduled` 轮询信号（SKIP LOCKED 批量）→ ReadinessRecompute 算受影响 D → CAS `UPDATE task_instance SET unmet_deps=? WHERE id=?` → 标记 processed → 有新就绪则 `wake()`；埋 `readiness_signal_lag`/`readiness_maintain_batch`/`readiness_recompute_scope`
- [ ] T016 [US1] 实现 `application/readiness/ReadinessReconciler.java`：低频有界审计（WAITING+unmet>0+停留超阈值/滚动窗）→ 重算自愈 → `readiness_drift_corrected`；**上线一次性全量初始化** + `scheduler.readiness.materialized` 开关门（§6=A / research R9）
- [ ] T017 [US1] 改 `SchedulerKernel.selectRunnable`：加 `AND unmet_deps=0`（NORMAL/BACKFILL；TEST 不变，受开关 `materialized` 门控）；**删认领路径对** `batchUpstreamReady`+`batchCrossCycleReady` **的调用**（其判定逻辑已由 T009 抽取进 ReadinessRecompute，非删语义）+ 删 `collectReady` 窗口游标翻窗；候选即就绪→单窗 assign；埋 `unmet_ready_candidates`（I1）
- [ ] T018 [US1] 清理 `SchedulerKernel` 中随窗口游标失效的死代码（`markClaimExtraWindow` 调用点、`claimMaxWindows`/`claimCandidateSize` 相关分支），保留 TEST 路径与四不变量

### 测试

- [ ] T019 [P] [US1] `ReadinessInitializerTest.java`：宽 DAG(N未完成上游→N) / **上游已 SUCCESS 时初值扣减(C1 回归：A 已完成再物化 B→B 初值不计 A)** / 跨周期上周期已 SUCCESS→初值不计 / 多跨周期 / 首周期豁免 / 无未满足依赖直通(0) / 非 CRON 忽略跨周期
- [ ] T020 [P] [US1] `ReadinessRecomputeTest.java`：STRONG=SUCCESS / WEAK=终态 / 跨周期逆偏移正确 / 双跑同值(幂等) / 上游 rerun 后 unmet 加回
- [ ] T021 [P] [US1] `ReadinessMaintainerTest.java`：信号→受影响下游正确重算→wake；SKIP LOCKED 多消费者不重复推进（或重复但幂等）；processed 标记
- [ ] T022 [P] [US1] `ReadinessReconcilerTest.java`：注入漂移(改 unmet/删信号)→一轮内检出自愈；`materialized=false` 时认领不启用 unmet 过滤
- [ ] T023 [US1] `SchedulerKernelReadinessTest.java`：只认领 unmet_deps=0；unmet>0 不被认领；无 Java 就绪门调用；端到端 A→B(强依赖) 物化(B unmet=1)→A SUCCESS→重算 B=0→B 被认领（H2+PG 双跑）
- [ ] T024 [US1] SC-001/002 压测（复用 045/046 cron-stress）：1000wf `*/2s` 堆 WAITING 50万+，实测 `round_duration` 与堆积无关（对比 049 的 49ms→1.58s 反弹消除）、`markClaimExtraWindow`=0、claim 吞吐≥物化、就绪滞后 p99<3s；数字回写 research.md

**Checkpoint**: US1 独立可验——结构终解落地，MVP 可交付。

---

## Phase 4: User Story 2 - 崩溃注入 + 四不变量审计（P2，收口 046/048/049 三连欠）

**Goal**: 对含 US1 物化的物化→认领→下发全链真崩溃注入 + 四不变量审计；恰好一次/不丢/无死锁 + 就绪态不漂移。
**Independent Test**: 高负载中 `docker kill` master（认领事务提交后、下发前）→ 重启核对无重复/不丢/无死锁 + unmet_deps 收敛权威值。

- [ ] T025 [US2] idempotency 回归单测（`SchedulerKernelReadinessTest.java` 扩展）：同实例并发可见恰好认领一次（casDispatchBatch WHERE state=WAITING CAS）——回归核 046/048/049 语义未被 unmet 过滤破坏
- [ ] T026 [US2] 崩溃注入真跑（复用 fault-injection，多 master docker）：`setup -n 1000` 起负载 → `docker kill dataweave-master-2 && sleep 2 && docker start` → 等 30s 核对 ① 无重复 dispatch ② 不丢（readiness_signal 崩溃前已提交→重启续处理；已认领未下发 casRequeue 重派）③ 无 deadlock ④ `unmet_deps` 收敛权威值（`readiness_drift_corrected` 可>0 但自愈）
- [ ] T027 [US2] 死锁四不变量代码审计（对照 contracts/components.contract.md §四不变量 + research R8）：① 认领仅 SKIP LOCKED ② CAS 状态推进 + unmet 落权威重算值 ③ 完成事务只 append 信号不锁下游行、Maintainer 按 PK 改 task_instance 无反向/跨表锁 ④ 状态事务内/下发+unmet维护事务外——出审计结论文档段，长跑压测无 deadlock/活锁佐证

**Checkpoint**: US2 通过——三连欠崩溃注入+四不变量收口，物化未引入正确性退化。

---

## Phase 5: User Story 3 - 慢任务压测补 slot_util（P3）

**Goal**: 真跑慢任务证明 slot_util 真实占用 + 慢任务不饿死。
**Independent Test**: worker 真跑 sleep 占槽压测，slot 成绑定约束 + slot_util≥80% + 慢任务认领延迟无长尾。

- [ ] T028 [US3] 准备慢任务压测档：rebuild worker image 使 ShellTaskExecutor 真跑 sleep（占槽数十秒）；构造慢+快混合负载脚本
- [ ] T029 [US3] SC-003/007 压测：持续加压核对 ① slot 成绑定约束（claim 吞吐不再随负载升、瓶颈转 worker）② 稳态 `slot_utilization`≥80% ③ 慢任务认领延迟分布无长尾饿死 ④ 就绪滞后 p99<3s；记录 slot 饱和点 + 瓶颈是否转 worker，回写 research.md

**Checkpoint**: US3 通过——容量证据补齐，性能链收敛闭合。

---

## Phase 6: Polish & 收尾

- [ ] T030 [P] `readiness_signal` 已处理行清理（既有历史清理机制接入或独立 TTL；非正确性关键，仅存储回收）
- [ ] T031 [P] 不退化验证（SC-006）：对比 046/048/049 基线指标（dispatch_latency/claim·dispatch 吞吐/round_duration）无回退，回写 research.md
- [ ] T032 编译 + dev-install 门：`./mvnw -q -pl dataweave-master compile` 零错误；改 master 后 `./dev-install.sh -pl dataweave-master -am` 再验（memory track1）
- [ ] T033 [P] 更新 memory + 三连欠标记：046 T016/T017/T018、048 T013/T014、049 T013/T014 在各 tasks.md 勾选并注明由 051 收口；写一条 051 落地 memory

---

## Dependencies（故事完成顺序）

- **Setup(P1) → Foundational(P2) → US1(P3) → US2(P4) → US3(P5) → Polish(P6)**
- US1 依赖 Foundational（ReadinessSignalRepository T008 + ReadinessRecompute T009）。**T011 Initializer 依赖 T009**（初值复用 Recompute 单实例入口，权威计未满足数——C1）。
- US2 依赖 US1（崩溃注入的对象含 US1 物化）。US3 依赖 US1（慢任务验证认领链路解除后的 slot）。US2/US3 **harness 准备可并行**（不同文件无冲突），但**同一集群压测执行须串行**（US2 崩溃 chaos 污染 US3 slot_util；worker 镜像冲突：US3 慢任务 vs US2 快任务）——除非双独立集群。串行顺序 **US2 先（正确性闸）→ US3 后（容量）**。
- T017/T018（selectRunnable 改+清理）依赖 T015/T016（维护器在位，否则认领会漏未维护实例）——**先维护后切过滤**，或用 `materialized` 开关灰度。

## Parallel Opportunities

- Setup：T005/T006/T007 并行（不同文件）。
- US1 测试：T019/T020/T021/T022 并行（不同测试类）。
- US2 与 US3 **harness 准备**可并行；**压测执行**同集群须串行(US2→US3)，双集群才可并行执行。
- Polish：T030/T031/T033 并行。

## Implementation Strategy

- **MVP = US1（Phase 1→2→3）**：结构终解可独立交付；用 `scheduler.readiness.materialized` 开关灰度上线（先跑维护+对账初始化，再切认领过滤）。
- **增量**：US1 稳后并行推 US2（正确性护栏）+ US3（容量证据）；Polish 收尾不退化对比 + 三连欠勾选。
- **风险闸**：T017 切认领过滤前，务必 T024 压测确认无饿死 + T026 崩溃注入确认不丢；四不变量 T027 审计过关方可合并。
