## ADDED Requirements

### Requirement: 失败上下文自动采集

当任务实例进入失败状态时，系统 SHALL 自动采集诊断上下文：任务日志/报错、所在 worker 节点的资源指标（CPU/内存/磁盘/load）、调度上下文（上游延迟/同节点并发争抢）、历史失败记录。采集结果 SHALL 关联到该任务实例并持久化到 `task_diagnosis`。

#### Scenario: 失败即采集

- **WHEN** 某任务实例状态变为「失败」
- **THEN** 系统生成一条 `task_diagnosis` 记录，含日志、机器指标、调度上下文、历史四类上下文

### Requirement: Agent 根因分析

系统 SHALL 基于采集的上下文，由 Agent 编排（`IntentRouter` 诊断意图 → 领域查询 → 推理）产出根因结论（如「3 号节点内存 95% 导致 OOM，同时段有两个任务挤同节点」）。MVP 阶段 SHALL 以规则 mock 实现，并预留 `LlmClient` 接口供后续替换真模型而不改编排骨架。

#### Scenario: 产出根因结论

- **WHEN** 用户在失败实例上问「为什么挂了」或从驾驶舱进入诊断
- **THEN** Agent 返回针对该实例的根因结论文本 + 关键证据（机器指标/调度争抢）

#### Scenario: mock 与真模型可替换

- **WHEN** 替换为真实 `LlmClient` 实现
- **THEN** 诊断编排骨架与 AG-UI 事件结构不变，仅推理来源切换

### Requirement: 修复建议与一键执行

诊断结果 SHALL 附带可执行的修复建议（如「调大 executor 内存重跑」「迁移到空闲节点重跑」「为该节点设调度权重上限」）。每条建议 SHALL 可由用户在右舷确认后一键触发对应领域操作，并反馈执行结果。

#### Scenario: 建议可一键执行

- **WHEN** 用户对某条修复建议点击「执行」
- **THEN** 系统触发对应操作（如重跑/迁移），并在右舷回报执行进展与结果

#### Scenario: 执行前需用户确认

- **WHEN** Agent 给出修复建议
- **THEN** 系统不自动执行有副作用的操作，须用户确认后再触发

### Requirement: 诊断结果 AG-UI 事件

后端 SHALL 通过 AG-UI CUSTOM 事件 `dataweave.diagnosis` 将结构化诊断结果（根因、证据、建议列表）推给前端右舷渲染。事件 SHALL 遵守既有 AG-UI 约定（SCREAMING_SNAKE_CASE 类型、完整 RUN 序列、文本走 Markdown）。

#### Scenario: 推送结构化诊断

- **WHEN** Agent 完成一次根因分析
- **THEN** 前端收到 `dataweave.diagnosis` CUSTOM 事件，渲染出根因、证据与可执行建议列表

#### Scenario: 驾驶舱展示诊断中事项

- **WHEN** 存在正在进行的诊断
- **THEN** 驾驶舱「Agent 正在诊断什么」区块列出这些事项
