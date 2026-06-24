## ADDED Requirements

### Requirement: 冻结任务 claim 跳过

调度器 SHALL 在 claim/生成阶段以 `WHERE frozen=false` 过滤,跳过已冻结(`task_def.frozen=true`)的任务,不为其生成新周期实例、不认领。冻结不持锁、不等待,不违反死锁防御不变量。

#### Scenario: 冻结任务不被认领
- **WHEN** 任务 frozen=true 且到达调度点
- **THEN** 调度器不生成其新实例、不认领,既有在途实例不受影响

#### Scenario: 解冻后恢复认领
- **WHEN** 任务 frozen 由 true 改为 false
- **THEN** 后续调度周期恢复正常生成与认领

### Requirement: 补数据实例的生成与认领

调度器 SHALL 支持 `run_mode='BACKFILL'` 实例:生成侧为纯 INSERT(每 bizDate × 目标任务一条,$bizdate 按日期注入);认领与执行复用既有 SKIP LOCKED 认领 + worker 执行路径,BACKFILL 仅作 runMode 标识,不新增认领 CAS 逻辑。

#### Scenario: 补数据实例经既有路径认领执行
- **WHEN** BACKFILL 实例就绪
- **THEN** 经既有 SKIP LOCKED 认领、worker 执行,无专用认领分支,死锁不变量不变

#### Scenario: 补数据与正常实例并存
- **WHEN** 同一任务同时存在 NORMAL 与 BACKFILL 实例
- **THEN** 两者经同一认领机制调度,互不干扰,各自 $bizdate 独立解析
