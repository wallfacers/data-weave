## ADDED Requirements

### Requirement: JDBC 家族连通性测试
系统 SHALL 对 JDBC 类型的数据源提供连通性测试能力。测试 MUST 尝试建立实际 JDBC 连接并执行验证查询（`SELECT 1` 或类型特定查询），返回连接结果。

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

#### Scenario: JDBC 驱动未安装
- **WHEN** 对 ORACLE 数据源执行连通性测试，但 Oracle JDBC 驱动 jar 未引入
- **THEN** 返回 `{success: false, message: "驱动未安装: oracle.jdbc.OracleDriver，请添加对应驱动 jar"}`

### Requirement: 非 JDBC 类型连通性测试占位
系统 SHALL 对非 JDBC 类型的数据源（MONGODB、REDIS、ELASTICSEARCH、HBASE、S3、HDFS、FTP）注册连通性测试的占位实现。P0 阶段测试 MUST 返回明确的"暂不支持"提示，而非抛异常。

#### Scenario: 非 JDBC 类型测试返回占位提示
- **WHEN** 对 MONGODB 数据源执行连通性测试
- **THEN** 返回 `{success: false, message: "MongoDB 连通性测试暂未实现，请手动确认连接参数"}`

### Requirement: 连通性测试不存储结果
连通性测试 SHALL 是幂等的只读操作，MUST NOT 修改数据源记录或写入任何持久化状态。测试结果仅返回给调用方。

#### Scenario: 测试不影响数据源状态
- **WHEN** 对数据源执行多次连通性测试
- **THEN** 数据源的 `updated_at` 不变，无审计日志产生

### Requirement: 连通性测试超时控制
连通性测试 MUST 有超时限制（默认 10 秒），超时后 MUST 返回超时错误而非无限等待。

#### Scenario: 测试超时
- **WHEN** 数据源主机存在但 TCP 端口不响应（黑洞路由），超过 10 秒
- **THEN** 返回 `{success: false, message: "连接超时 (10s)", latencyMs: 10000}`

### Requirement: 连通性测试端点
系统 SHALL 提供 `POST /api/datasources/{id}/test` 端点，对已保存的数据源执行连通性测试。同时 SHALL 支持 `POST /api/datasources/test` 端点，接受完整的数据源配置（用于新建前的预测试，不入库）。

#### Scenario: 测试已保存的数据源
- **WHEN** 客户端请求 `POST /api/datasources/1/test`
- **THEN** 使用数据源 1 的连接参数执行测试，返回测试结果

#### Scenario: 预测试未保存的配置
- **WHEN** 客户端请求 `POST /api/datasources/test`，body 包含完整连接参数
- **THEN** 使用提交的参数执行测试，不创建数据源记录，返回测试结果
