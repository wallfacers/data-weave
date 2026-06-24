## Why

运营中心当前是「看似标准大数据中台」的样子，但与平台真实数据模型对不上：`OpsService.tasks()` 用 `taskDefRepository.findAll()` 无任何状态过滤，把开发态 `DRAFT` 草稿也端进了运维侧，违反「开发/运维隔离、没发布的不展示」；同时它按 `TaskDef` 列「周期任务」，而平台的周期性根本不在任务上、而在任务流（`WorkflowDef.scheduleType=CRON`）上——对象选错了。本次重构把运营中心校正为**已发布(ONLINE)对象的运行时投影**，以**周期任务流**为运维主体，并把任务与任务流的概念彻底分离。

## What Changes

- **运维展示口径收紧为 ONLINE-only**：运营中心只展示从开发态 `publish` 过来的对象；`/api/ops/tasks` 等列表查询不再返回 `DRAFT`。**BREAKING**（运维侧不再出现草稿对象）。
- **「周期任务列表」→「周期任务流列表」**：数据源改为 `status=ONLINE & scheduleType=CRON` 的 `WorkflowDef`；任务与任务流概念分离——任务不独立出现在运维侧，只作为任务流的「节点 / 任务实例」被看到。
- **「手动·测试」tab 拆分**：新立「手动任务流列表」（`status=ONLINE & scheduleType=MANUAL`）作为与周期任务流同级的运维对象；`TEST_RUN` 测试实例逐出运营中心、回归数据开发侧；「手动触发」改为实例视图里的动作而非独立 tab。
- **任务级 freeze 废弃，改为节点级 DAG 冻结**：`POST /api/ops/tasks/{id}/freeze` 弃用；冻结作用于任务流 DAG 的某个节点，存为运维侧 overlay（键 `workflowId+nodeKey`，**不写入发布快照** `dag_snapshot_json`）；级联语义=冻结节点 N 则 N 及其传递下游 `SKIPPED`、不在 N 下游的照跑。**BREAKING**（freeze 接口语义变更）。
- **Q1 未发布任务可拖进画布、发布时收口**：开发态允许把 `DRAFT` 任务拖进任务流画布（节点打「未发布」标记），但 `workflow.publish` 时校验所有 `TASK` 节点引用的任务必须 `ONLINE`，否则拒绝发布。
- **Q2 被引用的任务禁止下线**：被任一 `ONLINE` 任务流引用的任务禁止 `offline`（对称于「ONLINE 任务流禁删」）；要下线须先把引用它的任务流下线或从其 DAG 移除该节点。

## Capabilities

### New Capabilities
- `node-freeze`: 节点级 DAG 冻结——运维侧 overlay 存储（不污染发布快照）、按 `workflowId+nodeKey` 冻结/解冻、调度时叠加到快照 DAG、冻结节点及其传递下游级联 `SKIPPED` 的语义。

### Modified Capabilities
- `ops-center-view`: 运维展示口径收紧为 ONLINE-only；「周期任务列表」更名为「周期任务流列表」并改数据源为 CRON workflow；「手动·测试」拆为「手动任务流列表」+ 测试逐出 + 手动触发动作化；tab 全貌重排。
- `workflow-authoring`: 任务流 `publish` 新增校验——所有 `TASK` 节点引用的任务必须 `ONLINE`，否则拒绝（沿用既有 publish 闸门与无环校验旁路）。
- `workflow-canvas`: 画布允许放置引用 `DRAFT` 任务的节点并给出「未发布」视觉标记（开发态编排自由）。
- `task-crud`: 任务 `offline` 新增前置校验——被任一 `ONLINE` 任务流引用的任务禁止下线。
- `task-freeze`: 废弃任务级 `freeze` 接口与 `task_def.frozen` 调度门，迁移到 `node-freeze`。
- `scheduler-core`: 修正弱依赖就绪判定——手动停止(`STOPPED`)不再视为"上游跑完"，不放行弱依赖下游（仅 `SUCCESS`/`FAILED` 放行）。

## Impact

- **后端 master**：`OpsService`（tasks/instances 列表加 ONLINE 过滤、新增 manual workflow 列表）、`TaskService.offline()`（引用校验）、`WorkflowService.publish()`（节点 ONLINE 校验）、新增节点冻结 overlay 表与服务、`SchedulerKernel`/`CronScheduler` 在生成实例时读取节点冻结 overlay 并标记 `SKIPPED` + 级联下游。
- **后端 api**：`OpsController` 周期任务流/手动任务流列表端点、节点冻结端点（取代 `/api/ops/tasks/{id}/freeze`）。
- **前端 ops 视图**：`ops-view.tsx` tab 重排（周期任务流列表 / 手动任务流列表 / 任务流实例 / 补数据实例），`periodic-tasks-panel`→`periodic-workflows-panel`，新增 manual-workflows 面板，移除 `manual-tests-panel`，DAG 实例视图加节点冻结/解冻入口与级联可视化。
- **前端开发侧**：`workflow-canvas-view` 节点「未发布」标记；`task-editor-pane` 下线按钮在被引用时禁用并提示；TEST_RUN 实例归位到开发侧自测视图。
- **数据迁移**：新增节点冻结 overlay 表；`task_def.frozen` 列退役（保留兼容或迁移到 workflow 级，见 design.md）。
- **i18n**：新增/更名 copy（周期任务流列表、手动任务流列表、未发布标记、冻结节点、禁止下线/发布的错误码）双语齐全。
- **依赖前置**：本变更建立在 `data-ops-center` 之上，需确认其归档顺序（见 design.md 开放问题）。
