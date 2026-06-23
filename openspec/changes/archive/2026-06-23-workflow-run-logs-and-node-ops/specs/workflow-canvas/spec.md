## ADDED Requirements

### Requirement: 画布运行日志 Tabs

工作流画布子 Tab SHALL 在其下方提供**运行日志 Tabs 区**，与任务编辑器共用同一套日志组件（`LogTab` / `RunLogsTabs` / `useEventSource`）。粒度 MUST 为「每个 TASK 节点一条独立日志流、一个 Tab」（A1）：每个 Tab 订阅该节点对应任务实例的日志流 `GET /api/ops/instances/{taskInstanceId}/logs/stream`，节点到 `taskInstanceId` 的映射来自工作流实例详情。日志区 MUST 支持自动滚底（用户上滚查看历史时暂停自动滚动）、断线续传（Last-Event-ID）、运行中/已结束/错误的连接态指示，以及拖拽调整高度（与任务编辑器一致，高度持久化）。日志区在无任何运行日志 Tab 时 MUST 隐藏。VIRTUAL 节点无任务实例，MUST NOT 产生日志 Tab。

#### Scenario: 运行时自动顶上当前运行节点日志
- **WHEN** 用户触发工作流运行后，DAG 状态流推进，某 TASK 节点进入运行中
- **THEN** 画布自动打开该节点的日志 Tab 并将其置为前台激活，日志实时滚屏

#### Scenario: 日志 Tab 复用任务运行组件与续传
- **WHEN** 一个节点日志 Tab 在流式接收日志过程中发生断线
- **THEN** 前端携带 Last-Event-ID 重连并接续日志，不重复刷全量、不丢行

#### Scenario: 已完成实例仍可读历史日志
- **WHEN** 用户打开一个已运行结束节点的日志 Tab
- **THEN** 日志区展示该实例的历史日志（经实时流/归档/DB 摘要回退），连接态显示为已结束

#### Scenario: 无运行日志时日志区隐藏
- **WHEN** 画布当前没有任何打开的运行日志 Tab
- **THEN** 画布不渲染日志 Tabs 区，编辑区占满可用高度

### Requirement: 画布节点右键菜单

画布 SHALL 为节点提供右键上下文菜单（启用既有 `<ContextMenu>` 组件），在运行态与编辑态下均可用。TASK 节点菜单 MUST 含：①「查看日志」——打开/激活该节点日志 Tab（运行中或已完成均可）；②「单独运行」——脱离当前工作流，按节点绑定的 `task_id` 调用现成 `POST /api/tasks/{taskId}/run`（B1 语义：不进当前工作流实例、不触发下游），成功后用返回的 `resultInstanceId` 打开一个日志 Tab；③「删除节点」——从画布移除该节点并置脏。VIRTUAL 节点无任务，「单独运行」与「查看日志」MUST 置灰或不出现，仅保留「删除节点」。

#### Scenario: 右击 TASK 节点查看日志
- **WHEN** 用户右击一个有运行实例的 TASK 节点并点「查看日志」
- **THEN** 画布打开/激活该节点对应实例的日志 Tab

#### Scenario: 单独运行节点开新日志 Tab
- **WHEN** 用户右击一个 TASK 节点并点「单独运行」
- **THEN** 前端以该节点 `task_id` 调用 `POST /api/tasks/{taskId}/run`，运行被闸门放行后用 `resultInstanceId` 打开一个日志 Tab；该运行不进当前工作流实例、不触发下游节点

#### Scenario: VIRTUAL 节点单独运行不可用
- **WHEN** 用户右击一个 VIRTUAL 节点
- **THEN** 菜单中「单独运行」「查看日志」置灰或不出现，「删除节点」可用

#### Scenario: 右击删除节点置脏
- **WHEN** 用户右击节点并点「删除节点」
- **THEN** 画布移除该节点（及其关联边），标记未保存脏状态

### Requirement: 画布边操作与删除键

画布 SHALL 支持删除边：边可被选中并通过右键菜单「删除」移除，也 MUST 支持选中后按删除键删除。React Flow 的 `deleteKeyCode` MUST 同时识别 `Backspace` 与 `Delete`，对选中的节点与边均生效。任何删除 MUST 标记未保存脏状态，不静默丢弃。

#### Scenario: 选中边按 Delete 删除
- **WHEN** 用户点击选中一条边后按 `Delete` 键
- **THEN** 该边从画布移除并置脏

#### Scenario: Backspace 仍可删除
- **WHEN** 用户选中一个节点或一条边后按 `Backspace` 键
- **THEN** 选中元素被删除并置脏（保留既有默认键位）

#### Scenario: 右击边删除
- **WHEN** 用户右击一条边并点「删除」
- **THEN** 该边从画布移除并置脏
