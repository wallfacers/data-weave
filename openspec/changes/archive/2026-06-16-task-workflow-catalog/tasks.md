## 1. 数据库 DDL（H2 + PostgreSQL 双兼容）

- [x] 1.1 `schema.sql` 新增 `catalog_node` 表（id, parent_id, project_id, tenant_id, name, path, sort_order, 审计列），含 `parent_id`、`path`、`project_id` 索引
- [x] 1.2 `schema.sql` 新增 `tag` 表（id, project_id, name, color, 审计列），项目内 `name` 唯一约束
- [x] 1.3 `schema.sql` 新增 `entity_tag` 表（tag_id, entity_type, entity_id），`(tag_id, entity_type, entity_id)` 联合唯一 + `(entity_type, entity_id)` 索引
- [x] 1.4 `schema.sql` 给 `task_def`、`workflow_def` 加 `catalog_node_id BIGINT NULL` 列（**加在表尾**，与兄弟变更 `task-core-capabilities` 的 task_def/workflow_def 加列互不撞行）+ 索引
- [x] 1.5 新增 `db/migration/catalog-pg.sql`（PG 向后兼容：所有建表用 `CREATE TABLE IF NOT EXISTS`、所有加列用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，与 `task-core-capabilities` 互不依赖加载顺序，谁先归档都不冲突）
- [x] 1.6 H2 profile DDL 加载无误（CatalogServiceTest/CatalogApiTest 共 15 测试在 h2 profile 全过，schema.sql 含新表/列正常建立）；PG 待有库环境时跑 catalog-pg.sql 验证

## 2. 后端领域层（dataweave-master：catalog 领域）

- [x] 2.1 新增 `CatalogNode` 领域实体（含 path 派生字段，不可由外部直接构造非法 path）
- [x] 2.2 新增 `Tag`、`EntityTag` 领域实体（entity_type 枚举 TASK/WORKFLOW）
- [x] 2.3 新增 `CatalogNodeRepository`（瘦仓储 + 子节点查询；path 子树/移动/计数 SQL 在 CatalogTreeService）
- [x] 2.4 新增 `TagRepository`、`EntityTagRepository`（打标/解绑/按 entity 查标签/按标签查 entity）
- [x] 2.5 `CatalogTreeService`：建文件夹（计算 path）、读整棵树（带任务/工作流/未分类计数）；**计数避免 N+1**——用 `GROUP BY catalog_node_id` 各一条聚合查询（task + workflow，含 `IS NULL` 的未分类分组），应用层 join 进树，总计 2~3 条 SQL；同级按 `(COALESCE(sort_order,0), name)` 稳定排序
- [x] 2.6 `CatalogTreeService`：移动文件夹（单事务更新 parent_id + 子树 path 前缀批量重写）+ 防环校验。移动 SQL **钉死两库通用写法**：`UPDATE catalog_node SET path = CONCAT(?newPrefix, SUBSTRING(path, ?oldPrefixLen+1)) WHERE path LIKE ?oldPrefix || '%'`（用 `CONCAT` 而非 `||` 拼接，避兼容模式坑；path 仅含数字与 `/`，LIKE 无需 ESCAPE）；防环用 `target.path LIKE old.path || '%'` 判定目标是否自身后代
- [x] 2.7 `CatalogTreeService`：删除文件夹（非空校验：有子文件夹或归类资产则抛冲突）
- [x] 2.8 `CatalogAssignService`：设置/清空 task、workflow 的 catalog_node_id（唯一归属，覆盖语义；区分显式 null=清空 vs 字段缺失=不改）
- [x] 2.9 `TagService`：标签 CRUD（项目内 name 唯一）、打标（幂等去重）、解绑、删除级联清 entity_tag
- [x] 2.10 写方法置于 application/domain 层，入参为纯领域对象（不依赖 HttpServletRequest 等 HTTP 上下文），便于二期 MCP handler 构造 `ActionRequest` 后直接复用同一写方法
- [x] 2.11 编译验证：`./mvnw -q -pl dataweave-master compile`

## 3. 后端接口层（dataweave-api）

