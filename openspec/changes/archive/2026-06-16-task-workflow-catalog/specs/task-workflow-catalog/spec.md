## ADDED Requirements

### Requirement: 文件夹类目树

系统 SHALL 在项目内提供任意深度的文件夹类目树。每个文件夹是一个 `catalog_node`，通过 `parent_id` 形成树（根节点 `parent_id` 为空），并维护物化路径 `path` 以支撑子树查询、面包屑与防环。`path` 为后端派生字段，MUST 仅由后端在创建/移动时维护，REST 入参 MUST NOT 接受 `path`。

#### Scenario: 创建根文件夹
- **WHEN** 用户在项目内提交 `POST /api/catalog/nodes`，仅含 `name` 且无 `parentId`
- **THEN** 系统创建一个 `parent_id` 为空的文件夹，`path` 形如 `/{id}/`，返回该节点

#### Scenario: 创建子文件夹
- **WHEN** 用户提交 `POST /api/catalog/nodes`，含 `name` 与已存在父文件夹的 `parentId`
- **THEN** 系统创建文件夹，`parent_id` 指向父节点，`path` 为父 `path` 追加 `{id}/`

#### Scenario: 读取整棵类目树
- **WHEN** 用户请求 `GET /api/catalog/tree?projectId={id}`
- **THEN** 系统返回该项目所有文件夹节点构成的树，且每个节点带其直属任务数与直属工作流数，并附带未分类资产计数

#### Scenario: 同级文件夹稳定排序
- **WHEN** 系统返回类目树
- **THEN** 同一父节点下的子文件夹按 `(COALESCE(sort_order, 0), name)` 稳定排序，顺序可预期且与请求次序无关

### Requirement: 任务与工作流统一混挂、唯一归属

任务（`task_def`）与工作流（`workflow_def`）SHALL 各通过可空外键 `catalog_node_id` 归属于至多一个文件夹（唯一归属）。同一文件夹下 MAY 同时存在任务与工作流。

#### Scenario: 将任务归入文件夹
- **WHEN** 用户提交 `PATCH /api/tasks/{id}/catalog`，含目标 `catalogNodeId`
- **THEN** 系统将该任务的 `catalog_node_id` 设为目标文件夹，该任务在树中显示于该文件夹下

#### Scenario: 将工作流归入文件夹
- **WHEN** 用户提交 `PATCH /api/workflows/{id}/catalog`，含目标 `catalogNodeId`
- **THEN** 系统将该工作流的 `catalog_node_id` 设为目标文件夹

#### Scenario: 改归属为另一个文件夹
- **WHEN** 已归类的任务再次 `PATCH /api/tasks/{id}/catalog` 指向另一文件夹
- **THEN** 系统以新值覆盖旧 `catalog_node_id`（仍唯一归属），不产生多重归属

### Requirement: 未分类虚拟根

`catalog_node_id` 为空的任务与工作流 SHALL 归入一个虚拟「未分类」根（非真实 `catalog_node` 行）。系统 SHALL 支持查询与计数未分类资产。

#### Scenario: 既有资产默认未分类
- **WHEN** 一个任务从未被归类（`catalog_node_id` 为空）
- **THEN** 它出现在「未分类」节点下，并计入未分类计数

#### Scenario: 显式 null 清空归属回到未分类
- **WHEN** 用户 `PATCH /api/tasks/{id}/catalog` 请求体为 `{"catalogNodeId": null}`
- **THEN** 系统将该任务 `catalog_node_id` 置空，任务回到「未分类」

#### Scenario: 字段缺失不改归属
- **WHEN** 用户 `PATCH /api/tasks/{id}/catalog` 请求体不含 `catalogNodeId` 字段（如 `{}`）
- **THEN** 系统不改动该任务的 `catalog_node_id`（标准 PATCH 局部更新语义）

#### Scenario: 按未分类过滤列表
- **WHEN** 用户请求 `GET /api/tasks?uncategorized=true`
- **THEN** 系统仅返回 `catalog_node_id` 为空的任务

### Requirement: 文件夹移动与防环

系统 SHALL 支持移动文件夹（改 `parentId`），并在单事务内同步更新被移动子树所有后代文件夹的 `path`。系统 MUST 拒绝把一个文件夹移动到其自身或其后代之下（防环）。

#### Scenario: 移动文件夹并重写子树路径
- **WHEN** 用户 `PATCH /api/catalog/nodes/{id}` 将文件夹移到新父节点
- **THEN** 系统更新该节点 `parent_id` 与 `path`，并批量重写其所有后代文件夹的 `path` 前缀

#### Scenario: 拒绝移动成环
- **WHEN** 用户尝试将文件夹移动到其自身的某个后代文件夹之下
- **THEN** 系统拒绝操作并返回环路冲突错误（如 `400 CATALOG_CYCLE`），不做任何变更

### Requirement: 非空文件夹禁删

系统 MUST 拒绝删除任何包含子文件夹或已归类资产（任务/工作流）的文件夹，并提示用户先清空。

