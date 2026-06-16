## Context

DataWeave 的任务（`TaskDef`）与工作流（`WorkflowDef`）当前在 `dataweave-master` 中是扁平实体，仅有 `tenant > project` 两级隐含归属，无任何分组维度。前端 `task-flow-view` 与 `workflow-canvas-view` 均为搜索 + 枚举过滤 + 扁平列表/下拉。本设计为二者引入统一的**类目组织层**：文件夹树（唯一归属）+ 标签（多维横切）。

约束：
- 遵循 DDD 依赖方向 `domain ← application ← infrastructure ← interfaces`，新 catalog 领域落 `dataweave-master`。
- DDL 须 H2 + PostgreSQL 双兼容（H2 用于零依赖 profile）。
- Spring Data JDBC + JdbcTemplate；主键沿用现有风格（业务实体用 BIGINT 雪花/序列，非 UUID）。
- 不碰 AG-UI 协议、调度死锁不变量、指标口径。
- 前端遵守 Frontend Stack Gate（CopilotKit v2、hugeicons、语义 token、base 风格 `render` prop）。

## Goals / Non-Goals

**Goals:**
- 项目内任意深度的文件夹树，任务与工作流统一混挂、唯一归属一个文件夹。
- 多维标签，横切于文件夹树，支持按标签筛选任务/工作流。
- 高效子树查询、面包屑、防环——通过物化路径 `path`。
- 非空文件夹禁删的安全语义。
- 一个可复用的前端 `<CatalogTree>` 组件，首发接入工作流画布左侧拖拽面板。
- 为二期「Agent 可操作类目」预留干净出口（MCP 工具 + 闸门 + 意图），但本期不实现。

**Non-Goals:**
- 不实现 MCP 类目写工具、`IntentRouter` 类目意图（二期）。
- 不做跨项目/跨租户的类目共享或移动。
- 不做文件夹级权限/ACL（沿用现有 tenant/project 边界）。
- 不改造 `task-flow-view`、独立 Catalog 视图为强制落点（组件预留复用，本期只硬性接画布面板）。
- 不做标签的层级/分组（标签是扁平的）。

## Decisions

### D1：文件夹单独建表，任务/工作流加外键回指（不用多态 item 表）

文件夹是共享的一棵树，但叶子来自 `task_def` / `workflow_def` 两张异构表。

- **方案**：`catalog_node` 只存文件夹节点；`task_def`、`workflow_def` 各加 `catalog_node_id BIGINT NULL` 外键。「展开文件夹 X」= 子文件夹（`parent_id=X`）∪ 任务（`catalog_node_id=X`）∪ 工作流（`catalog_node_id=X`）。
- **为何不用** 单张多态 `catalog_item(node_id, entity_type, entity_id)`：会丢失对 task/workflow 的外键引用完整性，且与既有列表查询/分页割裂，归类状态需双写。回指外键最小侵入、引用完整。
- **`tenant_id` 保留**：`catalog_node` 带 `tenant_id` 与 `task_def`/`workflow_def` 的多租隔离惯例一致。本期所有树查询按 `project_id` 过滤（`tenant_id` 暂不参与谓词），保留它是为未来扩展到 tenant 维度查询时无须再改表；不是冗余设计而是与既有表对齐。

### D2：邻接表 `parent_id` + 物化路径 `path` 双存

- `parent_id BIGINT NULL` 管父子关系与外键完整性（根节点 `parent_id IS NULL`）。
- `path VARCHAR` 存「根→自身」的 id 串，形如 `/1/4/9/`（首尾带 `/`，便于 `LIKE` 前缀匹配且不误命中 `/1/` vs `/12/`）。
- **`path` 强项**：子树批量查询 `WHERE path LIKE '/1/4/%'` 一条 SQL；面包屑拆串零查询；防环 `LIKE` 一次判定。
- **移动代价可控**：移动子树时一条 `UPDATE` 批量重写 path 前缀，仅影响**文件夹节点**（任务/工作流不在 `catalog_node` 表，不受影响），毫秒级。
- **`path` 是派生字段**：仅由后端在创建/移动时维护，REST 入参绝不接受 `path`；保证一致性。
- **为何不用** 纯邻接表：面包屑/子树/防环都要递归 CTE 或多次往返，代码更绕，而本树的核心交互正是这些。
- **为何不用** 闭包表（closure table）：对中等规模目录树是过度设计，多一张 N² 关系表，移动维护更重。
- **H2/PG 方言钉死（移动 SQL）**：统一用两库通用函数，避免方言坑——
  - 移动重写：`UPDATE catalog_node SET path = CONCAT(?newPrefix, SUBSTRING(path, ?oldPrefixLen + 1)) WHERE path LIKE ?oldPrefix || '%'`。用 `CONCAT(...)` 而非 `||`（H2 在 MySQL 兼容模式下 `||` 会变逻辑或，`CONCAT` 两库恒为拼接）。`SUBSTRING(str, start)` 两参形式两库一致。
  - `path` 内容只含 `[0-9]` 与 `/`，**不含** `_`/`%`，故 `LIKE` 前缀匹配无需 ESCAPE，两库行为一致。
  - 防环判定同样走 `LIKE`（见 D2 风险段）。所有移动/子树 SQL 在 H2 profile 与 PG 各落一条测试（见 tasks 4.9.1/4.9.2）。

