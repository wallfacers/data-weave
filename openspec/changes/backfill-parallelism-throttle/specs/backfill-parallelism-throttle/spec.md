## ADDED Requirements

### Requirement: bizDate 粒度并发节流语义

补数据 `parallelism=N` SHALL 严格表示「同时最多 N 个 bizDate 链处于可被认领/运行状态」,而非「最多 N 个实例」。节流粒度 MUST 为 bizDate(整批同日实例),使得单个 bizDate 的工作流 DAG 内部上下游节点 MUST NOT 因节流被串行化(同日 DAG 的并行节点照常并发,仅复用既有就绪门约束)。

#### Scenario: 工作流目标补数据不串行化同日 DAG
- **WHEN** 对含并行分支的工作流以 `parallelism=1` 发起 3 天补数据
- **THEN** 同一时刻仅 1 个 bizDate 的 DAG 处于可认领状态,但该 bizDate 内本应并行的节点照常并发(不被节流串行化)

#### Scenario: 任务目标补数据按 bizDate 限并发
- **WHEN** 对单任务以 `parallelism=2` 发起 5 天补数据
- **THEN** 同时最多 2 个 bizDate 的实例可被认领,其余 3 个 bizDate 等待晋升

#### Scenario: parallelism 不小于 bizDate 总数时退化为无节流
- **WHEN** 以 `parallelism=10` 发起 3 天补数据
- **THEN** 全部 3 个 bizDate 直接可认领(`held=0`),行为与 M1 一致,无任何实例被持有

### Requirement: held 标志与认领守卫

补数据实例 SHALL 携带 `backfill_held` 标志:生成时按 bizDate 升序,前 `parallelism` 个 bizDate 的实例置 `held=0`(可认领),其余置 `held=1`(被持有)。调度认领门 MUST 额外校验 `backfill_held=0`,held 实例 MUST NOT 被任何 master 认领。held 标志 MUST NOT 改变实例的 `state`(实例仍为 WAITING,如实反映「在等待」)。

#### Scenario: held 实例不可被认领
- **WHEN** 一个 `held=1` 的 WAITING 补数据实例存在且 worker 有空闲槽位
- **THEN** 该实例 MUST NOT 被认领下发,停留等待晋升;同批 `held=0` 实例正常被认领

#### Scenario: held 实例不污染状态机
- **WHEN** 查询 held 补数据实例的状态
- **THEN** 其 `state` 仍为 WAITING(非新增伪状态),held 仅作认领门旁路标志

### Requirement: 完成即晋升(BackfillPromoter)

系统 SHALL 提供晋升器:当一个 backfill_run 的某个活跃 bizDate 链(`held=0` 的实例集)全部进入终态(SUCCESS/FAILED),且该 run 仍有 held bizDate 待跑,则晋升下一个 held bizDate(整批 `held=1→0`)以补足并发配额,并发 WAKE 事件触发调度。晋升 MUST 经乐观 CAS(`WHERE backfill_held=1 AND biz_date=?`),并发晋升 SHALL 幂等(同一 bizDate 不被重复计入配额)。晋升 SHALL 事件驱动(订阅实例终态)为主、周期 sweep 为兜底(防漏事件),且 MUST 在认领事务之外执行。

#### Scenario: 活跃 bizDate 完成后晋升下一个
- **WHEN** `parallelism=2` 补数据中,2 个活跃 bizDate 之一全部实例进入终态
- **THEN** 晋升器将下一个 held bizDate 整批置 `held=0` 并发 WAKE,活跃 bizDate 数回到 2

#### Scenario: 全部 bizDate 跑完后批次收敛
- **WHEN** 最后一个 bizDate 链进入终态且无 held bizDate 剩余
- **THEN** 晋升器不再晋升,`backfill_run` 派生 state 收敛为 SUCCESS/FAILED/PARTIAL

#### Scenario: 漏事件由周期 sweep 兜底
- **WHEN** 某次终态事件未送达(Redis 抖动)导致活跃 bizDate 已空但仍有 held bizDate
- **THEN** 周期 sweep 在下一轮检测到空闲配额并补晋升,无补数据批次永久卡死

#### Scenario: 并发晋升幂等
- **WHEN** 事件晋升与 sweep 晋升同时尝试晋升同一 held bizDate
- **THEN** 仅一方 CAS 成功,另一方影响 0 行让步,该 bizDate 不被重复晋升、配额不超发

### Requirement: 节流进度可观测

`backfill_run` 视图 SHALL 暴露 `activeDates`(当前 `held=0` 且未全部终态的 bizDate 数)与 `heldDates`(当前 `held=1` 的 bizDate 数),供前端展示「N 并发 / M 待晋升」。该字段 MUST 向后兼容(响应体新增字段,不破坏既有消费者)。

#### Scenario: run 视图带并发/待晋升计数
- **WHEN** 查询 `GET /api/ops/backfill/{runId}`
- **THEN** 返回体含 `activeDates`/`heldDates`,随晋升推进动态变化,跑完后 `heldDates=0`
