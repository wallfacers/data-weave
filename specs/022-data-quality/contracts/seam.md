# Contract: 质量执行接缝(执行复用 / post-task 钩子 / BLOCK 阻断 / QUALITY_FAILED 喂 021)

四条接缝,全部**复用既有内核**,不另起引擎、不新增 DAG 状态机状态、不破坏依赖方向。逐条依据见 [research.md](./research.md)。

## 接缝 A:断言执行复用 worker 执行器(TaskExecutionGateway,research.md D1)

`QualityCheckRunner`(master application)把断言编译为度量 SQL,经**既有** `TaskExecutionGateway` 下发 `QualityProbeExecutor`,读回标量度量值——**不**新建执行接缝、**不** fork 第二查询引擎(原则 III/V)。

```
QualityCheckRunner.run(ruleSet, trigger, taskInstanceId?, locale)
  │  QualityRuleCompiler.compile(rule) → 度量 SQL + 期望比较器(8 类,D6)
  ▼
TaskExecutionGateway.dispatch / executeSync   ← 既有接缝(TaskExecutionGateway.java:12)
  ├─ all-in-one : InProcessTaskExecutionGateway(byType[QUALITY_PROBE], 进程内 execute)
  └─ distributed: WorkerExecService.executeSync(POST /internal/worker/exec)
  ▼
QualityProbeExecutor (worker infrastructure, type=QUALITY_PROBE)
  · 继承 SqlTaskExecutor 建连/驱动隔离/超时/SKIPPED 不变量(SqlTaskExecutor.java:55-127)
  · 仅扩展:把 ResultSet 首行首列读为标量 measured_value 回传
  ▼
ExecutionResult{ measured? / skipped? } → QualityCheckRunner 比较期望 → 写 quality_check_result
```

**语义分离(FR-007/SC-005 红线)**:
- `ExecutionResult.skipped()`(未绑库 / 连不上 / 无驱动)→ result `status=ERROR`(基础设施失败)→ **不发信号、不阻断、不计入质量分**。
- 真读回度量值 → 进 PASS/FAIL 比较。

**为何不新建执行器复制连库逻辑**:`QualityProbeExecutor` 继承 `SqlTaskExecutor`,`openConnection`/`isConnectionFailure`/SKIPPED 全复用,仅覆写「读回标量」一处——最小扩展,零语义漂移。

## 接缝 B:post-task 门禁钩子(应用内 ApplicationEvent,research.md D2.1)

master 在任务终态推进点 publish 应用内事件,质量侧 `@EventListener` 消费——**master 编译期不依赖质量 application 服务**(框架级 `ApplicationEventPublisher`,守依赖方向,同 021 D1)。

```
InstanceStateMachine.casTaskTerminal(id, from, "SUCCESS", reason)  ← 既有点(file:64-73)
  │ CAS 成功(恰 1 行)后,在 publishTaskState(file:69-71) 旁额外:
  ▼ eventPublisher.publishEvent(new TaskSucceededEvent(taskInstanceId, taskId, tenantId, ...))
  ▼
TaskSucceededListener.@EventListener (质量 application)
  · 事务提交后异步执行(事务外副作用,守死锁防御不变量③:事务内只落状态)
  · 查该 taskId 是否有 enabled 的 POST_TASK 质量规则(quality_rule.bound_task_id)
  · 有 → QualityCheckRunner.run(rules, trigger=POST_TASK, taskInstanceId=该实例)
```

**时序保证**:post-task 钩子在上游 `SUCCESS` 之后、下游被 `SchedulerKernel.claim` 之前完成质量裁决;对 FAIL 的 BLOCK 规则在下游被 claim 前把下游标 SKIPPED(接缝 C)。

## 接缝 C:BLOCK 阻断下游 DAG(复用既有状态机,不新增状态,research.md D3 —— 红线)

