## MODIFIED Requirements

### Requirement: SQL 任务真实执行与数据源绑定

worker SHALL 提供 `SqlTaskExecutor` 执行 `type=SQL` 的调度任务：按 `task.datasourceId` 从 `datasources` 表解析业务数据源连接信息（jdbcUrl / username / 解密 password），建立 JDBC 连接执行任务脚本。执行 MUST 复用 `TaskExecutor` 的逐行日志回调（`onLine`）与 `biz_date`/`attempt` 注入约定，与 `ShellTaskExecutor` 同源同质。本期 SqlTaskExecutor MUST NOT 向日志输出查询结果集（`SELECT` 的行数据）；仅输出执行诊断信息（见「DataWorks 风启动日志」）。

执行连接 SHALL 遵循与连通性测试一致的驱动来源优先级——数据源绑定上传 jar（`driver_jar_id` 非 NULL 且资产 ACTIVE）时用隔离 ClassLoader 加载并直接调用 `Driver.connect()`，否则降级 `DriverManager.getConnection`（内置默认驱动）。`ExecutionContext.DataSourceRef` MUST 携带 `driverJarId` 与 `driverClass`（绑定时），以支撑 distributed 模式下 worker 依据 `driverJarId` 自行从存储后端拉取 jar 并隔离加载。隔离加载/连接失败时 SHALL 降级为方案 A 模拟执行（日志标注原因），不中断调度闭环。

#### Scenario: 配置数据源后真实执行 SQL

- **WHEN** 一个 `type=SQL` 且 `datasourceId` 指向可用数据源的任务被执行
- **THEN** SqlTaskExecutor 连接该数据源、执行脚本、返回真实退出状态（成功/失败），失败时日志含数据库错误信息

#### Scenario: SQL 失败如实反映

- **WHEN** SQL 脚本存在语法错误或目标表不存在
- **THEN** 实例状态为 FAILED，日志包含数据库返回的错误，error_message 含关键摘要

#### Scenario: 本期不打印结果集

- **WHEN** SQL 任务执行 `SELECT * FROM some_table`
- **THEN** 日志输出执行诊断（连接、开始、影响/返回行数摘要、耗时），但 MUST NOT 逐行打印结果集行数据

#### Scenario: 绑定上传 jar 时隔离加载执行

- **WHEN** `type=SQL` 任务的数据源绑定上传 jar，任务在 worker 执行
- **THEN** worker 用隔离 ClassLoader 加载该 jar、`Driver.connect` 建连执行脚本，不受 classpath 内置驱动版本影响

#### Scenario: distributed 模式 worker 自取 jar

- **WHEN** distributed 模式下 worker 执行绑定 jar 的 SQL 任务
- **THEN** worker 依据 `DataSourceRef.driverJarId` 从存储后端拉取 jar 并隔离加载（命中本地缓存则免拉取）
