## Context

任务运行刚完成「解绑发布与运行 + 日志 Tabs 化」（commit db8482b）。`task-editor-pane.tsx` 内已沉淀一套成熟日志零件：`useEventSource`（原生 EventSource + Last-Event-ID 续传 + end 主动关闭防重连）、`LogTab`（OverlayScrollbars 自动滚底、连接态指示）、`RunLogsTabs`（TabStrip 容器 + 关闭族操作）。但这些零件目前**内联在 task-editor-pane 内部**，未抽出。

工作流画布 `workflow-canvas-view.tsx` 基于 `@xyflow/react`（React Flow v12），已有：节点/边渲染、选中态、`onNodesChange/onEdgesChange` 删除落草稿、运行时订阅 `/api/ops/workflow-instances/{id}/events/stream` 给节点变色、`instanceToTaskDef` 映射（实例 UUID ↔ taskDefId）。缺：日志 Tabs 区、右键菜单、单独运行、删除键扩展。

后端三个端点全部现成且通用：`POST /api/tasks/{id}/run`、`GET /api/ops/instances/{id}/logs/stream`、`GET /api/ops/workflow-instances/{id}/events/stream`。

## Goals / Non-Goals

**Goals:**
- 工作流画布获得与任务运行**同构**的实时日志体验（每节点一 Tab、自动滚屏、断线续传），通过**抽公共组件**复用而非复制。
- 画布节点/边支持右键菜单与删除键，节点支持「单独运行」（B1：脱流试跑）。
- 后端零改动（或仅确认实例详情已返回 node→instance 映射）。

**Non-Goals:**
- 不做「在工作流实例内重跑节点 + 触发下游」（B2 语义）—— 复杂度高一档，本次明确排除。
- 不新增工作流级聚合日志流端点（A2）—— 维持每节点独立流。
- 不改任务编辑器现有行为，只把其内部零件抽出共用，对外表现不变。
- 不改后端调度/闸门/审计链路。

## Decisions

### 决策 1：抽出公共日志组件，task 与 workflow 共用
**选择**：把 `RunTab` 类型、`LogTab`、`RunLogsTabs`（含连接态 `conn` 管理与关闭族操作）从 `task-editor-pane.tsx` 提取到新文件（拟 `frontend/components/workspace/run-logs-tabs.tsx`），两处 import。
**理由**：spec 要求「与任务编辑器共用同一套日志组件」，避免两份漂移。`useEventSource` 已在 `lib/workspace/`，本身就是共享的。
**备选**：复制一份到画布 —— 否决，违背 DRY，后续两边各自演化必然分叉。

### 决策 2：日志 Tab 粒度 = 每节点一条实例流（A1）
**选择**：每个打开的节点对应一个 `RunTab{ instanceId=taskInstanceId, taskName=节点名, kind:"log" }`，订阅 `/api/ops/instances/{taskInstanceId}/logs/stream`。
**理由**：与任务运行语义一致（一个 Tab = 一条真实实例日志流），零成本复用 `LogTab`。工作流没有单条「整流日志」，聚合需新端点。
**备选**：A2 聚合流 —— 否决（要新后端端点 + 前缀合并）；A3 混合 —— 暂不做，A1 已满足需求。

### 决策 3：运行时自动顶日志 —— 复用既有状态流，不新增订阅
**选择**：画布已订阅 events/stream 拿 `{taskId(实例UUID), taskState}`。当某节点 taskState 进入 `RUNNING/DISPATCHED` 且其日志 Tab 未打开时，自动建 Tab 并 `setActive`。用 `instanceToTaskDef` 反查节点名。需去重：同一实例只开一次（用已开 Tab 的 instanceId 集合判重）。
**理由**：状态流本就在驱动节点变色，顺势触发开 Tab，零额外网络订阅。
**风险点**：状态事件可能晚于/乱序到达，判重必须以 instanceId 为键而非节点位置。

### 决策 4：单独运行 = 节点 taskId → 复用任务运行端点（B1）
**选择**：右键「单独运行」时，节点 `nodeKey.taskId → POST /api/tasks/{taskId}/run`（body 可空或仅 bizDate），拿 `resultInstanceId` 开日志 Tab。
**理由**：后端零改动；语义清晰即「单测这一步的已发布任务」。`run` 端点已内建已发布(NORMAL)/草稿(TEST) 分流与闸门。
**取舍**：跑的是**该任务的已发布版本**，不带工作流上下文、不触发下游。VIRTUAL 无 taskId → 置灰。这点已与用户确认接受。
**注意**：返回可能是 `PENDING_APPROVAL`/`REJECTED`（闸门 L2+），前端须按 outcome 分别提示，仅 `EXECUTED` 才开 Tab —— 与任务编辑器 handleRun 处理一致，逻辑可直接搬。