### D3：唯一归属 + 未分类虚拟根

- 一个任务/工作流的 `catalog_node_id` 至多一个值（唯一归属）。
- `catalog_node_id IS NULL` 的资产归入前端虚拟「未分类」根（非真实 `catalog_node` 行），后端提供计数与列表查询。

### D4：非空文件夹禁删

- 删除前校验：存在子文件夹（`parent_id=id`）或已归类资产（`task_def`/`workflow_def` 中 `catalog_node_id=id`）则拒绝，返回明确错误（如 `409 CONFLICT` + `CATALOG_NODE_NOT_EMPTY`）。
- 用 `path LIKE` 可一次判定整棵子树是否有后代文件夹；资产计数走两表 `COUNT`。

### D5：标签多态多对多

- `tag(id, project_id, name, color)`，项目内 `name` 唯一。
- `color` 存原始字符串，推荐 hex `#RRGGBB`（可空，缺省由前端按语义 token 分配）；后端不解析、不校验色值，前端负责渲染时转换为主题色。
- `entity_tag(tag_id, entity_type, entity_id)`，`entity_type ∈ {TASK, WORKFLOW}`，联合唯一防重复打标。
- 删除标签 → 级联清 `entity_tag`（标签是纯横切元数据，删之不损资产本体）。

### D6：REST 端点形态

- `GET /api/catalog/tree?projectId=` → 整棵文件夹树（含每节点直属任务/工作流计数、未分类计数）。**计数避免 N+1**：树读取用一次 `SELECT catalog_node_id, COUNT(*) FROM task_def WHERE project_id=? GROUP BY catalog_node_id` + 同形工作流查询（含 `catalog_node_id IS NULL` 的未分类分组），在应用层 join 进树节点，总计 2~3 条 SQL 而非 1+2N。
- `POST /api/catalog/nodes`（建文件夹：name + parentId）、`PATCH /api/catalog/nodes/{id}`（改名/移动 parentId）、`DELETE /api/catalog/nodes/{id}`（非空禁删）。
- `PATCH /api/tasks/{id}/catalog` + `PATCH /api/workflows/{id}/catalog`（归类）。**PATCH 语义钉死**：请求体 `{"catalogNodeId": null}` 显式清空归属（回未分类）；字段**缺失**（`{}`）表示不改该字段。实现用可区分「显式 null」与「缺失」的载荷（如 `JsonNode`/`Optional` 包装），不能用裸 `Long`（裸 `Long` 无法区分二者）。同理 `path` 字段一律拒收。
- `GET /api/tags?projectId=`、`POST /api/tags`、`DELETE /api/tags/{id}`、`POST /api/{tasks|workflows}/{id}/tags`、`DELETE /api/{tasks|workflows}/{id}/tags/{tagId}`。
- `TaskController`/`WorkflowController` 列表查询扩展 `catalogNodeId`（含 `?uncategorized=true`）与 `tagId` 过滤。

### D7：前端可复用 `<CatalogTree>` + 两种拖拽语义

