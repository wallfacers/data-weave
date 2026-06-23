## ADDED Requirements

### Requirement: SQL 任务真实执行与数据源绑定

worker SHALL 提供 `SqlTaskExecutor` 执行 `type=SQL` 的调度任务：按 `task.datasourceId` 从 `datasources` 表解析业务数据源连接信息（jdbcUrl / username / 解密 password），建立 JDBC 连接执行任务脚本。执行 MUST 复用 `TaskExecutor` 的逐行日志回调（`onLine`）与 `biz_date`/`attempt` 注入约定，与 `ShellTaskExecutor` 同源同质。本期 SqlTaskExecutor MUST NOT 向日志输出查询结果集（`SELECT` 的行数据）；仅输出执行诊断信息（见「DataWorks 风启动日志」）。

#### Scenario: 配置数据源后真实执行 SQL

- **WHEN** 一个 `type=SQL` 且 `datasourceId` 指向可用数据源的任务被执行
- **THEN** SqlTaskExecutor 连接该数据源、执行脚本、返回真实退出状态（成功/失败），失败时日志含数据库错误信息

#### Scenario: SQL 失败如实反映

- **WHEN** SQL 脚本存在语法错误或目标表不存在
- **THEN** 实例状态为 FAILED，日志包含数据库返回的错误，error_message 含关键摘要

#### Scenario: 本期不打印结果集

- **WHEN** SQL 任务执行 `SELECT * FROM some_table`
- **THEN** 日志输出执行诊断（连接、开始、影响/返回行数摘要、耗时），但 MUST NOT 逐行打印结果集行数据

### Requirement: 无可用数据源时回退模拟（方案 A）

当 `type=SQL` 任务**未绑定数据源**、绑定的数据源不存在/不可用，或运行环境无 JDBC 驱动（如 all-in-one / H2 零依赖模式）时，SqlTaskExecutor SHALL 回退到「模拟执行成功 + 打印启动/诊断日志」，MUST NOT 抛错中断调度闭环。回退路径 MUST 在日志中明确标注「未配置可用数据源，模拟执行」，使行为对用户透明。该回退保证克隆即跑与 CI 零外部依赖底线不被破坏。

#### Scenario: 无数据源回退模拟

- **WHEN** 在 all-in-one 模式下执行一个未绑定数据源的 SQL 任务
- **THEN** 实例以模拟方式成功结束，日志含「未配置可用数据源，模拟执行」标注，调度闭环正常闭合

#### Scenario: 数据源不可用回退模拟

- **WHEN** SQL 任务绑定的数据源连接失败（如网络不可达）
- **THEN** 执行回退为模拟成功并在日志标注连接失败原因，不让单个不可用数据源阻断调度

### Requirement: ECHO 任务真实执行

worker SHALL 提供 `EchoTaskExecutor` 执行 `type=ECHO` 任务：将任务内容按行回显到日志（经 `onLine` 回调），支持 `${...}`/`$bizdate` 等参数替换后的内容，立即成功返回。ECHO 执行 MUST 产生真实日志行（不再走 all-in-one 「无执行器模拟」分支），用于最直接地验证「保存即测试运行 + 实时滚屏日志」闭环。

#### Scenario: ECHO 真回显

- **WHEN** 一个内容为多行文本的 ECHO 任务被测试运行
- **THEN** 日志逐行流出该文本内容，实例成功结束

### Requirement: DataWorks 风启动日志

SQL/ECHO/SHELL 任务执行 SHALL 在脚本输出前后包裹结构化「启动/收尾」诊断日志，风格对齐 DataWorks 运行日志：至少含运行模式（TEST/NORMAL）、任务类型、（SQL 时）目标数据源标识、开始时间、结束时间与耗时、最终状态。该诊断日志 MUST 经同一日志总线/SSE 通道流出，与脚本逐行输出在同一日志流内有序呈现。

#### Scenario: 启动日志包裹脚本输出

- **WHEN** 用户测试运行一个 SQL 任务
- **THEN** 日志开头出现「开始执行 / 运行模式 / 数据源」等启动行，结尾出现「执行结束 / 耗时 / 状态」收尾行，中间为脚本诊断输出

#### Scenario: 启动日志区分运行模式

- **WHEN** 同一任务分别以 TEST 与 NORMAL 运行
- **THEN** 两次日志的启动行分别标注 run_mode=TEST 与 run_mode=NORMAL
