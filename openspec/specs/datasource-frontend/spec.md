# datasource-frontend Specification

## Purpose
TBD - created by archiving change datasource-management. Update Purpose after archive.
## Requirements
### Requirement: datasources 视图注册
前端 SHALL 新增 `datasources` 视图类型（ViewType），注册到 workspace 视图系统。视图 MUST 有独立图标（DatabaseIcon 或类似）和 i18n 标题（`views.datasources`）。该视图 MUST NOT 替换现有 `integration` 视图。

#### Scenario: 视图出现在 tab launcher
- **WHEN** 用户点击 workspace tab 栏的 "+" 启动器
- **THEN** 视图列表中出现"数据源管理"（datasources）选项

#### Scenario: 打开视图
- **WHEN** 用户选择"数据源管理"
- **THEN** workspace 右侧打开 datasources 视图，加载数据源列表

### Requirement: 数据源列表页
视图 SHALL 以表格形式展示当前项目下的数据源列表。表格列 MUST 包含：名称、类型（含类型图标/标签）、主机地址、数据库名、状态、创建时间、操作。操作列 MUST 包含：编辑、测试连通性、删除。

#### Scenario: 列表加载
- **WHEN** 视图打开
- **THEN** 调用 `GET /api/datasources?projectId=<current>` 加载列表，表格渲染数据源行

#### Scenario: 空列表
- **WHEN** 当前项目无数据源
- **THEN** 显示空状态占位图，提示"暂无数据源，点击新增创建"

#### Scenario: 按类型标签过滤
- **WHEN** 用户点击类型过滤标签（如"RDB"、"MPP/OLAP"）
- **THEN** 列表仅显示该分类下的数据源

### Requirement: 新增/编辑数据源对话框
视图 SHALL 提供新增/编辑数据源的对话框（Dialog）。对话框 MUST 包含类型感知动态表单：选择数据源类型后，表单字段根据类型配置动态渲染。

表单 MUST 包含的通用字段：
- 数据源名称（必填）
- 数据源类型（必填，下拉选择，按 category 分组展示）
- 描述（可选）

按类型动态渲染的字段（由前端 `DATASOURCE_TYPE_CONFIG` 配置驱动）：
- 主机地址
- 端口（有默认值）
- 数据库名 / Bucket / Namespace（按类型标签不同）
- 用户名 / Access Key
- 密码 / Secret Key（密码输入框）
- 高级选项（折叠面板，含 JDBC URL 覆盖、propsJson 参数）

#### Scenario: 选择 MySQL 类型后表单渲染
- **WHEN** 用户在类型下拉中选择"MySQL"
- **THEN** 表单显示：主机地址、端口（默认 3306）、数据库、用户名、密码

#### Scenario: 选择 S3 类型后表单渲染
- **WHEN** 用户在类型下拉中选择"S3/MinIO"
- **THEN** 表单显示：Endpoint（替代主机）、Bucket（替代数据库）、Access Key（替代用户名）、Secret Key（替代密码）、Region

#### Scenario: 编辑时密码不回显
- **WHEN** 用户打开编辑对话框
- **THEN** 密码字段显示占位符"••••••••"，不显示明文或密文

#### Scenario: 编辑时不修改密码
- **WHEN** 用户编辑数据源但不修改密码字段（保持占位符）
- **THEN** 提交时不发送 password 字段，服务端保持原密码不变

### Requirement: 连通性测试按钮
新增/编辑对话框中 SHALL 包含"测试连通性"按钮。点击后 MUST 调用后端连通性测试接口，显示 loading 状态，测试完成后显示结果（成功/失败 + 详细信息）。

#### Scenario: 新建时测试连通性
- **WHEN** 用户填写表单后点击"测试连通性"
- **THEN** 调用 `POST /api/datasources/test` 提交当前表单参数，显示测试结果

#### Scenario: 测试成功
- **WHEN** 连通性测试返回 `success: true`
- **THEN** 显示绿色成功提示"连接成功 (延迟 45ms, 版本 8.0.32)"

#### Scenario: 测试失败
- **WHEN** 连通性测试返回 `success: false`
- **THEN** 显示红色错误提示"连接失败: Connection refused"

#### Scenario: 测试中禁用保存
- **WHEN** 连通性测试正在进行中
- **THEN** "保存"按钮禁用，防止在测试完成前保存

### Requirement: 删除确认
删除数据源时 SHALL 弹出确认对话框。如果数据源已被任务引用，确认对话框 MUST 显示警告信息。

#### Scenario: 删除未引用的数据源
- **WHEN** 用户点击删除，数据源未被任务引用
- **THEN** 弹出确认对话框"确定删除数据源 orders_mysql？"

#### Scenario: 删除已引用的数据源
- **WHEN** 用户点击删除，数据源被 3 个任务引用
- **THEN** 弹出确认对话框"该数据源已被 3 个任务引用，删除后相关任务将无法执行。确定删除？"

### Requirement: i18n 双语支持
datasources 视图的所有静态文案 MUST 同时提供中文（zh-CN）和英文（en-US）翻译，存放在 `frontend/messages/` 对应文件中。i18n key MUST 以 `datasources.` 为命名空间前缀。

#### Scenario: 中文环境显示中文
- **WHEN** 前端 locale 为 zh-CN
- **THEN** 视图标题为"数据源管理"，按钮文案为"新增数据源"、"测试连通性"、"保存"、"取消"

#### Scenario: 英文环境显示英文
- **WHEN** 前端 locale 为 en-US
- **THEN** 视图标题为"Datasource Management"，按钮文案为"New Datasource"、"Test Connection"、"Save"、"Cancel"