```
quality_check_result FAIL + rule.action=BLOCK
  ▼
QualityGateService.block(taskInstanceId, resultId)
  · 从绑定 task 的 workflow_node 出发,遍历 workflow_edge(schema.sql:376-390)
    取传递下游闭包(含弱依赖边,与冻结传递下游一致)
  · 对每个下游 task 实例:
      InstanceStateMachine.casTaskState(downstreamId, "WAITING", "SKIPPED")  ← 既有 CAS(file:41-47)
      失败原因写 failure_reason = "QUALITY_BLOCKED:rule={id} result={resultId}"
  ▼
既有就绪门(SchedulerKernel.claim, file:284-292: pred.state='SUCCESS')天然不放行
  → 下游 SKIPPED/已置终态,永不被 claim、永不下发(SC-003)
```

**不变量**:
- 用既有 `SKIPPED`/`FAILED` 表达阻断,**零新增 DAG 状态机状态**(红线,spec Assumptions)。
- 乐观 CAS `WHERE state='WAITING'`,若下游已被并发 claim(已非 WAITING)→ 影响 0 行让步(死锁防御不变量②),绝不持锁等待。
- 失败可追溯:下游 `failure_reason` 指向具体 `quality_check_result`(FR-005/SC-003)。
- `WARN` 动作 FAIL **不**触碰下游状态机(下游照常 claim),仅记 result + 发信号(接缝 D)。

## 接缝 D:QUALITY_FAILED 喂 021 告警引擎(复用 021 AlertSignalPublisher,research.md D4)

**022 是产生方,021 是消费方。** 021 已预留 `AlertSignal.Type.QUALITY_FAILED` + 消费路径 + 接缝测试桩(`specs/021-alert-engine/contracts/signal-seam.md:18,40-43`);022 兑现产生方。

```
quality_check_result FAIL(BLOCK 或 WARN 均)
  ▼
QualitySignalEmitter.emit(result)
  · 复用 021 AlertSignalPublisher(Spring ApplicationEvent;master 只 publish,
    编译期不依赖 alert 消费者 —— 同 021 D1 依赖方向处置)
  · publish AlertSignal {
      type: QUALITY_FAILED,
      tenantId,
      fingerprintHint: datasetRef,            // 参与 021 fingerprint 去重
      severityHint: rule.severity,            // 021 规则可覆盖
      context: { ruleId, runId, resultId, datasetRef, assertionType,
                 measuredValue, expected, action },
      occurredAt
    }
  · quality_check_result.signal_emitted=1(幂等防重发,SC-004)
  ▼
021 AlertSignalListener.@EventListener(signal_source=QUALITY_FAILED 规则匹配)
  → AlertEvaluator → AlertLifecycleService → AlertDispatchService(去重/抑制/分发)
```

**责任边界(spec 范围)**:022 **只产生事件 + 携带上下文**;通知规则/分发/去重/静默/恢复全在 021(FR-006)。022 不调 021 的 service、不做通知。

**ERROR 不发信号**:基础设施失败(probe SKIPPED→result ERROR)**不** emit `QUALITY_FAILED`(语义分离,SC-005)。

## 合并期闭环约定(Cross-Feature,SDD)

- **落地顺序**:先合 021(定义 `AlertSignal`/`AlertSignalPublisher`),再合 022(复用其契约 publish `QUALITY_FAILED`)。若 021 把 `AlertSignal` 置于 alert 模块,022(master 代码)`publish` 需 alert 在 master 运行期可见——与 021 D1 对 master 发射 `AlertSignal` 的处置一致,合并期对齐其放置位置。
- **共享发射点**:021 与 022 都在 `InstanceStateMachine.casTaskTerminal` 成功后各加自己的 publish(021 发 `AlertSignal` 终态信号、022 发 `TaskSucceededEvent`)。合并后 re-run 状态机测试,确认**两发射点都在、互不吞**。
- **接缝测试(SC-004)**:022 合并后 re-run 021 quickstart 场景 7(`specs/021-alert-engine/quickstart.md:47-50`)——造**真实** 022 断言 FAIL(非桩信号)→ 断言 021 `signal_source=QUALITY_FAILED` 规则触发告警 + 分发。seam 闭合即「功能闭环」。
- **schema_version 并行升版**:021=`0.1.0`、022 占位 `0.2.0`、023 顺延;合并按落地顺序定终值(research.md D12)。
