# selectRunnable 优化设计（selectRunnable SQL optimization）

> 基于 048 R10 实测结论的后续 feature。前置:048 已合 main（b6723a4），批量化 crossCycleReady/casDispatch 但 **selectRunnable 重 SQL 在 WAITING 大量堆积时退化**，round_duration 反弹 49ms→1.58s，claim 仅 13 inst/s ≪ 物化 1000 inst/s，SC-002 未达成。
>
> 本 feature 在 dw-046 worktree 分支 `049-select-runnable-optimization`（基于 main b6723a4）。

## 1. 背景 + 根因（EXPLAIN 三轮隔离实测）

048 R10 后,distributed 双 master 1000wf `*/2s` 下 WAITING 涨到 53 万,claim 仅 13 inst/s。EXPLAIN 三轮隔离(每轮加一个成分)定位两个瓶颈:

| SQL 形态 | 耗时 | 计划 |
|---|---|---|
| `run_mode IN ('NORMAL','BACKFILL')` 简化(无 NOT EXISTS/子查询) | **244ms** | Seq Scan 全表 64 万行 + External Sort 25MB 磁盘 |
| `run_mode='NORMAL'` 简化 | **0.43ms** | Index Scan idx_task_instance_claim(**566x**) |
| + 标量子查询(task_def × 3 / workflow_instance × 4) | **1.037ms** | SubPlan 都 Index Scan PK,0.001-0.004ms × 50,快 |
| + NOT EXISTS 上游门 | **327-493ms** | **NOT EXISTS 是大头**(标量子查询都快) |

**两个瓶颈**:
1. `run_mode IN ('NORMAL','BACKFILL')` 打破 idx_task_instance_claim(state,run_mode,deleted,updated_at) 的索引有序性 → planner 选 Seq Scan + 磁盘 sort(BACKFILL 当前 0 行,IN 纯负开销)
2. NOT EXISTS 上游门(workflow_edge JOIN pred task_instance)→ 1ms → 327-493ms(FOR UPDATE + NOT EXISTS 交互劣化)

**推翻原假设**:标量子查询(task_def/workflow_instance)实际很快(PK Index Scan),**不需要 JOIN 重写**。

## 2. Scope 决策

**A 路线:Java 批量化(类比 048 batchCrossCycleReady)**。selectRunnable 去 NOT EXISTS + `run_mode='NORMAL'` 用索引;NOT EXISTS 上游门移 Java 层 `batchUpstreamReady`(批量查 workflow_edge + pred)。复用 048 成功模式,死锁面不动(SELECT 不锁),H2/PG 兼容(去 NOT EXISTS 方言)。

排除 B(纯 SQL JOIN anti-join):FOR UPDATE + 多表 JOIN 锁面增大,死锁核对面变;NOT EXISTS 慢的根因(FOR UPDATE 交互)未必靠 JOIN 解。
排除 C(schema 改 runnable_flag):跨 feature,重。

## 3. 方案

### 3.1 selectRunnable 重构(去 NOT EXISTS + run_mode='NORMAL')

- **NORMAL**:去 NOT EXISTS(移 Java)+ `run_mode='NORMAL'`(Index Scan 0.43ms)+ LIMIT 放大 `claimCandidateSize=200`(可配,留 filter 余量)+ 保留标量子查询(PK lookup 快)+ 保留 workflow_instance state 门(SubPlan 快)。
- **BACKFILL**:分开 `selectRunnable(BACKFILL)`,`run_mode='BACKFILL'` Index Scan(LIMIT 50,量少)。
- **TEST**:不变(已 Index Scan)。

### 3.2 batchUpstreamReady(Java 批量,类比 batchCrossCycleReady)

输入:候选 `List<Row>`(NORMAL,去 NOT EXISTS 后的 200 行)。输出:`Set<UUID readyIds>`。

1. **批量查 workflow_edge**:`SELECT to_node_id, from_node_id, strength FROM workflow_edge WHERE deleted=0 AND to_node_id IN (...)`(行构造器 IN,H2 T2 OK)→ `Map<toNodeId, List<(fromNodeId, strength)>>`。
2. **批量查 pred task_instance**:`SELECT workflow_instance_id, workflow_node_id, state FROM task_instance WHERE deleted=0 AND state IN ('SUCCESS','FAILED') AND (workflow_instance_id, workflow_node_id) IN (...)` → `Map<(wi,node), Set<state>>`。
3. **Java filter**:每行 ti 检查其 to_node_id=ti.node 的所有 edge 的 pred(from_node_id):
   - 强依赖(`strength='STRONG'` 或默认):pred 必须 SUCCESS
   - 弱依赖(`strength='WEAK'`):pred 必须 SUCCESS 或 FAILED(自然跑完)
   - 任一上游不满足 → 不 ready;无 edge → 直通 ready(单节点 wf)
   - 语义同现状 NOT EXISTS(`:290-298`)

### 3.3 claimAndMark 串联

