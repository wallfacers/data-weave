# Feature Specification: claim 速率深优化

**Feature Branch**: `048-claim-rate-optimization`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "claim 速率深优化(claim-rate optimization)。046 解除 dispatch 串行后瓶颈转移到 claim 速率:单线程认领速率 ~333 inst/s 跟不上触发层物化 ~600 inst/s,task 物化后排队等认领,1000wf */2s 全负载下 scheduler_dispatch_latency 135.7s、task_instance WAITING 堆积 67859。优化使 1000wf */2s 全负载下 dispatch_latency < 5s、认领吞吐 ≥ 600 inst/s、WAITING 不堆积。约束:保持死锁防御 4 不变量;H2/PG 双兼容。基于 046 worktree 连续做。"

## User Scenarios & Testing

### User Story 1 - 高负载下任务及时被认领 (Priority: P1)

作为调度系统,在 1000 个工作流每 2 秒触发的全负载下,被触发物化的任务实例应在 5 秒内被认领并进入下发,而非长时间排队等待。当前(046 解除 dispatch 串行后)认领速率(~333 实例/秒)跟不上触发层物化速率(~600 实例/秒),任务实例物化后平均排队 135 秒才被认领。

**Why this priority**: 认领是任务执行链路的入口,直接决定端到端延迟;不解除则高负载下系统实质不可用(任务大面积延迟)。

**Independent Test**: 1000 个工作流 `*/2s` 全负载连续运行 3 分钟,核对任务实例从进入待认领到被认领的延迟 p99 < 5 秒,且待认领队列稳态不堆积。

**Acceptance Scenarios**:

1. **Given** 1000wf `*/2s` 全负载已跑稳,**When** 持续运行 3 分钟,**Then** 待认领实例数稳定不增长(认领速率 ≥ 物化速率)
2. **Given** 任务实例进入待认领状态,**When** 调度器认领它,**Then** 认领延迟(`waitingSince` → 认领成功时刻)p99 < 5 秒
3. **Given** 高负载下大量实例待认领,**When** 认领持续进行,**Then** 无实例因认领排队过久而触发租约超时或误判

---

### User Story 2 - 找到认领吞吐上限 (Priority: P2)

作为性能工程师,想知道认领层在优化后的吞吐上限,以便评估未来可承载规模(还能支持多少工作流、多高触发频率),并为下一轮优化(若需要)提供依据。

**Why this priority**: 容量规划与瓶颈预测;知道上限与最先饱和资源才知道系统余量。

**Independent Test**: 在优化后系统上持续加压(增加工作流数或触发频率),直到认领吞吐不再提升,记录饱和点(实例/秒)与最先饱和的资源层。

**Acceptance Scenarios**:

1. **Given** 优化后的系统,**When** 持续加压到认领吞吐不再提升,**Then** 记录饱和吞吐(实例/秒)与最先饱和资源
2. **Given** 达到饱和,**When** 继续加压,**Then** 系统以背压/降级方式承接而非报错崩溃

---

### User Story 3 - 认领不重复、不丢、无死锁 (Priority: P3)

作为可靠性工程师,优化后认领必须保持既有的死锁防御不变量:不重复认领同一实例、不丢任务、无死锁。性能优化不得以牺牲正确性为代价。

**Why this priority**: 正确性是不可协商底线;批量化改变认领形态后必须重新核对不变量。

**Independent Test**: 高负载认领运行中注入崩溃(认领事务提交后、下发前杀掉一个 master 节点),重启后核对:无实例被重复认领、无实例丢失、无死锁。

**Acceptance Scenarios**:

1. **Given** 高负载认领中,**When** 同一实例可能被并发可见,**Then** 该实例恰好被认领一次(无重复下发)
2. **Given** 认领事务已提交但下发尚未完成,**When** master 崩溃后重启,**Then** 已认领未下发的实例被正确处理(重派或恢复,不丢)
3. **Given** 持续高负载长跑,**When** 认领事务并发进行,**Then** 无死锁(任一事务都能在有限时间内推进)

---

### Edge Cases

- **跨周期依赖未就绪**:周期触发(CRON)实例的上游周期尚未 SUCCESS 时,本轮批量判定必须将其排除(同现状单行语义)
- **首周期豁免**:`biz_date` 早于依赖的 `earliest_biz_date` 时豁免检查(首周期 bootstrap),批量判定需保留该豁免
- **占位符解析失败**:认领后内容解析失败的实例须置失败态、不下发(同现状)
- **混合触发类型**:同一轮认领中 CRON/手动/补数据/测试实例混合时,手动/测试/补数据实例不走跨周期门(同现状)
- **空批/无可用槽**:无可认领实例或无可用 worker 槽时,认领为空操作(同现状)

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 在单轮认领内一次性完成所有周期触发实例的跨周期依赖就绪判定,而非每实例一次重复查询
- **FR-002**: 系统 MUST 在单轮认领内一次性推进所有可认领实例的状态(WAITING→DISPATCHED),而非每实例一次
- **FR-003**: 系统 MUST 保持认领的恰好一次语义:同一实例在并发可见时恰好被认领一次,不产生重复下发
- **FR-004**: 系统 MUST 保证状态推进发生在认领事务内、下发发生在认领事务提交后(状态事务内 + 下发事务外)
- **FR-005**: 系统 MUST 保持既有的锁顺序(实例→工作流),批量化不得引入跨表锁或锁顺序反转
- **FR-006**: 系统 MUST 在测试环境(H2)与生产环境(PostgreSQL)下行为一致(批量操作双兼容)
- **FR-007**: 系统 MUST 保留跨周期依赖的首周期豁免语义(`biz_date` 早于 `earliest_biz_date` 不检查)
- **FR-008**: 系统 MUST 在占位符解析失败时将实例置失败态、不下发(同现状)
- **FR-009**: 系统 MUST 保留 SKIP LOCKED 认领语义(并发认领不互相阻塞,跳过被锁实例)

### Key Entities

- **task_instance**: 被认领的任务实例;状态由 WAITING 推进到 DISPATCHED,认领时写入目标节点、租约、重试次数。批量认领在单轮内推进一组实例的状态
- **workflow_dependency**: 跨周期依赖关系(depend_node / date_offset / earliest_biz_date);批量判定时一次性读取并按周期偏移校验上游 SUCCESS
- **DispatchCommand**: 认领产出的待下发指令;批量认领后收集为一组,在事务提交后异步下发

## Success Criteria

### Measurable Outcomes

- **SC-001**: 1000 个工作流每 2 秒触发的全负载下,任务实例认领延迟(`waitingSince` → 认领成功时刻)p99 < 5 秒(当前 135 秒)
- **SC-002**: 认领吞吐 ≥ 600 实例/秒,匹配触发层物化速率,待认领队列在稳态下不堆积(当前堆积 6 万+)
- **SC-003**: 高负载持续运行 + 崩溃注入下,无重复认领、无任务丢失、无死锁(不变量核对通过)

## Assumptions

- 046 的 dispatch 解耦成果已就位(dispatch 异步队列不积压);本 feature 聚焦认领速率,不改 dispatch 链路
- 测试用 H2、生产用 PostgreSQL;批量操作的 SQL 形态需两者兼容(若 H2 不支持某形态,plan 阶段选定兼容方案)
- 单线程认领 + 批量化即可满足吞吐目标(多线程认领分片为本 feature 之外的后续可能,设计文档已 defer)
- 现有 cron-stress 压测工具(1000wf `*/2s` 极限档,distributed 双 master)可复用于验证
- 性能优化不得破坏可观测性(既有调度指标/日志保留)
