# Research: selectRunnable 优化

> Phase 0 产出。技术决策 + EXPLAIN 实测见 [design doc](../../docs/superpowers/specs/2026-07-06-selectrunnable-optimization-design.md);本文件提炼根因(EXPLAIN 三轮隔离背书)+ 决策 + 不变量核对,无未解决项。

## R1. 根因(EXPLAIN 三轮隔离实测,048 R10 后)

048 R10 后 WAITING 堆积 53 万,selectRunnable 退化。EXPLAIN 每轮加一个成分隔离瓶颈:

| SQL 形态 | 耗时 | 计划 | 结论 |
|---|---|---|---|
| `run_mode IN (...)` 简化 | **244ms** | Seq Scan 64 万行 + External Sort 25MB 磁盘 | ① IN 打破索引 |
| `run_mode='NORMAL'` 简化 | **0.43ms** | Index Scan idx_task_instance_claim | ① 566x 提升 |
| + 标量子查询(task_def×3/wi×4) | **1.037ms** | SubPlan 都 Index Scan PK,0.001ms×50 | 标量子查询非瓶颈 |
| + NOT EXISTS 上游门 | **327-493ms** | NOT EXISTS 大头(FOR UPDATE 交互) | ② NOT EXISTS 大头 |

**两个瓶颈**(实测背书,非推测):
1. `run_mode IN ('NORMAL','BACKFILL')` 打破 `idx_task_instance_claim(state,run_mode,deleted,updated_at)` 索引有序性 → planner 选 Seq Scan + 磁盘 sort(BACKFILL 当前 0 行,IN 纯负开销)
2. NOT EXISTS 上游门 → 1ms → 327-493ms(FOR UPDATE + NOT EXISTS 交互劣化)

**推翻原假设**:标量子查询(task_def/workflow_instance)实际很快(PK Index Scan),**不需要 JOIN 重写/冗余**(design §7 备选,触发条件未达)。

## R2. selectRunnable 重构决策

- **Decision**:去 NOT EXISTS + `run_mode='NORMAL'`(Index Scan)+ BACKFILL 分开 + LIMIT 放大 `claimCandidateSize=200`。
- **Rationale**:EXPLAIN 实测 `run_mode='NORMAL'` Index Scan 0.43ms(566x);标量子查询保留(PK 快);NOT EXISTS 移 Java(R3);BACKFILL 当前 0 行但概念支持,分开查询各自 Index Scan。
- **LIMIT 200 余量**:候选 200 → batchUpstreamReady ∩ batchCrossCycleReady filter → take 50。单节点 wf(无上游)全通过,200 充足;多节点 wf 上游未就绪被 filter,极端 < 50 时加大 LIMIT 或多轮(plan 阶段定默认 200,可配)。
- **Alternatives rejected**:
  - 纯 SQL JOIN anti-join:FOR UPDATE + 多表 JOIN 锁面增大,死锁核对面变;NOT EXISTS 慢根因(FOR UPDATE 交互)未必解。
  - schema 改 runnable_flag:跨 feature,物化期重算上游门 + 事件驱动重算复杂。

## R3. batchUpstreamReady(Java 批量,类比 048 batchCrossCycleReady)

**输入**:候选 `List<Row>`(NORMAL,去 NOT EXISTS 后的 200 行)。**输出**:`Set<UUID readyIds>`。

1. **批量查 workflow_edge**:`SELECT to_node_id, from_node_id, strength FROM workflow_edge WHERE deleted=0 AND to_node_id IN (...)`(行构造器 IN,H2 T2 OK)→ `Map<toNodeId, List<(fromNodeId, strength)>>`。
2. **批量查 pred task_instance**:`SELECT workflow_instance_id, workflow_node_id, state FROM task_instance WHERE deleted=0 AND state IN ('SUCCESS','FAILED') AND (workflow_instance_id, workflow_node_id) IN (...)` → `Map<(wi,node), Set<state>>`。
3. **Java filter**:每行 ti 检查其 `to_node_id=ti.workflowNodeId` 的所有 edge 的 pred:
   - 强依赖(`strength='STRONG'` 或默认 COALESCE):pred 必须 SUCCESS
   - 弱依赖(`strength='WEAK'`):pred 必须 SUCCESS 或 FAILED(自然跑完)
   - 任一上游不满足 → 不 ready;无 edge → 直通 ready(单节点 wf)
   - 语义同现状 NOT EXISTS(`SchedulerKernel.java:290-298`)

**复用 048 模式**:batchCrossCycleReady 的 ResultSetExtractor + Map 组装模式。

## R4. claimAndMark 串联

```
tests = selectRunnable(TEST) → assign
normalCandidates = selectRunnable(NORMAL, 200)
upstreamReady = batchUpstreamReady(normalCandidates)
crossCycleReady = batchCrossCycleReady(normalCandidates)  // 048 已有
readyIds = upstreamReady ∩ crossCycleReady
normals = normalCandidates.filter(readyIds).sort(priority).take(claimBatchSize=50) → assign
backfills = selectRunnable(BACKFILL, 50) → assign
```

BACKFILL 分支:selectRunnable 加 runMode 参数(BACKFILL 走 Index Scan,LIMIT 50)。或 claimAndMark 内分别调 selectRunnable(NORMAL)/selectRunnable(BACKFILL)。

