# Feature Specification: dispatch 链路并行化优化

**Feature Branch**: `046-dispatch-parallelization`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "046 dispatch 并行化优化 —— 基于 045 R9 瓶颈结论(触发层并行化后,瓶颈转移到 dispatch 端:1000wf */2s 稳态物化 600 inst/s,但 scheduler_dispatch_latency max 227.77s,task_instance 物化后排队等 dispatch,RUNNING 堆积 159347,SUCCESS 仅 ~17/s;HikariCP active=1/64、PG 138/200 不是瓶颈)。目标:解除 dispatch 认领串行,使 dispatch 吞吐跟上触发层物化(≥600 inst/s,dispatch_latency < 5s),死锁 4 不变量保持。"

**技术设计参考**:`docs/superpowers/specs/2026-07-05-dispatch-parallelization-design.md`(brainstorming 产出,杠杆 1+2+3 + 配套 4+5,聚合 7 defer)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - dispatch 认领不节流,task 快速到 worker (Priority: P1)

平台承载大量周期任务时(045 后触发层稳态物化 600 inst/s),task_instance 物化后应被快速认领并 dispatch 到 worker 执行,不在认领环节排队。045 R9 实测 dispatch_latency max 227.77s,SUCCESS 聚合仅 ~17/s,workflow_instance RUNNING 堆积 159347,证明认领串行是瓶颈(HikariCP active=1、slot_util=0 证明非 DB/worker 瓶颈)。用户(运维/平台方)期望 dispatch 层跟上触发层物化,task 快速到 worker。

**Why this priority**:本 feature 核心价值 —— 解除 dispatch 认领节流,让"物化→认领→下发"不再瓶颈。不解决则 045 触发层优化(600 inst/s 物化)的吞吐被 dispatch 卡死,task 堆在 WAITING。

**Independent Test**:复用 045 cron-stress(1000wf `*/2s` 极限档),观察 dispatch_latency / dispatch 吞吐 / queue.size;对比 R9 基线(227s max、SUCCESS ~17/s)应显著提升。

**Acceptance Scenarios**:

1. **Given** 1000wf `*/2s` 物化 600 inst/s, **When** 观察 dispatch, **Then** dispatch_latency p99 < 5s(R9=227s),dispatch 吞吐 ≥600 inst/s。
2. **Given** 高并发物化持续, **When** 观察 dispatchQueue 深度, **Then** 稳态不持续增长,queue.full.count=0 或可观测(非无限积压)。
3. **Given** dispatch 解除瓶颈, **When** 观察 worker, **Then** slot_utilization 升高(task 真到 worker,SC-002 045 遗留复测)。

---

### User Story 2 - 压测定位 dispatch 链路资源极限 (Priority: P2)

平台方需知道 dispatch 链路解除认领串行后,下一个瓶颈是什么(dispatchExecutor 池 / DB 连接 / worker slot / 聚合),以及吞吐天花板。通过密负载压测记录饱和点,为后续优化(如聚合异步化)提供依据。

**Why this priority**:量化"解除认领后"的边界。US1 解除认领后,需定位下一层瓶颈(预测是聚合 computeAndUpdate,但需实测)。

**Independent Test**:1000wf+ 密负载(或更密 cron)压测,记录最大 dispatch 吞吐与最先饱和指标。

**Acceptance Scenarios**:

1. **Given** 解除认领瓶颈, **When** 持续加压到吞吐不再提升, **Then** 记录最大 dispatch 吞吐 + 最先饱和资源(dispatchExecutor / DB 连接 / worker slot / 聚合)。
2. **Given** 压测过程, **When** 观察新增 metrics(队列深度/降级/下发延迟), **Then** 各指标可观测、可定位瓶颈。

---

### User Story 3 - dispatch 链路可靠:不重复/不丢/无死锁 (Priority: P3)

高并发 dispatch 下,链路必须保证:① 不重复 dispatch(同一 task_instance 不会被重复下发)② 不丢 dispatch(进程崩溃时未下发的 DispatchCommand 能回填重派)③ 无死锁(死锁 4 不变量保持)。可靠性是吞吐优化的前提 —— 不能为快而重复/丢/死锁。

**Why this priority**:性能不以可靠性为代价。US1/US2 的吞吐提升必须在不破坏正确性下。P3 是质量护栏。

**Independent Test**:压测中模拟崩溃(kill master)、并发认领、长高负载,核对无重复 dispatch + 崩溃后回填 + 无死锁。

**Acceptance Scenarios**:

1. **Given** master 崩溃时 dispatchExecutor 有未消费 DispatchCommand, **When** 重启, **Then** 残余 task_instance 被 casRequeue 回 WAITING,下一轮重派(不丢)。
2. **Given** 同一 task_instance, **When** 并发认领/重试, **Then** 恰好被 dispatch 一次(casDispatch CAS 保证,无重复)。
3. **Given** 任意并发 dispatch 场景, **When** 检查死锁 4 不变量, **Then** 全部保持(SKIP LOCKED claim / CAS 状态推进 / 锁顺序 task→workflow / 状态事务内 + dispatch 事务外)。

---

### Edge Cases

