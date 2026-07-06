# Audit: 死锁防御四不变量 + 崩溃恢复正确性（051 US2 / T027）

> 审计对象：051「认领就绪态物化」US1 的 readiness 实现 + SchedulerKernel 认领/dispatch 改动。
> 审计基线：**US1 已合并 main**（commit `f9a4acc`，merge `4475aef`）。原审计写于 `dw-051` 未提交快照（HEAD `a7adf92`），2026-07-06 已对照合并后 main 逐条复核 file:line。
> 对照标准：[contracts/components.contract.md](contracts/components.contract.md) §四不变量 + [research.md](research.md) R8。
> **复核结论（2026-07-06，对照 `f9a4acc`）**：28 条引用 25 一致、3 漂移（均在 `WorkerReportService.java`，因 F1 修复插入 `TransactionTemplate` 使后续方法整体下移 ~15-19 行），已在下表修正。F1 修复复核通过；F2 仍成立——本轮已修复（加 `state='WAITING'` 守卫 + 回归测试，见 §3-F2）。四不变量结论不变。

---

## 0. 审计范围与口径

死锁防御四不变量（spec SC-004 acceptance 3 / contracts §四不变量 / research R8）：

1. **①** 认领仅经 `FOR UPDATE SKIP LOCKED`；
2. **②** 状态推进均走乐观 CAS（`WHERE state=?`）；`unmet_deps` 落权威重算值；
3. **③** 完成事务只 append 信号、不锁下游行；Maintainer 读 edge/dependency 无锁、按 PK 改 task_instance，无反向/跨表锁；
4. **④** 状态仅在事务内持久化、下发在事务外；`unmet_deps` 维护是独立事务，不碰 dispatch 路径。

审计证据文件（均位于 `backend/dataweave-master/src/main/java/com/dataweave/master/`）：

| 组件 | 文件 | 关键行 |
|---|---|---|
| 认领/下发 | `application/SchedulerKernel.java` | selectRunnable 369-424、claimAndMark 192-235、runRound 147-189 |
| 状态机 CAS | `application/InstanceStateMachine.java` | casDispatchBatch 81-104、casTaskTerminal 141-167、casRequeue 180-187 |
| 信号维护 | `application/readiness/ReadinessMaintainer.java` | maintainInTx 86-146 |
| 信号写入 | `application/readiness/ReadinessSignalWriter.java` | writeTerminal 39-47、writeReset 52-60 |
| 信号仓储 | `infrastructure/JdbcReadinessSignalRepository.java` | pollPending 70-96、markProcessed 99-113 |
| 完成回调 | `application/WorkerReportService.java` | reportFinished 94-120、writeTerminalSignal 264-278（F1 修复后行号） |
| 重算核心 | `application/readiness/ReadinessRecompute.java` | recomputeFromTerminal 46-77、recompute 85-109 |

---

## 1. 逐条审计

### ① 认领仅经 `FOR UPDATE SKIP LOCKED` —— ✅ 满足

- `SchedulerKernel.selectRunnable`（SchedulerKernel.java:401）：SQL 末尾固定 `ORDER BY ti.updated_at ASC, ti.id ASC LIMIT <n> FOR UPDATE SKIP LOCKED`。
- 051 物化仅在 NORMAL/BACKFILL 分支追加过滤条件 `AND ti.unmet_deps = 0`（SchedulerKernel.java:391-393），**不改锁语义**，过滤在 SKIP LOCKED 之前作用于 WHERE。
- 批量 CAS 推进（`casDispatchBatch`）作用于同事务内 SELECT 已锁住的行（SELECT 阶段 SKIP LOCKED 锁取 → 同事务内 UPDATE 必然命中 WAITING），无新锁竞争（InstanceStateMachine.java:74-80 注释明确此设计）。
- 信号消费侧同样 SKIP LOCKED：`JdbcReadinessSignalRepository.pollPending`（JdbcReadinessSignalRepository.java:74-75）`... ORDER BY id ASC LIMIT ? FOR UPDATE SKIP LOCKED`。

