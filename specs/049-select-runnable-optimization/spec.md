# Feature Specification: selectRunnable 优化

**Feature Branch**: `049-select-runnable-optimization`

**Created**: 2026-07-06

**Status**: Draft

**Input**: User description: "049 selectRunnable 优化。048 批量化后 selectRunnable 重 SQL 在 WAITING 大量堆积(53万行)时退化——run_mode IN 打破索引(Seq Scan 244ms)+ NOT EXISTS 上游门(327-493ms),round_duration 反弹 49ms→1.58s,claim 13 inst/s << 物化 1000 inst/s,SC-002 未达成。优化 selectRunnable 使 round_duration 稳定低位,claim 跟上物化,WAITING 不堆积。约束:死锁 4 不变量;H2/PG 双兼容;不退化 046+048。基于 main b6723a4(含 046+048)。"

## User Scenarios & Testing

### User Story 1 - 高负载下认领候选查询稳定快 (Priority: P1)

作为调度系统,在大量任务实例堆积(WAITING 50 万+)时,认领候选的查询仍应快速稳定返回(毫秒级),而非随堆积量线性退化到秒级。当前(048 批量化后)认领候选查询在堆积时退化到 0.3-1.6 秒,认领速率被拖垮。

**Why this priority**: 认领候选查询是认领链路的起点,堆积时退化直接导致认领速率崩塌(13 inst/s),系统在高负载下不可用。

**Independent Test**: 1000wf `*/2s` 跑稳后人为让 WAITING 堆积(50 万+),核对单轮认领候选查询耗时稳定在低位(不随 WAITING 量增长)。

**Acceptance Scenarios**:

1. **Given** WAITING 实例堆积 50 万+,**When** 调度器查询认领候选,**Then** 查询耗时稳定 < 30ms(不随堆积量退化)
2. **Given** 高负载持续,**When** 单轮认领完整执行(查询 + 批量就绪判定 + 批量推进),**Then** 单轮耗时稳定低位(早期与堆积后一致)
3. **Given** 不同运行模式(NORMAL/BACKFILL/TEST)混合,**When** 认领,**Then** 各模式候选都被覆盖(NORMAL 不拖累 BACKFILL)

---

### User Story 2 - 认领跟上触发层物化 (Priority: P2)

作为调度系统,认领速率应跟上触发层物化速率(~1000 inst/s),WAITING 队列稳态不堆积。048 后认领仅 13 inst/s,WAITING 涨到 53 万。

**Why this priority**: 这是性能优化的最终目标(端到端 SC-002);依赖 US1(查询不退化)达成。

**Independent Test**: 1000wf `*/2s` 全负载跑 3 分钟,核对 WAITING 稳态不涨、认领吞吐 ≥ 物化速率。

**Acceptance Scenarios**:

1. **Given** 1000wf `*/2s` 全负载,**When** 持续运行 3min,**Then** WAITING 实例数稳态不增长(认领 ≥ 物化)
2. **Given** 高负载,**When** 认领持续,**Then** 认领吞吐 ≥ 600 inst/s(匹配触发层物化)
3. **Given** 认领速率跟上物化,**When** 端到端看任务实例,**Then** 从物化到认领的延迟 p99 < 5s

---

### User Story 3 - 认领不重复、不丢、无死锁 (Priority: P3)

作为可靠性工程师,优化后认领必须保持既有的死锁防御不变量:不重复认领、不丢任务、无死锁。查询优化与批量判定不得牺牲正确性。

**Why this priority**: 正确性是不可协商底线;上游就绪判定从 SQL 移到 Java 层后必须重新核对语义一致。

**Independent Test**: 高负载认领运行中注入崩溃,重启后核对:无实例被重复认领、无实例丢失、无死锁。

**Acceptance Scenarios**:

1. **Given** 高负载认领中,**When** 同一实例并发可见,**Then** 恰好被认领一次(无重复下发)
2. **Given** 认领事务已提交但下发未完成,**When** master 崩溃后重启,**Then** 已认领未下发实例被正确处理(不丢)
3. **Given** 持续高负载长跑,**When** 认领事务并发进行,**Then** 无死锁

---

### Edge Cases

- **多节点工作流上游未就绪**:上游节点未 SUCCESS/FAILED 时,本轮批量判定必须将下游实例排除(同现状 NOT EXISTS 语义)
- **强 vs 弱依赖**:强依赖(strength=STRONG)上游须 SUCCESS;弱依赖(WEAK)上游须 SUCCESS 或 FAILED(自然跑完)
- **BACKFILL 实例**:补数据实例不被 NORMAL 的查询退化拖累(分开查询)
- **候选 filter 后不足 batch**:上游/跨周期 filter 后候选数 < 认领批量时,需有足够候选余量(放大初始 LIMIT)
- **单节点工作流无上游**:无上游 edge 的实例直通就绪(不查上游)

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 在认领候选查询中使用等值过滤(非多值 IN)以利用索引,避免堆积时全表扫描 + 磁盘排序
- **FR-002**: 系统 MUST 将上游就绪判定从认领查询 SQL 内移至批量应用层(不在查询内做 NOT EXISTS 子查询)
- **FR-003**: 系统 MUST 分开查询不同运行模式(NORMAL vs BACKFILL)的候选,避免多值过滤破坏索引
- **FR-004**: 系统 MUST 保留 SKIP LOCKED 认领语义(并发认领不互相阻塞,跳过被锁实例)
- **FR-005**: 系统 MUST 保持 CAS 状态推进、锁顺序(实例→工作流)、状态事务内+下发事务外
- **FR-006**: 系统 MUST 在测试环境(H2)与生产环境(PostgreSQL)下行为一致
- **FR-007**: 系统 MUST 保留强/弱依赖的上游就绪语义(强须 SUCCESS,弱须 SUCCESS/FAILED)
- **FR-008**: 系统 MUST 不退化 046+048 成果(dispatch 异步队列不积压、claim 批量化不破坏)
- **FR-009**: 系统 MUST 提供足够候选余量(初始候选数 > 认领批量,filter 后仍满足批量推进)
- **FR-010**: 系统 MUST 保留可观测性(既有调度指标/日志保留,认领查询耗时可观测)

### Key Entities

- **task_instance**: 认领候选;上游就绪判定时读同工作流实例内上游节点的状态
- **workflow_edge**: 上游依赖关系(to_node_id/from_node_id/strength);批量判定时一次性读取
- **workflow_instance**: 工作流实例;认领候选查询时读其状态/优先级/触发类型

## Success Criteria

### Measurable Outcomes

- **SC-001**: 1000wf `*/2s` 全负载下,单轮认领候选查询耗时稳定 < 30ms(WAITING 堆积 50 万+ 时不退化;048 R10=0.3-1.6s)
- **SC-002**: 认领吞吐 ≥ 600 inst/s,匹配触发层物化,WAITING 队列稳态不堆积(048 R10=53 万堆积,13 inst/s)
- **SC-003**: 高负载持续运行 + 崩溃注入下,无重复认领、无任务丢失、无死锁(不变量核对通过)

## Assumptions

- 046(dispatch 解耦)+ 048(claim 批量化)已合 main,本 feature 聚焦 selectRunnable 查询优化
- 测试用 H2、生产用 PostgreSQL;查询形态需两者兼容
- 单线程认领 + selectRunnable 优化即可满足吞吐(多线程认领分片仍为本 feature 之外的可能)
- 现有 cron-stress 压测工具(1000wf `*/2s` 极限档,distributed 双 master)可复用于验证
- 性能优化不得破坏可观测性(既有调度指标/日志保留)
