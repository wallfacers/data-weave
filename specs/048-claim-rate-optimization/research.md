# Research: claim 速率深优化

> Phase 0 产出。技术决策详情见 [design doc](../../docs/superpowers/specs/2026-07-05-claim-rate-optimization-design.md);本文件提炼 H2 兼容实测 + 批量化形态选型 + 不变量核对,无未解决项。

## R1. H2 2.4.240 批量 SQL 兼容性实测(关键)

design doc 标的最大风险点:`UPDATE FROM VALUES … RETURNING` 的 H2 兼容性。用 `java -cp h2-2.4.240.jar` 直连 H2 内存库(`MODE=PostgreSQL`)实测 5 种语法,结果:

| 测试 | 语法 | H2 2.4.240 | 结论 |
|---|---|---|---|
| T1 | `UPDATE … FROM (VALUES …) … RETURNING id` | **FAIL**(语法错,RETURNING 在 UPDATE FROM 下) | H2 不支持此组合 |
| T5 | `UPDATE … FROM (VALUES …) …`(无 RETURNING) | **OK**(updateCount=2) | H2 支持 UPDATE FROM VALUES 本体 |
| T2 | `WHERE (col,col) IN ((?,?),(?,?))` 行构造器 | **OK**(RETURNING 2 rows) | H2 支持行构造器 IN |
| T3 | `MERGE INTO … KEY(id) VALUES(…)` | **OK**(updateCount=1) | H2 MERGE 可用(备选) |
| T4 | `CASE id WHEN … THEN … END` 单 SQL 多字段 | **OK**(updateCount=2) | H2/PG 通用 |

**核心结论**:`UPDATE FROM VALUES` 本体 H2/PG 双兼容,但 **+ `RETURNING` 是 H2 不兼容点**。PG 原生支持 T1,故生产无虞;但测试用 H2 跑不通 T1。

## R2. casDispatch 批量化形态决策

- **Decision**:`UPDATE FROM VALUES` **去掉 RETURNING**,靠 `updateCount` + 事务内行锁保护。
  ```sql
  UPDATE task_instance
  SET state='DISPATCHED', worker_node_code=v.nc, lease_expire_at=v.ls,
      attempt=v.at, updated_at=?
  FROM (VALUES (?,?,?,?),(?,?,?,?),…) AS v(id, nc, ls, at)
  WHERE task_instance.id = v.id
    AND task_instance.state = 'WAITING'
    AND task_instance.deleted = 0
  ```
- **Rationale**:
  1. `selectRunnable` 的 `FOR UPDATE SKIP LOCKED` 已在事务内锁住行 → `state` 必为 `WAITING`,`UPDATE` 必全成功 → `updateCount == placements.size()`(防御性核对,理论无失败行)。
  2. 故**不需要 RETURNING 拿成功集**——所有 placements 全部成功。
  3. T5 验证 H2 支持,PG 原生支持 → 双兼容。
  4. SQL 简洁(VALUES 列表,各字段不同)。
- **Alternatives rejected**:
  - `UPDATE FROM VALUES + RETURNING`(T1):H2 FAIL,需测试切 Testcontainers PG,增复杂度。
  - `CASE WHEN`(T4):H2/PG 通用但 SQL 拼接复杂(50 行 × 3 字段),可读性差。
  - `MERGE USING VALUES`(T3):H2 MERGE 语义(`KEY(id)`)与 PG MERGE 语法不同,跨库不一致。
  - `JdbcTemplate.batchUpdate`(50 条 UPDATE 1 往返):兼容性最好但仍是 50 次 SQL 执行,round duration 较 FROM VALUES 高(~10-15ms vs ~2ms);作为终极兜底。

## R3. crossCycleReady 批量化(① ②)

- **行构造器 IN**(T2 OK):`WHERE (workflow_id, node_id) IN ((?,?),(?,?),…)` 与 `(workflow_node_id, biz_date) IN ((?,?),…)` 双兼容。
- **Row 加 `workflowId` 字段**:selectRunnable NORMAL SQL 加标量子查询 `(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wfid`(TEST 不查跨周期,可不加)。
- **批量查依赖**(1 SQL):本轮 CRON normals 的 `(workflowId, nodeId)` 去重 → 一次查 workflow_dependency。
- **批量 COUNT**(1 SQL):Java 层算 `prevBizDate=offsetBizDate(bizDate, dateOffset)` → `(dependNodeId, prevBizDate)` 去重 → `SELECT workflow_node_id, biz_date, COUNT(*) … GROUP BY` → Map 校验。
- **保留**:首周期豁免(`bizDate < earliestBizDate` 跳过);单行 `crossCycleReady(Row)` 可留作兜底/测试。

