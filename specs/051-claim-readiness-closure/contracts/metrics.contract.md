# Metrics Contract: 051 就绪态物化

指标定义**不可变**：新增只增不改；由 `SchedulerMetrics` 统一插桩。沿用既有 Micrometer/Actuator 暴露（`GET /api/ops/metrics`、`/actuator/prometheus`）。

## 新增指标（只增）

| 指标 | 类型 | 含义 | 用于验收 |
|---|---|---|---|
| `readiness_signal_lag` | Timer/分布 | 满足方终态 → 下游变 unmet_deps=0 的滞后 | SC-005（p99 < 3s） |
| `readiness_maintain_batch` | Counter/Gauge | Maintainer 每轮处理信号数 / 受影响下游数 | 吞吐观测、瓶颈定位 |
| `readiness_signal_pending` | Gauge | 未处理信号积压深度（processed=0 行数） | 维护跟不上 → 积压可见 |
| `readiness_drift_corrected` | Counter | Reconciler 检出并自愈的漂移条数 | SC-005（正常应 ≈0，异常可观测） |
| `readiness_recompute_scope` | 分布 | 单次重算的下游 D 规模（扇出宽度） | 证明成本由扇出而非 WAITING 界定 |
| `unmet_ready_candidates` | Gauge | 认领时 unmet_deps=0 的候选量 | SC-001（就绪判定 O(1) 佐证） |

## 保留/对比的既有指标（不改）

- `scheduler_dispatch_latency`、claim/dispatch 吞吐、`round_duration`、`slot_utilization`、`markClaimExtraWindow`（窗口游标删除后应 →0）、queue 深度等——用于 SC-001/002/006 与 046/048/049 不退化对比。
- `markClaimExtraWindow`：本 feature 删窗口游标后此信号应恒 0（可作"翻窗兜底已消除"的证据）。

## 验收映射

- **SC-001**（就绪判定与堆积无关）：`round_duration` 在 WAITING 50万+ 与早期一致；`unmet_ready_candidates` 稳定；无 `batchUpstreamReady` 扫描耗时。
- **SC-002**（claim 跟上物化）：claim 吞吐 ≥ 物化速率、WAITING 稳态不涨。
- **SC-003**（慢任务 slot_util）：`slot_utilization` ≥ 80% 且成绑定约束（加压吞吐不再升）。
- **SC-005**（就绪滞后 + 无漂移）：`readiness_signal_lag` p99 < 3s；`readiness_drift_corrected` 正常 ≈0、异常可自愈。
- **SC-006**（不退化）：对比 046/048/049 基线指标无回退。
