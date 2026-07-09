# Phase 0 Research: 节点容错闭环

本特性的方案已在 brainstorming + clarify 阶段收敛，Technical Context 无遗留 `NEEDS CLARIFICATION`。以下把关键技术决议固化为「决策 / 理由 / 备选」。

## D1. `attempt` 双职拆分方式

- **决策**：保留 `task_instance.attempt` 为**纯下发纪元栅栏**（`casDispatch` 每次下发 +1，`isCurrentDispatch` 用 `COALESCE(attempt,0) <= cmd.attempt`，worker 幂等键含 attempt）；新增列 `business_attempt` 承载业务重试，`RetryService` 改比 `business_attempt <= retry_max`。
- **理由**：现状 `attempt` 同时被 `casDispatch` 递增和 `RetryService`/`LeaseReaper` 当 `retry_max` 比较，导致每次 infra 重派都烧业务重试。拆列是"infra 不烧业务"的物理前提，且让栅栏令牌保持严格单调、`isCurrentDispatch` 语义零改动（守 CLAUDE.md 硬不变量）。
- **备选**：① 复用单一 `attempt` + 旁挂"本次是否 infra"标志 → 无法回溯累计业务次数，且栅栏与计数耦合易错，弃。② 用 `PREEMPTED` 式"不计次回炉"（已有先例）套到 infra → 能不烧业务，但仍无法区分"真跑过几次业务失败"，无法支撑毒任务上限与可观测，弃。

## D2. "任务真跑过"的边界信号

- **决策**：以 `started_at IS NOT NULL`（等价于曾 CAS 到 RUNNING）为界。仅当失效实例 `started_at≠null` 且来源为 worker `reportFailed` 时才 `business_attempt+1`；租约回收/下发失败一律 infra。
- **理由**：`WorkerReportService.reportStarted` 在 DISPATCHED→RUNNING 时落 `started_at`，是既有、可靠、幂等的"进入运行态"标记。
- **备选**：以 state 快照判断 → 有 TOCTOU；用 `started_at` 列判定与 `casTaskTerminalFromActive` 的 DB 裁决一致，稳。

## D3. 节点可用性门的收口点

- **决策**：全部谓词加在 `SlotManager`（`findByStatus("ONLINE")` → 追加 `last_heartbeat` 新鲜 + `incarnation_since <= now-稳定窗` + `quarantined_until IS NULL OR < now`）。`snapshotOnline()` 与 `availableForNormal/Test()` 同源过滤。
- **理由**：claim 候选唯一来源即 `SlotManager`，一处过滤满足 FR-005「单点收口」，避免多点判定不一致。
- **备选**：在 `SchedulerKernel.claimAndMark` 里过滤 → 分散、易与未来路径分叉，弃。

## D4. 熔断计数的并发安全更新

- **决策**：`NodeHealthService` 用原子/单调 SQL：失败 `UPDATE worker_nodes SET consecutive_infra_failures = consecutive_infra_failures + 1, quarantined_until = CASE WHEN consecutive_infra_failures+1 >= :thr THEN GREATEST(COALESCE(quarantined_until, :now), :now + :backoff) ELSE quarantined_until END WHERE node_code=:c`；成功/复位 `SET consecutive_infra_failures=0, quarantined_until=NULL`。
- **理由**：对等 master 并发更新同一行，原子自增 + `GREATEST` 单调只增，结果与串行等价（FR-006），无需行锁或乐观 version 重试。
- **备选**：读-改-写 + `@Version` 乐观锁 → 并发冲突需重试、复杂；纯 SQL 原子表达式更简更稳。
- **归因边界**：计入熔断的仅"下发不可达 / 运行中执行异常回收"；`WORKER_RESTART`（纪元变化的正常重启）由稳定窗 D3 处置，**不**计熔断，避免正常滚动重启的节点被隔离。

## D5. 恢复唤醒抽干

- **决策**：节点由不可用→可用的边界（`FleetService.report` 检测到 status 恢复 ONLINE / 隔离到期 / 纪元跨过稳定窗），发 `eventBus.publish(WAKE_CHANNEL,"node-recovered")`。隔离到期这类"时间触发"由 `NodeHealthService`/`StuckInstanceSweeper` 周期检测并唤醒。
- **理由**：复用既有 `WAKE_CHANNEL` 事件驱动认领（045/046 模式），使等待在恢复瞬间抽干，而非等 5s 兜底轮询（FR-014「系统健康无残留等待」）。
- **备选**：只靠 poll-interval → 有最坏 5s 残留，不满足"恢复即跑"。

## D6. 毒任务/崩溃循环上限