**结论**：物化未引入非 SKIP LOCKED 的认领锁路径。① 保持。

### ② 状态推进走乐观 CAS + unmet 落权威重算值 —— ✅ 满足（state CAS），unmet 非 CAS 但构造性幂等

**state CAS（不变量②主体）**：
- `casDispatchBatch`（InstanceStateMachine.java:91-92）：`WHERE task_instance.id=v.id AND task_instance.state='WAITING' AND deleted=0`。
- `casTaskTerminal`（141-145）：`WHERE id=? AND state=? AND deleted=0`。
- `casRequeue`（180-184）：`WHERE id=? AND state=? AND deleted=0`。
- 所有 state 推进都是 `UPDATE … WHERE id=? AND state=?`，0 行即让步，无先读后写锁窗口。✅

**unmet_deps 的并发模型（非 CAS，但 R8② 设计如此）**：
- `ReadinessMaintainer` 更新 unmet 用 `UPDATE task_instance SET unmet_deps=?, updated_at=? WHERE id=? AND state='WAITING'`（ReadinessMaintainer.java:120-124，`state='WAITING'` 守卫系 F2 修复加入）—— 无 `WHERE unmet_deps` 值守卫，落**权威重算值**非递减，故非严格 CAS。
- 但 R8② 的设计本意即此："状态 CAS 不变；unmet_deps 落**权威重算值**（并发两 master 算同值 → 收敛一致；信号 SKIP LOCKED 领取避重复处理）"。并发安全靠两支柱：
  1. **信号领取 SKIP LOCKED**：每条 readiness_signal 至多被一个 master 事务领取（pollPending SKIP LOCKED），杜绝两个 master 并发处理同一条信号 → 同一下游不会被并发写。
  2. **重算幂等**：`ReadinessRecompute` 是纯读权威态的确定性函数（ReadinessRecompute.java:85-109，`Math.max(0, unmet)` 兜底防负），重复处理同信号 → 同值，安全重放（contracts R2/R8②）。
- 故 unmet 非 CAS **不构成不变量违反**；它不是"状态推进"，是辅助计数维护，靠"重算幂等 + SKIP LOCKED 领取"保证收敛。

**结论**：② 保持。state CAS 全覆盖；unmet 落权威重算值 + 信号 SKIP LOCKED，并发收敛一致。

### ③ 完成事务只 append 信号不锁下游行；Maintainer 读无锁、按 PK 改 —— ⚠️ "不锁下游行"满足，但"同事务 append"偏离（见 §3-F1）

**Maintainer 侧（满足）**：
- 读：`ReadinessRecompute` 全程纯 SELECT（无 `FOR UPDATE`），读 `workflow_edge`/`workflow_dependency`/`task_instance`（ReadinessRecompute.java:131-167、356-484）。
- 写：`UPDATE task_instance SET unmet_deps=? … WHERE id=?`（ReadinessMaintainer.java:120），PK 行锁，无跨表 join、无反向锁顺序。
- 信号标记：`UPDATE readiness_signal SET processed=1 … WHERE id IN (…)`（JdbcReadinessSignalRepository.java:110），信号表 PK 锁。

**SignalWriter 侧（"不锁下游行"满足；"同事务 append"偏离）**：
- `ReadinessSignalWriter.writeTerminal/writeReset` 只 `repo.insert(row)`（ReadinessSignalWriter.java:44、57），单行 INSERT readiness_signal，**不触碰下游 task_instance**。"不锁下游行"满足。
- 但 contracts ReadinessSignalWriter 契约 / research R4 承诺"`casTaskTerminal` 成功后**同一事务内** append TERMINAL 信号 = no-loss 构造保证（信号与完成同提交 → 崩溃不丢、重启续处理）"。实现**未满足此契约**，详见 §3-F1。

