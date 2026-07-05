# API Contracts: selectRunnable 优化

## 外部 API

**无新外部 API**。本 feature 是调度内核内部优化(selectRunnable 查询 + 上游就绪判定 Java 化),不暴露 REST / MCP / CLI 端点。

## 可观测性契约（既有,`/actuator/prometheus`,双 master）

| 指标 | 状态 | 049 用途 |
|---|---|---|
| `scheduler_round_duration_seconds` | 既有 | **SC-001 验证目标**:稳定 < 30ms(048 R10=0.3-1.6s) |
| `scheduler_dispatch_latency_delivery_seconds` | 既有 | 端到端认领延迟(SC-001 端到端) |
| `scheduler_claim_rounds_total` / `claim_empty_total` | 既有 | rounds/s 提升 + 空 claim 占比降 |
| `scheduler_dispatch_count_total` | 既有 | claim/dispatch 吞吐(SC-002 ≥600 inst/s) |
| `dw.dispatch.queue.size` / `queue.full.count` | 046 | **不退化**核 ≤18 / 0 |
| `dw.claim.candidate.size`(可选) | 049 拟加 | 候选批量大小(LIMIT 200)gauge(Phase 2 决定) |

## 事件契约（既有,不变）

- `publishTaskState(id, "DISPATCHED")`:认领成功事件(048 batchDispatchBatch 内逐个发),本 feature 不触及。
- 上游就绪判定移 Java 层不影响事件(只读判定,不改状态)。

## 约束契约（不变量,CLAUDE.md 硬规则）

selectRunnable 优化必须保持:
1. `SELECT … FOR UPDATE SKIP LOCKED`(单线程 claim,无并发竞争)— NORMAL/BACKFILL/TEST 都保留
2. `WHERE state='WAITING'` 的 CAS 语义(casDispatchBatch 048 不变)
3. 锁顺序 task→workflow(batchUpstreamReady 是 SELECT 无锁,无新锁路径)
4. 状态事务内 + dispatch 事务外(claimAndMark 内,048 已验)