#### Scenario: 删除空文件夹成功
- **WHEN** 用户对一个无子文件夹、无归类资产的文件夹发起 `DELETE /api/catalog/nodes/{id}`
- **THEN** 系统删除该文件夹并返回成功

#### Scenario: 拒绝删除含子文件夹的文件夹
- **WHEN** 用户尝试删除一个仍有子文件夹的文件夹
- **THEN** 系统拒绝并返回非空冲突错误（如 `409 CATALOG_NODE_NOT_EMPTY`），文件夹保留

#### Scenario: 拒绝删除含资产的文件夹
- **WHEN** 用户尝试删除一个仍有任务或工作流归类其下的文件夹
- **THEN** 系统拒绝并返回非空冲突错误，文件夹保留

### Requirement: 标签多维横切

系统 SHALL 提供项目内标签（`tag`），任务与工作流 MAY 贴多个标签（多对多 `entity_tag`，`entity_type ∈ {TASK, WORKFLOW}`）。标签横切于文件夹树，用于多维筛选。项目内标签 `name` SHALL 唯一。

#### Scenario: 创建标签
- **WHEN** 用户提交 `POST /api/tags`，含项目内唯一的 `name`（可选 `color`）
- **THEN** 系统创建标签并返回

#### Scenario: 给任务打多个标签
- **WHEN** 用户对同一任务多次 `POST /api/tasks/{id}/tags` 关联不同标签
- **THEN** 该任务同时拥有这些标签，重复关联同一标签不产生重复记录

#### Scenario: 按标签过滤资产
- **WHEN** 用户请求 `GET /api/tasks?tagId={id}`
- **THEN** 系统仅返回贴有该标签的任务

#### Scenario: 删除标签级联解绑
- **WHEN** 用户 `DELETE /api/tags/{id}`
- **THEN** 系统删除标签并清除其所有 `entity_tag` 关联，关联的任务/工作流本体不受影响

### Requirement: 前端可复用类目树组件与画布集成

前端 SHALL 提供可复用的 `<CatalogTree>` 组件，并首发接入工作流画布左侧拖拽面板。组件 MUST 区分两种拖拽语义：树内拖动节点表示移动归属，从树拖任务到画布表示新建 DAG 节点。

#### Scenario: 画布面板按类目树展示待拖任务
- **WHEN** 用户打开工作流画布视图
- **THEN** 左侧面板以类目树（文件夹 + 任务，区分图标）组织待拖任务，而非扁平列表

#### Scenario: 树内拖动移动归属
- **WHEN** 用户在类目树内把一个任务节点拖入另一个文件夹
- **THEN** 前端调用归类接口更新该任务归属，不在画布上新建节点

#### Scenario: 拖任务到画布建节点
- **WHEN** 用户把一个任务从类目树拖到 ReactFlow 画布区域
- **THEN** 前端按既有逻辑在画布新建该任务的 DAG 节点，不改变该任务的类目归属

#### Scenario: 按标签横切过滤树
- **WHEN** 用户在面板顶部选中某标签 chip
- **THEN** 树内仅保留贴有该标签的任务/工作流可见

### Requirement: catalog 写方法分层可复用

catalog 领域的创建、移动、归类、打标等写方法 SHALL 位于 application/domain 层，入参为纯领域对象（不依赖 HTTP/Servlet 上下文），可被 REST Controller 与 MCP handler 共同调用，无重复业务逻辑。

#### Scenario: 写方法不耦合 HTTP 层
- **WHEN** 实现 catalog 的创建/移动/归类/打标写方法
- **THEN** 这些方法签名只接受领域参数（如 projectId、name、parentId、entityId），Controller 仅做参数解析与编排，业务逻辑不沉积在 Controller

#### Scenario: 同一写方法可被多入口复用
- **WHEN** REST Controller 与 MCP handler 各自需要执行同一类目写操作
- **THEN** 两者调用同一 application/domain 写方法，不各自重写业务逻辑

### Requirement: Agent 经闸门操作类目

类目写工具（建夹、移动、归类、打标）经 Agent 大脑执行时 MUST 经 `GatedActionService.submit` → `PolicyEngine` 闸门裁决并落 `agent_action` 审计，无绕过路径；`IntentRouter` SHALL 提供对应类目意图分支。

> Phase: 本 requirement 属二期实现范围。本期（一期）不实现 MCP 工具与意图分支，仅由「catalog 写方法分层可复用」requirement 为其铺好复用出口。以下场景为二期验收用例。

#### Scenario: Agent 建文件夹经闸门裁决
- **WHEN** Agent 调用 `catalog_create` MCP 工具创建文件夹
- **THEN** 请求构造 `ActionRequest` 经 `GatedActionService.submit` 裁决，按分级直执行或建审批单返回 `PENDING_APPROVAL`，并写 `agent_action` 留痕

#### Scenario: Agent 归类与打标经闸门
- **WHEN** Agent 调用 `catalog_move` 或 `catalog_tag` MCP 工具
- **THEN** 写操作经同一闸门裁决并留痕，无绕过 `PolicyEngine` 的直执行路径
