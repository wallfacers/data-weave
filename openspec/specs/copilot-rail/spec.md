# copilot-rail Specification

## Purpose

定义全站右舷常驻 Agent 面板的行为：基于 CopilotKit v2 + AG-UI 直连后端、跨页常驻、收起/悬浮球展开、页面上下文感知。

## Requirements

### Requirement: 右舷常驻 Agent 面板

系统 SHALL 在全站右侧常驻一个 Agent copilot 面板，基于 CopilotKit v2（`@copilotkit/react-core/v2`）+ `@ag-ui/client` `HttpAgent` 直连后端 `/agui`。Provider SHALL 提升至全局 layout，使对话状态跨页面切换不丢失。原 `/agent` 独立菜单项 SHALL 由该常驻面板取代。

#### Scenario: 全局常驻且跨页保持

- **WHEN** 用户在不同模块路由间切换
- **THEN** 右舷 Agent 面板持续可见，已有对话历史不被清空

#### Scenario: 直连后端 AG-UI 流式回复

- **WHEN** 用户在右舷输入中文消息并发送
- **THEN** 面板通过 AG-UI 协议收到流式回复并以 Markdown 渲染（RUN_STARTED…RUN_FINISHED 序列完整）

### Requirement: 面板收起与悬浮球展开

右舷面板 SHALL 提供右上角 `✕` 收起按钮；收起后 SHALL 在**同一位置（右上角贴边）**呈现悬浮球，点击悬浮球 SHALL 原地展开回完整面板。开合落点 SHALL 一致，避免位移。

#### Scenario: 收起为同位悬浮球

- **WHEN** 用户点击右舷面板右上角 `✕`
- **THEN** 面板收起，右上角贴边出现悬浮球，中部观测台占满腾出的空间

#### Scenario: 悬浮球原地展开

- **WHEN** 用户点击右上角悬浮球
- **THEN** 右舷面板在原位置重新展开，先前对话历史保留

### Requirement: 页面上下文感知

右舷 Agent SHALL 感知用户当前所在页面与选中对象（当前模块、选中的任务/失败实例/机器节点），并将该上下文随对话一并提供给后端，使「就当前所见」提问无需用户重复说明对象。

#### Scenario: 携带当前页面上下文提问

- **WHEN** 用户在某失败任务的诊断视图打开右舷并问「为什么挂了」
- **THEN** Agent 收到的请求携带该失败实例标识，回复针对该实例而非泛泛而谈
