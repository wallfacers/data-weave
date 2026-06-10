## ADDED Requirements

### Requirement: 双模式开关
系统 SHALL 支持 `agent.mode=mock|workhorse`（默认 mock）。mock 模式走既有 IntentRouter 路径且零外部依赖；workhorse 模式把对话转发至 workhorse 会话。两模式 MUST 产出同构的 AG-UI 事件序列（RUN_STARTED…RUN_FINISHED，经同一 AguiOrchestrator 出口）。

#### Scenario: mock 模式零依赖照旧
- **WHEN** 未配置 workhorse 且 mode=mock
- **THEN** 现有全部意图与测试行为不变

#### Scenario: workhorse 模式流式透传
- **WHEN** mode=workhorse 且用户发送消息
- **THEN** 桥接层建立/复用 workhorse 会话，模型流式文本经 AG-UI TEXT_MESSAGE_CONTENT 增量送达右舷

### Requirement: 会话映射
桥接层 SHALL 维护 AG-UI 对话与 workhorse 会话的一对一映射（`agent_session` 表），同一前端对话的后续消息 MUST 复用同一 workhorse 会话以保留上下文。

#### Scenario: 跨消息上下文保留
- **WHEN** 用户在同一右舷对话连续发两条消息
- **THEN** 第二条到达同一 workhorse 会话，agent 可引用第一条的结论

### Requirement: 页面上下文逐消息注入
前端 SHALL 把当前页面上下文（模块路径、选中的 taskId/instanceId/nodeId）随**每条**用户消息送达后端；后端两种模式 MUST 都消费：mock 模式 IntentRouter 据此定位对象，workhorse 模式拼入消息上下文段。

#### Scenario: 免复述对象
- **WHEN** 用户在某失败实例详情页对右舷问「为什么挂了」
- **THEN** 后端收到该 instanceId 并针对该实例作答，无需用户复述

#### Scenario: 导航后上下文跟随
- **WHEN** 用户从 /ops 切到 /fleet 再提问
- **THEN** 该消息携带的上下文为 /fleet 及其选中节点，而非旧页面

### Requirement: 审批卡片交互
workhorse/平台产生审批单时，右舷 SHALL 渲染 `dataweave.approval` CUSTOM 事件为审批卡片（摘要、等级、批准/拒绝按钮）；用户决策后前端调用审批接口，并自动向对话追加一条说明消息使 agent 感知结果并继续。

#### Scenario: 批准续做
- **WHEN** 用户点击审批卡片「批准」
- **THEN** 平台执行动作，右舷自动追加「审批单 #N 已批准并执行：<结果摘要>」，agent 在后续回复中延续任务
