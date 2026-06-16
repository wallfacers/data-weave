## Why

当前"任务开发"散落在三处且体验割裂：`sql-workbench`（写死样例、不存盘的 Monaco）、`task-flow`（实例运维表）、侧栏 `task-edit`（用 `<textarea>` 写脚本、无语法高亮、漂在侧边而非主区）。用户无法在一个地方完成 DataWorks 式的闭环——**在类目树里组织资产、点开任务进 IDE 编辑器写代码、配置调度、立即跑一次、看日志、在画布上看 DAG 运行态变色**。同时后端缺少"立即跑一次"的入口：所有运行类端点都作用于已存在实例，靠 cron 凭空生实例，写完无法点一下就跑。

## What Changes

- **新增"数据开发"IDE 壳**（由现有 `workflow-canvas` 视图升格，标题 `工作流编排` → `数据开发`）：左侧常驻类目树 + 右侧**内层子 Tab 区**（复用 `TabStrip`，风格与顶层一致）。画布是一种子 Tab，任务编辑器是另一种子 Tab。
- **任务编辑器搬进子 Tab**：把侧栏 `TaskEditPanel` 的配置/参数/替换预览能力并入编辑子 Tab，脚本区 `<textarea>` 换成 Monaco（`CodeEditor`），按任务类型语法高亮（SQL/SHELL）。新建任务后**直接进编辑态子 Tab**。
- **类目树增强**：新增本地搜索框（过滤 name）；叶子 CRUD（重命名/删除任务与工作流）；修正缩进对齐；**删除拖拽提示文案**（"拖任务到画布建节点 · 拖入文件夹改归属"）。
- **后端新增"手动触发正式实例"能力**：`POST /api/tasks/{id}/run` 与 `POST /api/workflows/{id}/run` 起**正式实例**，不进 cron、不污染定时计划；返回 `instanceId` 供前端接日志流 / DAG 事件流。写操作 MUST 经 `GatedActionService` 闸门 + `agent_action` 留痕。
  - 工作流侧：薄包装现成的 `WorkflowTriggerService.trigger(wf, "MANUAL", bizDate, null)`（`workflow_instance.trigger_type` 列已存在），零数据模型改动。
  - 任务侧：以 `run_mode=NORMAL` 即时起一个正式 task_instance（"正式/不计统计"由 `run_mode=NORMAL` 判定，与 OpsService/WorkflowStateService 现有口径一致），**不新增 `trigger_type` 列**。
- **跑后即观测**：编辑子 Tab 内嵌日志流（复用 `/api/ops/instances/{id}/logs/stream`）；画布子 Tab 把工作流实例事件（`/api/ops/workflow-instances/{id}/events/stream`）叠到节点上**实时变色**。
- **消除原生弹框**：新增 base 风格 `Dialog` 组件，替换 2 处 `window.prompt`（建文件夹、建工作流），并承载类目树 CRUD 表单。
- **BREAKING（前端视图）**：移除 `sql-workbench`（任务开发）与 `task-flow`（任务流）独立视图及"+"启动菜单入口；移除侧栏 `task-edit` 面板。其能力并入"数据开发"IDE。`task-flow` 退出 Pinned 底座。

## Capabilities

### New Capabilities
- `data-development-ide`: 数据开发 IDE 壳——左树常驻 + 右侧内层子 Tab 系统（画布/编辑两类子 Tab）、点树开对应子 Tab、新建即进编辑态、Monaco 按类型高亮、跑后内嵌日志、画布 DAG 运行态叠加变色、Dialog 替代原生弹框。
- `manual-run-trigger`: 任务/工作流"手动触发正式实例"——`POST /run` 以 `trigger_type=MANUAL` 起正式实例走现有 executor / 调度内核，不进 cron，经 `GatedActionService` 闸门 + 审计留痕，返回 `instanceId` 供前端观测。

### Modified Capabilities
- `task-workflow-catalog`: 类目树新增本地搜索、叶子（任务/工作流）重命名与删除、缩进对齐修正、移除拖拽提示文案、建文件夹改用 Dialog（去原生 prompt）。
- `workflow-canvas`: 画布由独立视图改为"数据开发"IDE 内的一种**子 Tab**；移除画布左侧拖拽提示文案；画布 MUST 能叠加渲染所属工作流实例的节点运行态（事件流驱动变色）。
- `agent-workspace`: 视图注册表移除 `sql-workbench` 与 `task-flow`；`workflow-canvas` 标题改为"数据开发"；`task-flow` 退出 Pinned 底座（Pinned 由**五个减为四个**：驾驶舱 / 数据新鲜度 / 业务报表 / 系统指标）。

## Impact

- **前端**：`lib/workspace/{views.ts,registry.tsx}`（移除两视图、改标题）、`components/workspace/tab-bar.tsx`（Launcher 列表）、`components/workspace/views/workflow-canvas-view.tsx`（升格为 IDE 壳 + 内层 TabStrip）、`components/workspace/catalog-tree.tsx`（搜索/CRUD/缩进/去提示）、新增编辑子 Tab 组件（并 `components/ops/task-edit-panel.tsx` + `components/code-editor.tsx`）、新增 `components/ui/dialog.tsx`；删除 `components/sql-workbench.tsx`、`components/workspace/views/{sql-workbench-view,task-flow-view}.tsx`、侧栏 `task-edit` 注册。
- **后端**：`WorkflowController` 新增 `POST /{id}/run`（薄包装 `WorkflowTriggerService.trigger(..,"MANUAL",..)` + 闸门）；`TaskController` 新增 `POST /{id}/run`（起 `run_mode=NORMAL` 正式 task_instance + 闸门）。重命名复用既有 `PUT /api/tasks/{id}`、`PUT /api/workflows/{id}`，**无需新增端点**。
- **数据**：`workflow_instance.trigger_type` 列**已存在**，工作流侧零 DDL；task_instance 用既有 `run_mode` 字段（NORMAL/TEST），**不加新列**。`policy_rules` 增 MANUAL 运行分级（默认 L1）。
- **关联规范**：`task-test-run`（run_mode=TEST 草稿沙箱、不进统计）目前是 **spec 存在但未实现**（`TaskController` 无任何 run/test-run 端点）——本变更不依赖它兜底，且其 MANUAL 是**正式实例**、进正式运维统计，语义不同。
- **契约门**：触发"跑"为写操作，必经闸门；改 AG-UI 无关。前端改动触发 Browser Verification Gate。
