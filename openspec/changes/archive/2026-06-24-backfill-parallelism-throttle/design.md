## Context

补数据 M1(data-ops-center)生成侧把整个日期区间的 BACKFILL 实例一次性插入 WAITING,`parallelism` 仅落库不强制。`SchedulerKernel` 认领门(`SchedulerKernel.java:262`)当前是:

```sql
WHERE ti.state='WAITING' AND ti.run_mode IN ('NORMAL','BACKFILL') AND ti.deleted=0
  AND COALESCE((SELECT td.frozen FROM task_def td WHERE td.id=ti.task_id),0)=0
  ... FOR UPDATE SKIP LOCKED
```

`frozen` 守卫已是「旁路标志不污染 state」的先例。本设计沿用同一模式落地 bizDate 粒度节流。

**关键约束(为何 M1 推迟):** `parallelism` 的正确语义是 bizDate 粒度,不是实例粒度。朴素的「认领时按 backfill_run 统计在跑实例数,≥N 就不认领」对**工作流目标补数据是错的**——一个 bizDate 的 DAG 有多个本应并行的节点,按实例数限到 N 会把单条 DAG 内部也串行化,破坏工作流补数语义。正确做法必须以 bizDate 为节流单位,并需要一个「完成即晋升下一批」的组件。

**死锁不变量(硬约束,不可破坏):** ① 认领只用 SKIP LOCKED 永不等待行锁;② 状态推进一律乐观 CAS;③ 固定锁顺序 task→workflow;④ 事务内只落状态,副作用在提交后。

## Goals / Non-Goals

**Goals:**
- `parallelism=N` 严格语义:同时最多 N 个 bizDate 链可被认领/运行;不串行化单 bizDate 的 DAG。
- 认领热路径**零新增分支**:仅加一个与 `frozen` 同构的 `backfill_held=0` 守卫,四条死锁不变量原样保持。
- 完成即晋升:活跃 bizDate 链跑完即补足配额;事件驱动 + 周期 sweep 兜底。
- `parallelism ≥ bizDate 总数` 时无开销退化为 M1 行为。

**Non-Goals:**
- 不做跨 backfill_run 的全局补数据配额(只在单 run 内节流);全局容量仍由 worker 槽位自然约束。
- 不做动态调整运行中 run 的 parallelism(M2.1 可加 PATCH,本期不做)。
- 不改 PolicyEngine 闸门链路、AG-UI 事件 schema、既有 REST 端点签名(仅响应体加字段)。
- 不引入优先级/抢占:held bizDate 严格按日期升序晋升。

## Decisions

### D1: `backfill_held` 标志列,而非新增实例状态

held 用 `task_instance.backfill_held SMALLINT DEFAULT 0` 表达,实例 `state` 仍为 WAITING。
- **为何不新增 HELD 状态:** 新状态会波及 InstanceStateMachine、状态展示、终态判定、SSE 着色等几十处,爆炸半径大且易漏。held 是「能否被认领」的旁路条件,不是生命周期阶段——实例确实在 WAITING(等待被放行),如实反映。
- **认领守卫:** claim SQL 加 `AND ti.backfill_held=0`。直接列比 `frozen` 的子查询更省。配 `idx_task_instance_backfill_held`(部分索引 `WHERE backfill_held=1`,仅持有集,极小)。
- **替代(否决):** 推迟 INSERT(held bizDate 不入库,晋升时才生成)——会让 `total` 进度在跑完前不准、run 详情看不到全貌,且生成逻辑要拆成两段,更复杂。一次性插入 + held 标志最简单且进度完整。

### D2: 生成侧配额分配(INSERT 时即定 held,race-free —— 实现采用)

`BackfillService.submitBackfill` 展开 bizDate 升序列表后:前 `parallelism` 个 bizDate 的实例 `held=0`,其余 `held=1`。
- 工作流目标:同一 bizDate 的整张 DAG 共享同一 held 值(按 bizDate 而非按节点)。
- **held 在 INSERT 时即定**:`held` 经两个 backfill trigger 入口(`triggerBackfillTaskRun`/工作流 `trigger` 重载,均只被 BackfillService 调用)透传,在 `new TaskInstance()` 处 `setBackfillHeld(held)`。
- **为何不用「先插入(held=0)再批量 UPDATE」**(初版设计的「最简做法」):trigger 内部 `wake()` 会立即唤醒调度,「插入后→UPDATE held 前」存在被抢认领的竞态,违反严格 N。INSERT 时即定 held 根除该窗口。代价仅为给两个内部 trigger 入口加一个 `int held` 参数(调用方唯一,改动可控)。
- `parallelism ≥ |bizDates|`:全部 `held=0`,退化 M1,无额外开销。

### D3: 晋升器 BackfillPromoter —— WAKE 订阅 + @Scheduled sweep,per-run 行锁保证严格 N

