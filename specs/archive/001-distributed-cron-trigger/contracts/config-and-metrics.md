# Contract: 配置键与观测指标

## 新增/沿用配置(`application.yml` 的 `scheduler.*`)

现有(`application.yml:69-74`,**保留**):`mode` / `poll-interval-ms`(5000)/ `claim-batch-size`(50)/ `cron-misfire`(fire_once)。

新增(Batch A):

| 键 | 默认 | 含义 |
|----|------|------|
| `scheduler.cron-scan-interval-ms` | 15000 | 短周期扫描间隔(替代旧 60s tick) |
| `scheduler.cron-lookahead-ms` | 30000 | 预读窗口(捞 `next_trigger_time ≤ now + 此值`;建议 ≥ 2×扫描间隔) |

新增(Batch B,分片):

| 键 | 默认 | 含义 |
|----|------|------|
| `scheduler.cron-sharding-enabled` | false | 是否启用 master 分片预读(>10k 才需开) |
| `scheduler.master-heartbeat-ms` | 10000 | master 心跳续约周期 |
| `scheduler.master-offline-threshold-sec` | 30 | master 心跳超时判离线 |
| `scheduler.cron-fire-retention-days` | 30 | cron_fire 历史保留天数(归档清理,FR-011) |

**兼容性**: 不设新键时取默认值,行为 = 「15s 扫 + 不分片 + fire_once」,既有分钟级 cron 工作流零改动通过(SC-004)。`cron-misfire` 沿用 `fire_once`(默认,对齐 PowerJob)/`skip`。

## 观测指标(`SchedulerMetrics`,Micrometer → `/api/ops/metrics` + `/actuator/prometheus`)

| 指标 | 类型 | 标签 | 验证 |
|------|------|------|------|
| `dw.cron.trigger.latency` | Timer | — | SC-001(p99 ≤ 2s)、SC-005(秒级) |
| `dw.cron.misfire.count` | Counter | `policy=fire_once\|skip` | SC-003 |
| `dw.cron.window.size` | Gauge | — | 预读窗口装载点数 |
| `dw.cron.shard.workflows` | Gauge | — | SC-007 单机负载 |

## 端点影响

- **无新增 REST 端点**;复用现有 `GET /api/ops/metrics` / `/actuator/prometheus` 暴露上述指标。
- 现有 `GET /api/ops/eta-summary`(SlaService)消费的「下一次触发时间」从此可直接读 `workflow_def.next_trigger_time`(更准),为可选下游收益,不在本特性强制范围。
