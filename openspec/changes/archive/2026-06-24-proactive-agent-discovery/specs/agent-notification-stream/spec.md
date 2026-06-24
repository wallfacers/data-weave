## ADDED Requirements

### Requirement: 持久服务端推送通道

系统 SHALL 提供持久 SSE 端点 `GET /api/agent/stream`（`text/event-stream`，长连），作为服务端主动向客户端推送的通道。该通道 SHALL 至少承载事件：`agent.finding`（新发现的结构化 Finding）、`agent.message`（Agent 主动开口的会话消息 `{sessionId?,markdown,findingId?}`）、`keepalive`（保活）。事件名 SHALL 采用小写点分（如 `agent.finding`），与 AG-UI 的 SCREAMING_SNAKE_CASE 事件区分。`POST /agui` 既有契约 SHALL 保持不变。

#### Scenario: 客户端订阅持久通道

- **WHEN** 前端挂载时连接 `GET /api/agent/stream`
- **THEN** 连接保持打开，周期性收到 `keepalive`，并在有新事件时实时收到推送

#### Scenario: 发现即推送

- **WHEN** 巡检器落库一条新 Finding
- **THEN** `AgentNotifier` 经该通道广播一条 `agent.finding` 事件，已连接的客户端立即收到

### Requirement: AgentNotifier 广播

系统 SHALL 以 `AgentNotifier` 统一对外广播（all-in-one 模式经进程内 Reactor `Sinks` 多播；分布式模式经 Redis pub/sub 桥接）。同一事件 SHALL fan-out 到所有已连接客户端。

#### Scenario: 多客户端同时收到

- **WHEN** 有多个客户端连接 `/api/agent/stream` 且发生一次广播
- **THEN** 每个客户端都收到该事件各一份

#### Scenario: 主动开口

- **WHEN** 系统判定需让 Agent 就某 Finding 主动开口
- **THEN** 经通道推送一条 `agent.message`，载明 markdown 与关联 `findingId`，供前端追加进会话