```
tests = selectRunnable(TEST) → assign
normalCandidates = selectRunnable(NORMAL, 200)
upstreamReady = batchUpstreamReady(normalCandidates)
crossCycleReady = batchCrossCycleReady(normalCandidates)  // 048 已有
readyIds = upstreamReady ∩ crossCycleReady
normals = normalCandidates.filter(readyIds).sort(priority).take(claimBatchSize=50) → assign
backfills = selectRunnable(BACKFILL, 50) → assign
```

**LIMIT 200 余量**:cron-stress 单节点 wf(无上游 edge)batchUpstreamReady 全通过,200 → take 50 充足。多节点 wf 上游未就绪被 filter,极端情况 200 候选 filter 后 < 50(加大 LIMIT 或多轮,plan 阶段定)。

## 4. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 本 feature | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | selectRunnable `FOR UPDATE SKIP LOCKED` 保留(锁候选 200 行) | NORMAL/BACKFILL/TEST 都带 |
| ② CAS 状态推进 | 不变(casDispatchBatch 048) | WHERE state='WAITING' |
| ③ 锁顺序 task→workflow | 不变;`batchUpstreamReady` 是 SELECT(不锁) | 仅 task_instance 行锁,无跨表锁 |
| ④ 状态事务内 + dispatch 事务外 | 不变 | claimAndMark 内,048 已验 |

多 master distributed:每 master 独立 selectRunnable SKIP LOCKED,无竞争(不变)。

## 5. H2/PG 兼容

- 去 NOT EXISTS(PG 特有慢,FOR UPDATE 交互)→ selectRunnable 仅 Index Scan + 标量子查询(H2/PG 通用)
- 行构造器 IN(`(col,col) IN ((?,?),(?))`):H2 T2 实测 OK(048 验证)
- batchUpstreamReady 的批量 SELECT:H2/PG 通用

## 6. 量化(EXPLAIN 实测背书)

| 指标 | 048 R10(当前) | 本 feature(估算) |
|---|---|---|
| selectRunnable 耗时 | 327-493ms(NOT EXISTS) | **~1ms**(Index Scan + 子查询) |
| 单轮 round duration | 128ms(avg)/1.58s(近期) | **~7-10ms** |
| claim 速率 | 13 inst/s | **~5000-7000 inst/s** |
| `dispatch_latency` p99(1000wf) | 端到端未达成(WAITING 堆积) | < 5s(目标) |
| WAITING(1000wf) | 53 万堆积 | 不堆积 |

round ~7-10ms = selectRunnable 1ms + batchUpstreamReady 2ms + batchCrossCycleReady 2ms + casDispatchBatch 2ms + place/other 1ms。rounds/s ~100-140,batch 50 → claim ~5000-7000 inst/s ≫ 物化 1000。

## 7. 备选方向:冗余(denormalization,用户提示)

若未来 batchUpstreamReady/batchCrossCycleReady 等批量查询仍出现 task_def 查询瓶颈,可把 task_def 字段(`timeout_sec`/`type`/`datasource_id`)在实例物化时**冗余到 task_instance**(落盘列),消除 claim 期跨表查 task_def。

- **当前不采用**:EXPLAIN 显示标量子查询(task_instance→task_def PK)实际很快(1ms,Index Scan PK),非瓶颈。
- **触发条件**:若 049 落地后 task_def 查询成新瓶颈(EXPLAIN 复测),再考虑冗余(schema 改 + 物化期落盘 + task_def 变更同步)。
- **代价**:冗余字段需在 task_def 变更(发布新版本)时同步到 task_instance(或按版本冗余),增加物化逻辑复杂度。

同理 workflow_instance 字段(`priority`/`trigger_type`/`workflow_id`)也可冗余(若成瓶颈)。

## 8. 涉及代码

- `backend/dataweave-master/.../application/SchedulerKernel.java`:
  - `selectRunnable(false)` NORMAL(`:282-300`):去 NOT EXISTS,`run_mode='NORMAL'`,LIMIT `claimCandidateSize`
  - 新增 `selectRunnable` BACKFILL 分支(或 `runMode` 参数)
  - 新增 `batchUpstreamReady(List<Row>) → Set<UUID>`
  - `claimAndMark`(`:160`):串联 batchUpstreamReady ∩ batchCrossCycleReady + BACKFILL 分支
  - `Row` 已 package-private(048),复用
- 配置:`application.yml` 加 `scheduler.claim-candidate-size: 200`(可配)
- 不改 InstanceStateMachine(casDispatchBatch 048 已有)

## 9. 排除方案

- **B 纯 SQL JOIN anti-join**:FOR UPDATE + 多表 JOIN 锁面增大,死锁核对面变;NOT EXISTS 慢根因(FOR UPDATE 交互)未必解;H2 anti-join 方言风险。
- **C schema 改 runnable_flag**:实例物化时标 runnable,claim 期只查 flag。跨 feature,重;物化期需重算上游门(同 Java 逻辑),且上游状态变化需重算 flag(事件驱动复杂)。
- **冗余(本 feature 不采用)**:见 §7,当前 task_def 子查询快,非瓶颈。
