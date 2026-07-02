# self-diagnosis Specification

## Purpose

定义失败任务的自诊断闭环：失败上下文自动采集、Agent 根因分析（mock/真模型可替换）、修复建议与一键执行、诊断结果 AG-UI 事件。
## Requirements
### Requirement: 失败上下文自动采集

当任务实例进入失败状态时，系统 SHALL 自动采集诊断上下文：任务日志/报错、所在 worker 节点的资源指标（CPU/内存/磁盘/load）、调度上下文（上游延迟/同节点并发争抢）、历史失败记录。采集结果 SHALL 关联到该任务实例并持久化到 `task_diagnosis`。采集 SHALL 基于真实运行态数据（`HeartbeatReporter` 真实节点指标、`NodeTelemetryService` 真实并发与近 7 天失败统计），不依赖预填演示种子；OOM@node-3 等演示场景数据 SHALL 仅在 `demo` profile（`demo-data.sql`）下加载，默认空库下系统据真实失败自行采集。

#### Scenario: 失败即采集

- **WHEN** 某任务实例状态变为「失败」
- **THEN** 系统生成一条 `task_diagnosis` 记录，含日志、机器指标、调度上下文、历史四类上下文，数据来自真实采集

#### Scenario: 默认无演示假数据

- **WHEN** 未启用 `demo` profile 的默认启动
- **THEN** 举手台不含任何预填演示诊断/finding，仅反映真实采集到的失败

#### Scenario: demo profile 提供演示场景

- **WHEN** 以 `-Dspring.profiles=demo` 启动
- **THEN** 加载 `demo-data.sql` 的 OOM@node-3 演示诊断/finding/节点高水位，供开箱演示

### Requirement: Agent 根因分析

系统 SHALL 基于采集的上下文产出根因结论（如「3 号节点内存 95% 导致 OOM，同时段有两个任务挤同节点」）。诊断推理 SHALL 默认由 **workhorse 真实大脑**承担：经 `DiagnosisAnalyzer` SPI（接口在 master 内层）的 `@Primary` 实现 `WorkhorseDiagnosisAnalyzer`（api 层）把真实遥测打包成结构化诊断 prompt，经 workhorse headless 会话推理，要求模型输出结构化结论（根因 + 建议），映射为 `Analysis` 落 `task_diagnosis`。当 workhorse 不可用或返回不可解析时，系统 MUST 回落到规则 `MockDiagnosisAnalyzer`，保证诊断永不缺席。诊断编排骨架（`DiagnosisService` + SPI）MUST 不随推理来源切换而改变。诊断 SHALL 既可被动触发（用户发问），也可由 `TaskFailureInspector` 经 `InspectorScheduler` **主动触发**：对未诊断的 FAILED 实例自动调用 `diagnoseInstance` 并将结果映射为统一 `Finding`。

#### Scenario: 产出根因结论

- **WHEN** 用户在失败实例上问「为什么挂了」或从驾驶舱进入诊断
- **THEN** Agent 返回针对该实例的根因结论文本 + 关键证据（机器指标/调度争抢）

#### Scenario: 默认真 LLM 推理诊断

- **WHEN** workhorse 可用且某 FAILED 实例进入诊断
- **THEN** `WorkhorseDiagnosisAnalyzer` 将真实遥测（节点内存、近 7 天同类失败次数、OOM 日志）发往 workhorse 推理，根因与建议由真模型生成（非套模板），结构化落 `task_diagnosis`/`finding`

#### Scenario: workhorse 不可用回落规则诊断

- **WHEN** workhorse 不可用 / 返回无法解析为结构化结论
- **THEN** 系统回落 `MockDiagnosisAnalyzer` 产出规则诊断，编排骨架与 AG-UI 事件结构不变，诊断仍产出

#### Scenario: 主动诊断未失败实例

- **WHEN** 巡检发现一个 FAILED 且无诊断的实例
- **THEN** 系统自动对其执行 `diagnoseInstance`，产出根因并映射为 OPEN 状态的 `Finding`，无需用户先发问

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

