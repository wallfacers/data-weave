# Phase 1 Data Model: 节点容错闭环

权威 DDL：`backend/dataweave-api/src/main/resources/schema.sql`（当前 `schema_version=0.14.3` → 本特性 **0.15.0**）。仅**加列 / 加状态枚举值**，向后兼容，H2/PG 通用。

## 1. `worker_nodes`（节点健康）

| 新列 | 类型 | 默认 | 语义 | FR |
|---|---|---|---|---|
| `incarnation_since` | TIMESTAMP | NULL | 当前 `incarnation` 首次被观察到的时刻；`FleetService.report` 在纪元变化时置 `now`。稳定窗判据：`now - incarnation_since >= 稳定窗`。 | FR-002 |
| `consecutive_infra_failures` | INTEGER | 0 | 近期连续基础设施故障计数（原子自增）；成功执行一次或隔离到期稳定后复位 0。 | FR-003/004/006 |
| `quarantined_until` | TIMESTAMP | NULL | 熔断隔离到期时刻（只增不减 `GREATEST`）；`> now` 期间不进候选。 | FR-003/006 |

既有沿用：`incarnation`、`last_heartbeat`、`status`、`version`。

**候选过滤谓词**（`SlotManager` `findByStatus("ONLINE")` 追加）：
```
AND last_heartbeat >= now - :offlineThreshold
AND (incarnation_since IS NULL OR incarnation_since <= now - :stabilizationWindow)
AND (quarantined_until IS NULL OR quarantined_until <= now)
```
（`incarnation_since IS NULL` 视为老数据/稳定，放行；新注册节点由 `FleetService` 落 `now`，首个稳定窗内自然不被派。）

## 2. `task_instance`（任务实例）

| 新列 | 类型 | 默认 | 语义 | FR |
|---|---|---|---|---|
| `business_attempt` | INTEGER | 0 | 业务重试计数；仅"曾进入 RUNNING 后业务失败"时 +1，与 `retry_max` 比较。与 `attempt` 相互独立。 | FR-007/009 |
| `infra_redispatch_count` | INTEGER | 0 | 单实例连续基础设施重派计数；超上限 → `SUSPENDED`。人工 rerun 复位 0。 | FR-012 |
| `external_job_handle` | VARCHAR(512) | NULL | 外部托管长驻作业句柄：引擎 JobID + REST/tracking 端点（JSON 编码）；reattach 与未来实时卡片的挂载点。 | FR-023/024 |

既有沿用（语义不变或微调）：
- `attempt`：**纯下发纪元栅栏**，`casDispatch` 每次 +1，`isCurrentDispatch` 用之。**不再**用于业务重试比较。
- `lease_expire_at`：进程内 RUNNING 续约判据（心跳 `renewLease` 延长）。
- `failure_reason` VARCHAR(64)：扩充取值 `WORKER_RESTART / WORKER_LOST / TIMEOUT / EXIT_NONZERO / INFRA_SUSPENDED`（新增最后一个仅作可见性，不改列宽）。
- `started_at`：进入 RUNNING 的边界信号（`business_attempt` 计次门）。
- `task_type`：已存 `FLINK` 等；配合 `task_def.long_running` 判类别。

### 状态枚举（`state` VARCHAR(32)）

现有：`NOT_RUN/WAITING/DISPATCHED/RUNNING/SUCCESS/FAILED/STOPPED/PREEMPTED/PAUSED`。

**新增 `SUSPENDED`**：单实例 infra 重派超限后的保护挂起态。性质：**非终态、不可被 claim、不自动判 FAILED**；仅可由人工 `kill`（→STOPPED 终态）或 `rerun`（→WAITING，复位计数）转出。

### 状态迁移（本特性相关）

```
WAITING --claim/casDispatch(attempt+1)--> DISPATCHED --reportStarted(started_at)--> RUNNING
DISPATCHED/RUNNING --infra 回收(WORKER_LOST/RESTART/下发失败)--> WAITING        # business_attempt 不变；infra_redispatch_count+1；清 worker_node_code；attempt 保留
DISPATCHED/RUNNING --业务失败 reportFailed(started_at≠null)-->
        business_attempt+1 ; (business_attempt<=retry_max? WAITING : FAILED)
RUNNING --now-started_at > timeout_sec 且非 long_running--> FAILED(TIMEOUT)
* --infra_redispatch_count > 上限--> SUSPENDED                                   # 告警；不判死
SUSPENDED --人工 rerun--> WAITING (business_attempt=0, infra_redispatch_count=0, attempt=0)
* --人工 kill--> STOPPED (+long_running: 按 external_job_handle 取消集群作业)
```

**回收判据（LeaseReaper 修正）**：
- 租约过期 + 节点 `OFFLINE` → `WORKER_LOST`（infra）。
- 租约过期 + 节点 `ONLINE` 但 incarnation 已变（真比对：dispatch 时纪元快照 ≠ 当前 `worker_nodes.incarnation`）→ `WORKER_RESTART`（infra）。
- 二者都走 infra 分类（不烧 `business_attempt`，`infra_redispatch_count+1`）。

## 3. `task_def`（任务定义）

| 列 | 类型 | 默认 | 语义 | FR |
|---|---|---|---|---|
| `timeout_sec` | INTEGER | NULL | **已存在**：任务级最大运行时长；本特性启用为 max-runtime（`long_running` 豁免）。 | FR-020 |
| `long_running`（新增） | BOOLEAN | FALSE | 标记外部托管长驻作业（Flink 流式=true）；决定 detached+reattach 路径与 timeout/自我中止豁免。 | FR-022/026 |

> `task_def_version`（快照表）如镜像 `long_running`/`timeout_sec` 供物化，需同步加列（与 059 content 扩列同处）。

## 4. 配置项（`application.yml` `scheduler.*`，保守默认可配）

| key | 默认 | 用途 |
|---|---|---|
| `scheduler.node.stabilization-window-ms` | 15000 | incarnation 稳定窗 |
| `scheduler.node.quarantine-threshold` | 3 | 连续 infra 故障熔断阈值 |
| `scheduler.node.quarantine-backoff-ms` | 30000 | 隔离退避（可指数） |
| `scheduler.infra-redispatch-max` | 10 | 单实例 infra 重派上限 → 挂起 |
| `scheduler.stuck-wait-alert-ms` | 300000 | 无节点等待告警阈值 |
| `scheduler.worker.self-fence-grace-ms` | ≥ 续约窗 | worker 失联自我中止阈值（须 ≥ master 判过期窗，防抖动误杀） |

## 5. 不变量（数据层）

- `attempt` 单调不减；`business_attempt`、`infra_redispatch_count` 单调不减（除人工 rerun 复位）。
- 节点健康三列更新全走原子/单调 SQL（自增 + `GREATEST`），对等 master 并发安全。
- 所有失效回收 CAS `WHERE state IN ('DISPATCHED','RUNNING')`（`casTaskTerminalFromActive`）单赢。
- `SUSPENDED` 永不出现在 claim 候选、永不被 sweeper 自动判终态。
