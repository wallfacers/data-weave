## Why

SQL / Shell / Python 等任务需要连接外部数据源（MySQL、PostgreSQL、Hive、ClickHouse 等），但当前平台缺少数据源管理功能——没有 REST API、没有前端 UI、没有密码加密、没有统一的连接解析服务。用户无法注册、编辑、测试数据源连通性，任务只能依赖硬编码或模拟执行。这是数据平台的基础设施缺口，阻塞所有需要外部数据接入的任务类型。

## What Changes

- **新增数据源 CRUD REST API**：`DatasourceController` 提供 `/api/datasources` 的增删改查 + `/api/datasource-types` 类型列表 + `/api/datasources/{id}/test` 连通性测试。
- **扩展种子数据至 18 种数据源类型**：对标 DataWorks，覆盖 RDB（6种）、MPP/OLAP（5种）、NoSQL（4种）、Storage（3种），全部预置 `datasource_types` 种子数据。
- **密码 AES-256-GCM 加密存储**：新增 `DatasourceEncryptor`，主密钥从环境变量 `DATASOURCE_MASTER_KEY` 读取；写入时加密、读取时解密、前端永远只看到 `******`。
- **JDBC 家族连通性测试**：`ConnectionTester` 策略模式，P0 实现 `JdbcConnectionTester`（覆盖 11 种 JDBC 类型），非 JDBC 类型（MongoDB/Redis/ES/S3/HBase/HDFS/FTP）先注册类型、测试能力后续补。
- **统一连接解析服务 `DatasourceResolver`**：按任务类型输出不同格式的连接配置——SQL → `DataSourceRef`（JDBC）、Shell → 环境变量 `DW_DS_*`、Python → 临时 JSON 文件 `DW_DATASOURCE_CONFIG`。
- **Shell 执行器数据源注入**：`ShellTaskExecutor` 增加数据源环境变量注入，Shell 脚本可通过 `$DW_DS_HOST` / `$DW_DS_PORT` / `$DW_DS_USER` / `$DW_DS_PASSWORD` / `$DW_DS_URL` 访问绑定的数据源。
- **Python 执行器数据源注入**：`PythonTaskExecutor` 读取数据源配置写入临时 JSON 文件，通过 `$DW_DATASOURCE_CONFIG` 环境变量告知脚本路径，执行完即删。
- **前端 `datasources` 视图**：新增独立视图（不复用 `integration`），包含数据源列表（表格）+ 新增/编辑对话框（类型感知动态表单）+ 连通性测试按钮。支持按类型分类浏览、搜索、分页。
- **项目级隔离**：所有数据源操作强制 `tenant_id` + `project_id` 过滤，前端传入当前项目上下文。

## Capabilities

### New Capabilities

- `datasource-crud`: 数据源 CRUD REST API（DatasourceController + DatasourceService），包括列表、详情、创建、更新、删除、类型查询，项目级隔离，密码脱敏返回。
- `datasource-encryption`: 数据源密码 AES-256-GCM 加密/解密服务（DatasourceEncryptor），主密钥环境变量注入，写入加密 + 读取解密 + 前端永不暴露密文。
- `datasource-connection-test`: 连通性测试服务（ConnectionTester + JdbcConnectionTester），策略模式架构，P0 覆盖 JDBC 家族 11 种类型，返回成功/失败/延迟/服务端版本。
- `datasource-resolver`: 统一连接解析服务（DatasourceResolver），按任务类型输出连接配置——SQL→DataSourceRef、Shell→环境变量、Python→JSON 文件。改造 ShellTaskExecutor 和 PythonTaskExecutor 支持数据源注入。
- `datasource-frontend`: 前端 datasources 视图——列表页 + 类型感知动态表单 + 连通性测试 + 分类浏览。新增 ViewType `datasources`，i18n 双语。

### Modified Capabilities

- `worker-exec`: Shell 执行器增加数据源环境变量注入（`DW_DS_*` 系列）；新增 Python 执行器读取数据源 JSON 配置。`ExecutionContext` 扩展支持 Shell 环境变量和 Python 配置路径。`InProcessTaskExecutionGateway` 改用 `DatasourceResolver` 解密密码。

## Impact

- **后端新增文件**：`DatasourceController`、`DatasourceService`、`DatasourceEncryptor`、`ConnectionTester`/`JdbcConnectionTester`、`DatasourceResolver`、`PythonTaskExecutor`（约 6 个新 Java 文件）。
- **后端修改文件**：`ShellTaskExecutor`（+env 注入）、`ExecutionContext`（+shellEnvVars/pythonConfigPath）、`InProcessTaskExecutionGateway`（改用 DatasourceResolver）、`DatasourceRepository`（+查询方法）、`data.sql`（18 种类型种子数据）。
- **前端新增文件**：`datasources-view.tsx`、`datasourceApi`（约 2-3 个新文件）。
- **前端修改文件**：`types.ts`（+Datasource 类型）、`views.ts`（+datasources ViewType）、`registry.tsx`（+视图注册）、`zh-CN.json` / `en-US.json`（+i18n）。
- **配置**：`application.yml` 新增 `datasource.master-key` 配置项。
- **依赖**：JDBC 驱动按需引入（MySQL、PostgreSQL 已有；Oracle、SQL Server、ClickHouse、MariaDB、DB2 等需要评估是否在 P0 引入驱动 jar）。
- **Schema**：`datasources` 和 `datasource_types` 表结构不变（P0 复用现有字段），仅扩充种子数据。