新增 `BackfillPromoter`(application 层):
- **触发:** ① 订阅既有 `WAKE_CHANNEL`(实例完成/释放槽位本就发 WAKE)→ 每次 wake 跑一次 `promoteEligible()`;② `@Scheduled`(默认 15s)兜底 sweep,防漏事件(参照 `LeaseReaper`)。二者调同一幂等方法。
- **promoteEligible() 逻辑(每个 RUNNING backfill_run):**
  1. `SELECT … FROM backfill_run WHERE id=? AND state='RUNNING' FOR UPDATE`(**per-run 行锁**,串行化同一 run 的并发晋升)。
  2. 重算 `activeDates = COUNT(DISTINCT biz_date)`,条件 `backfill_run_id=? AND backfill_held=0 AND state NOT IN ('SUCCESS','FAILED')`。
  3. `while activeDates < parallelism`:取最小 held bizDate(`MIN(biz_date) WHERE held=1`),`UPDATE task_instance SET backfill_held=0 WHERE backfill_run_id=? AND biz_date=? AND backfill_held=1`,`activeDates++`;无 held bizDate 则 break。
  4. 提交事务后 `eventBus.publish(WAKE_CHANNEL, …)` 触发认领(**invariant ④:副作用在提交后**)。
- **为何 per-run 行锁而非纯乐观 CAS:** 两个并发 wake 各自算 `active=1<2`,若分别晋升**不同**的 held bizDate,会把 active 推到 3 > parallelism,违反严格 N。per-run `FOR UPDATE` 串行化晋升决策即可根治。
- **不违反死锁不变量:** 该行锁只锁 `backfill_run`(认领路径从不锁此表),单表单行无环;晋升在认领事务**之外**;锁顺序 task→workflow 不涉及。文档化:promoter 与 claim 无共享锁、无交叉等待。

### D4: run 视图暴露 activeDates/heldDates

`OpsContracts.BackfillRunView` + api `BackfillRun` dto 加 `activeDates`/`heldDates`(由 `toView` 聚合查询派生)。响应体加字段向后兼容。前端 run 卡片展示「2 并发 / 3 待晋升」。

### D5: 收敛与边界

- run 派生 state 复用既有 `deriveState`:`activeDates=0 且 heldDates=0` 时全终态 → SUCCESS/FAILED/PARTIAL。
- held bizDate 若其任务被 `frozen`:两守卫叠加(`held=0 且 frozen=0` 才认领),晋升后仍受 frozen 约束,语义正确。
- 失败重跑:补数据实例的 rerun 不改 held(已是 held=0 的活跃 bizDate 才可能失败);held bizDate 不会被 rerun(尚未认领过)。

## Risks / Trade-offs

- **[per-run 行锁串行化晋升]** → 仅锁单 run 单行,且晋升是轻量 UPDATE,持锁极短;不同 run 互不阻塞;与高频认领路径零交叉。可接受。
- **[sweep 与事件双触发重复跑]** → `promoteEligible` 幂等(CAS + 行锁重算),重复跑至多多一次空转查询;15s sweep 频率低。
- **[held 实例长期 WAITING 占用列表视觉]** → run 视图用 `heldDates` 明确区分「待晋升」,前端文案如实展示,不误导为「卡住」。
- **[大区间(365 天)held 集索引]** → 部分索引仅 `held=1` 行,持有集随晋升单调缩小;`COUNT(DISTINCT biz_date)` 有 `(backfill_run_id, biz_date)` 既有索引支撑。

## Migration Plan

1. schema.sql 加 `backfill_held` 列(`DEFAULT 0`)+ 部分索引;启动 drop-create,无存量数据迁移问题(开发期)。生产升级:`ALTER TABLE … ADD COLUMN backfill_held SMALLINT DEFAULT 0`,既有实例默认 0(可认领),向后兼容。
2. 先上 master(claim 守卫 + BackfillService 配额 + Promoter),再上 api/前端字段。守卫对存量 NORMAL 实例无影响(它们 held=0)。
3. **回滚:** 移除 Promoter + claim 守卫即回到 M1(held 列保留无害,全 0 等价无节流)。无破坏性回滚。

## Open Questions

- **晋升粒度是否需要「半批晋升」**(配额=2,一个 bizDate 完成时只补 1 个)——当前设计每次 wake 补满到 parallelism,天然就是增量补足,无需特殊处理。
- **运行中动态调 parallelism**(调大立即多晋升 / 调小如何收缩已在跑的)——收缩语义复杂(不可能杀掉已认领实例),留 M2.1,本期 parallelism 提交后不可变。
- **跨 run 全局补数据配额**(避免多个 backfill_run 叠加打满集群)——本期靠 worker 槽位自然约束,全局配额留后续。
