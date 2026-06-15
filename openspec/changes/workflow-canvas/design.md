## Context

DataWeave 的调度内核已经成熟：`SchedulerKernel` 认领/下发、`WorkflowTriggerService.trigger()` 按 `workflow_node`+`workflow_edge` 物化 `task_instance`、`PreemptionService`/内核用 `pred.state<>'SUCCESS'` 判断 DAG 下游 readiness、`WorkflowGraphValidator.validateWorkflowDagAcyclic()` 已实现发布期环检测。但**编辑侧完全缺失**——没有 `WorkflowController`/`WorkflowService`，工作流定义与 DAG 只能靠预置 `data.sql` 产生。

数据地基已预埋：`workflow_node.pos_x/pos_y`（画布坐标）、`workflow_def.current_version_no/has_draft_change/dag_snapshot_json`（与 `task_def` 同构的版本范式）。本设计补齐「画 DAG 存」的后端 API + 前端 React Flow 画布，并明确 `node_type` 引入后的执行语义。

约束（CLAUDE.md）：DDD 四层依赖方向（domain ← application ← infrastructure ← interfaces）；写操作经 `GatedActionService` 闸门；前端只用 CopilotKit v2 / shadcn base 风格 / hugeicons；新功能必须有测试；改前端走 Browser Verification Gate。

## Goals / Non-Goals

**Goals:**
- 用户能在画布上拖拽已有 `task_def` 编排成工作流 DAG，保存草稿并发布。
- 支持两种节点：`TASK`（绑 task_def）、`VIRTUAL`（zero-load 起始/汇聚锚点）。
- 后端补齐工作流定义 CRUD + DAG 整图读写 + 发布，复用既有环检测与版本范式。
- 虚拟节点在调度时零负载（直接 SUCCESS），不污染下发路径。

**Non-Goals:**
- 不做节点级独立调度（调度配置继续留在 `workflow_def`）。
- 不做条件分支边（v1 `workflow_edge` 不加 `condition`；分支/归并由拓扑天然表达）。
- 不做循环/赋值/人工卡点节点（留 v2）。
- 不做画布实时协同编辑（单用户编辑，乐观锁兜底）。
- 不改 `task-flow-view`（任务管理列表保持原样）。

## Decisions

### D1：调度模型沿用 workflow 持调度
画布只画节点拓扑与依赖，`cron/schedule_type/window` 继续留在 `workflow_def`。
- **理由**：与现有 `cron-scheduler`/`SchedulerKernel` 零冲突，schema 改动最小。
- **备选**：DataWorks 式节点级调度——否决，需在 node 上挂全套调度字段 + 重构调度内核，工作量数倍且 v1 不需要。

### D2：v1 仅两种节点类型，无条件分支
`node_type ∈ {TASK, VIRTUAL}`。分支=节点多出边（fan-out），归并=节点多入边（fan-in），均为无条件。
- **理由**：DAG 拓扑本身已能表达「并行扇出 / 汇聚等待」，绝大多数编排场景够用；避免过早引入 edge `condition` 的配置 UI 与执行期表达式求值复杂度。
- **备选**：v1 就上分支节点 + 条件边——否决，留 v2。

### D3：整图保存（whole-graph PUT），而非增量
画布编辑在前端内存进行，「保存草稿」一次 PUT 全量 `{nodes, edges}`。后端按 `node_key` 对账：新增→insert、存在→update（坐标/名称/类型/task 绑定）、缺失→软删（`deleted=1`）；边同理按 `(from_node_key, to_node_key)` 对账。整个保存在单事务内，锁序 task→workflow 之外不引入新锁。
- **理由**：契合 `has_draft_change` 标志语义；前端实现简单（无需维护逐操作的增量补丁）；DAG 规模小（一个工作流通常 < 几十节点），全量提交开销可忽略。
- **备选**：每次拖动/连线即时增量保存——否决，网络抖动下易产生半成品图，且与「草稿 vs 发布」版本范式不吻合。
- **并发**：`workflow_def.version` 乐观锁；保存时带上客户端读到的 version，冲突返回 409 让前端提示「他人已修改，请刷新」。

### D4：发布复用 WorkflowGraphValidator + 既有版本范式
发布流程：`validateWorkflowDagAcyclic(workflowId)`（有环抛 `IllegalStateException` → 转 4xx）→ 生成 `dag_snapshot_json`（冻结 nodes+edges+各 TASK 节点的 `current_version_no`）→ `current_version_no++`、`has_draft_change=0`、`status=ONLINE`。
- **理由**：环检测、快照冻结、版本号自增都已有现成范式（`task_def`/`WorkflowDefVersion`），不重造轮子。
- **校验位置**：后端发布/保存时权威校验；前端连线时本地即时反馈（连线即将成环则拒绝并提示），二者独立，后端为准。

