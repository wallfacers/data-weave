## ADDED Requirements

### Requirement: SQL 任务数据源解析
`DatasourceResolver` SHALL 提供 `resolve(datasourceId, taskType)` 方法。当 `taskType` 为 `SQL` 时，MUST 返回 `DataSourceRef`（jdbcUrl, username, password），password 为解密后的明文。

#### Scenario: SQL 任务获取 JDBC 连接配置
- **WHEN** SQL 任务绑定了数据源 ID=1（MySQL），执行时调用 `DatasourceResolver.resolve(1, "SQL")`
- **THEN** 返回 `DataSourceRef { jdbcUrl: "jdbc:mysql://10.0.0.20:3306/shop", username: "app", password: "明文" }`

#### Scenario: SQL 任务未绑定数据源
- **WHEN** SQL 任务的 `datasourceId` 为 null
- **THEN** `DatasourceResolver` 返回 null，`SqlTaskExecutor` 回退到模拟执行

### Requirement: Shell 任务数据源环境变量注入
当 `taskType` 为 `SHELL` 且任务绑定了数据源时，`DatasourceResolver` SHALL 生成环境变量 Map，`ShellTaskExecutor` MUST 将这些环境变量注入到子进程环境中。

环境变量命名规范：
- `DW_DS_URL` — JDBC URL（如适用）
- `DW_DS_HOST` — 主机地址
- `DW_DS_PORT` — 端口号
- `DW_DS_DATABASE` — 数据库名
- `DW_DS_USER` — 用户名
- `DW_DS_PASSWORD` — 密码（明文）
- `DW_DS_TYPE` — 数据源类型代码（如 MYSQL）

#### Scenario: Shell 任务绑定数据源时注入环境变量
- **WHEN** SHELL 任务绑定了 MySQL 数据源，执行脚本 `echo $DW_DS_HOST`
- **THEN** 输出 `10.0.0.20`

#### Scenario: Shell 任务可用全部环境变量
- **WHEN** SHELL 任务绑定了 PostgreSQL 数据源，执行脚本 `echo $DW_DS_TYPE:$DW_DS_DATABASE`
- **THEN** 输出 `POSTGRES:mydb`

#### Scenario: Shell 任务未绑定数据源
- **WHEN** SHELL 任务的 `datasourceId` 为 null
- **THEN** 不注入 `DW_DS_*` 环境变量，`ShellTaskExecutor` 正常运行（与当前行为一致）

### Requirement: Python 任务数据源 JSON 配置注入
当 `taskType` 为 `PYTHON` 且任务绑定了数据源时，`DatasourceResolver` SHALL 将连接配置写入临时 JSON 文件，通过环境变量 `DW_DATASOURCE_CONFIG` 告知脚本文件路径。

JSON 文件内容格式：
```json
{
  "type": "MYSQL",
  "host": "10.0.0.20",
  "port": 3306,
  "database": "shop",
  "username": "app",
  "password": "明文",
  "jdbc_url": "jdbc:mysql://10.0.0.20:3306/shop",
  "props": {}
}
```

文件 MUST 位于系统临时目录，文件名含 instanceId 防冲突，文件权限 MUST 为 600（owner only），执行完毕后 MUST 删除。

#### Scenario: Python 任务读取数据源配置
- **WHEN** PYTHON 任务绑定了 MySQL 数据源，脚本读取 `$DW_DATASOURCE_CONFIG` 指向的 JSON 文件
- **THEN** JSON 内容为上述格式，包含解密后的密码

#### Scenario: Python 任务执行完毕清理临时文件
- **WHEN** PYTHON 任务执行结束（无论成功或失败）
- **THEN** `$DW_DATASOURCE_CONFIG` 指向的临时文件被删除

#### Scenario: Python 任务未绑定数据源
- **WHEN** PYTHON 任务的 `datasourceId` 为 null
- **THEN** 不创建临时文件，不设置 `DW_DATASOURCE_CONFIG` 环境变量

### Requirement: DatasourceResolver 解密密码
`DatasourceResolver` SHALL 在解析过程中调用 `DatasourceEncryptor.decrypt()` 解密密码。解密失败 MUST 抛出异常，阻止任务执行，不返回密文。

#### Scenario: 正常解密
- **WHEN** 数据源的 `password_enc` 是有效密文
- **THEN** 返回解密后的明文密码

#### Scenario: 解密失败阻止执行
- **WHEN** 数据源的 `password_enc` 解密失败（密文损坏或主密钥变更）
- **THEN** 抛出异常，任务实例状态标记为 FAILED，日志记录"数据源密码解密失败"

### Requirement: 向后兼容现有 SqlTaskExecutor
`DatasourceResolver` 对 SQL 任务的输出 MUST 与现有 `ExecutionContext.DataSourceRef` 兼容。`InProcessTaskExecutionGateway` 改用 `DatasourceResolver` 后，`SqlTaskExecutor` 的行为 MUST 不变（除了密码从明文变为解密后的明文）。

#### Scenario: SqlTaskExecutor 无感切换
- **WHEN** SQL 任务通过新的 `DatasourceResolver` 获取连接配置
- **THEN** `SqlTaskExecutor.doExecute()` 使用 `DataSourceRef` 的方式与改造前完全一致
