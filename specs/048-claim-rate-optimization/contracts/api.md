# API Contracts: claim 速率深优化

## 外部 API

**无新外部 API**。本 feature 是调度内核内部优化(claim 链路批量化),不暴露 REST / MCP / CLI 端点。task_instance 的状态推进仍是内部 CAS,无对外契约变化。

## 可观测性契约（既有,`/actuator/prometheus`,双 master）

| 指标 | 状态 | 048 用途 |
|---|---|---|
| `scheduler_dispatch_latency` | 既有 | **SC-001 验证目标**:p99 < 5s(R9=135s) |
| `scheduler_round_duration` | 既有 | 核 single-round 耗时下降(估算 ~100-150ms → ~10-15ms) |
| `scheduler_claim_empty` | 既有 | 空 claim 占比(不变) |
| `dw.dispatch.queue.size` | 046 新增 | **不退化**核 ≤18(046 成果) |
| `dw.dispatch.queue.full.count` | 046 新增 | **不退化**核 = 0 |
| `dw.claim.batch.size`(可选) | 048 拟加 | 认领批量大小 gauge(Phase 2 决定是否加) |

## 事件契约（既有,不变）

- `publishTaskState(id, "DISPATCHED")`:认领成功事件。批量化后**逐个发**(阶段 3,每行一个),语义/订阅者不变(021-alert/022-data-quality 等下游不受影响)。
- `TaskSucceededEvent` / `TaskFailedEvent`:终态事件,本 feature 不触及。

## 约束契约（不变量,CLAUDE.md 硬规则）

认领批量化必须保持:
1. `SELECT … FOR UPDATE SKIP LOCKED`(单线程 claim,无并发竞争)
2. `WHERE state='WAITING'` 的 CAS 语义(批量 UPDATE 带,updateCount 核对)
3. 锁顺序 task→workflow(无跨表锁)
4. 状态事务内 + dispatch 事务外(批量 UPDATE 在 txTemplate 内,out.add 后提交 → dispatchAllAsync)
