## MODIFIED Requirements

### Requirement: JDBC 家族连通性测试
系统 SHALL 对 JDBC 类型的数据源提供连通性测试能力。测试 MUST 尝试建立实际 JDBC 连接并执行验证查询（`SELECT 1` 或类型特定查询），返回连接结果。

测试 SHALL 按驱动来源优先级选择加载方式：① 若数据源绑定了上传驱动 jar（`driver_jar_id` 非 NULL 且资产 `ACTIVE`），MUST 用该 jar 的隔离 ClassLoader 加载并直接调用 `Driver.connect()`；② 否则降级用 classpath 内置默认驱动经 `DriverManager.getConnection`；③ 两者均不可用时返回「驱动未安装」。驱动类名 SHALL 从 `datasource_types.driver` 字段解析（取代硬编码 switch）。

支持的 JDBC 类型（11 种）：MYSQL、POSTGRES、ORACLE、SQLSERVER、MARIADB、DB2、CLICKHOUSE、STARROCKS、DORIS、HIVE、IMPALA。

#### Scenario: JDBC 连接成功
- **WHEN** 对 MySQL 数据源执行连通性测试，连接参数正确且网络可达
- **THEN** 返回 `{success: true, message: "连接成功", latencyMs: 45, serverVersion: "8.0.32"}`

#### Scenario: JDBC 连接失败（网络不可达）
- **WHEN** 对 MySQL 数据源执行连通性测试，主机不可达
- **THEN** 返回 `{success: false, message: "连接失败: Connection refused (10.0.0.20:3306)", latencyMs: 3000}`

#### Scenario: JDBC 连接失败（认证失败）
- **WHEN** 对 PostgreSQL 数据源执行连通性测试，密码错误
- **THEN** 返回 `{success: false, message: "连接失败: 认证失败 (password authentication failed)", latencyMs: 120}`

#### Scenario: 绑定上传 jar 时用隔离加载
- **WHEN** 数据源绑定了上传的 ojdbc6 jar，执行连通性测试
- **THEN** 用该 jar 的隔离 ClassLoader 加载、`Driver.connect` 建连，classpath 是否内置 Oracle 驱动不影响结果

#### Scenario: 未绑定则用内置默认驱动
- **WHEN** MySQL 数据源未绑定上传 jar，且 classpath 内置 mysql 驱动
- **THEN** 经 `DriverManager` 用内置驱动建连

#### Scenario: 内置与上传均不可用
- **WHEN** 数据源未绑定 jar 且 classpath 无对应内置驱动
- **THEN** 返回「驱动未安装: <driverClass>」（文案经 i18n 本地化）
