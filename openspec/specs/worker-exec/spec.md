# worker-exec Specification

## Purpose

定义 worker 受控命令执行能力：`dataweave-worker` 提供白名单受限、强制超时与输出截断的命令执行端点，配合 master 侧审计落库，并以 `node_exec` MCP 工具契约校验节点在线后转发执行。
## Requirements
### Requirement: 受控命令执行端点
`dataweave-worker` SHALL 提供命令执行端点：仅接受配置化白名单前缀的命令（默认含 `dw `、`tail `、`grep `、`cat `、`df `、`free `、`jstat `），强制超时（默认 60s 可配）与输出截断（默认 64KB 可配），返回 exitCode、stdout/stderr（截断标记含原始长度）。

#### Scenario: 白名单内执行
- **WHEN** master 派发 `df -h` 到某在线 worker
- **THEN** worker 执行并返回退出码与输出

#### Scenario: 白名单外拒绝
- **WHEN** 命令首词不在白名单（如 `rm`、`curl`）
- **THEN** worker 拒绝执行并返回明确错误，不产生子进程

#### Scenario: 超时终止
- **WHEN** 命令执行超过时限
- **THEN** 进程被终止，返回超时标记与已产生的部分输出

### Requirement: 执行审计
worker 端点的每次调用 SHALL 由 master 侧落 `agent_action`（命令、目标节点、发起 step、结果摘要）；worker 自身 MUST 记录执行日志。

#### Scenario: 执行留痕
- **WHEN** node_exec 工具在节点 node-3 执行一条命令
- **THEN** agent_action 记录 target=node-3、完整命令与结果摘要，可由审计回放查到

### Requirement: node_exec 工具契约
MCP 工具 `node_exec` SHALL 接受 `{nodeCode, command}`，由 master 校验节点在线后转发至对应 worker；节点离线或不存在 MUST 返回明确错误。该工具的等级裁决遵循 policy-engine 的命令串安全解析。

#### Scenario: 离线节点报错
- **WHEN** agent 对 OFFLINE 节点调用 node_exec
- **THEN** 返回「节点离线」错误，不入派发队列

### Requirement: Shell 执行器数据源环境变量注入
`ShellTaskExecutor` SHALL 支持数据源环境变量注入。当 `ExecutionContext` 包含非空的数据源环境变量 Map 时，`ShellTaskExecutor` MUST 将这些变量注入到子进程的环境变量中（`ProcessBuilder.environment()`）。

注入 MUST 与现有环境变量（`DW_ATTEMPT`、`DW_BIZ_DATE`）共存，不覆盖已有变量。数据源环境变量命名以 `DW_DS_` 为前缀。

#### Scenario: 有数据源绑定时注入环境变量
- **WHEN** Shell 任务的 `ExecutionContext` 包含数据源环境变量 `{DW_DS_HOST: "10.0.0.20", DW_DS_PORT: "3306", DW_DS_USER: "app", DW_DS_PASSWORD: "***", DW_DS_TYPE: "MYSQL"}`
- **THEN** 子进程可通过 `System.getenv("DW_DS_HOST")` 或 Shell 脚本中 `$DW_DS_HOST` 获取到对应值

#### Scenario: 无数据源绑定时不注入
- **WHEN** Shell 任务的 `ExecutionContext` 数据源环境变量为 null 或空 Map
- **THEN** 子进程环境中不包含 `DW_DS_*` 变量，行为与改造前一致

#### Scenario: Shell 脚本使用数据源环境变量
- **WHEN** Shell 脚本内容为 `mysql -h $DW_DS_HOST -P $DW_DS_PORT -u $DW_DS_USER -p$DW_DS_PASSWORD -e "SELECT 1"`
- **THEN** Shell 执行器将数据源环境变量注入子进程，脚本可正确连接数据库

### Requirement: ExecutionContext 扩展数据源环境变量
`ExecutionContext` record SHALL 新增 `shellEnvVars` 字段（`Map<String, String>`），用于承载 Shell 任务的数据源环境变量。该字段 MUST 可选（null 或空 Map 表示无数据源注入）。

#### Scenario: 构建带数据源的 ExecutionContext
- **WHEN** `InProcessTaskExecutionGateway` 为 Shell 任务构建 `ExecutionContext`
- **THEN** 调用 `DatasourceResolver.resolve(datasourceId, "SHELL")` 获取环境变量 Map，传入 `ExecutionContext` 构造器

### Requirement: InProcessTaskExecutionGateway 改用 DatasourceResolver
`InProcessTaskExecutionGateway` SHALL 将现有 `resolveDatasource(Long)` 方法改为使用 `DatasourceResolver`。对 SQL 任务，输出与现有 `DataSourceRef` 兼容；对 Shell 任务，输出环境变量 Map；对 Python 任务，输出 JSON 配置路径。

#### Scenario: SQL 任务走 DatasourceResolver
- **WHEN** SQL 任务执行，`datasourceId=1`
- **THEN** `InProcessTaskExecutionGateway` 调用 `DatasourceResolver.resolve(1, "SQL")`，返回 `DataSourceRef`，传给 `SqlTaskExecutor`

#### Scenario: Shell 任务走 DatasourceResolver
- **WHEN** Shell 任务执行，`datasourceId=1`
- **THEN** `InProcessTaskExecutionGateway` 调用 `DatasourceResolver.resolve(1, "SHELL")`，返回环境变量 Map，放入 `ExecutionContext.shellEnvVars`

