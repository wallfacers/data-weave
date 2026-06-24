## MODIFIED Requirements

### Requirement: 多 master 对等认领

调度器 SHALL 支持多个 master 实例同时运行且完全对等（无 leader 选举）。待调度实例的认领 MUST 使用 `SELECT … FOR UPDATE SKIP LOCKED`，同一实例 SHALL 仅被一个 master 认领成功。PostgreSQL 是调度状态的唯一真相源；Redis 不可用时调度 SHALL 退化为轮询驱动而不丢失任何任务。

认领门 SHALL 额外排除被持有的补数据实例：认领条件 MUST 含 `backfill_held=0`（与既有 `frozen` 守卫同构的旁路标志）。`backfill_held=1` 的实例 MUST NOT 被任何 master 认领。该守卫 MUST NOT 改变四条死锁不变量：① 认领仍只用 SKIP LOCKED 且永不等待行锁；② held 标志推进（晋升器 `held=1→0`）一律经乐观 CAS（`WHERE backfill_held=1 AND biz_date=?`）；③ 不引入新的跨表锁顺序；④ 标志推进在认领事务之外执行。

#### Scenario: 双 master 竞争认领

- **WHEN** 两个 master 同时对同一批 WAITING 实例发起认领
- **THEN** 每个实例恰好被一个 master 认领，无重复下发、无锁等待

#### Scenario: Redis 完全不可用

- **WHEN** Redis 宕机期间提交新的工作流实例
- **THEN** 实例在兜底轮询周期内被正常调度执行，无任务丢失

#### Scenario: 被持有的补数据实例不被认领

- **WHEN** 一批 `backfill_held=1` 的 WAITING 补数据实例与正常 WAITING 实例同时存在，且有空闲 worker 槽位
- **THEN** 仅 `backfill_held=0` 的实例被认领下发；held 实例停留 WAITING 等待晋升，认领门不等待、不报错

#### Scenario: 晋升不破坏认领不变量

- **WHEN** 晋升器将某 held bizDate 整批 `held=1→0` 的同时，认领轮询正在扫描 WAITING 实例
- **THEN** 晋升经乐观 CAS 完成、认领经 SKIP LOCKED 完成，二者无锁等待、无重复认领；晋升在认领事务之外提交