- dispatchQueue 满(下发跟不上认领):offer 200ms 超时 → 降级同步 dispatch(当前 claim 线程跑)+ markQueueFull 背压,不丢。
- worker 不可达/慢响应:distributed WebClient 3s 超时 → casRequeue 回 WAITING,下一轮重派。
- dispatchExecutor shutdown(master 关闭):drain 队列,残余 DispatchCommand casRequeue 防丢。
- N+1 批量预取缓存失效:Caffeine 缓存 task_def_version(content/params 静态可缓存),未命中走批量 SELECT IN,无功能影响。
- 聚合变新瓶颈(解除认领后 SUCCESS 飙升):046 defer,实测 computeAndUpdate 是否限 SUCCESS 速率,若瓶颈开新 feature。
- distributed 双 master 并发认领同 task_instance:SKIP LOCKED + casDispatch CAS 保证唯一认领,无重复 dispatch。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**:系统 MUST 将 task_instance 认领(claim 事务)与下发(dispatch I/O)解耦 —— 认领事务提交后将 DispatchCommand 推进程内队列,下发由独立线程池异步消费,认领不等下发。
- **FR-002**:系统 MUST 取消下发的屏障语义(等全部下发完成才返回下一轮)—— 改 fire-and-forget(推队列立即返回),下发并行度由独立线程池承载。
- **FR-003**:系统 MUST 消除认领内的 N+1 查询 —— 单轮认领的 content/params 批量预取(一次拿全)+ task_def_version 内容缓存 + 依赖检查批量化。
- **FR-004**:系统 MUST 给 distributed 下发 HTTP 配置响应超时(默认 3s),防慢 worker 挂起下发线程。
- **FR-005**:系统 MUST 在下发队列满时降级为同步下发(当前认领线程执行),不丢 DispatchCommand,并通过指标暴露背压。
- **FR-006**:系统 MUST 在下发失败(worker 不可达/超时/HTTP 错误)时,将 task_instance 状态 CAS 回退到 WAITING,下一轮认领重派。
- **FR-007**:系统 MUST 提供可配置的下发队列容量 / 下发线程池大小 / WebClient 超时参数,支持默认与极限两档。
- **FR-008**:系统 MUST 新增观测指标(下发队列深度 / 满队列降级次数 / 下发执行延迟分布),用于定位瓶颈。
- **FR-009**:系统 MUST 在本优化的全部改动中保持现有死锁防御 4 不变量:① 认领只经 SKIP LOCKED ② 状态推进经乐观 CAS ③ 固定锁顺序 task→workflow ④ 状态在事务内持久化、HTTP dispatch 在事务外。
- **FR-010**:系统 MUST 在下发线程池关闭时排空队列并将残余 DispatchCommand 状态回退,保证不丢。

### Key Entities *(include if feature involves data)*

- **DispatchCommand(新,进程内)**:认领事务内已读全的下发任务载荷(task_instance_id + content + params + lease + worker_node_code 等),认领线程与下发线程池间传递的队列元素。
- **dispatchQueue + dispatchExecutor(新,进程内)**:有界队列 + 固定线程池,衔接认领与下发(类似 045 fireQueue + fireExecutor)。
- **TaskDefVersionBatchLoader + Caffeine cache(新)**:批量预取 task_def_version 的 content/params + 缓存,消除认领内 N+1 查询。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**:dispatch 认领排队延迟(dispatch_latency)p99 < 5s(045 R9 基线 max 227.77s)。
- **SC-002**:dispatch 吞吐 ≥600 inst/s(跟上 045 触发层物化,R9 SUCCESS 仅 ~17/s)。
- **SC-003**:压测全过程中无重复 dispatch(同一 task_instance 恰好被下发一次)。
- **SC-004**:全部压测场景下死锁防御 4 不变量保持,无死锁/活锁/状态推进竞态。
- **SC-005**:dispatchQueue 深度稳态不持续增长(queue.full.count=0 或可观测,非无限积压)。
- **SC-006**:压测记录 dispatch 链路最大吞吐与最先饱和资源(量化解除认领后的下一层瓶颈)。

## Assumptions

- 复用 045 cron-stress 压测脚本与 cron-watch 观测工具(扩展 dispatch 指标列),不重写测试基建。
- 测试环境为 distributed 模式(双 master + 双 worker),与 045 一致;all-in-one 模式不在本 feature 验证范围。
- 045 触发层并行化(已 merge main `a0e47bf`)是前置 —— 稳态物化 600 inst/s 喂给 dispatch 层。
- 现有 SchedulerKernel claim 的 SKIP LOCKED + CAS + assign 框架不变,仅内部解耦(claim↔dispatch)+ 批量化(N+1 消除)+ 去屏障(invokeAll → 队列)。
- 聚合异步化(WorkflowStateService.computeAndUpdate)defer —— 解除认领后若变瓶颈,开新 feature。
- 执行端(worker ShellTaskExecutor / InProcessTaskExecutionGateway 池)非本 feature 范围(045 SC-002 slot_util=0 证明非瓶颈);SC-002 slot_util 复测需 rebuild worker image(ShellTaskExecutor 真跑 sleep)。
- 技术实现细节(组件拆分 / 资源池初值 / 不变量核对)见 design doc 与后续 plan.md;本 spec 聚焦 WHAT/WHY。
