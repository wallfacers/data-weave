## Why

任务（TaskDef）和工作流（WorkflowDef）当前是**完全扁平**的：后端无任何分组字段，前端只能靠名称模糊搜索 + type/status 下拉过滤，工作流甚至只是个下拉选择。当资产数量增长，用户无法按业务域/数仓分层组织和定位资产，工作流画布左侧拖任务建节点时也只能在一长串扁平列表里翻找。需要一层**可分组的类目组织结构**，让任务与工作流像 IDE 项目树一样可浏览、可归类、可多维筛选。

## What Changes

- 新增**文件夹类目树**：用户可在项目内创建任意深度的文件夹（`catalog_node`），把任务和工作流归入文件夹（唯一归属）。统一一棵树，任务与工作流混挂。
- 新增**标签**：任务/工作流可贴多个标签（多对多），作为横切于文件夹树的多维筛选手段。
- `task_def` / `workflow_def` 各新增 `catalog_node_id`（可空外键）；未归类资产（`catalog_node_id IS NULL`）落入虚拟「未分类」根。
- 文件夹树采用**邻接表 `parent_id` + 物化路径 `path`** 存储：`parent_id` 管父子关系与外键完整性，`path` 支撑子树批量查询、面包屑、防环检测。`path` 为后端维护的派生字段。
- **删除语义**：非空文件夹（含子文件夹或已归类资产）禁止删除，提示用户先清空。
- 新增 REST 端点：类目树读取、文件夹 CRUD/移动、资产归类、标签 CRUD 与打标。
- 新增可复用前端组件 `<CatalogTree>`：**首发落点为工作流画布左侧拖拽面板**（按类目树组织待拖任务），并预留复用至任务列表视图与独立 Catalog 视图。需区分两种拖拽语义：树内移动归属 vs 拖任务到画布建 DAG 节点。
- **Agent 可操作（分两期）**：本变更聚焦一期（领域 + REST + 前端树，人工操作）；二期再新增 MCP 工具（`catalog_create`/`catalog_move`/`catalog_tag`，经 `GatedActionService` 闸门）+ `IntentRouter` 意图分支。本提案为二期预留出口但不实现。

## Capabilities

### New Capabilities

- `task-workflow-catalog`: 任务与工作流的类目组织能力——文件夹树（邻接表 + 物化路径、唯一归属、非空禁删）、标签（多维横切）、资产归类与未分类兜底、类目树 REST 端点，以及前端可复用类目树组件与画布面板集成。Agent 可操作能力（MCP 工具 + 意图）作为二期出口在本能力内声明、不在本变更实现。

### Modified Capabilities

- `workflow-canvas`: 画布左侧拖入节点的**来源**从扁平 `task_def` 列表升级为类目树（`<CatalogTree>`）。「从左侧拖入即建 `TASK` 节点并绑定 task」的行为契约不变，但既有 spec 明确写了"从左侧 `task_def` 列表拖入节点"，故构成 requirement 改写，需 delta（`specs/workflow-canvas/spec.md`，MODIFIED）。同时新增"树内拖动 = 移动归属"的拖拽语义区分。

<!-- catalog_node_id 字段是对 task_def/workflow_def 的加列扩展，不改写 task-crud 等既有 spec 的行为契约，故不列入。 -->

## Impact

- **数据库**（`dataweave-api/src/main/resources/schema.sql` + `db/migration/*-pg.sql`）：新增 `catalog_node`、`tag`、`entity_tag` 表；`task_def`、`workflow_def` 加 `catalog_node_id` 列。H2 与 PG DDL 双兼容。**与兄弟变更 `task-core-capabilities` 的 schema 冲突**：后者也改 `task_def`/`workflow_def`（加 `priority`/`description`/`owner_id`、`last_fire_time` 等）。两边一律用 `ADD COLUMN IF NOT EXISTS`（PG migration）+ 列加在表尾，互不依赖加载顺序、互不阻塞，谁先归档都不冲突。
- **后端领域**（`dataweave-master`）：新增 catalog 领域（CatalogNode 实体、CatalogTreeService、仓储、path 维护与移动重写、非空禁删校验、标签服务）。遵循 DDD 四层与依赖方向。
- **后端接口**（`dataweave-api`）：新增 `CatalogController`、`TagController`；扩展 `TaskController`/`WorkflowController` 支持按 `catalogNodeId`/`tag` 过滤与归类。
- **前端**（`frontend/components/workspace/`）：新增 `<CatalogTree>` 组件 + zustand 树状态；接入 `workflow-canvas-view` 左侧面板；任务/工作流写入归类操作。
- **不影响**：AG-UI 协议、调度死锁不变量、指标口径。
- **二期出口**（不在本变更实现）：`McpToolRegistry` 类目写工具经 `PolicyEngine` 闸门、`IntentRouter` 类目意图分支。
