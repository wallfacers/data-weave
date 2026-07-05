# Feature Specification: cron 触发并发吞吐优化

**Feature Branch**: `045-cron-trigger-parallelization`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "045 cron 触发并发吞吐优化 —— 大规模同触发点(50-200 工作流同秒到期)下,定时触发实例创建吞吐被 cron-trigger 串行节流(044 实测 1-3 inst/s,执行端 slot_util=0.1 全闲)。目标:最大化并发触发吞吐到机器资源极限(≥10x),同时保证不丢触发、不重复实例、无死锁。"

**技术设计参考**:`docs/superpowers/specs/2026-07-05-cron-trigger-parallelization-design.md`(brainstorming 产出,方案 A:进程内队列 + reconciler + 三层幂等)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 大规模同触发点并发触发不节流 (Priority: P1)

平台承载大量周期任务(50-200 个工作流同秒到期,如整点跑批)时,所有到期的定时触发应在极短时间内完成实例创建,不被触发器自身串行处理节流。044 实测 50wf 同触发点吞吐仅 1-3 inst/s(执行端闲置 slot_util=0.1),证明触发器是瓶颈而非执行端。用户(运维/平台方)期望触发层在高并发到期下吞吐充足,实例被快速创建后交给调度内核执行。

**Why this priority**:这是本 feature 的核心价值 —— 解除触发层节流,让"到期→实例创建"不再是被压榨链路的瓶颈。不解决则后续执行端/dispatch 优化无意义。

**Independent Test**:建 50 个同 cron 工作流(同触发点到期),观察触发实例创建速率与延迟;对比 044 基线(1-3 inst/s、p99 0.248s)应显著提升。

**Acceptance Scenarios**:

1. **Given** 50 个 ONLINE 周期工作流同 cron 触发点, **When** 到点并发触发, **Then** 所有实例在 1s 内创建完(吞吐 ≥30 inst/s),无丢失。
2. **Given** 100/200 个工作流同触发点, **When** 并发触发, **Then** 吞吐随并发提升不崩塌(底线 ≥10x 基线)。
3. **Given** 触发层高并发, **When** 观察执行端, **Then** slot_utilization > 0.5(证明执行端真干活,触发不再节流)。

---

### User Story 2 - 压测定位机器资源极限 (Priority: P2)

平台方需知道当前机器资源(20 核 / PG / 连接池)下,cron 触发链路的最大吞吐天花板,以及最先饱和的资源(CPU / DB 连接 / 锁 / worker 池)。通过两档配置(默认安全档 / 极限档)对比压测,记录饱和点,为后续容量规划提供依据。

**Why this priority**:用户原话"目标当前机器资源的性能指标最大化"—— 需量化"最大化"的边界。先有 US1 解除节流,才能真实触及资源极限而非触发层瓶颈。

**Independent Test**:默认档(池/HikariCP 保守)与极限档(池/HikariCP/PG max_connections 调大)各跑压测,记录最大吞吐与最先饱和指标。

**Acceptance Scenarios**:

1. **Given** 默认档配置, **When** 50/100/200wf 压测, **Then** 记录吞吐/p99/slot_util 稳态值,达 ≥10x 基线。
2. **Given** 极限档配置(PG max_connections 调大), **When** 持续加压, **Then** 吞吐达饱和(再加压不提升),记录最先饱和资源。
3. **Given** 压测过程, **When** 观察新增 metrics(队列深度/降级/物化延迟/reconcile), **Then** 各指标可观测、可定位瓶颈。

---

### User Story 3 - 触发链路可靠:不丢/不重复/无死锁 (Priority: P3)

高并发触发下,触发链路必须保证:① 不丢触发(进程崩溃时未创建实例的触发点能被补偿)② 不重复实例(同一触发点不会创建多个 workflow_instance)③ 无死锁(遵守现有死锁防御不变量:SKIP LOCKED / CAS / 锁顺序 / 状态事务内)。可靠性是吞吐优化的前提 —— 不能为快而丢/重/死锁。

**Why this priority**:性能不能以可靠性为代价。US1/US2 的吞吐提升必须在不破坏正确性的前提下。P3 是质量护栏。

**Independent Test**:压测中模拟崩溃(kill master)、并发同触发点、长时间高负载,核对实例无重复 + 崩溃后补偿 + 无死锁。

**Acceptance Scenarios**:

1. **Given** master 进程在触发队列有未消费任务时崩溃, **When** 重启后, **Then** 未创建实例的 cron_fire 记录在 30s 内被补偿重试,实例最终创建。
2. **Given** 同一 (workflow_id, scheduled_fire_time) 触发点, **When** 并发 fire / reconciler 重试, **Then** 恰好创建 1 个 workflow_instance(无重复)。
3. **Given** 任意并发触发场景, **When** 检查死锁防御 4 不变量, **Then** 全部保持(SKIP LOCKED claim / CAS 状态推进 / 锁顺序 task→workflow / 状态事务内 + dispatch 事务外)。

---

### Edge Cases

