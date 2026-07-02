## 1. 组件基建(DataTable + Toolbar)

- [x] 1.1 定义类型:`ColumnDef<T>`(key/header/widthPct/align/cell)、`FilterDef`(key/label/kind/options/tier/default)、`FilterPreset`、`PageResult<T>`(content/totalElements/page/size)、`DataTableMode`
- [x] 1.2 实现 `components/ui/data-table-toolbar.tsx`:按 `FilterDef.kind` 渲染 search/segmented/multiSelect/dateRange/toggle;primary 常驻 + advanced「更多筛选」;激活计数 + 一键清空;预设 chips
- [x] 1.3 实现 `components/ui/data-table.tsx`:三段式布局、双表共享 colgroup 固定表头、`DwScroll` 数据区滚动、`Pagination`、空/加载插槽、可选多选 + 批量操作栏
- [x] 1.4 实现双模式:server 模式 `fetcher(query)=>Promise<PageResult<T>>` 不持全量;client 模式本地筛选/排序/分页;统一筛选状态对象联动 toolbar
- [x] 1.5 分页基准归一适配层(0-based / 1-based 端点差异在前端归一到 `Pagination`)
- [x] 1.6 组件 vitest:列渲染、筛选值联动、预设设值、client 分页、空/加载态
- [x] 1.7 `frontend/DESIGN.md` 写入结构化表格规范条款(布局/固定表头/滚动条/分页/筛选词汇·分层·预设·智能默认/空·加载);如涉变量同步 `globals.css`;`pnpm design:lint` 通过

## 2. 标杆回填验证

- [x] 2.1 用 `DataTable`+`Toolbar` 重写 `periodic-instances-panel`,列/筛选/多选批量/预设(今天失败·正在运行·超时)与默认(bizDate=今天·未成功优先)等价
- [x] 2.2 浏览器验证门:实景渲染、筛选/翻页/批量走通、console 无错;交互与视觉不退化

## 3. 后端就绪端点补维度(任务实例 / 任务定义)

- [x] 3.1 `GET /api/ops/instances` 补 state 多选、业务日期区间、起止时间区间、`workerNodeCode`、失败原因搜索;默认今天+未成功优先;WebTestClient(带 JWT)测
- [x] 3.2 `GET /api/tasks` 补 owner、frozen、datasourceId 筛选维度;测
- [x] 3.3 前端 `instance-table`、`task-def-list` 迁移到 `DataTable`(server 模式),配筛选 + 预设(我的草稿/已冻结)

## 4. 中改端点(周期/手动任务流)

- [x] 4.1 `WorkflowDefRepository` 加按名称/hasDraftChange/目录/负责人的动态查询 + 分页(CONCAT 拼接,H2/PG 各测)
- [x] 4.2 `OpsService`/`OpsController` 周期任务流加筛选分页;响应暴露 lastFireTime/hasDraftChange/priority/timeoutSec/updatedAt/updatedBy;「最近触发结果」按最近实例 state 关联
- [x] 4.3 手动任务流加筛选(名称/最近触发结果/负责人/目录)+ 分页
- [x] 4.4 后端 WebTestClient 测周期/手动任务流筛选分页(契约 200 + $.code/$.data)
- [x] 4.5 前端 `periodic-workflows-panel` 迁移 `DataTable`:补列 + 筛选(名称搜索/草稿段控/最近触发结果)+ 预设(有未发布改动/最近触发失败)
- [x] 4.6 前端 `manual-workflows-panel` 迁移 `DataTable` + 筛选

## 5. 补数据 Run 桩补真

- [x] 5.1 `BackfillRunRepository` 加按状态(含 PARTIAL)/目标名/目标类型/业务日期区间/发起人/发起时间的查询 + 分页
- [x] 5.2 `DataOpsBridge` 补数据列表去掉「待 Stream A」桩,接真实查询返回 `Page`;`OpsController` 加 query 参数
- [x] 5.3 WebTestClient 测补数据列表筛选分页
- [x] 5.4 前端 `backfill-panel` 迁移 `DataTable`:筛选(状态多选/目标搜索/日期区间)+ 预设(进行中/部分失败/我发起的)+ 默认我发起的近 7 天

## 6. 数据新鲜度后端化

- [x] 6.1 后端新增数据新鲜度聚合端点:按任务 `MAX(finished_at) WHERE state=SUCCESS` 聚合 + 分档(6h/24h 阈值后端常量),支持分档筛选/名称搜索/最差优先排序/分页
- [x] 6.2 WebTestClient 测新鲜度端点(分档筛选、最陈旧优先、从未成功)
- [x] 6.3 前端 `freshness-view` 改 server 模式消费新接口,删除前端 `Date.now()` 派生;迁移 `DataTable`;预设(陈旧/从未成功)

## 7. 数据源 / 用户 / 角色 / 项目

- [x] 7.1 `DatasourceController`/`DatasourceService`/Repository 加名称·host 搜索、typeCode 多选、connectionStatus 筛选 + 分页;测
- [x] 7.2 `UserController`/Repository 加搜索(用户名/显示名/邮箱)、状态、角色多选 + 分页;`ProjectController` 加搜索/状态/负责人 + 分页;`RoleController` 加 code/名称搜索 + 分页;测
- [x] 7.3 前端 `datasources-view` 由手写 `<table>` 迁移 `DataTable` + 筛选(类型/连接状态/搜索)+ 预设(连接异常)
- [x] 7.4 前端 `settings-view` 用户表迁移 `DataTable` + 筛选(状态/角色/搜索)
- [x] 7.5 前端 `settings-view` 角色表迁移 `DataTable`(克制:仅搜索框)、项目表迁移 `DataTable` + 筛选(状态/负责人/搜索)

## 8. i18n 与整体验证

- [x] 8.1 补 `messages/{zh-CN,en-US}.json`:筛选标签、列头、预设名、空/加载文案(双 bundle 键集对齐);后端校验错误走 `BizException` + code
- [x] 8.2 后端各改动模块 `./mvnw -q -pl <module> compile` 零错;前端 `pnpm typecheck` 零错;`pnpm design:lint` 通过
- [x] 8.3 全量浏览器验证门:逐表实景渲染、筛选/翻页/预设/批量走通、console 无错、SSE/交互不退化;无 `…` 表「进行中」
- [x] 8.4 i18n 键集对齐脚本校验(zh-only/en-only 为空,每个 `t("key")` 可静态解析)
