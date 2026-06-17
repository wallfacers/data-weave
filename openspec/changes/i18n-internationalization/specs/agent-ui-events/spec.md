## ADDED Requirements

### Requirement: AG-UI 文本内容按 Agent locale 本地化

AG-UI `TEXT_MESSAGE_CONTENT` 事件承载的 markdown 内容 SHALL 按 **agent locale**（经 `x-dw-agent-locale` 请求头传入）本地化产出。该本地化范围涵盖 `IntentRouter`（mock 模式）与 `WorkhorseBridge`（workhorse 模式）产出的全部回复文本，以及 `CUSTOM(name="dataweave.result")` 载荷中的文案。`agent.mode=mock` 时，`IntentRouter` 的意图匹配关键词词典 SHALL 同时覆盖中文与英文（如「诊断 / why failed」），使任一语种的用户消息都能命中对应意图。

#### Scenario: agent locale 决定回复语种

- **WHEN** `/agui` 请求头 `x-dw-agent-locale: en-US`，用户询问失败诊断
- **THEN** `TEXT_MESSAGE_CONTENT` 的 markdown 以英文产出（「Root Cause」「Fix Suggestions」等），整体事件序列（RUN_STARTED…RUN_FINISHED）结构不变

#### Scenario: agent locale 缺失时 fallback

- **WHEN** `/agui` 请求未携带 `x-dw-agent-locale` 头，但 `Accept-Language: en-US`
- **THEN** 回复语种 fallback 到 `Accept-Language`（英文）；两个头均缺失时 fallback 到 zh-CN

#### Scenario: 英文用户消息命中意图

- **WHEN** `agent.mode=mock`，用户发送英文消息「why did the task fail」
- **THEN** 命中诊断意图并补发 `ui.open { view: "diagnosis" }`，行为与中文「为什么失败」一致

### Requirement: Agent locale 经请求头传输

AG-UI `/agui` 请求 SHALL 经 `x-dw-agent-locale` 请求头携带 agent locale（取值 `zh-CN` 或 `en-US`）。后端 SHALL 在整条 run 内沿用建连时传入的 agent locale 生成所有 markdown 回复与 MCP 工具描述，不得在中途变更。

#### Scenario: 请求头携带 agent locale

- **WHEN** 前端发起 `/agui` 请求，用户已将 Agent 语言设为 English
- **THEN** 请求头包含 `x-dw-agent-locale: en-US`，后端该 run 内所有 Agent 产出的文本均以英文生成