**结论**：③ 完全满足——"完成事务不锁下游行 / Maintainer 读无锁按 PK 改"保持；"同事务 append 信号"经 F1 修复（2026-07-06）后达成——`casTaskTerminal + writeTerminalSignal` 现同事务原子提交（见 §3-F1 修复复核）。

### ④ 状态仅事务内持久化、下发+unmet 维护事务外 —— ✅ 满足

- **认领事务**：`SchedulerKernel.runRound`（SchedulerKernel.java:154）`txTemplate.execute(status -> claimAndMark(events))`，事务内只做 SELECT SKIP LOCKED + assign + casDispatchBatch（CAS state 落库）。DISPATCHED 事件收集到内存 `events`，**事务提交后**才 `publishDispatchedEvents`（160）。
- **下发事务外**：`dispatcher.dispatchAllAsync`（175）在 `txTemplate.execute` 返回后调用，fire-and-forget，claim 线程不等 dispatch。
- **unmet 维护独立事务**：`ReadinessMaintainer.maintain`（ReadinessMaintainer.java:74）`txTemplate.executeWithoutResult(status -> maintainInTx())`，与认领事务、下发、完成事务完全分离。✅
- 完成路径同样：`casTaskTerminal` 提交后，`recomputeWorkflow`/`writeTerminalSignal`/`wake` 均在外。

**结论**：④ 保持。物化的 unmet 维护走独立事务，未沾染 dispatch 路径，未引入"事务内下发"或"下发事务内持久化状态"的回退。

---

## 2. 死锁/活锁环形等待分析（理论论证，待 T026 长跑佐证）

**核心论点：不变量① 的 SKIP LOCKED 是无死锁的构造性根因——认领事务永不进入等待链。**

并发事务的锁接触面：

| 事务 | 锁对象 | 等待行为 |
|---|---|---|
| 认领 T_claim | task_instance 行（SELECT `FOR UPDATE SKIP LOCKED`） | **被锁即跳过，不等待** |
| Maintainer T_maint | readiness_signal 行（SKIP LOCKED）→ task_instance 行（UPDATE WHERE id PK 锁） | UPDATE 命中被锁行时会等待 |
| 完成 T_terminal | task_instance 行（casTaskTerminal PK 锁）→ readiness_signal 行（INSERT 新行） | INSERT 新行无锁竞争 |

**唯一可能的等待方向**：T_maint 的 `UPDATE task_instance WHERE id=?` 命中 T_claim（或 T_terminal）持有的行锁 → T_maint 等 T_claim/T_terminal 提交。

**为何不成环**：
- T_claim 用 SKIP LOCKED，**永不等待任何事务**（被锁即跳过）→ T_claim 不在等待链中。
- T_terminal 的 casTaskTerminal 是 CAS（WHERE state=?），命中即改、不命中即 0 行返回，**不等待**（乐观 CAS 无先读后写窗口）。
- T_maint 等 T_claim/T_terminal，但 T_claim/T_terminal 都不回头等 T_maint → **无环形等待 → 无死锁**。

**批量 UPDATE 的锁顺序一致性**：
- T_maint 单事务内处理一批信号：先 SKIP LOCKED 锁 readiness_signal 行 → recompute 纯读 → UPDATE 多个 task_instance（按 id）。锁顺序固定为 `readiness_signal → task_instance`，且 task_instance 按 PK 离散锁，不与认领的范围锁交叉。
- `casDispatchBatch` 批量 UPDATE 作用于本事务 SELECT 已锁行，不引入跨事务锁等待。
- 级联 STOPPED（WorkerReportService.java:225-228，块 217-233；F1 修复后行号 `UPDATE task_instance SET state='STOPPED' WHERE workflow_instance_id=? AND state IN ('WAITING',...)`）是范围 UPDATE，可能锁住若干 WAITING 行；若某行正被 T_claim 持有，级联会等 T_claim 提交——但 T_claim 不回头等级联，仍无环。

