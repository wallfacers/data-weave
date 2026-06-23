# datasource-crud Specification

## Purpose
TBD - created by archiving change datasource-management. Update Purpose after archive.
## Requirements
### Requirement: 数据源类型列表查询
系统 SHALL 提供 `GET /api/datasource-types` 端点，返回所有可用的数据源类型，按 category 分组，包含 code、name、category、driver、defaultPort 字段。该端点 SHALL 支持 `?category=` 查询参数过滤。

#### Scenario: 查询全部类型
- **WHEN** 客户端请求 `GET /api/datasource-types`
- **THEN** 返回 18 种数据源类型（RDB 6种 + MPP/OLAP 5种 + NoSQL 4种 + Storage 3种），按 category 排列

#### Scenario: 按 category 过滤
- **WHEN** 客户端请求 `GET /api/datasource-types?category=RDB`
- **THEN** 仅返回 RDB 类别的 6 种类型（MySQL、PostgreSQL、Oracle、SQL Server、MariaDB、DB2）

### Requirement: 数据源列表查询（项目级隔离）
系统 SHALL 提供 `GET /api/datasources` 端点，返回当前项目下的数据源列表。请求 MUST 携带 `projectId`（路径参数或查询参数），返回结果 MUST 仅包含该项目的数据源。返回数据 MUST 对密码字段脱敏（`password_enc` 不返回，或返回 `"******"`）。

#### Scenario: 按项目查询列表
- **WHEN** 客户端请求 `GET /api/datasources?projectId=1`
- **THEN** 返回 `project_id=1` 的所有 ACTIVE 数据源，密码字段为 `"******"` 或不存在

#### Scenario: 空列表
- **WHEN** 客户端请求 `GET /api/datasources?projectId=999`（不存在的项目）
- **THEN** 返回空数组 `[]`，HTTP 200

### Requirement: 数据源详情查询
系统 SHALL 提供 `GET /api/datasources/{id}` 端点，返回指定数据源的详情。返回数据 MUST 对密码字段脱敏。

#### Scenario: 查询存在的數據源
- **WHEN** 客户端请求 `GET /api/datasources/1`
- **THEN** 返回该数据源的完整信息（name, typeCode, host, port, databaseName, jdbcUrl, username, propsJson, status），密码字段为 `"******"`

#### Scenario: 查询不存在的数据源
- **WHEN** 客户端请求 `GET /api/datasources/999`
- **THEN** 返回 404 错误，消息体符合全局异常处理规范

### Requirement: 创建数据源
系统 SHALL 提供 `POST /api/datasources` 端点，接受数据源创建请求。请求体 MUST 包含 `name`、`typeCode`、`projectId`。密码字段 MUST 在入库前加密存储（AES-256-GCM）。`tenantId` 从当前认证上下文获取。

#### Scenario: 成功创建 MySQL 数据源
- **WHEN** 客户端提交 `{name: "orders_mysql", typeCode: "MYSQL", projectId: 1, host: "10.0.0.20", port: 3306, databaseName: "shop", username: "app", password: "***"}`
- **THEN** 数据源创建成功，`password_enc` 列存储 AES-GCM 密文，返回创建后的数据源（密码脱敏），HTTP 200

#### Scenario: 缺少必填字段
- **WHEN** 客户端提交 `{name: "test"}`（缺少 typeCode、projectId）
- **THEN** 返回 400 错误，提示缺少必填字段

#### Scenario: 名称重复（同项目内）
- **WHEN** 客户端提交的项目内已存在同名数据源
- **THEN** 返回 409 冲突错误，提示"数据源名称已存在"

### Requirement: 更新数据源
系统 SHALL 提供 `PUT /api/datasources/{id}` 端点，接受数据源更新请求。如果请求包含 `password` 字段且不为空，MUST 重新加密存储；如果密码字段为空或不存在，MUST 保留原密码不变。

#### Scenario: 更新连接信息（不改密码）
- **WHEN** 客户端提交 `{host: "10.0.0.21", port: 3307}`，不包含 password 字段
- **THEN** host 和 port 更新，密码保持原值不变

#### Scenario: 更新连接信息（同时改密码）
- **WHEN** 客户端提交 `{host: "10.0.0.21", password: "new_password"}`
- **THEN** host 更新，密码重新加密存储

#### Scenario: 更新不存在的数据源
- **WHEN** 客户端请求 `PUT /api/datasources/999`
- **THEN** 返回 404 错误

### Requirement: 删除数据源（软删除）
系统 SHALL 提供 `DELETE /api/datasources/{id}` 端点，执行软删除（`deleted` 标记置 1）。已绑定到任务的数据源删除时 MUST 返回警告提示（但不阻止删除）。

#### Scenario: 删除未绑定的数据源
- **WHEN** 客户端请求 `DELETE /api/datasources/1`
- **THEN** `deleted` 置 1，HTTP 200，后续列表查询不再返回该数据源

#### Scenario: 删除已绑定任务的数据源
- **WHEN** 客户端请求删除一个被 `task_def.datasource_id` 引用的数据源
- **THEN** 删除成功，但响应中包含警告信息"该数据源已被 N 个任务引用，删除后相关任务将无法执行"

### Requirement: 数据源名称唯一性约束
同一 `project_id` 下，数据源名称 MUST 唯一（未删除的记录间）。不同项目允许同名数据源。

#### Scenario: 同项目同名冲突
- **WHEN** 项目 1 已有名为 "orders" 的数据源，再次创建同名数据源
- **THEN** 返回 409 冲突错误

#### Scenario: 不同项目同名允许
- **WHEN** 项目 1 和项目 2 各自创建名为 "orders" 的数据源
- **THEN** 两者均创建成功

