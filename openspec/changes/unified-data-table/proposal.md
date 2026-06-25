## Why

项目里有 11 个表格(运营中心周期/手动任务流、任务流实例、补数据、任务定义、任务实例、数据源、用户、角色、项目、数据新鲜度),只有「周期任务流实例」一个达到了生产级:三段式布局、固定表头、滚动条、分页、多选批量、有意义的筛选。其余表格各写一套——有的手写 `<table>`、有的全量裸返回、有的(周期任务流)只有 5 列光秃秃没有任何筛选、还有「数据新鲜度」是前端 client 端派生、「补数据」后端是 `待 Stream A` 桩。结果是视觉与交互不统一、运维/数据开发面对一堆「demo 表」无法高效定位目标。需要一次性把表格风格与筛选能力统一到标杆水平,并把筛选做成接后端真实查询、而非前端假兜底。

## What Changes

- **新增前端高阶组件 `DataTable<T>` + `DataTableToolbar`**:沉淀标杆 `periodic-instances-panel` 的三段式布局、双表共享 colgroup 固定表头、`DwScroll` 滚动、`Pagination`、多选批量、空/加载态;列以 `ColumnDef<T>[]` 配置取代手写 `TableHead`/`TableCell`。
- **组件具备 client / server 双模式能力(可配置)**;但**本次 11 个表格一律配置为 server 模式**,筛选与分页全部走后端真实查询。**禁止 client 端全量拉回再假筛选**。
- **按页面诉求为每个表配置「有价值」的筛选**:智能默认(打开即停在最该看的一屏)+ 语义化快捷预设(如「今天失败」「我的草稿」「连接异常」「部分补数失败」)+ 主/高级筛选分层 + 克制(小表如角色只给搜索框,不堆筛选)。
- **后端为每个列表接口补齐真实筛选 query 参数与分页**:周期/手动任务流由全量改为带筛选分页;**补数据 Run 列表从 `待 Stream A` 桩补成真实现** + 筛选;数据源/用户/角色/项目加 query 参数 + 分页;**数据新鲜度由前端派生改为后端真做聚合查询接口**;任务实例/任务定义补齐缺失筛选维度。
- **周期任务流表格补列**:`lastFireTime`(上次触发)、`hasDraftChange`(草稿待发布)、`priority`/`timeoutSec`、`updatedAt`/`updatedBy`,并升级为筛选维度。
- **`frontend/DESIGN.md` 写入结构化表格规范条款**(目前只规范 Markdown 表格与滚动条,缺结构化数据表标准):布局、固定表头、滚动条、分页、筛选工具栏、智能默认与预设。
- 改造 3 个手写 `<table>` 页面(`datasources-view`、`settings-view` 的用户/角色/项目)迁移到 `DataTable`。

## Capabilities

### New Capabilities
- `data-table-component`: 前端可复用的 `DataTable<T>` + `DataTableToolbar` 标准——三段式布局、双表固定表头、`DwScroll` 滚动、`Pagination`、多选批量、空/加载态;`ColumnDef` 列配置;`FilterDef` 筛选词汇(search/segmented/multiSelect/dateRange/toggle)与 client/server 双模式;智能默认、语义化快捷预设、主/高级分层;以及 `DESIGN.md` 结构化表格规范条款。
- `list-query-filtering`: 平台所有列表查询端点统一的后端筛选 + 分页契约——query 参数命名约定、`Page` 响应信封、每个资源(周期/手动任务流、任务流实例、补数据 Run、任务定义、任务实例、数据源、用户、角色、项目)的筛选维度与默认值;含补数据列表由桩补成真实查询实现。
- `data-freshness-monitor`: 后端新增数据新鲜度聚合查询接口(取代前端 client 端派生),按任务产出最近成功时间分档(新鲜/老化/陈旧/从未成功),支持时效分档与名称筛选、排序与分页。

### Modified Capabilities
<!-- 列表筛选契约统一由新 capability list-query-filtering 承载,避免拆成多个碎片 delta;补数据桩→真实现亦纳入其中 -->

## Impact

- **前端**:新增 `components/ui/data-table.tsx`、`components/ui/data-table-toolbar.tsx`(及 `ColumnDef`/`FilterDef` 类型);改造 11 处表格;`DESIGN.md` 新增表格规范;`messages/{zh-CN,en-US}.json` 补筛选/列/预设的 i18n 键(双 bundle 同步)。
- **后端**:`OpsController`/`OpsService` 周期·手动任务流加筛选分页;`DataOpsBridge` 补数据列表真实现 + 筛选;新增数据新鲜度聚合端点;`DatasourceController`、`UserController`、`RoleController`、`ProjectController` 加 query 参数 + 分页;对应 Repository 加查询方法;`TaskController` 补齐缺维度。Errors 走 `BizException` i18n。
- **契约**:列表端点返回信封统一为 `Page`(content/totalElements/page/size),无参时保留兼容;新增 query 参数向后兼容。
- **测试**:后端新筛选/分页端点 JUnit + WebTestClient;前端 `DataTable` 组件 vitest;浏览器验证门(标杆同款实景跑通)。