### 决策 5：右键菜单用既有 `<ContextMenu>`，删除键用 React Flow 原生
**选择**：节点/边各包一层 `ContextMenuTrigger`；删除走 React Flow `deleteKeyCode={["Backspace","Delete"]}`，删除事件仍经 `onNodesChange/onEdgesChange` 的 `remove` 分支落草稿（已有逻辑）。
**理由**：组件已存在仅未启用；删除键是一行配置，删除落草稿链路已通。
**备选**：自写键盘监听 —— 否决，React Flow 原生已支持且更稳。

## Risks / Trade-offs

- **公共组件抽取触碰刚上线的任务运行路径** → 抽取后必须对**任务编辑器**也跑一次 Browser Verification Gate，确认任务日志 Tab 行为零回归（不只验画布）。
- **node→taskInstanceId 映射缺失/字段名不符** → 实施前先确认工作流实例详情接口返回结构（前端 `instanceToTaskDef` 已存在，理论上够；若取日志需要 instanceId 而当前 Map 是 taskDefId→state，需补 nodeKey/taskDefId → instanceId 的反查）。这是最可能的隐藏工作量，列为首个实施任务去核对。
- **自动顶日志打断用户**：运行中用户可能正在看 A 节点日志，自动顶到 B 会打断 → 仅在「该实例 Tab 尚未打开」时自动 setActive；已打开的不抢焦点（或仅首个运行节点抢焦点）。实施时按"首次出现才顶、已存在不抢焦"。
- **删除键与画布内编辑冲突**：若未来节点/标签可内联编辑，Delete 键会误删 → 当前画布无内联文本编辑，暂无冲突；React Flow 在输入框聚焦时默认不触发 deleteKeyCode。
- **闸门 PENDING/REJECTED 路径**：单独运行可能不立即执行 → 必须复用任务编辑器的 outcome 分支提示，不能假定总是 EXECUTED。

## Migration Plan

- 纯前端增量 + 后端零改动，无数据迁移、无 DDL。
- 抽取公共组件为第一步，确保 task 编辑器编译/类型通过且浏览器实跑无回归后，再在画布接入。
- 回滚：还原 `workflow-canvas-view.tsx` 与公共组件文件即可，不影响后端与任务运行。

## Open Questions

- 工作流实例详情接口是否已直接返回每节点的 `taskInstanceId`？（实施任务 1 核对；若只返回 taskDefId 需补字段或前端二次查询。）→ **已解决**：详情返回 `id(实例UUID)/taskDefId/...`（无 nodeKey），按 taskDefId 派生 `taskDefToInstance` 即可，无需改后端接口。
- 「单独运行」是否需要让用户选 bizDate？初版默认不弹、用空/当天，后续可加；本次按最简（不弹）实现。

## Apply 阶段发现（推翻「后端零改动」前提）

实测发现：`events/stream` 订阅的 `dw:evt:{id}` 频道**全代码库无任何发布者**（所有 `eventBus.publish` 都发往调度 `WAKE_CHANNEL`）。即既有的「节点 live 变色」从未真正经事件流工作过——只靠 `runWorkflow` 那一次性详情拉取上色。实证：慢节点(sleep)后端已 SUCCESS，画布节点点却卡在初始态；events/stream 抓 12s 零负载。

经与用户确认，采纳**后端补发状态事件**方案（而非前端轮询/延后）：
- 在 `InstanceStateMachine` 各 CAS 成功后集中补发（唯一状态变迁入口，最小改动全覆盖），单跑实例跳过；`WorkerReportService` 失败级联 STOPPED 处补发。
- 频道/payload 契约写入 `realtime-streams` 的「工作流状态实时流」delta（此前 spec 只声明"应发布"，实现缺失，本次补全并固化契约）。
- 竞态加固：`runWorkflow` 拉详情时若节点已 RUNNING/DISPATCHED 也立即顶日志（覆盖"SSE 连上前就进 RUNNING"）。
- 取舍：事件在 CAS 内发布（调用方事务内），偶发 rollback 误差由前端下次拉取自愈，不违反死锁防御不变量（事件非资源、不阻塞）。