**活锁**：T_maint 维护失败会重试（信号未标 processed，下轮 SKIP LOCKED 再领，ReadinessMaintainer.java:131 注释"不标记 processed，下轮重试"）；重算幂等保证重试收敛。认领侧 SKIP LOCKED 不会因反复被跳过而饿死（049 的窗口游标已处理；051 物化后候选即就绪，无饿死路径）。**理论无活锁**。

**结论**：四不变量 + SKIP LOCKED 语义 → 构造性无环形等待 → 无死锁/活锁的理论基础成立。**长跑实证待 T026**（kill/restart + 30s 核对日志/actuator 无 deadlock）。

---

## 3. 审计发现与建议

### F1【✅ 已修复 2026-07-06 · fix-f1.md 落地，复核通过】TERMINAL 信号 append 并入 casTaskTerminal 同事务（原偏离：不同事务 → R4 no-loss 退化）

- **承诺**（research R4 / contracts ReadinessSignalWriter）："`casTaskTerminal` 成功后、**同一事务内** append TERMINAL 信号"——事务型 outbox = **no-loss 构造保证**（信号与完成同提交 → 崩溃不丢、重启续处理）。
- **实现**（WorkerReportService.java:95-107）：
  ```
  boolean ok = stateMachine.casTaskTerminal(...);  // 独立 jdbc.update，auto-commit 提交
  ...
  writeTerminalSignal(ti);                          // 另一个独立 INSERT readiness_signal
  ```
  - `reportFinished` 无 `@Transactional`，跨 bean 调用（stateMachine vs readinessSignalWriter）事务不传播 → 两者是**两个独立事务**。
  - `writeTerminalSignal`（WorkerReportService.java:245-262）还 `try/catch` 吞异常后只 `log.warn`（260）——INSERT 失败**静默降级**，完成路径不感知。
  - 代码注释（WorkerReportService.java:104）声称"同事务写 TERMINAL 信号（no-loss）"，**与实际语义不符**。
- **后果（崩溃窗口）**：完成事务（task_instance → SUCCESS）已提交，但 INSERT readiness_signal 前进程崩溃/INSERT 异常 → **信号永久丢失**（无事务回滚把它带走）。该 SUCCESS 实例的下游 `unmet_deps` 不会被 Maintainer 递减 → 下游卡 WAITING，**直到 Reconciler 低频兜底**（默认 ~60s，ReadinessReconciler）才自愈。
  - 不丢任务（Reconciler 最终自愈），但破坏 SC-005「就绪滞后 p99 < 3s」——丢失信号路径的滞后退化为 ~60s 量级。
  - 正是 T026 要验证的崩溃窗口（"readiness_signal 崩溃前已提交→重启续处理"）的反面：**若信号 append 在完成提交后、崩溃前丢失，则不满足 T026 ②「不丢」的严格口径**（需靠 Reconciler 兜底，而非构造 no-loss）。
- **定性**：这不是死锁四不变量违反（①②③④ 死锁角度全保持）；是 R4/contracts 契约偏离 + SC-005 时效退化。
- **建议（交主 Claude 裁决，不自行改 US1）**：二选一——
  1. 让 US1 把 `writeTerminalSignal` 纳入 `casTaskTerminal` 的同事务（如 `@Transactional` 包 reportFinished 的终态段，或把信号 INSERT 并入 casTaskTerminal 的 UPDATE 语句同事务）；或
  2. 接受退化为"最终一致 + Reconciler 兜底"，相应**修订 contracts/R4 措辞**（去掉"同事务 no-loss 构造保证"表述，改为"best-effort append + Reconciler 兜底"），并确认 SC-005 p99<3s 口径是否放宽。
- **对 US2 的影响**：T026 崩溃注入若恰好打中此窗口，会观测到下游滞后飙升（等 Reconciler），可能被判为"丢/卡"。需主 Claude 先裁 F1，T026 再定核对口径。