- 队列满(worker 消费跟不上):降级同步执行(timer 线程直接物化),不丢触发,背压通过 metrics 暴露。
- reconciler 误重试(物化慢被误判丢失):三层幂等挡,撞唯一约束 → 查已有回填,不产生重复。
- 超长重试(trigger 持续失败):超 180s 标 DEAD + 告警,避免无限重试占资源。
- 多 master 并发扫同一崩溃 cron_fire 行:DB 唯一约束兜底,撞键方查已有 instance 回填。
- 极限档连接数耗尽:PG max_connections=200 + HikariCP=64/双 master=128,留余量;监控连接池等待。
- misfire 政策(fire_once/skip)在异步物化下仍正确:advanceNext 在物化完成后重算,逾期点不逐个回放。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:系统 MUST 将 cron 触发的"去重判定"与"实例创建物化"解耦 —— 到点先同步完成去重(保证唯一触发权),物化异步进行,不阻塞触发线程。
- **FR-002**:系统 MUST 支持多个触发点到期时并发创建实例(进程内 worker 池),吞吐随 worker 数扩展,而非单/双线程串行。
- **FR-003**:系统 MUST 批量物化工作流实例(单事务原子写 workflow_instance + 所有 task_instance),减少数据库往返与提交次数。
- **FR-004**:系统 MUST 在触发进程崩溃导致实例未创建时,由补偿器周期扫描并在限定时间内重试创建,保证不丢触发。
- **FR-005**:系统 MUST 保证同一触发点(workflow_id + scheduled_fire_time)至多创建一个 workflow_instance(幂等),应用层快查 + 数据库唯一约束双层防护。
- **FR-006**:系统 MUST 在触发队列满时降级为同步执行(不丢触发),并通过指标暴露背压。
- **FR-007**:系统 MUST 提供可配置的触发线程池 / worker 池 / 队列容量 / 数据库连接池参数,支持默认(安全)与极限(压测)两档。
- **FR-008**:系统 MUST 新增观测指标(触发队列深度 / 满队列降级次数 / 物化延迟分布 / 补偿器各状态计数),用于定位瓶颈。
- **FR-009**:系统 MUST 在本优化的全部改动中保持现有死锁防御 4 不变量:① 认领只经 SKIP LOCKED ② 状态推进经乐观 CAS ③ 固定锁顺序 task→workflow ④ 状态在事务内持久化、HTTP dispatch 在事务外。
- **FR-010**:补偿器 MUST 对超时仍失败的触发点标记终态并告警,避免无限重试。

### Key Entities *(include if feature involves data)*

- **cron_fire(扩展)**:增加 status 字段(PENDING/FIRED/DEAD)记录触发点生命周期;增加 (instance_id, created_at) 索引支撑补偿器扫描。
- **workflow_instance(约束)**:增加 (workflow_id, scheduled_fire_time) 部分唯一约束(WHERE scheduled_fire_time 非空),保证周期触发点幂等(手动/补数据 scheduled_fire_time 为空不受约束)。
- **FireTask(新,进程内)**:触发点物化任务(workflow_id + due + cron_fire_id),在触发线程与 worker 池间传递的队列元素。
- **CronFireReconciler(新组件)**:周期扫描 cron_fire 中 instance 未回填且超 grace 期的记录,幂等重试创建实例。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**:50 个工作流同触发点并发到期,实例创建吞吐 ≥30 inst/s(相对 044 基线 1-3 inst/s 提升 ≥10x)。
- **SC-002**:压测中执行端 slot_utilization > 0.5(证明触发层不再节流、执行端真被利用,对比 044 的 0.1)。
- **SC-003**:压测全过程中,同一 (workflow_id, scheduled_fire_time) 触发点无重复 workflow_instance(幂等三层防护有效)。
- **SC-004**:master 进程崩溃后,未创建实例的触发点在 30s 内被补偿创建(不丢触发)。
- **SC-005**:全部压测场景下,死锁防御 4 不变量保持,无死锁/活锁/状态推进竞态。
- **SC-006**:极限档压测记录最大吞吐与最先饱和资源(量化"机器资源极限")。

## Assumptions

- 复用 044 的 cron-stress 压测脚本与 cron-watch 观测工具(扩展新指标列),不重写测试基建。
- 测试环境为 distributed 模式(双 master + 双 worker),与 044 一致;all-in-one 模式不在本 feature 验证范围。
- PostgreSQL max_connections 在极限档需从默认 100 调至 200(docker-compose postgres command),以支撑 HikariCP 扩容。
- 现有 WorkflowTriggerService.trigger 签名不变(手动触发/补数据/单任务运行等共用路径不受影响,仅内部批量化 + 事务化)。
- 技术实现细节(组件拆分/资源池初值/schema DDL/不变量核对)见 design doc 与后续 plan.md;本 spec 聚焦 WHAT/WHY。
- 执行端(scheduler kernel / dispatch / worker)非本 feature 改动范围(044 证明非瓶颈);本 feature 仅优化 cron 触发链路。
