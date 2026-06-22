## Why

工作流（workflow）的画布运行体验明显落后于任务（task）运行：任务刚做完「解绑发布与运行 + 日志 Tabs 化」，运行即自动开日志 Tab、实时滚屏、断线续传；而工作流运行时画布只有节点变色，看不到任何节点的实时日志，也无法在画布上右击节点做查看日志、单独运行、删除等操作，边的删除还只能靠默认的 Backspace。用户需要在画布上完成「运行 → 看日志 → 调单步 → 改图」的闭环，复用任务运行已经打磨好的那套日志零件即可，无需另造轮子。

## What Changes

- 工作流画布底部新增**运行日志 Tabs 区**：每个 TASK 节点对应一条独立日志流、一个 Tab（A1 粒度），复用任务编辑器现成的 `LogTab` / `RunLogsTabs` / `useEventSource`（含自动滚底与 Last-Event-ID 断线续传）。
- **运行时自动顶日志**：触发工作流运行后，随 DAG 状态流推进，自动把「当前正在运行的节点」日志 Tab 顶到前台。
- **节点右键菜单**（ContextMenu）：① 查看日志（运行中/已完成均可，续传现成）；② 单独运行；③ 删除节点。VIRTUAL 节点无任务，「单独运行」置灰。
- **单独运行节点**：按 B1 语义 —— 节点 `nodeKey → taskId`（前端 `instanceToTaskDef` 映射已有），复用现成 `POST /api/tasks/{taskId}/run` 脱离工作流独立试跑，返回 `resultInstanceId` 后开一个日志 Tab。**不进当前工作流实例、不触发下游**。
- **边操作**：边右键菜单（删除）+ 选中边按删除键删除。
- **删除键扩展**：React Flow `deleteKeyCode` 同时支持 `Backspace` 与 `Delete`（节点、边通用）。
- **i18n**：右键菜单项、Tab 文案、空态/提示文案双语补齐（zh-CN + en-US 等量）。

## Capabilities

### New Capabilities
<!-- 无新增 capability：本变更全部落在既有 workflow-canvas 行为上 -->

### Modified Capabilities
- `workflow-canvas`: 新增「画布运行日志 Tabs（每节点一 Tab、运行时自动顶上当前运行节点）」「节点右键菜单（查看日志/单独运行/删除）」「边右键菜单与删除键删除」三类画布交互要求；删除键从默认 Backspace 扩展为 Backspace + Delete。
- `realtime-streams`: **实现补缺** —— 既有「工作流状态实时流」要求 task_instance 状态变化发布到事件流，但实现侧从无任何 `eventBus.publish("dw:evt:…")`，该频道一直无人发布（节点 live 变色实际从未工作）。本次在 `InstanceStateMachine` 各 CAS 成功后补发 `dw:evt:{workflowInstanceId}` 状态事件，明确频道与 payload 契约。

## Impact

- **前端（主战场）**：
  - `frontend/components/workspace/task-editor-pane.tsx` —— 抽出 `LogTab` / `RunLogsTabs` / `RunTab` 类型为公共组件（新建 `frontend/components/workspace/run-logs-tabs.tsx` 或 `lib/workspace/` 下共享），供画布与任务编辑器共用。
  - `frontend/components/workspace/views/workflow-canvas-view.tsx` —— 底部接日志 Tabs 区（拖拽高度，照搬任务编辑器）、节点/边右键菜单、运行时自动顶日志、`deleteKeyCode` 配置。
  - `frontend/components/ui/context-menu.tsx` —— 已存在，本变更首次启用。
  - `frontend/messages/{zh-CN,en-US}.json` —— 新增 workflow 画布菜单/Tab 文案键。
- **后端**：原计划零改动；apply 阶段实测发现 `events/stream` 订阅的 `dw:evt:{id}` 频道**从无发布者**（既有节点 live 变色从未真正工作），故新增最小后端改动补缺：
  - `dataweave-master/.../application/InstanceStateMachine.java` —— 注入 `EventBus`，各 task CAS 成功后发布 `dw:evt:{workflowInstanceId}` 状态事件 `{"taskId","taskState"}`（单跑实例 workflow_instance_id 为 null 时跳过）；workflow CAS 发布 `{"workflowState"}`。
  - `dataweave-master/.../application/WorkerReportService.java` —— 失败级联置 STOPPED（绕过状态机的批量 UPDATE）后逐条补发事件。
  - 复用 `POST /api/tasks/{id}/run`、`GET /api/ops/instances/{id}/logs/stream`、`GET /api/ops/workflow-instances/{id}/events/stream`，端点本身不改。节点→实例映射按 taskDefId（详情接口现有字段，无需加 nodeKey）。
- **依赖**：无新增。沿用 `@xyflow/react`、`overlayscrollbars`、原生 `EventSource`。
- **验证**：触达画布 + AG-UI/SSE 渲染，需过 Browser Verification Gate（实跑一次，确认日志 Tab 渲染、节点右键菜单、单独运行开 Tab、边删除）。
