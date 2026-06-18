## Why

DataWeave 的任务开发模块目前只有"创建即上线"的单点能力，缺乏 CRUD、调度执行、实例生命周期管理、日志查看等调度平台最基本的功能。与 DolphinScheduler / DataWorks 对比，差距集中在 P0 层：用户无法搜索/编辑/删除任务，cron 表达式存了但不执行，实例只能 rerun 不能暂停/终止，日志被 4KB 截断无法排查问题。本次变更补齐这四项核心能力，使 DataWeave 作为"AI Agent 原生调度平台"基本能用。

## What Changes

- **任务 CRUD + 搜索**：新增 `TaskController`（`/api/tasks`），支持创建草稿、分页搜索（名称/类型/状态/时间范围）、查看详情、编辑（仅 DRAFT 可改）、软删除、发布上线、下线。前端重构 `task-flow-view`，新增搜索栏 + 可操作列表 + 编辑抽屉。
- **Cron 调度引擎**：新增 `CronScheduler` Bean，每分钟扫描 `workflow_def` 中 `schedule_type='CRON' AND status='ONLINE'` 的工作流，到达触发时间则创建 `WorkflowInstance` 并按 DAG 节点生成 `TaskInstance` 执行。`workflow_def` 新增 `last_fire_time` 防重复触发。
- **实例生命周期管理**：`TaskInstance` 和 `WorkflowInstance` 的 `state` 增加 `PAUSED` 状态。新增暂停/恢复/终止 API（`/api/ops/instances/{id}/pause|resume|kill`）。前端运维面板增加操作按钮列。
- **日志系统改造**：`task_instance.log` 字段类型从 `VARCHAR(4000)` 改为 `TEXT`。新增分块拉取接口 `GET /api/ops/instances/{id}/log`（支持 offset/limit）。前端新增日志查看器组件。
- **日期格式统一**：后端 Jackson 全局配置 `yyyy-MM-dd HH:mm:ss`（`LocalDateTimeSerializer/Deserializer`）。前端 `formatDateTime()` 同步改为输出相同格式。
- **数据库 Schema 补全**：`task_def` 新增 `priority`/`description`/`owner_id`；`task_instance` 新增 `exit_code`/`error_message`；`workflow_def` 新增 `last_fire_time`/`priority`/`timeout_sec`；`workflow_instance` 新增 `total_tasks`/`completed_tasks`/`failed_tasks`；`worker_nodes` 新增 `max_concurrent_tasks`/`node_group`。修复 `WorkflowDependency.dateOffset` 类型错误（Integer→String）和 `AlertRule`/`NotificationChannel` 的 `createdBy` 类型错误（String→Long）。补建 `WorkflowDefVersion` Entity 类。
- **MCP 工具补齐**：`McpToolRegistry` 新增 `update_task`、`delete_task`、`pause_instance`、`resume_instance`、`kill_instance` 工具，使 Agent 也能执行这些操作。

## Capabilities

### New Capabilities

- `task-crud`: 任务定义的完整 CRUD 操作（创建草稿、搜索、详情、编辑、软删除、发布/下线），包括分页搜索、前端管理列表、编辑抽屉、日期格式统一。
- `cron-scheduler`: Cron 调度引擎，定时扫描并触发工作流执行，包括防重复触发、调度窗口控制、触发日志。
- `instance-lifecycle`: 任务实例和工作流实例的生命周期管理，包括暂停/恢复/终止操作，PAUSED 状态机，前端操作面板。
- `task-logging`: 日志系统改造，TEXT 类型存储、分块拉取 API、前端日志查看器。
- `schema-evolution`: 数据库 Schema 演进（新增字段、类型修复、Entity 补建），为上述能力提供数据基础。

### Modified Capabilities

（无现有 spec 需要修改——任务管理此前无 spec 定义）

## Impact

- **后端代码**：
  - `dataweave-master`：`TaskService` 重构（拆出 CRUD 方法）、`OpsService` 新增实例操作方法、新增 `CronScheduler`、Repository 新增查询方法、`WorkflowDependency`/`AlertRule`/`NotificationChannel` Entity 类型修复、新增 `WorkflowDefVersion` Entity。
  - `dataweave-api`：新增 `TaskController`、`OpsController` 新增端点、新增 `JacksonConfig`、`schema.sql` DDL 变更、`McpToolRegistry` 新增 5 个工具。
  - `dataweave-worker`：无改动。
  - `dataweave-alert`：Entity 类型修复。
- **前端代码**：
  - `components/ops/`：`task-def-list.tsx` 重写、`instance-table.tsx` 增加操作列、新增 `task-edit-drawer.tsx`/`task-search-bar.tsx`/`log-viewer.tsx`。
  - `components/workspace/views/task-flow-view.tsx`：整合搜索+列表+操作。
  - `lib/types.ts`：`formatDateTime()` 改格式。
- **数据库**：6 张表的 DDL 变更（新增字段+类型修改），向后兼容（全部 ADD COLUMN，无 DROP）。
- **API 契约**：新增 `/api/tasks/*` 系列 REST 端点，新增 `/api/ops/instances/{id}/pause|resume|kill|log` 端点。现有 `/api/ops/tasks` 和 `/api/ops/instances` 保持兼容但建议前端迁移到新端点。
- **AG-UI 协议**：无变更。
- **MCP 工具**：新增 5 个写操作工具（均经 `GatedActionService` 闸门）。
- **依赖**：无新增外部依赖（Cron 使用 Spring 内置 `@Scheduled`）。
