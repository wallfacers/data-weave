# workflow-canvas Specification

## Purpose
工作流编排的前端可视化能力：基于 React Flow 的 DAG 画布编辑器，支持拖拽建节点、连线建边、节点定位，以及草稿保存与发布的交互闭环（含本地成环检测与未发布改动提示）。作为独立 workspace 视图注册，不改动既有 `task-flow-view`。

## Requirements
### Requirement: 可视化 DAG 画布编辑

系统 SHALL 提供基于 React Flow（`@xyflow/react` v12+）的可视化工作流画布。画布 MUST 支持：从左侧 `task_def` 列表拖入节点（建 `TASK` 节点并绑定该 task）、从工具栏放置 `VIRTUAL` 节点、节点间拉线建边、节点拖动改变位置。节点位置变化 MUST 反映到内存图并在保存时回写 `pos_x/pos_y`。画布视图 MUST 作为独立 workspace 视图注册，不改动既有 `task-flow-view`。

#### Scenario: 拖入任务建节点
- **WHEN** 用户从左侧任务列表将一个 `task_def` 拖到画布
- **THEN** 画布在落点创建一个 `TASK` 节点，绑定该 task_id，记录落点坐标

#### Scenario: 放置虚拟节点并连线
- **WHEN** 用户从工具栏添加 `VIRTUAL` 节点并从它连线到一个 `TASK` 节点
- **THEN** 画布创建虚拟节点与一条有向边（虚拟节点→任务节点）

#### Scenario: 节点类型可视区分
- **WHEN** 画布渲染节点
- **THEN** `TASK` 与 `VIRTUAL` 节点以不同的视觉样式（图标/形状）区分，使用语义化设计 token

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
