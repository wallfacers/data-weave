# Runbook: 崩溃注入真跑（051 US2 / T026）

> 一次性收口 046 T017 / 048 T013 / 049 T013 三连欠的崩溃注入真跑。
> 复用 046 quickstart §5/§6 口径 + 045 cron-stress 框架；051 新增 ④ unmet_deps 收敛核对。
> **阻塞**：本 runbook 的**执行**依赖 US1 已落进 master docker 镜像（`scheduler.readiness.materialized=true`）+ readiness_* 指标已注册。当前 US1 未合并 main、未进镜像——harness 已就绪，真跑待 US1 落地。

## 0. 核对口径与 F1 依赖

T026 核四项：① 无重复 dispatch；② 不丢；③ 无 deadlock；④ unmet_deps 收敛权威值。

**②「不丢」走严格 no-loss 口径**（F1 已于 2026-07-06 修复并复核通过，见 audit.md §3-F1：`writeTerminalSignal` 已并入 `casTaskTerminal` 同事务原子提交）：

- 崩溃前已提交的完成（SUCCESS/FAILED），其 TERMINAL 信号**必同事务提交**——重启后 Maintainer 续处理，`readiness_signal.processed` 收敛为 1。
- 严格核对：崩溃窗口内已提交的终态，其信号不得"未写"（不得出现"完成已落库但 readiness_signal 无对应行"）。若观测到 → F1 修复回归，立即停报主 Claude。
- 假事件副作用（非阻塞，见 audit.md §3-F1 残留副作用）：信号 INSERT 失败回滚完成时，`casTaskTerminal` 内已发的 AlertSignal/TaskSucceededEvent 为假事件，长跑时留意 alert 计数是否有"终态回滚假 alert"模式。

## 1. 前置

- distributed profile 后端已起：双 master（`dataweave-master` 8000 + `dataweave-master-2` 8200）+ 双 worker（worker-1/2）+ PG（max_connections=200）+ Redis。
- master 镜像含 US1 改动 + `SCHEDULER_READINESS_MATERIALIZED=true`（认领走 `unmet_deps=0` 过滤）。
- 045 cron-stress 脚本（`tmp/cron-stress/cron-stress.sh`，git-ignored）。
- `admin/admin` 登录态。

```bash
# 健康核对
curl -s localhost:8000/api/health   # master-1
curl -s localhost:8200/api/health   # master-2
curl -s localhost:8000/api/fleet    # worker-1/2 ONLINE
# 确认物化开关已开（任一 master）
curl -s localhost:8000/actuator/prometheus | grep -E 'readiness_signal_pending|readiness_drift_corrected'
```

## 2. 起负载（1000wf */2s 极限档，跑稳）

```bash
cd tmp/cron-stress
./cron-stress.sh setup -n 1000 -c '*/2 * * * * *'
./cron-stress.sh cron-watch -m 1 -i 5    # 观测 1 分钟，确认 claim/dispatch 跑稳、WAITING 在堆积
```

记录崩溃前的基线计数（供 ④ 收敛对比）：

```bash
# 基线：未处理信号积压 + 已就绪候选
docker exec -e PGPASSWORD=dataweave dataweave-postgres psql -U dataweave -d dataweave -tAc \
  "SELECT 'pending_signals', COUNT(*) FROM readiness_signal WHERE processed=0
   UNION ALL SELECT 'waiting_total', COUNT(*) FROM task_instance WHERE state='WAITING' AND deleted=0
   UNION ALL SELECT 'waiting_unmet_gt0', COUNT(*) FROM task_instance WHERE state='WAITING' AND unmet_deps>0 AND deleted=0;"
```

## 3. 崩溃注入（认领事务提交后、下发未完成的窗口）

> 无需精确瞄准窗口——任意时刻 kill 均可触发两种恢复路径：① 已认领未下发 → casRequeue 重派；② 已提交完成的 TERMINAL 信号 → Maintainer 续处理（或 Reconciler 兜底，视 F1 裁决）。在高负载稳态下连续 kill 2-3 次提高命中窗口概率。

```bash
# 窗口 1
docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2
sleep 10   # 让恢复路径跑一轮
# 窗口 2（高负载下再注入一次，提高命中"认领已提交/下发未完成"窗口）
docker kill dataweave-master-2 && sleep 2 && docker start dataweave-master-2
sleep 30   # 等 Maintainer/Reconciler 续处理 + casRequeue 重派收敛
```

记录 kill/start 精确时序（贴回交主 Claude）：

```bash
date -Is && docker kill dataweave-master-2 && date -Is && \
sleep 2 && docker start dataweave-master-2 && date -Is
```

## 4. 四项核对（等 30s 后）

### ① 无重复 dispatch（同 task_instance 恰好下发一次）

```sql
-- task_instance 状态轨迹：同实例不应出现重复 attempt 的 DISPATCHED（casDispatchBatch WHERE state=WAITING 保证）
-- attempt 单调递增；同 (id, attempt) 的下发即重复。dispatch 无独立表，靠状态轨迹 + worker 日志。
SELECT id, attempt, state, worker_node_code, updated_at
FROM task_instance
WHERE updated_at > '<崩溃注入时刻>'
ORDER BY id DESC, attempt DESC LIMIT 50;
```

