# copilot-rail Specification

## Purpose

定义全站左侧常驻 Agent 对话主驾栏的行为：基于 CopilotKit v2 + AG-UI 直连后端、跨视图常驻、拖拽调宽、Workspace 激活 tab 上下文感知。

## Requirements

### Requirement: 右舷常驻 Agent 面板

系统 SHALL 在全站**左侧**常驻 Agent 对话主驾栏，基于 CopilotKit v2（`@copilotkit/react-core/v2`）+ `@ag-ui/client` `HttpAgent` 直连后端 `/agui`。Provider SHALL 提升至全局 layout，使对话状态在 Workspace 视图切换间不丢失。对话栏 SHALL 支持拖拽调宽且宽度持久化；作为主驾，它 SHALL 始终可见，不提供整体收起。

#### Scenario: 全局常驻且跨视图保持

- **WHEN** 用户在 Workspace 中切换、打开或关闭 tab
- **THEN** 左栏 Agent 对话持续可见，已有对话历史不被清空

#### Scenario: 直连后端 AG-UI 流式回复

- **WHEN** 用户在左栏输入中文消息并发送
- **THEN** 面板通过 AG-UI 协议收到流式回复并以 Markdown 渲染（RUN_STARTED…RUN_FINISHED 序列完整）

#### Scenario: 拖拽调宽并持久化

- **WHEN** 用户拖拽对话栏与 Workspace 之间的分割线
- **THEN** 对话栏宽度实时变化并在刷新后保持

### Requirement: 页面上下文感知

左栏 Agent SHALL 感知 Workspace 当前激活 tab 与选中对象（视图类型、视图参数、选中的任务/失败实例/机器节点），并将该上下文随对话一并提供给后端，使「就当前所见」提问无需用户重复说明对象。

#### Scenario: 携带激活视图上下文提问

- **WHEN** 用户激活某失败实例的 `diagnosis` tab 后在左栏问「为什么挂了」
- **THEN** Agent 收到的请求携带该失败实例标识，回复针对该实例而非泛泛而谈