> **修复复核（2026-07-06，dw-051 快照）**：方案 B 已落地——`WorkerReportService` 注入 `TransactionTemplate`（字段 50 / 构造器 65/79），`reportFinished`（103-109）与 `reportFailed`（197-201）用 `txTemplate.execute{ casTaskTerminal + writeTerminalSignal }` 同事务，`writeLog/recordSyncedRows/recomputeWorkflow/wake` 均事务外（未退化成长事务），`writeTerminalSignal`（264-278）已去 try-catch、异常传播触发回滚。**fix-f1.md 4 处改动全部到位，no-loss 构造保证恢复**。runbook §0 切严格 no-loss 口径。
>
> **残留副作用（非阻塞，独立优化项）**：`casTaskTerminal` 内部 UPDATE 后立即发 UI/alert/quality 事件（`InstanceStateMachine.java:147/150/159` 的 `publishTaskState`/`publishAlertSignalForTask`/`TaskSucceededEvent`）——这些事件在事务提交前发出。修复前 `casTaskTerminal` 是独立 auto-commit（发事件时状态已提交，非假事件）；**修复后并入外层事务，若信号 INSERT 失败回滚，已发事件成"假事件"**（task 实际未到终态，但 AlertSignal/TaskSucceededEvent 已发）。这是 InstanceStateMachine 既有"事务内发副作用"纪律（注释 24 行）的窗口放大，非 F1 引入的新违规。彻底解法：把事件发布挪到 `TransactionSynchronization.afterCommit`（独立重构，不在 F1 范围）。alert 假信号可能误报，建议 T026 长跑时观测 `readiness_drift_corrected` 与 alert 计数是否出现"终态回滚导致的假 alert"模式。

### F2【✅ 已修复 2026-07-06 · T027 收口】Maintainer 的 unmet UPDATE 顺带改了非 WAITING 实例的 updated_at

- **原偏离**：`ReadinessMaintainer.java:120` `UPDATE task_instance SET unmet_deps=?, updated_at=? WHERE id=?`——对**任何状态**的实例（含已 DISPATCHED/RUNNING）都会改 `updated_at`。而 `updated_at` 是认领候选排序键（`ORDER BY updated_at`）与 dispatch_latency 基准（SchedulerKernel.java:298 `recordDispatchLatency(Duration.between(r.waitingSince, now))`，waitingSince=updated_at）。残留副作用：非 WAITING 实例被无谓改 updated_at 污染 latency 基准；WAITING 实例的候选窗排序位置可能被前移到"重算时刻"，偏离原始入队 FIFO。
- **修复**（主 Claude 裁决采纳建议一）：Maintainer 的 UPDATE 加 `AND state = 'WAITING'` 守卫（ReadinessMaintainer.java:120-124）——只维护仍在等的实例，跳过已认领/运行/终态（其 unmet_deps 不再被读，无意义）。并把 wake 条件改为 `updated>0 && prevUnmet>0 && newUnmet==0`（仅实际命中的 WAITING→就绪才 wake，torn 竞态下 UPDATE 0 行不误 wake）。
- **一致性论证**：`recomputeFromTerminal` 的 D 解析本就只取 `state=WAITING`（ReadinessRecompute resolveDownstream），守卫与之对齐；额外覆盖"D 解析后、UPDATE 前被并发认领"的 torn 竞态——此时守卫使 UPDATE 空跑、unmet 保持（对非 WAITING 无害，其 unmet 不被读；若日后 requeue 回 WAITING，RESET 信号/Reconciler 会重算）。不改任何不变量。
- **回归测试**：`ReadinessMaintainerTest.maintainerSkipsNonWaiting`（mock recompute 强塞 RUNNING 实例→守卫跳过，unmet/updated_at 均不动、不误 wake）+ `maintainerStillUpdatesWaiting`（WAITING 实例正常重算落库 1→0 + wake 一次，护就绪路径不被误伤）。

### F3【事务粒度，性能而非正确性】Maintainer 单事务处理整批信号

