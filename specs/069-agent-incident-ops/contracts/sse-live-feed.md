# Contract: 指挥中心直播流 SSE（`GET /api/incidents/stream`）

- `produces: text/event-stream`；鉴权经 query `token`（EventSource 无自定义 header）+ `projectId` query（项目域隔离）——对齐既有日志/DAG 流模式；前端用 `useEventSource` 直连 `SSE_BASE`（绕过 Next rewrite 缓冲，既有硬约定）。
- 通道来源：Redis pub/sub `dw:incident:evt:{projectId}`（`EventBus.publish/subscribe`），API 节点以 Sinks 桥接；连接建立先发**快照**再直播（同 `workflowEventsStream` 模式）。
- `Last-Event-ID` = 最后收到的持久化消息 `incidentId:seq`；重连时快照补齐持久化消息，瞬态事件不重放。

## 事件类型（`event:` 字段）与 `data:` JSON

### 快照与结构事件（持久化背书，可重放）

| event | data | 说明 |
|---|---|---|
| `snapshot` | `{incidents: IncidentSummary[], briefingStats}` | 连接建立时一次：全部未收口事故 + 实时数字 |
| `incident` | `IncidentSummary` | 事故开立/状态变更/归并/收口（含 state 变化，前端据此更新置顶区与过滤计数） |
| `message` | `IncidentMessage`（含 incidentId） | 持久化消息落库后广播（诊断结论/动作/对话完整轮/系统事件/提案卡） |
| `briefing` | `{summaryLine, stats, generatedAt}` | 播报更新（防抖后） |

### 瞬态直播事件（不落库、不重放——「智能感」层）

| event | data | 说明 |
|---|---|---|
| `thinking` | `{incidentId, phase: START\|STOP, label}` | 思考态指示（如「正在分析日志」） |
| `chip` | `{incidentId, chipId, label, status: RUNNING\|DONE\|FAILED}` | 工具动作点亮（读取日志 ✓ → 分析代码 ✓ → …） |
| `delta` | `{incidentId, streamId, text}` | 流式文本分片（诊断叙述/对话回复打字流）；同 streamId 的分片拼接，收到对应持久化 `message`（payload.streamId 匹配）即以完整消息替换 |
| `end` | `{reason}` | 服务端主动关流（对齐既有 end 语义，前端停止重连） |

## 顺序与一致性约定

1. 同一事故内：`message.seq` 单调递增；瞬态事件夹在相邻持久化消息之间，无序号、允许丢失（断线不补）。
2. `delta` 流以持久化 `message` 收尾——前端永远以落库消息为最终真相（SC-005 可还原性只依赖持久化层）。
3. 事故状态以最后一条 `incident` 事件为准；快照兜底纠偏。
4. 心跳：沿用平台 SSE 心跳/超时惯例，空闲期发注释行保活。

## 前端消费（supervision 视图）

- feed：`snapshot` 建表 → `incident`/`message` 增量 → `thinking`/`chip`/`delta` 驱动进行中条目的动效与打字流（全部 `motion-safe:`，`prefers-reduced-motion` 降级为静态状态文本）。
- 线程视图复用同一连接（按 incidentId 过滤事件），历史翻页走 `GET /api/incidents/{id}/messages?afterSeq=`。
- 洪峰（FR-015/US4-7）：待处理（AWAITING_APPROVAL/NEEDS_HUMAN）固定置顶区不参与滚动流；feed 按 state/taskDefId 客户端过滤。
