## Why

数据平台的连通性测试与 SQL 执行目前只靠 classpath 上**唯一的内置驱动集**(全工程仅 PostgreSQL 一个),导致除 PostgreSQL 外的所有 JDBC 类型一律报 `驱动未安装`。更要命的是:老旧数据库(Oracle 11g、DB2 v9.7、GBase、达梦、人大金仓等)往往**必须使用某个特定版本的 driver jar**,而 Maven 依赖是全局单版本,无法做到"数据源 A 用 ojdbc6、数据源 B 用 ojdbc11"。这类"老顽固"兼容性场景在真实数据团队里高频存在,单靠加默认依赖解决不了版本隔离问题。

因此需要一个 **JDBC 驱动隔离子系统**:内置覆盖主流开源库的默认驱动(开箱即用),同时支持**每数据源上传并绑定专属 jar**,通过隔离 ClassLoader 加载、覆盖默认驱动,满足版本兼容刚需。

## What Changes

- **内置默认驱动全覆盖**:在 `master` + `worker` 两个模块声明主流开源 JDBC 驱动(MYSQL/STARROCKS/DORIS 共用 mysql-connector-j、SQLSERVER、MARIADB、CLICKHOUSE、ORACLE ojdbc、DB2 jcc),让无上传 jar 的数据源开箱可连。HIVE 因拖入 Hadoop 全家桶引发版本冲突,本期不默认打包,文档说明按需;IMPALA 闭源,文档说明手动安装。
- **新增驱动 jar 资产管理**:`driver_jars` 表 + 上传/查询/删除端点,jar 按内容 `sha256` 去重资产化(同内容复用),存 MinIO(分布式天然友好)+ 本地降级。
- **新增数据源↔驱动 jar 绑定**:`datasources` 表加 `driver_jar_id` 字段(NULL = 走内置默认);绑定/解绑/清空操作。
- **隔离 ClassLoader 加载**:连接测试与 SQL 执行在"绑定了上传 jar"时,用每 jar 版本一个 `URLClassLoader` 加载、**直接调 `Driver.connect()` 绕开 `DriverManager` 的 ClassLoader 校验**,实现多版本共存;未绑定时降级走原有 `DriverManager` 路径。
- **安全门**:上传/替换 jar 属任意代码加载(RCE 风险),MUST 经 `PolicyEngine` 门(默认 L2 审批)+ `agent_action` 审计;上传强制 `.jar`、sha256 去重、解析 `META-INF/services/java.sql.Driver` 校验为真驱动。
- **前端**:数据源编辑页支持上传/选择/解绑驱动 jar,展示当前生效驱动来源(内置/上传)。
- **技术债清理(顺带)**:`JdbcConnectionTester.resolveDriver()` 硬编码 switch 改为读 `DatasourceType.driver` 字段;报错文案走 i18n(`Messages.get` / `BizException`)。

无破坏性变更:所有新能力均有"未绑定 → 走原默认路径"的降级,现存行为不退化。

## Capabilities

### New Capabilities
- `datasource-driver-jars`: 驱动 jar 资产生命周期——上传、校验、sha256 去重、存储(MinIO/本地)、安全门与审计、隔离 ClassLoader 加载与缓存、按数据源绑定覆盖默认驱动。

### Modified Capabilities
- `datasource-connection-test`: 测试时优先用数据源绑定的上传 jar 隔离加载(`Driver.connect`),未绑定则降级到内置默认驱动(`DriverManager`)。原 `JDBC 驱动未安装` 行为细化为"内置/上传均不可用"时才报。
- `sql-task-execution`: worker 执行期同样支持按绑定 jar 隔离加载;`ExecutionContext.DataSourceRef` 扩展携带 `driverJarId` / `driverClass` 以支撑分布式模式下 worker 自取 jar。
- `datasource-crud`: `datasources` 增 `driver_jar_id` 字段;新增绑定/解绑/清空驱动 jar 的子操作(带安全门)。

## Impact

- **后端 `master`**: `JdbcConnectionTester` 改造(隔离加载 + 读 DB 字段 + i18n);新增 driver jar 资产 domain/application/infrastructure(上传、存储、校验、ClassLoader 加载器);复用 `S3LogArchiveStorage` 的 MinIO 基建;经 `GatedActionService`/`PolicyEngine`。
- **后端 `api`**: `DatasourceController` 新增上传/绑定端点(WebFlux `FilePart`/`PartEvent`,非 Spring MVC `MultipartFile`);datasource 的 create/update 接受 `driverJarId`。
- **后端 `worker`**: `SqlTaskExecutor` 改造为隔离加载路径;`ExecutionContext.DataSourceRef` 加字段;distributed 模式下从 MinIO 拉 jar。
- **pom**: `master` + `worker` 加默认驱动依赖(runtime scope);`driver_jars` 新表 + `datasources.driver_jar_id` 列(PG + H2 双方言 DDL)。
- **前端**: 数据源编辑页驱动 jar 上传/选择/解绑 UI + 生效来源展示;i18n 双语补键。
- **安全**: 上传 jar = RCE,严格经 PolicyEngine 门与审计;ClassLoader 生命周期管理防泄漏/文件锁。
- **无影响**: 非 JDBC 类型(MONGODB/REDIS/…)与 STORAGE 类型走原有 fallback,本次不涉及。
