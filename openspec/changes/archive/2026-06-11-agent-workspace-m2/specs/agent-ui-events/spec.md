# agent-ui-events Specification

## ADDED Requirements

### Requirement: dataweave.ui.open 事件契约

后端 SHALL 通过 AG-UI `CUSTOM` 事件召唤前端视图，事件名 `dataweave.ui.open`，载荷 `{ view: string, params?: object, activate?: boolean }`（`activate` 缺省为 true）。该事件 SHALL 出现在既有事件序列内（RUN_STARTED 与 RUN_FINISHED 之间），不改变 AG-UI 序列结构。

#### Scenario: 事件载荷结构

- **WHEN** 后端需要召唤「诊断视图，定位实例 i-17」
- **THEN** SSE 流中出现 `CUSTOM { name: "dataweave.ui.open", value: { view: "diagnosis", params: { instanceId: "i-17" } } }`，且整体序列仍以 RUN_STARTED 开头、RUN_FINISHED 结尾

### Requirement: 前端消费与去重激活

前端 SHALL 订阅 `dataweave.ui.open` 事件并调用 Workspace 打开视图。去重键 SHALL 为 `view + 规范化 params`：同键 tab 已存在时 SHALL 激活既有 tab 而非新开；同一次 run 内重复事件 SHALL 合并为一次打开。

#### Scenario: AI 召唤打开新 tab

- **WHEN** 用户对 Agent 说「看下集群机器」，后端发出 `ui.open { view: "fleet" }`
- **THEN** Workspace 自动打开并激活 fleet tab，对话流式回复正常完成

#### Scenario: 重复召唤去重激活

- **WHEN** fleet tab 已打开，后端再次发出 `ui.open { view: "fleet" }`
- **THEN** 既有 fleet tab 被激活，tab 总数不变

### Requirement: mock 模式发射规则

`agent.mode=mock` 时，`IntentRouter` 各意图分支 SHALL 在现有事件之外补发 `ui.open`：诊断意图→`diagnosis{instanceId}`、查机器→`fleet`、建任务→`task-flow{highlightTaskId}`、指标查询→`reports`、血缘→`lineage`、Text-to-SQL→`sql-workbench`。

#### Scenario: 诊断意图召唤诊断视图

- **WHEN** mock 模式下用户发送诊断类消息（命中诊断意图）
- **THEN** 事件流包含 `ui.open { view: "diagnosis", params: { instanceId: <对应实例> } }`

### Requirement: workhorse 模式发射规则

`agent.mode=workhorse` 时，`WorkhorseBridge` SHALL 维护静态「MCP 工具名 → viewType」映射表，在 `tool_call_done` 时按映射补发 `ui.open`（如 `create_task` → `task-flow`）。映射 SHALL 是确定性的，不依赖 LLM 输出格式；无映射的工具不发事件。

#### Scenario: 工具完成触发视图召唤

- **WHEN** workhorse 模式下 LLM 调用 `create_task` 工具且执行完成
- **THEN** 桥接层在转发流中补发 `ui.open { view: "task-flow" }`（闸门结果不含新任务 id，故无 params；mock 模式可带 highlightTaskId）

#### Scenario: 未映射工具不发事件

- **WHEN** LLM 调用一个映射表中不存在的查询工具
- **THEN** 事件流不出现 `ui.open`，其余转发行为不变
