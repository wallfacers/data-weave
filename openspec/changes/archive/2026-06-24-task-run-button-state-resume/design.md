## Context

数据开发(`data-development-ide`)的任务编辑器 `task-editor-pane.tsx` 与工作流画布 `workflow-canvas-view.tsx` 各有一套手动运行 + 日志 Tab 机制,共用 `run-logs-tabs.tsx`(`useRunLogTabs` / `RunLogsTabs` / `LogTab`)与 `use-event-source.ts`。

现状关键事实(已勘察确认):
- `task-editor-pane.tsx:308-347` 的 `handleRun` 进入 `setRunning(true)`、`finally` 立即 `setRunning(false)`——`running` 只覆盖 POST 往返,**不反映实例执行态**。
- 运行/停止是**两个独立按钮**:运行按钮常显(POST 期间禁用),停止按钮显隐看 `activeRunTab` 是否存在(`task-editor-pane.tsx:469`),与实例是否在跑脱钩。停止 kill 的是 `activeRunTab`。
- 真实运行态其实已算好:`LogTab` 经 `deriveRunDotState(parseEndState(end), ended, connected)` 得 `running/success/failed/stopped/connecting`,经 `onDot` 上报到 `RunLogsTabs` 内部的 `dot: Record<instanceId, RunDotState>`(`run-logs-tabs.tsx:158,208-212`),但**未再上提给工具栏 pane**。
- 工作流画布已有 `runWfId`/`runStatus`/`canStop`(`workflow-canvas-view.tsx:323-325,665`),`events/stream` 持续更新 `runStatus`,机制比单任务完整。
- 所有运行态均为**组件内存**,子 Tab 关闭重开或刷新即丢失。
- 后端 `OpsController` 无「按 taskDefId/workflowId 查最近实例」端点;`TaskInstanceRepository`/`WorkflowInstanceRepository` 有 `findByTaskId`/`findByWorkflowId`(无序列表)。`id` 为 UUIDv7(时间有序),`run_mode` 区分 NORMAL/TEST。kill 双路径:`/instances/{id}/kill`=工作流(级联杀节点)、`/task-instances/{id}/kill`=单任务。

约束:CLAUDE.md i18n 双 bundle 同源、SSE 必须 EventSource 直连后端(`SSE_BASE`)、后端改 domain/application 后需 `install` 再 run、Browser Verification Gate 强制实跑。

## Goals / Non-Goals

**Goals:**
- 运行按钮换 `PlayIcon`,合并为单按钮 Run⇄Stop,按钮态由当前运行实例真实执行态驱动。
- 关闭重开子 Tab / 刷新页面后,从后端权威态续接:按钮态、日志流、工作流节点着色全部恢复。
- 任务与工作流两处行为一致;复用既有 SSE 与圆点派生逻辑,不新增并行机制。

**Non-Goals:**
- 不改 `POST /run` 触发契约、不改 `logs/stream`/`events/stream` SSE 协议。
- 不做跨任务的「全局运行中实例面板」;续接仅针对当前打开的子 Tab 对应的定义。
- 不自动回显终态实例的历史日志 Tab(查历史日志走运维视图)。
- 不引入轮询;状态来源为「挂载时一次性查后端 + 之后 SSE 推送」。

## Decisions

### D1:按钮真相源 = 上提的圆点态,而非 POST 时长
将 `RunLogsTabs` 内部已聚合的 `dot` map 经新增 `onDotChange?(map)` 回调上提给 pane。pane 维护 `currentRunInstanceId`,按钮态 = `dot[currentRunInstanceId]`:`running`/`connecting` → 停止;`success`/`failed`/`stopped`/`undefined` → 运行。
- **为何**:`deriveRunDotState` 已是单一可信派生,按钮与圆点同源天然一致,零额外 SSE 连接。
- **替代**:工具栏独立再订阅一条 `logs/stream` 只看 end——被否,重复连接、双倍后端轮询负载。

### D2:`currentRunInstanceId` 的生命周期与「关 Tab 即放弃跟踪」
`handleRun` 成功(EXECUTED)即 `setCurrentRunInstanceId(resultInstanceId)` 并 `openRunTab`;续接命中也设它。若用户**手动关闭运行中的日志 Tab**,则该实例 dot 来源消失,按钮回落「运行」,**后台实例不受影响**;刷新/重开会再从后端续回。
- **为何**:后端权威续接已是兜底,无需拦截关闭,逻辑最简且自洽。
- **替代**:运行中禁止关 Tab / 工具栏独立保活订阅——均增复杂度,收益小。

