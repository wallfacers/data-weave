# workflow-canvas Specification

## Purpose
工作流编排的前端可视化能力：基于 React Flow 的 DAG 画布编辑器，支持拖拽建节点、连线建边、节点定位，以及草稿保存与发布的交互闭环（含本地成环检测与未发布改动提示）。作为独立 workspace 视图注册，不改动既有 `task-flow-view`。
## Requirements
### Requirement: 可视化 DAG 画布编辑

系统 SHALL 提供基于 React Flow（`@xyflow/react` v12+）的可视化工作流画布。画布 MUST 支持：从左侧**类目树**（`<CatalogTree>`，按文件夹组织的 `task_def`）拖入节点（建 `TASK` 节点并绑定该 task）、从工具栏放置 `VIRTUAL` 节点、节点间拉线建边、节点拖动改变位置。节点位置变化 MUST 反映到内存图并在保存时回写 `pos_x/pos_y`。画布 MUST 区分两种拖拽语义：在类目树内拖动节点表示移动归属（调归类接口），从类目树拖任务到画布表示建 DAG 节点；以 drop target（树容器 vs ReactFlow pane）判定意图。画布 MUST NOT 渲染常驻拖拽提示文案。画布 MUST 作为"数据开发"IDE 内的一种**子 Tab** 呈现（一个工作流对应一个画布子 Tab），不再作为独立顶层 workspace 视图。

#### Scenario: 拖入任务建节点
- **WHEN** 用户从左侧类目树将一个 `task_def` 拖到画布（ReactFlow pane）
- **THEN** 画布在落点创建一个 `TASK` 节点，绑定该 task_id，记录落点坐标，且不改变该 task 的类目归属

#### Scenario: 树内拖动移动归属而非建节点
- **WHEN** 用户在左侧类目树内把一个任务节点拖入另一个文件夹（drop target 在树容器内）
- **THEN** 前端调用归类接口更新该任务归属，画布上不新建任何节点

#### Scenario: 放置虚拟节点并连线
- **WHEN** 用户从工具栏添加 `VIRTUAL` 节点并从它连线到一个 `TASK` 节点
- **THEN** 画布创建虚拟节点与一条有向边（虚拟节点→任务节点）

#### Scenario: 节点类型可视区分
- **WHEN** 画布渲染节点
- **THEN** `TASK` 与 `VIRTUAL` 节点以不同的视觉样式（图标/形状）区分，使用语义化设计 token

#### Scenario: 画布以子 Tab 承载
- **WHEN** 用户点击类目树中一个工作流叶子
- **THEN** 该工作流的画布在数据开发 IDE 内层区以一个画布子 Tab 打开/激活，而非独立顶层视图

### Requirement: 草稿保存与发布交互

画布 SHALL 在前端内存维护编辑态并跟踪脏标记。用户点击「保存草稿」时 MUST 一次性 PUT 全量 `{nodes, edges}` 到 `/api/workflows/{id}/dag`。用户点击「发布」时 MUST 调用 `/api/workflows/{id}/publish`。保存或发布成功 SHALL 清除脏标记并刷新状态；失败（含 409 冲突、发布环路拒绝）MUST 向用户给出可读提示且不静默丢弃编辑。

#### Scenario: 保存草稿
- **WHEN** 用户编辑画布后点击「保存草稿」
- **THEN** 前端整图 PUT，成功后清除「未保存」脏标记

#### Scenario: 发布环路被拒
- **WHEN** 用户发布一个存在环路的画布，后端返回拒绝
- **THEN** 前端展示环路提示，画布编辑态保留不丢失

#### Scenario: 本地连线即时环路反馈
- **WHEN** 用户尝试拉一条会使画布成环的边
- **THEN** 前端本地检测并拒绝该连线，给出即时提示（不等待后端）

#### Scenario: 未发布改动提示
- **WHEN** 工作流 `has_draft_change=1`
- **THEN** 画布明示「有未发布改动」状态

### Requirement: 画布叠加 DAG 运行态

画布子 Tab SHALL 能订阅其所属工作流某次运行实例的事件流（`/api/ops/workflow-instances/{id}/events/stream`），并把每个节点的运行态实时叠加渲染到对应 DAG 节点上（按 node_key 对应）。运行态 MUST 以语义化设计 token 区分（运行中/成功/失败/等待），与既有节点类型样式叠加而不冲突。断线 MUST 支持续传（Last-Event-ID）。

#### Scenario: 节点随事件变色
- **WHEN** 画布订阅了一个运行中工作流实例的事件流，某节点状态从等待变为运行中再到成功
- **THEN** 画布上对应 node_key 的节点依次切换为运行中/成功的运行态样式

#### Scenario: 编辑态与运行态可区分
- **WHEN** 画布同时处于可编辑状态并叠加了运行态
- **THEN** 运行态着色不掩盖节点类型与选中态，用户仍能识别 TASK/VIRTUAL 与当前选择

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