- 组件：受控树，zustand 管展开/选中态，节点懒渲染；hugeicons 文件夹/任务/工作流图标区分类型。
- **首发落点**：`workflow-canvas-view` 左侧拖拽面板，按类目树组织待拖任务。
- **两种拖拽必须区分**：① 树内拖动节点 → 移动归属（调 `PATCH catalog`）；② 从树拖任务到 ReactFlow 画布 → 建 DAG 节点（既有逻辑，不改归属）。以拖拽目标区域（树内 vs 画布）区分意图。
- 标签作为面板顶部 filter chips，横切过滤树内可见资产。
- 预留 props 以便后续复用至 `task-flow-view` 与独立 Catalog 视图（本期不强制接入）。

### D8：二期 Agent 出口（本期铺路，spec 分两条 requirement）

- spec 拆为两条互不污染的 requirement：①「catalog 写方法分层可复用」是**本期可测**契约（写方法落 application/domain 层、入参纯领域对象、Controller 与 MCP handler 共用）；②「Agent 经闸门操作类目」是**二期**契约（带 `> Phase:` 标注），其 scenario 写成二期可直接当验收的接口+闸门行为，不带「（二期）」字样污染。
- 二期实现时：`McpToolRegistry` 注册 `catalog_create`/`catalog_move`/`catalog_tag` 写工具，全经 `GatedActionService.submit` → `PolicyEngine` 闸门 + `agent_action` 留痕；`IntentRouter` 加类目意图分支。因本期写方法已不耦合 HTTP 层，二期 MCP handler 是薄壳。

## Risks / Trade-offs

- **[`path` 与 `parent_id` 不一致风险]** → 二者只由后端 catalog 领域服务统一写入；移动操作在单事务内同时更新 `parent_id` 与子树 `path`；REST 不接受 `path` 入参。加领域层断言/测试覆盖移动后一致性。
- **[移动产生环（把父夹拖进自己子夹）]** → 移动前用 `newParent.path LIKE old.path || '%'` 判定目标是否为自身后代，是则拒绝（`400 CATALOG_CYCLE`）。
- **[H2 与 PG 的 `LIKE`/字符串函数差异]** → `path` 用纯 ASCII id + `/`，`LIKE` 前缀匹配两库一致；`SUBSTRING`/`||` 拼接选用两库共有语法，DDL 与移动 SQL 在 H2 profile 与 PG 各跑一遍测试。
- **[既有列表查询回归]** → `catalog_node_id`/`tagId` 过滤为可选参数，缺省时行为与现状完全一致；加测试断言无参等价旧行为。
- **[画布两种拖拽语义混淆]** → 以 drop target 明确区分（树容器 vs ReactFlow pane），并走 Browser Verification Gate 真跑确认两种拖拽各自生效、互不串味。
- **[未分类计数性能]** → 两表 `COUNT(*) WHERE catalog_node_id IS NULL AND project_id=?`，加 `catalog_node_id` 索引；数据量大时计数可缓存（本期不做，留 Open Question）。
- **[与兄弟变更 `task-core-capabilities` 改同表]** → 后者也在 `schema.sql` 改 `task_def`/`workflow_def`（加 `priority`/`description`/`owner_id`、`last_fire_time`/`timeout_sec` 等），且已在进行中。两边一律 `ADD COLUMN IF NOT EXISTS`（PG migration）、列加表尾、不依赖加载顺序，谁先归档都不冲突；`schema.sql` 合并时 catalog 列与 task-core 列各占独立行，git 层面也不撞行。

## Migration Plan

1. DDL 增量：`schema.sql` 加 `catalog_node`/`tag`/`entity_tag` 表 + `task_def`/`workflow_def` 加 `catalog_node_id` 列；新增 `db/migration/catalog-pg.sql`（PG 向后兼容 `ALTER TABLE ADD COLUMN IF NOT EXISTS`）。
2. 现有任务/工作流 `catalog_node_id` 默认 NULL → 自动归入「未分类」，无需数据回填，零破坏。
3. 后端领域 + REST 上线，前端 `<CatalogTree>` 接入画布面板。
4. 回滚：删 catalog 相关表与列、下线端点、前端面板回退扁平列表；因归类是加列、资产本体不变，回滚无数据损失。

## Open Questions

- 未分类资产数量极大时计数是否需缓存/物化（本期先实时 COUNT，留观察）。
- **`sort_order` 已定**：本期加列，树读取按 `(COALESCE(sort_order, 0), name)` 稳定排序（spec 有场景）；UI 手动调序留二期。
- 标签是否需要项目级预设色板 vs 自由取色（本期自由取色 `color` 字段，存 hex 字符串）。
