# Phase 1 Contracts: 节点容错闭环接口契约

本特性主体是**调度内核内部行为**，对外 REST 面很薄。契约按"接口 + 不变量"描述，作为实现与测试的验收锚点。

## C1. Worker 心跳载荷（HeartbeatReporter → `POST /api/fleet/heartbeat`）

**变更**：`runningInstanceIds` 从硬编码 `[]` 改为**真实在跑实例集合**。

```json
{
  "nodeCode": "worker-1",
  "host": "worker-1:8100",
  "incarnation": 1783611630,
  "runningInstanceIds": ["019f...-a", "019f...-b"],   // ← 真实值（FR-016）
  "runningTasks": 2, "cpu": 12.3, "mem": 40.1, "disk": 55.0, "loadAvg": 1.2
}
```

**契约不变量**：
- `runningInstanceIds` = worker `WorkerExecService.runningInstanceIds()` 当刻快照（进程内在跑）。
- master `FleetService.report` 对每个 id `renewLease(id, now+leaseSeconds)`；命中即续约成功。
- 空数组仅当该 worker 确无在跑实例（非"未实现"占位）。

## C2. 节点可用性门（SlotManager 候选查询）

**契约**：候选节点 = ONLINE ∧ 心跳新鲜 ∧ 纪元稳定 ∧ 未隔离（见 data-model 谓词）。

**验收不变量**：
- incarnation 抖动节点（`incarnation_since` 持续被刷新）**永不**进候选（SC-002）。
- 连续 infra 故障 ≥ 阈值的节点，`quarantined_until > now` 期间**不**进候选；到期且稳定后自动回归（FR-004）。
- 无候选时 claim 分配 0 个，实例留 WAITING（不判失败）（FR-013）。

## C3. 失效分类与计数（LeaseReaper / WorkerReportService / SchedulerKernel）

| 触发 | 分类 | business_attempt | infra_redispatch_count | attempt | 终态? |
|---|---|---|---|---|---|
| 下发不可达（DispatchException） | infra | 不变 | +1 | 下次下发 +1 | 否，回 WAITING |
| 租约过期 + 节点 OFFLINE | infra(WORKER_LOST) | 不变 | +1 | 下次下发 +1 | 否 |
| 租约过期 + 纪元变化 | infra(WORKER_RESTART) | 不变 | +1 | 下次下发 +1 | 否 |
| worker reportFailed（started_at≠null） | business | +1 | 不变 | — | business_attempt>retry_max 才 FAILED |
| RUNNING 超 timeout_sec（非 long_running） | timeout | 不变 | 不变 | — | 是，FAILED(TIMEOUT) |
| infra_redispatch_count > 上限 | 保护 | 不变 | — | — | 否，SUSPENDED + 告警 |

**验收不变量**：infra 类**永不**使 `business_attempt` 增、**永不**判终态 FAILED（SC-001/003）。

## C4. Worker 执行契约（WorkerExecService / HeartbeatReporter）

- **running 追踪**：`submit`/`executeSync` 进入即 `running.add(instanceId)`，`finally` `running.remove(instanceId)`；`runningInstanceIds()` 返回快照。
- **自我中止**（FR-021）：连续心跳失败累计时长 > `self-fence-grace-ms`（≥ 续约窗）→ `abortAll()` 销毁进程内在跑子进程；`long_running` 实例不在中止列内。
- 幂等键仍为 `(taskInstanceId, attempt)`——`attempt` 单调保证重派为新键，不双跑（C 与 master `isCurrentDispatch` 双栅栏）。

## C5. 外部长驻作业（FlinkTaskExecutor，仅 `long_running=true`）

```
submit(detached):  flink run -d ...  ──stdout──> parse "JobID: <hex>"
                   → 写回 task_instance.external_job_handle = {"jobId":"<hex>","rest":"<endpoint>"}
                   → 轮询 GET <rest>/jobs/<jobId> 驱动 RUNNING/续约/终态；不套 3600s timeout
reattach:          实例 external_job_handle≠null 到达新 worker
                   → 直接轮询 <rest>/jobs/<jobId>（不 flink run）
                   → job 存在=继续监控；job 不存在/FAILED=按业务重试重新提交
cancel(人工 kill): flink cancel <jobId>（或 REST）→ STOPPED
```

**验收不变量**：worker 故障后集群中该 job 的实例数不变（SC-007：0 重复提交）；有界 Flink（`long_running=false`）路径与 exit-code/stdout 语义**不变**（constitution III）。

## C6. Ops 兜底（OpsService）

- `killTask(instanceId)`：非终态 → STOPPED；若 `long_running` 且 `external_job_handle≠null` → 先按句柄取消集群作业（FR-027）。
- `rerunInstance(instanceId)`：终态/SUSPENDED → WAITING，**同时**重置 `attempt=0, business_attempt=0, infra_redispatch_count=0`，清 worker/租约/句柄/failure_reason，发 `WAKE_CHANNEL`（FR-028）。

## C7. 恢复唤醒 & 告警（EventBus / AlertSignal）

- 节点由不可用→可用（status 恢复 / 隔离到期 / 纪元稳定跨窗）→ `eventBus.publish(WAKE_CHANNEL, "node-recovered")`（FR-014）。
- 无节点等待 > `stuck-wait-alert-ms` → `AlertSignal(NODE_STARVATION/STUCK, ...)`；单实例挂起 SUSPENDED → `AlertSignal`（FR-012/015）。**告警失败不影响回收/挂起主流程。**