- [x] 3.1 `CatalogController`：`GET /api/catalog/tree`、`POST /api/catalog/nodes`、`PATCH /api/catalog/nodes/{id}`、`DELETE /api/catalog/nodes/{id}`
- [x] 3.2 错误映射（GlobalExceptionHandler）：非空冲突/标签重复 → 409；不存在 → 404；成环/非法 → 400
- [x] 3.3 `TagController` 标签 CRUD：`GET /api/tags?projectId=`、`POST /api/tags`、`DELETE /api/tags/{id}`
- [x] 3.3a 打标/解绑（4 条对称路径）：`POST /api/tasks/{id}/tags`（body `{"tagId":...}`）、`DELETE /api/tasks/{id}/tags/{tagId}`、`POST /api/workflows/{id}/tags`、`DELETE /api/workflows/{id}/tags/{tagId}`
- [x] 3.4 `PATCH /api/tasks/{id}/catalog`、`PATCH /api/workflows/{id}/catalog`：用 `Map` 载荷区分「显式 null」与「字段缺失」——`{"catalogNodeId":null}` 清空、`{}` 不改；拒收 `path` 字段
- [x] 3.5 扩展 `TaskController`/`WorkflowController` 列表查询：可选 `catalogNodeId`、`uncategorized=true`、`tagId` 过滤；无参时行为与现状等价（旧签名 search 保留并委托新签名）
- [x] 3.6 编译验证：`./mvnw install -DskipTests` 后 `./mvnw -q -pl dataweave-api compile`

## 4. 后端测试

- [x] 4.1 CatalogServiceTest：建树、读树计数、面包屑（path）；同级稳定排序
- [x] 4.2 移动文件夹单测：子树 path 前缀正确重写 + parent_id/path 一致性断言（含移到根）
- [x] 4.3 防环单测：移动到自身/后代被拒
- [x] 4.4 非空禁删单测：含子文件夹 / 含资产 两种均被拒，空文件夹可删
- [x] 4.5 归类单测：设置/覆盖唯一归属（service）；显式 null 清空 vs 字段缺失不改（CatalogApiTest）；未分类计数
- [x] 4.6 标签单测：项目内 name 唯一、打标幂等、删除级联解绑
- [x] 4.7 CatalogApiTest WebTestClient：端点状态码与错误码（409 NOT_EMPTY / 400 CYCLE / 409 TAG_DUPLICATE / 400 拒 path）正确
- [x] 4.8 列表无参回归测试：`GET /api/tasks` 不带新参数结果与旧行为一致
- [x] 4.9.1 H2 profile 下移动/子树/防环 SQL（`CONCAT`+`SUBSTRING`+`LIKE`）各通过（15 测试全过）
- [ ] 4.9.2 PG profile 下跑同组 SQL（待 Docker PG 环境；catalog-pg.sql 已用两库通用语法，迁移脚本就绪）

## 5. 前端可复用 CatalogTree 组件

- [x] 5.1 新增 zustand 树状态 store（展开/选中/标签过滤态）
- [x] 5.2 新增 `<CatalogTree>` 组件：可折叠文件夹、任务/工作流区分 hugeicons 图标、未分类虚拟根、语义 token
- [x] 5.3 类目树数据接入：调 `GET /api/catalog/tree` + 资产列表，组装为渲染树
- [x] 5.4 顶部标签 filter chips：选中标签横切过滤树内可见资产（取选中标签资产 id 并集过滤）
- [x] 5.5 树内拖动节点 → 调归类接口移动归属（MOVE_MIME 载荷，文件夹/未分类作 drop target，与画布 TASK_MIME 区分）
- [x] 5.6 类型检查：`pnpm typecheck`

## 6. 接入工作流画布左侧面板

- [x] 6.1 `workflow-canvas-view` 左侧拖拽面板替换为 `<CatalogTree>`（按类目组织待拖任务）——落实 workflow-canvas delta（MODIFIED「可视化 DAG 画布编辑」的左侧来源）
- [x] 6.2 保留并明确区分两种拖拽：树内移动归属 vs 拖任务到 ReactFlow 画布建 DAG 节点（以 drop target 判定，「拖入建 TASK 节点并绑定 task」行为契约不变）
- [x] 6.3 类型检查：`pnpm typecheck`
- [x] 6.4 Browser Verification Gate：真在浏览器跑通过——类目树渲染（数仓ODS>用户域、未分类）、拖任务到画布建节点（0→1）、拖工作流到文件夹改归属（画布节点不变 + 后端 workflowCount 0→1）互不串味、标签「核心」过滤生效、console 全程 0 error；验后清理临时产物

## 7. 文档与收口

- [x] 7.1 更新 CLAUDE.md 知识库导航：新增「类目树」与「前端类目树组件」两条
- [x] 7.2 自检 spec 场景全部有对应实现与测试；二期 requirement「Agent 经闸门操作类目」标注未实现（本期「catalog 写方法分层可复用」已落地：写方法入参纯领域参数、Controller 仅编排）
- [x] 7.3 全量编译通过；master 97 测试全过；api catalog 测试 15/15 全过；前端 `pnpm typecheck` 零错误。注：api 全量套件有 13 个**既有失败**（CliEndpoint/McpEndpoint/WorkspaceEndpoint/HealthAndCors 的 auth/CORS + SchedulerPreemption 异步 flaky）——已 `git stash` 在干净 main 上复现同样失败，确认与本变更无关。`openspec status` = 4/4 构件完成。