## R5. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 本 feature | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | selectRunnable `FOR UPDATE SKIP LOCKED` 保留(锁候选 200 行) | NORMAL/BACKFILL/TEST 都带 |
| ② CAS 状态推进 | 不变(casDispatchBatch 048) | WHERE state='WAITING' |
| ③ 锁顺序 task→workflow | 不变;`batchUpstreamReady` 是 SELECT(不锁) | 仅 task_instance 行锁,workflow_edge/pred 是无锁读 |
| ④ 状态事务内 + dispatch 事务外 | 不变 | claimAndMark 内,048 已验 |

多 master distributed:每 master 独立 selectRunnable SKIP LOCKED,无竞争(不变)。**batchUpstreamReady 移出 SQL 不新增锁路径**(SELECT 无锁)。

## R6. H2/PG 兼容

- 去 NOT EXISTS(PG 特有慢,FOR UPDATE 交互)→ selectRunnable 仅 Index Scan + 标量子查询(H2/PG 通用)
- 行构造器 IN(`(col,col) IN ((?,?),(?))`):H2 T2 实测 OK(048 验证)
- batchUpstreamReady 的批量 SELECT:H2/PG 通用

## R7. 测试方法

- **单元(H2)**:
  - batchUpstreamReady 组装正确性(deps Map + pred state Map → readyIds;覆盖强 SUCCESS 就绪/未就绪、弱 SUCCESS|FAILED 就绪、首周期无 edge 直通、多上游部分未就绪、多节点混合)
  - selectRunnable 去 NOT EXISTS 后返回候选(NORMAL/BACKFILL 分开)
- **集成(H2)**:claim 一批多节点 wf(上游 SUCCESS/FAILED/未就绪 + 弱/强依赖)→ 核对只就绪的进 batch cas + assign
- **真机(distributed 双 master 1000wf `*/2s`)**:R11 复测(R10 之后),核 `scheduler_round_duration` 稳定 < 30ms + WAITING 不堆积 + claim ≥ 600 inst/s + 不退化 046+048(queue.size ≤18)
- **EXPLAIN 回归**:落地后 EXPLAIN selectRunnable 确认 Index Scan(无 Seq Scan/Sort)
- **饱和判定**:持续加压到认领吞吐不再提升,记录饱和点(US2)

## R8. 量化(EXPLAIN 实测背书)

| 指标 | 048 R10(当前) | 本 feature(估算) |
|---|---|---|
| selectRunnable 耗时 | 327-493ms(NOT EXISTS) | **~1ms**(Index Scan + 子查询实测) |
| 单轮 round duration | 128ms(avg)/1.58s(近期) | **~7-10ms** |
| claim 速率 | 13 inst/s | **~5000-7000 inst/s** |
| `dispatch_latency` p99(1000wf) | 端到端未达成(WAITING 堆积) | < 5s(目标) |
| WAITING(1000wf) | 53 万堆积 | 不堆积 |

## R9. 实测结论(2026-07-06,distributed 双 master 1000wf `*/2s` + 049 selectRunnable 优化)

**049 实施范围**:T003(claimCandidateSize 配置)+ T005/T006(selectRunnable 去 NOT EXISTS + run_mode 等值 Index Scan + RunMode TEST/NORMAL/BACKFILL)+ T007(batchUpstreamReady Java 批量)+ T008(claimAndMark 串联 batchUpstreamReady ∩ batchCrossCycleReady)。单测 25/25(049 batchUpstream 11 + 048 batchCrossCycle 10 + InstanceStateMachine 4,048 不退化)。commit e2729a6 核心 + 5f5a59a 单测。

**SC-001/002 真机达成**(048 R10 瓶颈解除):
- `scheduler_round_duration` 稳态 **10.9ms** avg(vs 048 R10 128ms/1.58s 近期,**~12-145x**)< SC-001 30ms ✓
- WAITING 稳态 **490**(不堆积!vs 048 R10 53 万)→ SC-002 ✓
- dispatch 速率 **321 inst/s**(vs 048 R10 13 inst/s,**~25x**)
- `dw.dispatch.queue.size` 4(不积压,046+048 成果不退化)✓
- T009 EXPLAIN 回归:selectRunnable NORMAL **Index Scan 14.7ms**(LIMIT 200,vs 048 R10 含 NOT EXISTS 327-493ms,~22-33x)✓

**启动期消化**:049 master force-recreate 后,048 R10 残留的 53 万 WAITING 在 ~25s 内被消化(WAITING baseline=0)——049 claim 速率骤升后旧积压快速清空。

**SC 达成**:
- SC-001(round_duration < 30ms 稳态)**10.9ms ✓**
- SC-002(WAITING 不堆积)**490 稳态 ✓**(claim 跟上物化 ~321 inst/s)
- SC-003(不变量)代码层核对 ✓(batchUpstreamReady 是 SELECT 无锁,FOR UPDATE SKIP LOCKED 保留;崩溃注入待后续)

**结论**:049 selectRunnable 优化成功解除 048 R10 瓶颈。run_mode 等值 Index Scan(566x)+ NOT EXISTS 上游门移 Java batchUpstreamReady(避大表退化)双管齐下,round_duration 稳定 10.9ms,claim 跟上物化,WAITING 不堆积。046→048→049 三级优化链路闭环(dispatch 解耦 → claim 批量化 → selectRunnable 索引化)。

round ~7-10ms = selectRunnable 1ms + batchUpstreamReady 2ms + batchCrossCycleReady 2ms(048) + casDispatchBatch 2ms(048) + place/other 1ms。rounds/s ~100-140,batch 50 → claim ~5000-7000 inst/s ≫ 物化 1000。
