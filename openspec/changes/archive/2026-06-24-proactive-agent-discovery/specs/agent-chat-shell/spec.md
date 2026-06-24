## ADDED Requirements

### Requirement: 自有消息存储聊天台

前端 SHALL 以自研聊天台替换 CopilotKit，自行掌管消息存储。消息模型 SHALL 采用 `MessagePart` 联合（至少 `text`/`reasoning`/`tool_call`/`permission`/`error`/`pending`）与 `ChatMessage{id,role,parts[]}`，每会话一份 `ChatRuntime{messages[],streaming}`。用户发问 SHALL 经 `POST /agui` 消费 AG-UI 事件流（沿用既有 SCREAMING_SNAKE_CASE 事件序列），增量追加 parts。

#### Scenario: 用户对话流式渲染

- **WHEN** 用户发送一条消息
- **THEN** 前端 `POST /agui`，按 AG-UI 事件流增量渲染助手回复，结束于 RUN_FINISHED

#### Scenario: 掌管消息存储

- **WHEN** 任意来源需要向会话追加一条消息
- **THEN** 直接向对应 `ChatRuntime.messages` 追加，不依赖任何第三方聊天框架的注入能力

### Requirement: Agent 主动开口

前端 SHALL 持久订阅 `GET /api/agent/stream`。收到 `agent.message` 时 SHALL 将其作为一条助手消息追加进目标会话（无需用户先发问）；收到 `agent.finding` 时 SHALL 刷新举手台。

#### Scenario: 无人发问 Agent 主动说话

- **WHEN** 通道推来一条 `agent.message`
- **THEN** 左栏聊天台出现一条助手消息（如「我发现 X 失败了，根因…，要我处理吗」），用户未曾发问

### Requirement: 举手台渲染通用 Finding

前端举手台 SHALL 从 `GET /api/findings` 渲染通用 `Finding[]`（不再绑定单一诊断类型），展示 title/severity/rootCause/evidence；每个 Finding 的修复项 SHALL 可一键调用 `POST /api/findings/{id}/apply`。

#### Scenario: 通用发现上举手台

- **WHEN** 存在任意来源的未解决 Finding
- **THEN** 举手台按统一卡片渲染，无需区分来源

### Requirement: 闸门审批内联

当修复动作被闸门裁为 PENDING_APPROVAL 时，前端 SHALL 以 `permission` part 内联渲染同意/拒绝交互；用户点击 SHALL 直接提交决策并更新该 part 状态。

#### Scenario: 内联审批

- **WHEN** `POST /api/findings/{id}/apply` 返回 outcome=PENDING_APPROVAL
- **THEN** 会话内出现内联审批控件，用户点同意/拒绝即提交决策

### Requirement: 多会话

前端 SHALL 支持多会话（`runtimes: Map<sessionId, ChatRuntime>` + 会话侧栏），并经后端 `/api/agent/sessions*` 持久化（新建/列出/删除/历史重水合）。切换会话 SHALL 保留各自消息缓冲；非可见会话的后台流 SHALL 继续接收。

#### Scenario: 多会话并存

- **WHEN** 用户新建第二个会话并在其间切换
- **THEN** 每个会话保留独立消息历史，互不串台

#### Scenario: 会话持久化与重水合

- **WHEN** 用户重开一个已存在会话
- **THEN** 前端经 `GET /api/agent/sessions/{id}/history` 拉取并重建该会话消息
