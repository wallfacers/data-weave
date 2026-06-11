# workspace-persistence Specification

## ADDED Requirements

### Requirement: workspace 快照随会话存储

系统 SHALL 将 Workspace 状态快照随对话会话持久化：`agent_session` 表新增 `workspace_state` 文本列，存放前端序列化的 JSON（后端视为透明 blob，不解析语义）。快照 SHALL 按 `conversation_id`（AG-UI threadId）寻址，复用既有会话取或建逻辑。

#### Scenario: 快照随会话落库

- **WHEN** 前端对某 conversationId PUT workspace 快照
- **THEN** 对应 `agent_session` 行的 `workspace_state` 被更新；该 conversationId 无会话时先创建再写入

### Requirement: workspace 快照 REST 接口

api 模块 SHALL 暴露 `GET /api/agent/sessions/{conversationId}/workspace` 与 `PUT /api/agent/sessions/{conversationId}/workspace`。GET 在无快照时 SHALL 返回明确的空态（如 204 或空对象）而非错误；CORS 放行与既有端点一致。

#### Scenario: 读取已存快照

- **WHEN** 前端 GET 一个已写入快照的 conversationId
- **THEN** 返回上次 PUT 的 JSON 原文

#### Scenario: 无快照返回空态

- **WHEN** 前端 GET 一个从未写入快照的 conversationId
- **THEN** 返回空态响应，前端据此使用默认 Pinned 布局，不报错

### Requirement: 前端防抖同步与恢复回退

前端 SHALL 在 Workspace 状态变更后防抖（约 1 秒）PUT 快照；挂载时按当前 conversationId GET 恢复。快照 SHALL 只承载 Ephemeral tabs（含 pin 升级者）与激活 tab——Pinned 底座不依赖快照。GET 失败、快照损坏或引用未注册视图时 SHALL 回退默认 Pinned 布局（未知视图静默丢弃）。

#### Scenario: 刷新后恢复工作区

- **WHEN** 用户打开了若干 Ephemeral tab 后刷新页面（同一会话）
- **THEN** Workspace 恢复这些 tab 与激活态，Pinned 四 tab 始终在位

#### Scenario: 快照损坏回退底座

- **WHEN** 挂载时 GET 返回无法解析的 JSON 或引用了已不存在的 viewType
- **THEN** 损坏/未知部分被静默丢弃，Workspace 至少呈现完整 Pinned 布局，不白屏

#### Scenario: 持久化失败不阻塞交互

- **WHEN** 防抖 PUT 因网络故障失败
- **THEN** Workspace 交互不受影响，下次状态变更时重试
