# claim 速率深优化设计（claim-rate optimization）

> 基于 046 R9 实测结论的后续 feature。前置:046 已解除 dispatch 串行(`dispatch_queue.size` ≤18 不积压,早期 `dispatch_latency` ~128x 提升),瓶颈转移到 **claim 速率**。
>
> 本 feature 在 046 worktree 基础上连续做(不 merge 046),最后 046 + 本 feature 统一 merge main。

## 1. 背景 + 根因(046 R9 实测)

046 核心(dispatch 串行解除)达成后,distributed 双 master 1000wf `*/2s` 全负载(物化 ~600 inst/s)下:
- `dispatch_queue.size` 全程 ≤18,`queue.full=0` → **dispatch 解耦 ✓**
- 但 `dispatch_latency` 后期涨到 **135.7s**,WAITING 堆积 **67859**
- 诊断:`dispatch_latency` = WAITING 排队时间 = task 物化后**等 claim**(claim 速率 < 物化速率),与 dispatch 无关

**根因**:单线程 claim(`running` 护事务)每轮 `assign` 含**三个剩余 N+1**(046 T008 只消除了 contentOf/paramsJsonOf):

| N+1 | 代码位置 | 现状(50 行/轮) |
|---|---|---|
| ① crossCycleReady 查 workflow_dependency | `SchedulerKernel.java:338-346` 每行 1 查 | 50 次 |
| ② crossCycleReady 查 task_instance COUNT | `SchedulerKernel.java:352-355` 每行每 dep 1 查 | 50-150 次 |
| ③ casDispatch UPDATE | `stateMachine.casDispatch` (`:205`) 每行 1 UPDATE | 50 次 |

单轮 round duration ~100-150ms → claim 速率 ~333 inst/s < 物化 600 inst/s。

> `selectRunnable` 本身是 1 条重 SQL(NORMAL 含 NOT EXISTS 上游门 + 标量子查询),**不是 N+1**,本轮不动(YAGNI)。

## 2. Scope 决策

**纯批量化(消除 ①②③),claim 分片 defer**。

- 估算:三 N+1 消除后单轮 round ~10-15ms → claim 速率 ~3000+ inst/s(单线程),远超物化 600。
- claim 分片(多线程按 workflow_id hash)增死锁核对面(多线程 SKIP LOCKED + 分片级 running),YAGNI;批量化后单线程已远超需求,不够再加(下个 feature)。
- 死锁 4 不变量几乎不动(单线程 claim 保留)。

## 3. 方案:三个 N+1 批量化

### 3.1 crossCycleReady 批量化(① ②,大头收益 ~50-150ms → 2ms)

**现状**:`claimAndMark` 对每个 CRON normal row 调 `crossCycleReady(r)`,内部查 workflow_dependency + 每 dep 查 COUNT。

**批量化**(新增 `batchCrossCycleReady(List<Row> cronNormals) → Set<UUID readyIds>`):

1. `Row` 加 `workflowId` 字段(`selectRunnable` NORMAL SQL 加标量子查询 `(SELECT wi.workflow_id FROM workflow_instance wi WHERE wi.id=ti.workflow_instance_id) AS wfid`;TEST 不查跨周期,可不加)。
2. **批量查依赖**(1 SQL,`(workflow_id, node_id)` 去重):
   ```sql
   SELECT workflow_id, node_id, depend_node_id, date_offset, earliest_biz_date
   FROM workflow_dependency
   WHERE enabled=1 AND deleted=0 AND earliest_biz_date IS NOT NULL
     AND (workflow_id, node_id) IN ((?,?),(?,?),...)
   ```
3. **批量 COUNT**(1 SQL,Java 层先对每个 dep 算 `prevBizDate=offsetBizDate(bizDate, dateOffset)`,`(depend_node_id, prev_biz_date)` 去重):
   ```sql
   SELECT workflow_node_id, biz_date, COUNT(*)
   FROM task_instance
   WHERE state='SUCCESS' AND deleted=0
     AND (workflow_node_id, biz_date) IN ((?,?),(?,?),...)
   GROUP BY workflow_node_id, biz_date
   ```
4. Java 层组装 `Map<(nodeId,bizDate),count>`,对每个 CRON normal 校验所有 dep 就绪(含首周期 `earliestBizDate` 豁免,逻辑同现状 `:348-358`)。
5. `claimAndMark` 用 `readyIds` filter normals(替换单行 `.filter(this::crossCycleReady)`)。

**收益**:50-150 查询 → 2 查询。

### 3.2 casDispatch 批量化(③,重构 assign,50 UPDATE → 1 SQL)

**现状**:`assign` 每行 `stateMachine.casDispatch(r.id, WAITING→DISPATCHED, node, lease, attempt)` 单行 UPDATE。

**批量化**(三阶段):

- **阶段 1 place 所有行**(顺序贪心,`ns.used++` 乐观占槽,逻辑同现状 `:200-210`)→ 收集 `placements = [(id, node_code, lease, attempt)]`(place + 占槽不变,只是收集而非立即 UPDATE)。
- **阶段 2 批量 casDispatch**(1 SQL):
   ```sql
   UPDATE task_instance
   SET state='DISPATCHED', node_code=v.nc, lease=v.ls, attempt=v.at
   FROM (VALUES
     (?::uuid, ?::text, ?::timestamp, ?::int),
     ...
   ) AS v(id, nc, ls, at)
   WHERE task_instance.id = v.id
     AND task_instance.state = 'WAITING'
   RETURNING task_instance.id
   ```
