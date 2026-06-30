# Contract: AlertSignal 内部接缝(跨模块 + 跨特性)

## AlertSignalPublisher(master → alert,Spring ApplicationEvent)

master 在现有发射点 publish,alert 侧 `@EventListener` 消费。**master 编译期不依赖 alert 模块**(用框架 `ApplicationEventPublisher`)。

### AlertSignal 字段

```
AlertSignal {
  Type type;            // 见下枚举
  long tenantId;
  String fingerprintHint; // 如 taskId/workflowId/metricKey/datasetRef(参与 fingerprint)
  String severityHint;    // 信号侧建议 severity(规则可覆盖)
  Map<String,Object> context; // taskInstanceId/workflowInstanceId/breachMinutes/metricKey/value/failureReason...
  Instant occurredAt;
}
enum Type { TASK_FAILED, TASK_TIMEOUT, SLA_BREACH, WORKFLOW_STATE, NODE_OFFLINE, METRIC_BREACH, QUALITY_FAILED, ASSET_CHANGED }
```

### master 发射点(本特性改这 3+1 处)

| 信号 | 位置 | 触发条件 |
|---|---|---|
| TASK_FAILED / TASK_TIMEOUT | `InstanceStateMachine.casTaskTerminal(...)` (file:64-73) 推进 FAILED 后 | failureReason 区分超时 vs 失败 |
| WORKFLOW_STATE | `InstanceStateMachine.casWorkflowState(...)` (file:107-113) | 工作流转 FAILED/STOPPED |
| SLA_BREACH | `SlaService.recordCompletion(...)` (file:58-116) | 算出 `breached=1` |
| NODE_OFFLINE | `LeaseReaper` 心跳过期标 FAILED 处 | failureReason 含"心跳超期" |

METRIC_BREACH **不经此接缝**——由 `MetricPollEvaluator` 轮询产生(D2),非事件驱动。

### 消费:AlertSignalListener

`@EventListener` → 取该 tenant 下 `signal_source` 匹配且 `enabled=1` 的规则 → `AlertEvaluator` 评估(EVENT 模式即时;条件 + filter 命中)→ `AlertLifecycleService` 建/更新 `alert_event` → `AlertDispatchService` 分发。

## 跨特性预留(份2/份3 的产生方,本特性只定义类型 + 消费路径)

| 类型 | 产生方 | 本特性责任 |
|---|---|---|
| `QUALITY_FAILED` | 份2(022 数据质量)断言 FAIL 时 publish | 定义类型 + 消费路径 + 可定义匹配规则;**产生方在 022** |
| `ASSET_CHANGED` | 份3(023 资产/指标)schema/质量/新鲜度变更时 publish | 同上;**产生方在 023** |

**闭环约定**:022/023 实现时复用本 `AlertSignal`/`AlertSignalPublisher`,在各自发射点 publish 对应 Type;合并入 main 后 re-run 接缝集成测试(造 QUALITY_FAILED/ASSET_CHANGED 信号 → 断言对应规则触发告警 + 分发)。本特性 v1 提供一条针对 `QUALITY_FAILED` 的样例规则 + 接缝测试桩,证明消费路径通。
