## ADDED Requirements

### Requirement: 数据源驱动 jar 绑定
系统 SHALL 在 `datasources` 表提供 `driver_jar_id` 字段（NULL = 走内置默认驱动）。`POST /api/datasources`（创建）与 `PUT /api/datasources/{id}`（更新）SHALL 接受可选 `driverJarId`；绑定/解绑/清空为可选子操作。绑定或替换 `driver_jar_id` 到非 NULL 属副作用变更，MUST 经 `PolicyEngine` 安全门（与 driver jar 上传同级）。数据源详情返回 SHALL 包含 `driverJarId` 与生效来源标识（`builtin` / `uploaded`）。

#### Scenario: 创建时绑定上传驱动
- **WHEN** 创建 Oracle 数据源并指定 `driverJarId` 指向已审批（ACTIVE）的 ojdbc6 资产
- **THEN** `datasource.driver_jar_id` 存储该资产 id（经安全门），详情返回生效来源=`uploaded`

#### Scenario: 未绑定走内置默认
- **WHEN** 创建 MySQL 数据源不指定 `driverJarId`
- **THEN** `driver_jar_id` 为 NULL，详情返回生效来源=`builtin`

#### Scenario: 解绑清空
- **WHEN** 对已绑定 jar 的数据源执行解绑
- **THEN** `driver_jar_id` 置 NULL，后续连接改用内置默认驱动

#### Scenario: 绑定未审批资产被拒
- **WHEN** 绑定 `driverJarId` 指向 `status` 仍为 `PENDING` 的资产
- **THEN** 返回审批提示（如 409），拒绝绑定生效