- `maintainInTx`（ReadinessMaintainer.java:86-146）在单事务内处理 batchSize（默认 100）条信号：每条 → recompute（多次 SELECT）→ 多个 UPDATE task_instance → 末尾 markProcessed。事务持锁时间随批量与扇出增长，期间被它锁住的 task_instance 行会让认领 SKIP LOCKED 跳过（不阻塞、不死锁，但增加本轮回避率）。
- 不违反任何不变量；若 T024/T029 压测观测到 claim 回避率上升或 round_duration 抖动，可缩小 batchSize 或改逐信号小事务。属调参项，非缺陷。

---

## 4. 长跑压测无 deadlock/活锁佐证 —— 状态：待 T026

- **代码层论证**（本审计 §2）：四不变量 + SKIP LOCKED → 构造性无环形等待 → 无死锁/活锁。理论成立。
- **长跑实证**：依赖 T026 崩溃注入真跑（多 master docker，`docker kill dataweave-master-2 && sleep 2 && docker start` + 等 30s）核对 ③ actuator/日志无 deadlock。**当前阻塞**：US1 未合并 main、未进 docker 镜像、无测试。待 US1 落地后补证据回填本节。

---

## 5. 三连欠（046/048/049）收口映射

| 三连欠任务 | 原口径 | 051 收口载体 | 状态 |
|---|---|---|---|
| 046 T016 | idempotency 单测（casDispatchBatch WHERE state=WAITING 恰好一次） | T025（扩展 SchedulerKernelReadinessTest） | ✅ **已收口**（`casDispatchExactlyOnce`/`casDispatchNoDoubleDispatch` 随 US1 `f9a4acc` 合并、跑绿） |
| 046 T017 | 崩溃注入真跑（kill master） | T026 | ⏳ 待真跑（需分布式 docker 集群，runbook 就绪） |
| 046 T018 | 四不变量代码审计 | T027（本审计） | ✅ **代码审计已收口**（§1-§3 + F1/F2 已修 + F3 定性），长跑佐证待 T026 |
| 048 T013 | 崩溃注入真跑 | T026 | ⏳ 同 046 T017 |
| 048 T014 | 四不变量审计 | T027（本审计） | ✅ 同 046 T018 |
| 049 T013 | 崩溃注入真跑 | T026 | ⏳ 同 046 T017 |
| 049 T014 | 四不变量审计 | T027（本审计） | ✅ 同 046 T018 |

**已收口**：046 T016（T025 单测双库绿）+ 046 T018 / 048 T014 / 049 T014（T027 代码审计 §1-§3，F1/F2 已修、F3 定性为调参项）。**仍待**：三连欠的**真跑部分**（046 T017、048 T013、049 T013 = T026）阻塞于分布式多 master docker 集群 + cron-stress harness（仓库当前只有 PG+Redis 单机 compose，无 `dataweave-master-2` 配置、`tmp/cron-stress` 不存在）——runbook/harness 脚本已就绪，需实起集群方可正式勾选。

---

## 6. 复核清单（US1 定型后）

- [x] US1 提交后重新核对所有 file:line 引用（2026-07-06 对照 `f9a4acc`：25/28 一致，3 处 WorkerReportService 漂移已修正）；
- [x] F1 已修（writeTerminalSignal 并入同事务，2026-07-06 复核通过）→ §3-F1 已标注修复、③ 已重审为完全满足；
- [ ] T026 长跑无 deadlock 证据回填 §4（**阻塞**：需分布式多 master docker 集群，见 crash-injection-runbook.md）；
- [x] T025 双库绿：`SchedulerKernelReadinessTest.casDispatchExactlyOnce`/`casDispatchNoDoubleDispatch` 已随 US1 合并并跑绿（§5 单测行已可勾）；
- [x] Maintainer UPDATE 加了 `WHERE state='WAITING'`（F2 已修）→ §1② unmet 并发模型论述不变（守卫是 CAS 精神的加强，非 CAS 替代），§3-F2 已记修复。
