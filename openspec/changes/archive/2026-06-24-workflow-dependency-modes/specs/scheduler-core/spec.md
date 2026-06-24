## ADDED Requirements

### Requirement: 同周期依赖强度（强/弱）就绪判定

`workflow_edge` SHALL 携带 `strength`（`STRONG` 默认 / `WEAK`）。就绪门按边强度判定下游可运行性：`STRONG` 边要求前驱 `task_instance.state` 为 `SUCCESS` 才放行下游（现状）；`WEAK` 边要求前驱到达终态（`SUCCESS`/`FAILED`/`STOPPED`）即放行下游，不要求成功。本判定取代原就绪门中单一的 `pred.state<>'SUCCESS'` 阻塞条件，成为同周期上下游解锁的通用规则（虚拟节点因恒为 `SUCCESS`，对强弱边均自然放行）。弱依赖 MUST NOT 改变工作流整体态聚合口径——`WorkflowStateService.aggregate` 仍按各节点态聚合，如实反映失败节点的存在。

#### Scenario: 强依赖上游失败阻塞下游

- **WHEN** 边 A→B `strength=STRONG`，A 本周期 `FAILED`
- **THEN** B 不就绪，保持 `WAITING`

#### Scenario: 弱依赖上游失败仍放行下游

- **WHEN** 边 A→B `strength=WEAK`，A 本周期到达终态 `FAILED`
- **THEN** B 就绪可运行

#### Scenario: 弱依赖上游未到终态仍阻塞

- **WHEN** 边 A→B `strength=WEAK`，A 仍处于 `RUNNING`
- **THEN** B 不就绪（弱依赖要求前驱到达终态，而非无条件放行）

#### Scenario: 弱依赖不改变整体态聚合

- **WHEN** 弱依赖下 A `FAILED`、B 成功完成
- **THEN** `workflow_instance` 整体态为 `FAILED`，如实反映存在失败节点

#### Scenario: 旧边默认 STRONG 行为不变

- **WHEN** 一条边未指定 `strength`（历史数据）
- **THEN** 系统按 `STRONG` 处理，等价于改动前的就绪门行为

### Requirement: 跨周期依赖就绪判定

周期触发（`trigger_type='CRON'`）工作流实例的 NORMAL 节点，就绪门除同周期边强度判定外，SHALL 额外按启用的 `workflow_dependency`（`enabled=1` 且 `biz_date >= earliest_biz_date`）检查：本节点任一启用的跨周期依赖，其 `depend_node_id` 在 `biz_date` 按 `date_offset` 偏移后的实例 MUST 为 `SUCCESS`，否则不就绪。就绪判定 MUST 复用既有 SKIP LOCKED 认领与乐观 CAS 不变量，跨周期检查在认领事务内完成；`(workflow_node_id, biz_date)` 索引 MUST 支撑上一周期实例查询。非周期实例（`MANUAL` / `run_mode=TEST`）MUST NOT 附加跨周期检查。

#### Scenario: 跨周期上一周期 SUCCESS 解锁

- **WHEN** CRON 实例节点 N 有跨周期依赖（LAST_DAY），上一周期实例已 `SUCCESS`，且同周期上游已 `SUCCESS`
- **THEN** N 就绪、被认领下发

#### Scenario: 跨周期上一周期未跑阻塞

- **WHEN** CRON 实例节点 N 有跨周期依赖（LAST_DAY），上一周期实例不存在或非 `SUCCESS`
- **THEN** N 不就绪，留在 `WAITING`

#### Scenario: earliest 豁免跳过跨周期检查

- **WHEN** CRON 实例节点 N 的 `biz_date` 小于其跨周期依赖的 `earliest_biz_date`
- **THEN** N 跳过跨周期检查，仅按同周期就绪

#### Scenario: 手动与测试实例不做跨周期检查

- **WHEN** 一个 `MANUAL` 触发或 `run_mode=TEST` 的实例节点经过就绪门
- **THEN** 就绪门不附加跨周期 EXISTS 检查（该检查仅对周期实例适用）
