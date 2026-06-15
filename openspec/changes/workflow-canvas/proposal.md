## Why

DataWeave 已经能「读 DAG 跑」（`SchedulerKernel`/`WorkflowTriggerService` 按 `workflow_node`+`workflow_edge` 物化并调度实例），但**没人能「画 DAG 存」**——没有 `WorkflowController`、没有 `WorkflowService`，连创建工作流、编辑节点拓扑、发布版本的 API 都不存在，用户只能靠预置 `data.sql` 拿到工作流。要让用户真正自助编排任务，需要一个可视化画布把已有 `task_def` 拖拽连成工作流，并补齐其背后的编辑链路。

## What Changes

- **Schema 演进（向后兼容，全 ADD COLUMN）**：`workflow_node` 新增 `node_type`（`TASK`/`VIRTUAL`，默认 `TASK`）；`task_id` 由 `NOT NULL` 放宽为可空（虚拟节点不绑任务）。`workflow_edge` 不变（v1 无条件分支，分支/归并由 DAG 拓扑天然表达：多出边=fan-out，多入边=fan-in）。
- **后端编辑链路（新增）**：`WorkflowController`（`/api/workflows`）+ `WorkflowService` —— 工作流定义 CRUD（创建草稿/分页搜索/详情/编辑/软删/下线）、读 DAG、**整图保存**（一次提交全量 node+edge，批量 upsert + 软删差集）、发布（复用既有 `WorkflowGraphValidator.validateWorkflowDagAcyclic` 做无环校验 → 冻结 `dag_snapshot_json` → `current_version_no++`、`has_draft_change=0`）。
- **虚拟节点零负载执行**：`WorkflowTriggerService.trigger()` 物化实例时，`node_type=VIRTUAL` 的节点直接生成 `state=SUCCESS` 的 `task_instance`（不下发、不占槽），下游 readiness 检查（`pred.state<>'SUCCESS'`）自然放行。这是唯一一处调度侧改动。
- **前端画布（新增）**：引入 `@xyflow/react`（v12+，对齐 React 19）；新建 `workflow-canvas` 视图——左侧 `task_def` 列表拖入建 TASK 节点、工具栏放置 VIRTUAL 节点、连线建边、画布坐标回写 `pos_x/pos_y`、显式「保存草稿」整图 PUT、「发布」按钮。接入 workspace 视图注册表（`lib/workspace/views.ts` + `view-registry.tsx`）。`task-flow-view` 保持现状（任务管理列表），画布是独立新视图。
- **MCP 工具（可选补齐）**：`McpToolRegistry` 新增 `create_workflow`、`save_workflow_dag`、`publish_workflow` 等写工具，经 `GatedActionService` 闸门，使 Agent 也能编排工作流。

## Capabilities

### New Capabilities

- `workflow-authoring`: 工作流定义的完整编辑链路——CRUD、DAG 整图读写、发布版本冻结、`node_type`（TASK/VIRTUAL）语义、整图保存的批量 upsert/软删契约、发布前无环校验。覆盖 `WorkflowController` + `WorkflowService` 的后端行为。
- `workflow-canvas`: 基于 React Flow 的可视化 DAG 编辑器——拖拽建点（TASK/VIRTUAL）、连线建边、坐标持久化、保存草稿与发布的前端交互、脏标记、画布与 workspace 视图集成。

### Modified Capabilities

- `scheduler-core`: 新增「虚拟节点零负载执行」要求——触发物化时 `node_type=VIRTUAL` 的节点直接置 `SUCCESS`，不下发不占槽，仅作为 DAG 拓扑的起始/汇聚锚点参与下游解锁。

## Impact

- **数据库**：`workflow_node` 两处 DDL 变更（加列 + 放宽 `task_id` 可空），向后兼容。`schema.sql` + `data.sql`（虚拟节点示例可选）。
- **后端代码**：
  - `dataweave-master`：新增 `WorkflowService`（application）；`WorkflowNode` 实体加 `nodeType` 字段；`WorkflowTriggerService` 虚拟节点分支；复用 `WorkflowGraphValidator`。
  - `dataweave-api`：新增 `WorkflowController`（`/api/workflows`）；`McpToolRegistry` 可选新增工具；`schema.sql` DDL。
- **前端代码**：新增依赖 `@xyflow/react`；新建 `components/workspace/views/workflow-canvas-view.tsx` + 自定义节点组件 + 左侧任务面板；`lib/workspace/views.ts`、`view-registry.tsx` 各加一行；`lib/types.ts` 加 workflow/DAG 类型。
- **API 契约**：新增 `/api/workflows`（CRUD）、`/api/workflows/{id}/dag`（GET 读图 / PUT 整图保存）、`/api/workflows/{id}/publish`。
- **AG-UI 协议**：无变更（画布走 REST，不走 AG-UI）。可选发 `CUSTOM(dataweave.ui.open)` 让 Agent 召唤画布视图。
- **依赖**：前端新增 `@xyflow/react`；后端无新增外部依赖。
