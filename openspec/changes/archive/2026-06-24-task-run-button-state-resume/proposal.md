## Why

数据开发的任务编辑器与工作流画布,「运行」按钮目前只在 POST `/run` 请求往返的几百毫秒内显示「运行中」,随后立刻复位——按钮态完全不反映任务/工作流实例的**真实执行进度**。停止按钮的显隐又只看「是否开过日志 Tab」,与实例是否仍在跑脱钩。更关键的是:所有运行态只活在组件内存里,**关闭重开子 Tab 或刷新页面后全部丢失**,长耗时任务再也找不到「停止」入口,日志流也断了不续。用户对一个正在后台运行的任务,前端却显示「可运行、无日志」,严重误导。

## What Changes

- **运行按钮图标**:`RocketIcon` → `PlayIcon`(播放三角),与停止按钮 `StopIcon` 成对。任务编辑器与工作流画布两处同步;工作流画布「发布」按钮仍用 `RocketIcon` 不动。
- **单按钮切换 Run⇄Stop**:同一个按钮,真相源改为**当前运行实例的真实执行态**——非终态(WAITING/DISPATCHED/RUNNING/连接中)显示「停止」,终态(SUCCESS/FAILED/STOPPED)或无实例显示「运行/试跑」。停止操作终止的是「当前运行实例」而非「当前激活日志 Tab」。
- **后端最近活跃实例查询**(新端点):
  - `GET /api/ops/tasks/{taskDefId}/latest-instance?runMode=NORMAL|TEST`
  - `GET /api/ops/workflows/{workflowId}/latest-instance`
  - 利用 `id`(UUIDv7 时间序)降序取最新,返回 `{id, state, runMode}`(无实例返回空)。
- **重开/刷新状态续接**:子 Tab 挂载时查后端最近实例,若处非终态 → 设为当前运行实例、续开日志 Tab、SSE 续流、按钮显示「停止」;工作流则续订阅 `events/stream`、重建实例↔task_def 映射、节点重新着色。
- **运行态来源上提**:`RunLogsTabs` 已算好的每 Tab 圆点态(`deriveRunDotState`)上提给工具栏,工具栏按钮与日志圆点同源,零额外 SSE 连接。

## Capabilities

### New Capabilities
- `run-state-resume`: 数据开发运行按钮的「单按钮 Run⇄Stop 状态机」「按真实实例态驱动」「重开/刷新后从后端权威态续接(按钮态 + 日志流 + 工作流节点着色)」的统一行为契约,以及支撑续接的后端「最近活跃实例查询」端点。覆盖任务编辑器与工作流画布两处。

### Modified Capabilities
<!-- 运行触发接口(manual-run-trigger)、日志/事件 SSE(realtime-streams)的既有 REQUIREMENT 不变,仅被新能力复用。图标/按钮为视觉与交互细节,既有 UI spec 未在 REQUIREMENT 级约束,故不产生 delta。 -->

## Impact

- **前端**:
  - `frontend/components/workspace/task-editor-pane.tsx`(按钮合并、currentRunInstanceId、挂载续接)
  - `frontend/components/workspace/run-logs-tabs.tsx`(圆点态 map 上提回调)
  - `frontend/components/workspace/views/workflow-canvas-view.tsx`(图标、按钮态、挂载续接)
  - `frontend/lib/datasource-api.ts` 同级新增运维查询调用 / `frontend/lib/types.ts`(latest-instance 返回类型)
  - i18n:复用既有 `taskEditor.run/runTest/stop/stopping`、`workflowCanvas.run/stop/stopping`,如有新文案双 bundle 同步
- **后端**:
  - `backend/dataweave-api/.../interfaces/OpsController.java`(两个 GET 端点)
  - `backend/dataweave-master/.../domain/TaskInstanceRepository.java`、`WorkflowInstanceRepository.java`(各加一个 `findFirstBy...OrderByIdDesc` 查询方法)
  - `backend/dataweave-master/.../application/OpsService.java`(查询薄包装,可选)
- **测试**:Repository 查询单测(NORMAL/TEST 过滤、取最新、空)、OpsController WebTestClient(带 JWT);前端 Browser Verification Gate 实跑续接场景。
- **无破坏性变更**:运行触发与 SSE 端点契约不变;仅新增查询端点与前端交互行为。
