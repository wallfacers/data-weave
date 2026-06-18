# agent-conversation Specification

## Purpose

定义 AG-UI 流式对话端点与前端对话界面的能力：后端 `dataweave-api` 暴露 AG-UI 兼容端点以 SSE 流式返回文本增量与结构化结果，前端 `/agent` 路由渲染流式文本与表格型结果，并支持亮/暗主题切换。

## Requirements

### Requirement: AG-UI 流式对话端点

后端 `dataweave-api` SHALL 暴露一个 AG-UI 兼容的对话端点，接收用户消息并以 SSE 流式返回 AG-UI 事件（文本增量与结构化结果）。

#### Scenario: 用户发送一条中文消息并收到流式回复
- **WHEN** 前端通过 `@ag-ui/client` 的 `HttpAgent` 向该端点 POST 一条用户消息
- **THEN** 端点以 SSE 返回一串 AG-UI 事件，至少包含一个文本增量事件和一个运行结束事件
- **AND** 响应的 `Content-Type` 为 SSE（`text/event-stream`）

#### Scenario: 端点无需 Node CopilotKit Runtime
- **WHEN** 前端 `CopilotKitProvider`（v2）以 `selfManagedAgents={{ dataweave: httpAgent }}` 直连该端点
- **THEN** 对话可正常渲染，不要求配置 `runtimeUrl` / `publicApiKey`

### Requirement: 对话界面与结果可视化

前端 SHALL 提供 `/agent` 路由，用户可输入自然语言，界面渲染流式文本与结构化结果（表格）。

#### Scenario: 渲染表格型结果
- **WHEN** AG-UI 事件携带结构化的表格结果（列定义 + 行数据）
- **THEN** `/agent` 页用 shadcn `Table` 渲染该结果，而非纯文本

#### Scenario: 亮/暗主题切换
- **WHEN** 用户切换主题
- **THEN** 界面在亮/暗之间切换，使用 `DESIGN.md` 导出的 oklch 主题变量
