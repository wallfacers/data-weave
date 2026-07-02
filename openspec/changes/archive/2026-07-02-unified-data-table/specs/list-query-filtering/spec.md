## ADDED Requirements

### Requirement: 列表查询统一筛选与分页契约

平台所有列表查询端点 SHALL 遵循统一的后端筛选 + 分页契约:接受筛选 query 参数(按资源声明)与 `page`/`size` 分页参数,返回标准 `Page` 信封(`content`、`totalElements`、`page`、`size`)。筛选 MUST 在后端(SQL/Repository 层)真实执行,**禁止前端拉取全量后在 client 端假筛选**。为兼容既有调用,无任何筛选/分页参数时端点 MAY 保留返回全量数组的旧行为。

#### Scenario: 带筛选分页返回 Page 信封

- **WHEN** 调用方携带筛选参数与 `page`/`size` 请求任一列表端点
- **THEN** 后端按参数在数据层过滤并分页,返回 `Page`(content/totalElements/page/size)

#### Scenario: 筛选在后端执行

- **WHEN** 设置任一筛选条件
- **THEN** 结果集由数据库查询过滤产生,响应仅含匹配且当前页的记录,不在前端二次过滤

### Requirement: 周期任务流列表筛选

`GET /api/ops/periodic-workflows` SHALL 支持按名称模糊搜索、`hasDraftChange`(有未发布改动)、最近触发结果(成功/失败/从未触发)、业务域目录、负责人筛选,并支持分页。响应 MUST 暴露 `lastFireTime`、`hasDraftChange`、`priority`、`timeoutSec`、`updatedAt`、`updatedBy` 字段以支撑列展示与筛选。

#### Scenario: 仅看有未发布改动

- **WHEN** 筛选 `hasDraftChange=true`
- **THEN** 仅返回线上版本与编辑态不一致的周期任务流

#### Scenario: 按最近触发结果

- **WHEN** 筛选最近触发结果=失败
- **THEN** 仅返回最近一次调度触发为失败的周期任务流

### Requirement: 手动任务流列表筛选

`GET /api/ops/manual-workflows` SHALL 支持按名称模糊搜索、最近触发结果、负责人、业务域目录筛选,并支持分页。

#### Scenario: 按名称检索手动任务流

- **WHEN** 输入名称关键字
- **THEN** 返回名称匹配的手动任务流分页结果

### Requirement: 任务流实例列表筛选维度扩展

`GET /api/ops/instances` SHALL 在既有 `runMode`/`state`/`taskId`/`bizDate`/`page`/`size` 基础上,补充 `state` 多选、业务日期区间、起止时间区间、执行节点(`workerNodeCode`)、失败原因模糊搜索维度。默认 SHALL 以业务日期=今天、未成功优先返回。

#### Scenario: 今天失败预设

- **WHEN** 应用「今天失败」预设(bizDate=today、state=FAILED)
- **THEN** 返回当日失败实例分页结果

#### Scenario: 按执行节点排障

- **WHEN** 筛选某 `workerNodeCode`
- **THEN** 仅返回在该 worker 节点执行的实例

### Requirement: 补数据 Run 列表真实查询

补数据 Run 列表端点 SHALL 由当前 `待 Stream A` 桩补成真实查询实现:`BackfillRunRepository` 增加按状态(进行中/成功/失败/部分失败 PARTIAL)、目标对象名、目标类型(任务/工作流)、业务日期区间、发起人、发起时间区间的查询,返回 `Page` 信封。

#### Scenario: 部分失败可被一眼筛出

- **WHEN** 筛选状态=部分失败(PARTIAL)
- **THEN** 仅返回部分成功的补数 Run,且其状态在列表中明确标识

#### Scenario: 只看我发起的

- **WHEN** 启用「我发起的」并限定近 7 天
- **THEN** 返回当前用户在近 7 天发起的补数 Run 分页结果

### Requirement: 任务定义列表筛选补齐

`GET /api/tasks` SHALL 在既有 `keyword`/`type`/`status`/时间范围/`catalogNodeId`/`uncategorized`/`tagId` 基础上,补齐负责人(owner)、冻结状态(frozen)、数据源(datasourceId)筛选维度。

#### Scenario: 我的草稿预设

- **WHEN** 应用「我的草稿」(owner=当前用户、status=DRAFT)
- **THEN** 返回当前用户的草稿任务分页结果

### Requirement: 数据源连接列表筛选

`GET /api/datasources` SHALL 支持按名称/host 模糊搜索、类型(`typeCode` 多选)、连接状态(`connectionStatus` 段控)筛选,并支持分页;保留按项目过滤。

#### Scenario: 连接异常预设

- **WHEN** 应用「连接异常」(connectionStatus=异常)
- **THEN** 仅返回连接测试失败/断开的数据源

### Requirement: 用户·角色·项目列表筛选

`GET /api/users` SHALL 支持搜索(用户名/显示名/邮箱合一)、状态(启用/停用)、角色多选筛选与分页。`GET /api/projects` SHALL 支持搜索(code/名称)、状态(活跃/归档)、负责人筛选与分页。`GET /api/roles` 作为小表 SHALL 仅提供按 code/名称搜索(不堆砌多余筛选),并支持分页。

#### Scenario: 停用用户

- **WHEN** 筛选用户状态=停用
- **THEN** 仅返回被停用的用户分页结果

#### Scenario: 角色表克制筛选

- **WHEN** 渲染角色列表筛选工具栏
- **THEN** 仅提供一个搜索框,不提供多余的下拉筛选