### D5：虚拟节点零负载执行——改 WorkflowTriggerService 一处
`trigger()` 物化时：`node_type=VIRTUAL` → 生成 `task_instance` 时直接 `state=SUCCESS`、`started_at=finished_at=now`、不设 `task_id`，**不进 WAITING、不下发**。
- **理由**：下游 readiness 检查是 `NOT EXISTS(... pred.state<>'SUCCESS')`，虚拟节点已是 SUCCESS 自然放行；完全不碰 `SchedulerKernel` 下发路径（line 272 `taskId==null` 的 content 查找分支也不会被触达）。这是唯一调度侧改动。
- **备选**：让虚拟节点走正常 WAITING→DISPATCHED 再由 worker 空跑——否决，平白占槽 + 需要 worker 端识别空任务，复杂且无收益。
- **统计口径**：虚拟节点计入 `total_tasks` 与 `completed_tasks`（它确实「完成」了），保持计数自洽。

### D6：前端 @xyflow/react + 独立画布视图
新建 `workflow-canvas-view.tsx`，注册为独立 workspace 视图（不改 `task-flow-view`）。自定义 TASK/VIRTUAL 节点为 React 组件（shadcn token + hugeicons）。左侧 `task_def` 列表用 HTML5 DnD 拖入画布建 TASK 节点；工具栏按钮放置 VIRTUAL 节点。React Flow 的 `onNodesChange/onEdgesChange` 维护内存图与脏标记。
- **理由**：React Flow v12 原生支持 React 19，拖拽/连线/缩放/minimap 开箱即用，1~2 天可出可交互画布；独立视图风险隔离，不动已稳定的任务列表。
- **版本对齐**：必须 `@xyflow/react@^12`（v11 不支持 React 19）。import `@xyflow/react/dist/style.css`。

### D7：写操作经闸门
`WorkflowController` 的 create/save-dag/publish/delete 均为写操作。前端直接调 REST（带 `X-DW-Token`），后端在 service 层对写路径构造 `ActionRequest` 经 `GatedActionService` 留痕（或与现有 task CRUD 一致的处理方式对齐——实现时参照 `TaskController` 既有写操作模式）。MCP 工具版本走 `GatedActionService` 闸门。

## Risks / Trade-offs

- **[整图保存丢失并发编辑]** 两人同改一个工作流，后保存者覆盖前者 → 乐观锁 `version` 冲突返回 409，前端提示刷新。v1 不做实时协同。
- **[虚拟节点计数语义争议]** 计入 total/completed 可能让「任务数」看起来虚高 → 在 spec 明确口径；前端展示可标注节点类型区分。
- **[task_id 放宽可空影响既有读取]** `WorkflowNode.taskId` 变可空后，`WorkflowTriggerService`/`SchedulerKernel` 等读取处需确认空值安全 → 物化时按 `node_type` 分流，TASK 节点 `task_id` 仍非空（service 层校验保证），VIRTUAL 才为空。
- **[React Flow 与 React 19 / Turbopack 兼容]** 新依赖可能有 SSR/构建坑 → 画布视图 `"use client"`，按 Browser Verification Gate 真跑验证（拖拽/连线/保存/发布全链路 + console 无 error）。
- **[发布快照与编辑态漂移]** `dag_snapshot_json` 冻结后编辑态继续变 → 沿用 `has_draft_change` 标志，前端明示「有未发布改动」。

## Migration Plan

1. Schema：`schema.sql` 给 `workflow_node` 加 `node_type VARCHAR(32) DEFAULT 'TASK'`、`task_id` 去掉 `NOT NULL`。H2 开发库重启即重建；PG 走 `db/migration` 增量脚本（ADD COLUMN + ALTER COLUMN DROP NOT NULL，向后兼容，旧数据 `node_type` 取默认 `TASK`）。
2. 后端：实体加字段 → `WorkflowService` → `WorkflowController` → `install -DskipTests` → 单测。
3. 前端：装 `@xyflow/react` → 画布视图 → 注册 → `pnpm typecheck` → Browser Verification Gate。
4. 回滚：DDL 全 ADD COLUMN / DROP NOT NULL，向后兼容，回滚只需撤代码；已存数据 `node_type=TASK` 不影响旧调度。

## Open Questions

- MCP 工具是否 v1 就补齐，还是先只做 REST + 前端画布、Agent 编排留后续？（倾向：v1 先 REST + 画布跑通，MCP 工具作为收尾任务，可裁剪。）
- 画布是否需要「自动布局」（dagre/elk）？v1 倾向手动摆放 + 保存坐标即可，自动布局留增强。