## R4. assign 重构(三阶段,行锁保护无竞争)

- **阶段 1 place**:对 tests + readyNormals 逐个 `policy.place(r, avail)`(顺序贪心,`ns.used++` 乐观占槽)→ 收集 `placements = [(id, workerNodeCode, leaseExpireAt, attempt)]`(place + 占槽逻辑同现状 `:200-210`,只收集不立即 UPDATE)。
- **阶段 2 批量 casDispatch**(R2 的 1 SQL):`updated_at` 统一 now。`updateCount == placements.size()` 防御核对(不符则 log warn,理论不发生)。
- **阶段 3 后处理**:对每个 placement → `resolveContentSafely`(读 046 T008 缓存)+ `publishTaskState(id, "DISPATCHED")` + `out.add(DispatchCommand)`;content 解析失败的 `casFailed(DISPATCHED→FAILED)`(保留现状 `:211-214` 语义)。
- **关键安全性**:FOR UPDATE SKIP LOCKED 锁行 → 事务内无并发竞争 → 批量 cas 必全成功(语义同单行 CAS,合并提交)。`WHERE state='WAITING'` 保留 CAS 语义。

## R5. publishTaskState 事件保留

现状 `casDispatch` 单行成功后 `publishTaskState(id, "DISPATCHED")`(InstanceStateMachine `:67`)。批量化后该事件发布移到阶段 3 SchedulerKernel 侧逐个发(或 InstanceStateMachine 暴露批量入口内发)。**事件语义不变**(每行 DISPATCHED 一个事件),保 021-alert/022 等下游订阅不变。

## R6. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 本 feature | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | 不变(单线程 claim) | selectRunnable `FOR UPDATE SKIP LOCKED` 保留,`running` 仍护 claim 事务 |
| ② CAS 状态推进 | 批量 UPDATE 带 `WHERE state='WAITING'`,语义同单行 CAS | `updateCount` 核对(行锁保护必全成功) |
| ③ 锁顺序 task→workflow | 不变(无新增跨表锁) | 仅 task_instance UPDATE;dependency/COUNT 是无锁读 |
| ④ 状态事务内 + dispatch 事务外 | 批量 casDispatch 在 claim 事务内;dispatch 仍事务外 | claimAndMark 内 UPDATE → 事务提交 → dispatchAllAsync(046,不变) |

多 master distributed:每 master 独立 claim(各自 `running` + SKIP LOCKED),跨 master 由 task_instance 行锁保证唯一认领(不变)。

## R7. 测试方法

- **单元(H2)**:
  - 批量 crossCycleReady 组装正确性(deps Map + COUNT Map → 就绪判定;含首周期豁免/prevBizDate 偏移/多 dep/非 CRON 直通)
  - 批量 casDispatch:一批 placements → UPDATE → updateCount == size;RETURNING-less 逻辑核对
  - assign 三阶段:place 占槽 + 批量 cas + content 失败 casFailed 路径
- **集成(H2)**:claim 一批 normals(含 CRON 跨周期就绪/未就绪/首周期/手动非 CRON 直通/占位符失败)→ 核对只就绪的进批量 cas + out + 事件;idempotency 无重复。
- **真机(distributed 双 master 1000wf `*/2s`)**:R10 复测(R9 之后),核 `scheduler_dispatch_latency` p99 < 5s + WAITING 不堆积 + `dw.dispatch.queue.size` ≤18(046 成果不退化)+ 不变量(崩溃注入)。
- **饱和判定**:持续加压到认领吞吐不再提升,记录饱和点与最先饱和资源(US2)。

## R8. 量化估算

| 指标 | 046 R9(当前) | 本 feature(估算) |
|---|---|---|
| 单轮 round duration | ~100-150ms | ~10-15ms |
| claim 速率 | ~333 inst/s | ~3000+ inst/s |
| `dispatch_latency` p99(1000wf) | 135.7s | < 5s(目标) |
| WAITING 堆积(1000wf) | 67859 | 不堆积 |
| 每轮查询数 | ~100-150(crossCycle) + 50(cas) | 2 + 1 + selectRunnable 2 = ~5 |