### D3:后端查询用 Spring Data 派生方法 + UUIDv7 时间序
- `TaskInstanceRepository`: `Optional<TaskInstance> findFirstByTaskIdAndRunModeOrderByIdDesc(Long taskId, String runMode)`
- `WorkflowInstanceRepository`: `Optional<WorkflowInstance> findFirstByWorkflowIdOrderByIdDesc(Long workflowId)`
- **为何 `id DESC`**:`id` 是 UUIDv7,字典序≈时间序,取最新无需 `created_at`,避免墙钟/时区与并发同毫秒的歧义,H2/PG 一致。
- **替代**:`OrderByCreatedAtDesc`——可用但同毫秒并发不稳定,且需确认两库排序方言;`id` 更稳。

### D4:端点落在 OpsController,薄查询不写副作用
新增 `GET /api/ops/tasks/{taskDefId}/latest-instance?runMode=`(默认 NORMAL)与 `GET /api/ops/workflows/{workflowId}/latest-instance`,返回 `{id, state, runMode}` / `{id, state}`,无实例返回 `data=null`。OpsService 加薄包装方法(或 Controller 直调 repo)。
- **为何 Ops**:实例查询既有口径都在 OpsController/OpsService,TaskController/WorkflowController 只管定义 CRUD。

### D5:前端续接落点
- 任务:`TaskEditorPane` 的 `loadTask` 完成后(或独立 effect)查 latest-instance;非终态则 `setCurrentRunInstanceId` + `openRunTab`。终态状态集合复用判断:`state ∈ {SUCCESS, FAILED, STOPPED}` 视为终态(`PREEMPTED`/`PAUSED` 视为非终态、显示停止)。
- 工作流:`CanvasInner` 挂载 effect 查 latest-instance;非终态则复用 `runWorkflow` 内「setRunWfId + 拉 `workflow-instances/{id}` 建映射 + setRunStateByTaskDef + setRunStatus」那段,抽成可复用函数 `attachRunningInstance(wiId, state)`,供首跑与续接共用。

### D6:图标与文案
`RocketIcon`→`PlayIcon`(`@hugeicons/core-free-icons`)仅改运行按钮;工作流「发布」按钮 `RocketIcon` 保留。文案复用 `taskEditor.run/runTest/stop/stopping`、`workflowCanvas.run/stop/stopping`;`running` 文案不再用于按钮(按钮在运行中直接显示「停止」),如确需新键则双 bundle 同步。

## Risks / Trade-offs

- [关运行中 Tab 后按钮回落「运行」,可能让用户误以为已停止] → 文档/交互上「运行」即「可再次触发」,后台实例仍在跑;刷新即续回。可接受,后端权威兜底。
- [latest-instance 查询与 SSE 续流之间的竞态(查到非终态但已跑完)] → `logs/stream` 历史回放 + end 自愈,spec 已含场景;按钮收终态即复位。
- [UUIDv7 字典序假设] → 项目已用 UUIDv7 且现有 `/api/ops/instances` 即按 id 降序取最新,沿用同口径,无新风险。
- [TEST 与 NORMAL 串台] → 查询按 `run_mode` 过滤;任务编辑器续接默认查 NORMAL,试跑场景如需续接再按发布态选 runMode(首版可只续 NORMAL,TEST 试跑短、续接价值低)。
- [双库方言] → 派生查询由 Spring Data 生成,H2/PG 均支持 `findFirstBy...OrderByIdDesc`;仍按规范两库各跑一遍单测。

## Migration Plan

1. 后端先行:加 Repository 派生方法 + OpsController 端点 + 单测 → `./mvnw -pl dataweave-master,dataweave-api -am install -DskipTests` 使运行进程用新类。
2. 前端:`run-logs-tabs.tsx` 加 `onDotChange` 上提(向后兼容,可选回调)→ `task-editor-pane.tsx` 合并按钮 + 续接 → `workflow-canvas-view.tsx` 图标 + 续接。每步 `pnpm typecheck`。
3. Browser Verification Gate:实跑长任务,验证「运行→停止切换」「刷新后续接停止+日志」「工作流节点续接着色」;截图入 `tmp/` 后清理。
4. 回滚:纯增量,前端按钮/续接可单独 revert;后端两端点为新增,移除无副作用。

## Open Questions

- 任务编辑器续接是否需要覆盖 TEST 试跑实例?首版倾向只续 NORMAL,待实跑反馈再定是否按发布态择 runMode。