```bash
# worker 端幂等键日志：同 (instance_id, attempt) 不应被执行两次（worker 幂等键含 attempt 拦截旧命令）
docker logs worker-1 2>&1 | grep -iE 'duplicate|already running|instance.*attempt' | head
docker logs worker-2 2>&1 | grep -iE 'duplicate|already running|instance.*attempt' | head
# 预期：无重复执行证据
```

### ② 不丢

```sql
-- (a) 已认领未下发实例：DISPATCHED 滞留超租约的应被 LeaseReaper casRequeue 回 WAITING 重派（不丢）
SELECT COUNT(*) AS stranded_dispatched FROM task_instance
WHERE state='DISPATCHED' AND lease_expire_at < NOW() AND deleted=0;
-- 崩溃后短暂可 >0（LeaseReaper 周期内），30s 后应回落趋 0

-- (b) [F1 已修口径] 崩溃前已提交完成的 TERMINAL 信号：重启后 Maintainer 续处理
SELECT
  SUM(CASE WHEN processed=0 THEN 1 ELSE 0 END) AS unprocessed,
  SUM(CASE WHEN processed=1 THEN 1 ELSE 0 END) AS processed
FROM readiness_signal
WHERE created_at > '<崩溃注入时刻>';
-- 预期：unprocessed 趋 0（Maintainer 续处理）；processed 持续涨

-- (b')[F1 退化口径] 改查 Reconciler 自愈：readiness_drift_corrected 应有计数（说明对账兜底在工作）
```

### ③ 无 deadlock / 活锁（日志 + actuator）

```bash
# 任一 master 日志无死锁/锁等待超时
docker logs dataweave-master   2>&1 | grep -iE 'deadlock|deadlockdetected|lock wait timeout|livelock' | head
docker logs dataweave-master-2 2>&1 | grep -iE 'deadlock|deadlockdetected|lock wait timeout|livelock' | head
# 预期：空（无输出）

# actuator health 全绿
curl -s localhost:8000/actuator/health
curl -s localhost:8200/actuator/health

# round_duration 无异常飙升（claim 仍在推进，非活锁）
curl -s localhost:8000/actuator/prometheus | grep -E 'scheduler_round_duration|dw_dispatch_queue_size'
```

### ④ unmet_deps 收敛权威值（readiness_drift_corrected 可 >0 但自愈，无永久卡 WAITING）

```sql
-- 无实例永久卡 WAITING 且 unmet>0：Reconciler 应在 ~60s 内自愈漂移
-- 注意：unmet>0 且上游真未 SUCCESS 是正常"真未就绪"，非漂移；只关心"上游已 SUCCESS 但 unmet 仍>0"——
-- 用 Reconciler 的 drift 指标代替逐行核对（权威重算成本由 Reconciler 承担）。
SELECT COUNT(*) AS stuck_unmet_waiting FROM task_instance
WHERE state='WAITING' AND unmet_deps>0 AND deleted=0
  AND updated_at < NOW() - INTERVAL '5 minutes';
-- 预期：0（或仅"真未就绪"实例；5min 阈值远超 Reconciler 60s 周期，漂移应已自愈）
```

```bash
# 漂移自愈计数（可 >0，证明对账兜底在工作；关键是不无限涨 + 无永久卡死）
curl -s localhost:8000/actuator/prometheus | grep -E 'readiness_drift_corrected|readiness_signal_pending|readiness_signal_lag'
# readiness_signal_pending：应回落趋稳态（不无限积压）
# readiness_drift_corrected：可 >0（崩溃/并发触发的漂移被自愈），但应趋稳不再涨
# readiness_signal_lag：p99 < 3s（SC-005）
```

## 5. 长跑佐证（T027 §4 回填）

崩溃注入后继续 cron-watch 5-10 分钟，确认：
- `dw.dispatch.queue.size` 稳态不持续涨；
- claim/dispatch 吞吐恢复，无长期退化；
- 日志持续无 deadlock。

把以上 metrics 截图/数值 + 日志 grep 空输出回填 `audit.md` §4，即完成 T027 长跑佐证。

## 6. 拆卸

```bash
cd tmp/cron-stress && ./cron-stress.sh teardown
docker exec -e PGPASSWORD=dataweave dataweave-postgres psql -U dataweave -d dataweave \
  -c "TRUNCATE task_instance, workflow_instance, cron_fire, readiness_signal RESTART IDENTITY;"
```

## 7. 交付回主 Claude

真跑完成后，把以下证据贴回报：
- §3 的 kill/start 精确时序（3 个时间戳 ×2 窗口）；
- §4 ①②③④ 四项核对的查询结果（计数/日志片段/metrics 数值）；
- §5 长跑 5-10min 的 metrics 趋势 + 无 deadlock 日志；
- 若 ② 或 ④ 出现"丢/永久卡"：立即停，按 audit.md §3-F1 复现路径定位，报主 Claude 裁决（是否 F1 未修所致）。
