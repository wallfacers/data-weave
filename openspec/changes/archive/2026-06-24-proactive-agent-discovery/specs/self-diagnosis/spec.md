## MODIFIED Requirements

### Requirement: Agent 根因分析

系统 SHALL 基于采集的上下文，由 Agent 编排（`IntentRouter` 诊断意图 → 领域查询 → 推理）产出根因结论（如「3 号节点内存 95% 导致 OOM，同时段有两个任务挤同节点」）。MVP 阶段 SHALL 以规则 mock 实现，并预留 `LlmClient` 接口供后续替换真模型而不改编排骨架。诊断 SHALL 既可被动触发（用户发问），也可由 `TaskFailureInspector` 经 `InspectorScheduler` **主动触发**：对未诊断的 FAILED 实例自动调用 `diagnoseInstance` 并将结果映射为统一 `Finding`。

#### Scenario: 产出根因结论

- **WHEN** 用户在失败实例上问「为什么挂了」或从驾驶舱进入诊断
- **THEN** Agent 返回针对该实例的根因结论文本 + 关键证据（机器指标/调度争抢）

#### Scenario: mock 与真模型可替换

- **WHEN** 替换为真实 `LlmClient` 实现
- **THEN** 诊断编排骨架与 AG-UI 事件结构不变，仅推理来源切换

#### Scenario: 主动诊断未失败实例

- **WHEN** 巡检发现一个 FAILED 且无诊断的实例
- **THEN** 系统自动对其执行 `diagnoseInstance`，产出根因并映射为 OPEN 状态的 `Finding`，无需用户先发问