- **决策**：新增 `task_instance.infra_redispatch_count`，每次 infra 回收 +1；超 `scheduler.infra-redispatch-max`（保守默认如 10）→ CAS 置新状态 `SUSPENDED`（非终态、不可 claim、不判 FAILED）+ 发 `AlertSignal`。人工 rerun 复位。
- **理由**：满足 FR-012「不无限重派危害机群、不自动判死、人工兜底」。`SUSPENDED` 与 `PAUSED` 区分语义（PAUSED 是运维暂停整体，SUSPENDED 是单实例保护挂起）。
- **备选**：判终态 FAILED(INFRA_EXHAUSTED)（clarify 选项 C）→ 违背"不因 infra 判死"，已被用户否。

## D7. RUNNING 真续约（修复硬编码 []）

- **决策**：`WorkerExecService` 维护 `ConcurrentHashMap.newKeySet()<UUID> running`（`submit`/`executeSync` 入口 add，`finally` remove），暴露 `Set<UUID> runningInstanceIds()`；`HeartbeatReporter` 用它序列化替换 `instanceIdsArray="[]"`。`FleetService.report` 既有 `renewLease(runningInstanceIds)` 随即生效。
- **理由**：现状 worker 从不上报在跑实例 → `renewLease` 永不命中 → 所有 RUNNING 租约必到期被回收（长跑必被误杀）。这是 FR-016/017 的根因修复。
- **备选**：master 侧用 last-report 时间近似续约 → 不准且与租约模型冲突，弃。
- **`reapRestartedInstances` 修正**：现状只按"ONLINE + 租约过期"当 WORKER_RESTART（代码自注"简化实现"）。改为真比对 `task_instance` 记录的下发时纪元 vs `worker_nodes.incarnation`（需 claim 时快照 dispatch 纪元，或直接以租约到期 + 心跳新鲜度归为 WORKER_LOST/RESTART 二选一）——见 data-model「回收判据」。

## D8. worker 自我中止（防分区双跑）

- **决策**：`HeartbeatReporter` 记录连续心跳失败次数；连续失败时长超过 `续约窗口`（即 master 已可能判租约过期并重派）→ 通知 `WorkerExecService.abortAll()` 中止**进程内**在跑任务（销毁子进程）。`long_running` 外部作业不在此列（其执行不在 worker 进程内）。
- **理由**：master 栅栏只能防"双下发"，防不了旧 worker 物理续跑。worker 侧以"租约窗口内失联即自杀"使并发物理双跑不发生（FR-021 / SC-006）。阈值须 ≥ master 判过期窗，避免抖动误杀。
- **备选**：仅靠任务幂等（clarify 选项 B）→ 不满足用户"不双跑"硬要求，已被否。

## D9. 外部托管长驻作业（Flink 流式）reattach

- **决策**：
  1. `task_def` 加 `long_running BOOLEAN DEFAULT FALSE`（Flink 流式置真；有界 Flink SQL/DataX/Spark 仍 false 走原路径）。
  2. `FlinkTaskExecutor` 对 `long_running`：`flink run -d`（detached）提交，从 stdout 解析 **Flink JobID**，写回 `task_instance.external_job_handle`（JobID + REST 端点），随后**轮询** Flink REST job status 驱动状态/续约，而非阻塞子进程；**不套** `DEFAULT_TIMEOUT_SECONDS`。
  3. failover：新 worker 收到该实例（`external_job_handle` 非空）→ 进入 **reattach 模式**：直接按 JobID 轮询状态恢复监控，**不** `flink run`。仅当 REST 探测确认 job 不存在/FAILED 才按业务重试重新提交。
- **理由**：真实执行在 Flink 集群、与 worker 解耦；重派=重提交会产生重复作业。reattach 靠 JobID 幂等（FR-024/SC-007）。外部句柄同时是未来"实时任务卡片"的数据挂载点（FR-023）。
- **备选**：① 提交即置终态、流作业交独立监控器（clarify 选项 C）→ 与"卡片挂任务实例上"背离，弃。② 移出范围（clarify 选项 B）→ 用户要求纳入且要可扩展，弃。
- **⚠️ Cross-feature（059）**：`FlinkTaskExecutor` 属 059「大数据任务类型」范围，正在 `dw-059-*` worktree/主线活跃。plan→tasks 前必须对账：本特性对该类的改动（detached/JobID/reattach/long_running 标记）须与 059 的执行器契约协调，避免落地互相破坏。**依赖 059 的 Flink 执行器先稳定**；若 059 未就绪，切片 C 的 Flink 子项可先以契约 + 桩验证，真集成排在 059 合入后。

## D10. schema 版本与兼容

- **决策**：`schema_version` 0.14.3 → **0.15.0**（MINOR：多列新增 + 新状态值，向后兼容）。所有新列带默认值（`business_attempt DEFAULT 0` 等），H2/PG 通用 DDL。文件头版本注释、DB 行、项目版本三处同步。
- **理由**：遵循 CLAUDE.md「改结构必升版本」+ SemVer；纯加列不破坏既有读写。
