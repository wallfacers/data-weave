## ADDED Requirements

### Requirement: 统一的 DataTable 高阶组件

系统 SHALL 提供一个可复用的前端组件 `DataTable<T>`,封装标杆 `periodic-instances-panel` 的版式:三段式布局(筛选工具栏 `shrink-0` / 表格区 `flex-1` / 分页栏 `shrink-0`)、表头与数据行拆为两张共享 `colgroup` 的 `<table>` 实现固定表头、仅数据行区域用 `DwScroll` 纵向滚动、底部 `Pagination`、空状态与加载状态插槽。所有被纳入统一改造的列表表格 MUST 使用该组件渲染,不得再手写 `<table>` 或散落的 `TableHead`/`TableCell` 版式。

#### Scenario: 固定表头与独立滚动

- **WHEN** 数据行高度超过表格区可视高度
- **THEN** 表头保持固定不滚动,仅数据行区域出现 `DwScroll` 纵向滚动条,且表头与数据列宽严格对齐(共享同一 `colgroup`)

#### Scenario: 空状态与加载状态

- **WHEN** 列表数据为空 / 正在首次加载
- **THEN** 表格区分别渲染空状态插槽(图标 + 标题 + 提示)/ 加载状态,而非显示空白或破碎布局

#### Scenario: 手写表格被替换

- **WHEN** 改造 `datasources-view` 与 `settings-view`(用户/角色/项目)等原手写 `<table>` 页面
- **THEN** 它们改用 `DataTable` 渲染,视觉与交互与标杆一致

### Requirement: 列以 ColumnDef 配置

`DataTable` SHALL 通过 `ColumnDef<T>[]` 数组声明列(字段键、表头文案键、列宽、对齐、自定义单元格渲染),取代逐表手写 `TableHead`/`TableCell`。列宽 MUST 以 `colgroup` 百分比 + `table-fixed` 表达以保证表头与数据对齐。

#### Scenario: 自定义单元格渲染

- **WHEN** 某列需要渲染 `Badge`、按钮组、截断文本或副标题
- **THEN** 该列的 `ColumnDef.cell` 提供渲染函数,组件按其输出渲染单元格

### Requirement: client / server 双模式

`DataTable` SHALL 同时支持 client 与 server 两种筛选/分页模式,并通过配置切换:client 模式在前端对传入数据数组做筛选、排序、分页;server 模式将筛选与分页参数交由调用方的取数器拼成后端 query 请求。组件对外暴露一致的 `FilterDef`/分页接口,业务侧切换模式不改变列与筛选的声明方式。

#### Scenario: server 模式拼接后端查询

- **WHEN** 表格配置为 server 模式,用户改变筛选或翻页
- **THEN** 组件将当前筛选值与 `page`/`size` 交给取数器,触发后端真实查询并以返回的 `Page` 渲染;不在前端缓存全量再筛选

#### Scenario: client 模式本地处理

- **WHEN** 表格配置为 client 模式且数据为前端已持有的数组
- **THEN** 组件在前端完成筛选/排序/分页,不发起额外请求

### Requirement: 统一的筛选工具栏与筛选词汇

系统 SHALL 提供 `DataTableToolbar`,以 `FilterDef[]` 声明筛选条。`FilterDef.kind` MUST 限定为统一的交互词汇:`search`(防抖多字段文本)、`segmented`(二元/小枚举段控)、`multiSelect`(枚举多选下拉)、`dateRange`(日期区间)、`toggle`(布尔快捷,如「只看我的」)。工具栏 MUST 区分 `primary`(常驻,建议 ≤4)与 `advanced`(收进「更多筛选」)两层,并提供激活筛选计数与一键清空。筛选条不得暴露无实际使用价值的维度(如按手机号、按精确到秒的时间戳)。

#### Scenario: 主/高级筛选分层

- **WHEN** 某表声明的筛选条超过 4 个
- **THEN** `primary` 条常驻工具栏,`advanced` 条收入「更多筛选」抽屉/弹层,工具栏不拥挤

#### Scenario: 清空与计数

- **WHEN** 当前已激活若干筛选条
- **THEN** 工具栏显示激活数量并提供「清空」一键复位所有筛选

### Requirement: 智能默认与语义化快捷预设

每个表格 SHALL 声明「智能默认」筛选值,使页面打开即停在最该看的一屏(例:任务流实例默认业务日期=今天且未成功优先,数据新鲜度默认最陈旧在前)。监控/分诊型表格 SHALL 提供「语义化快捷预设」chips,一次点击设好一组筛选组合以回答一个真实问题(如「今天失败」「我的草稿」「连接异常」「部分补数失败」)。预设 MUST 是真实组合开关,而非装饰。

#### Scenario: 打开即停在有用视图

- **WHEN** 用户打开任务流实例表格且未手动设置筛选
- **THEN** 表格应用智能默认(业务日期=今天、聚焦失败+运行中),而非展示全量未排序数据

#### Scenario: 快捷预设设好组合

- **WHEN** 用户点击「今天失败」预设 chip
- **THEN** 表格一次性设置 `bizDate=today` 与 `state=FAILED` 并刷新结果

### Requirement: 多选与批量操作

`DataTable` SHALL 支持可选的行多选(表头全选 + 行选 Checkbox)与批量操作栏,行为与标杆 `periodic-instances-panel` 一致;批量写操作 MUST 经平台闸门(`GatedActionService`),并按 `outcome`(EXECUTED / PENDING_APPROVAL / REJECTED)分流反馈,不得仅凭 `code===0` 判定成功。

#### Scenario: 批量操作走闸门分流

- **WHEN** 用户选中多行并触发批量写操作(如重跑)
- **THEN** 前端按返回 `outcome` 分别提示「已执行 / 待审批 / 被拒绝」,而非统一提示成功

### Requirement: DESIGN.md 结构化表格规范条款

`frontend/DESIGN.md` SHALL 新增结构化数据表格规范条款,覆盖:三段式布局、双表固定表头、`DwScroll` 滚动、`Pagination`、筛选工具栏(词汇/分层/预设/智能默认)、空与加载态。该条款 MUST 与组件实现一致,作为后续新表格的设计来源。`pnpm design:lint` MUST 通过。

#### Scenario: 新表格遵循规范

- **WHEN** 后续新增任意列表表格
- **THEN** 其作者依据 `DESIGN.md` 表格条款采用 `DataTable`,无需重新发明版式
