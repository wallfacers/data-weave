# Contract: 统一信号契约（domain.signal.AlertSignal）

唯一健康信号契约。`quality.domain.AlertSignal` 删除，所有 emit 点统一用本类。

## 结构
`AlertSignal(Type type, long tenantId, String fingerprintHint, String severityHint, Map<String,Object> context)`，`occurredAt` 构造时取 now。

## Type（统一枚举）
`TASK_FAILED · TASK_TIMEOUT · SLA_BREACH · WORKFLOW_STATE · NODE_OFFLINE · METRIC_BREACH · QUALITY_FAILED · ASSET_CHANGED`，预留 `LINEAGE_CONFLICT`（024）。

## emit 点（统一后）
- SlaService → SLA_BREACH
- InstanceStateMachine/LeaseReaper → TASK_FAILED/TASK_TIMEOUT
- **QualitySignalEmitter → QUALITY_FAILED（本特性：从 quality.domain.AlertSignal 迁移，修孤儿）**
- AssetSubscriptionService → ASSET_CHANGED

## consume 点
- AlertSignalListener（告警分发，既有）
- **HealthEventRecorder（事件中心持久化，本特性新增，旁路）**

## 不变量
两个 @EventListener 独立消费同一信号；任一异常不影响另一（FR-007）。