- **阶段 3 后处理**:对 RETURNING 的每个 id(事务内行锁保护必全成功,RETURNING 防御)→ resolveContent(读 046 T008 缓存)+ `out.add(DispatchCommand)`;content 解析失败的单独 `casFailed(DISPATCHED→FAILED)`(保留现状 `:211-214` 语义);未返回的(理论无)回滚 `ns.used`。

**关键安全性**:`selectRunnable` 的 `FOR UPDATE SKIP LOCKED` 已锁住行 → 事务内批量 cas 必成功无竞争(语义同单行 CAS,只是合并提交)。`WHERE state='WAITING'` 保留 CAS 语义(并发被改的行不更新,虽事务内不会发生)。

**收益**:50 UPDATE → 1 SQL。

## 4. 死锁 4 不变量核对(CLAUDE.md 硬规则)

| 不变量 | 本 feature | 证据 |
|---|---|---|
| ① SKIP LOCKED claim | 不变(单线程 claim) | `selectRunnable` `FOR UPDATE SKIP LOCKED` 保留,`running` 仍护 claim 事务 |
| ② CAS 状态推进 | 批量 UPDATE 带 `WHERE state='WAITING'`,语义同单行 CAS | `RETURNING id` 拿成功集 |
| ③ 锁顺序 task→workflow | 不变(无新增跨表锁) | 仅 `task_instance` UPDATE,dependency/COUNT 是读 |
| ④ 状态事务内 + dispatch 事务外 | 批量 casDispatch 在 claim 事务内;dispatch 仍事务外 | `claimAndMark` 内 UPDATE → 事务提交 → `dispatchAllAsync`(046,不变) |

多 master distributed:每 master 独立 claim(各自 `running` + SKIP LOCKED),跨 master 由 task_instance 行锁保证唯一认领(不变)。

## 5. H2/PG 兼容(风险点,plan/research 验证)

- `UPDATE FROM VALUES … RETURNING`:PG 原生;**H2 2.x 兼容性需验证**。
- 备选链(递增复杂度):
  1. `CASE WHEN id THEN … END`(H2/PG 通用,但 50 行 × 3 字段 SQL 拼接复杂)
  2. H2 测试切 PG Testcontainers(项目已有 docker distributed,代价中等)
- `(workflow_id, node_id) IN ((?,?),(?))` 行构造器:PG/H2 都支持。

## 6. 量化估算

| 指标 | 046 R9(当前) | 本 feature(估算) |
|---|---|---|
| 单轮 round duration | ~100-150ms | ~10-15ms |
| claim 速率 | ~333 inst/s | ~3000+ inst/s |
| `dispatch_latency` p99(1000wf) | 135.7s | < 5s(目标) |
| WAITING 堆积 | 67859 | 不堆积(claim 跟上物化) |

## 7. 测试策略

- **单元**:批量 crossCycleReady 的 Java 层组装(deps Map + COUNT Map → 就绪判定,含首周期豁免/prevBizDate 偏移/多 dep);批量 casDispatch RETURNING → placements 映射(content 失败 casFailed 路径)。
- **集成(H2)**:claim 一批 normals(含 CRON 跨周期就绪/未就绪/首周期/手动非 CRON 直通),核对只就绪的进批量 cas + out;idempotency 无重复 dispatch。
- **真机(distributed 双 master 1000wf `*/2s`)**:R9 复测,核 `dispatch_latency` p99 < 5s + WAITING 不堆积 + `dispatch_queue.size` 仍 ≤18。
- **死锁不变量审计**:批量 UPDATE 无新锁路径(读 dependency/COUNT 无锁,UPDATE 仅 task_instance 行)。

## 8. 涉及代码

- `backend/dataweave-master/.../application/SchedulerKernel.java`:
  - `claimAndMark`(`:160`):normals filter 改用 `batchCrossCycleReady` 返回的 readyIds
  - 新增 `batchCrossCycleReady(List<Row>)`
  - `assign`(`:187`):重构为 place→批量 cas→RETURNING 后处理三阶段
  - `selectRunnable`(`:254`):NORMAL SQL 加 `wfid` 标量子查询;`Row` 加 `workflowId` 字段
  - `crossCycleReady(Row)`(`:331`):可保留(单行版,供测试/兜底)或移除
- `backend/dataweave-master/.../application/InstanceStateMachine.java`:`casDispatch` 单行版保留(测试/兜底),新增批量入口或在 SchedulerKernel 内直写批量 SQL(倾向后者,StateMachine 暴露批量 UPDATE)
- repo 层:无新增 Repository(批量 SQL 直写在 SchedulerKernel 内,同 selectRunnable 风格)
- `SchedulerMetrics`:可能加 `claim_batch_size` gauge(观测批量大小)

## 9. 排除方案

- **claim 分片(多线程 hash)**:YAGNI,批量化后单线程 ~3000+ inst/s 够;增死锁核对面。
- **selectRunnable 改 JOIN 消标量子查询**:非 N+1,次要,本轮不动。
- **聚合异步化(046 杠杆 7 defer)**:解除 claim 瓶颈后 SUCCESS 飙升,实测聚合是否变瓶颈再决(可能下个 feature)。
