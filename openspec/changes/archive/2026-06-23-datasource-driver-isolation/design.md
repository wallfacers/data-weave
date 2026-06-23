## Context

DataWeave 的数据源连通性测试(`dataweave-master` 的 `JdbcConnectionTester`)与 SQL 执行(`dataweave-worker` 的 `SqlTaskExecutor`)目前都通过 `DriverManager.getConnection` 走 **JDBC SPI 驱动发现**——即驱动 jar 必须在 JVM 的 classpath 上。但全工程 `pom.xml` 里**只有 PostgreSQL 一个驱动**(`dataweave-api/pom.xml:51-56`),于是除 PostgreSQL 外的所有 JDBC 类型一律 `驱动未安装`。

即便补齐内置驱动,仍有刚性缺口:老旧数据库(Oracle 11g、DB2 v9.7、GBase、达梦、人大金仓)常**必须用特定版本 driver jar**,而 Maven 依赖是全局单版本,无法按数据源隔离版本。这是 SeaTunnel / DolphinScheduler / DataX 等数据工具普遍用"上传 jar + 隔离 ClassLoader"解决的标准问题。

关键现状约束(决定改造接触面):

- `JdbcConnectionTester.resolveDriver()` 是**硬编码 switch**,无视 `datasource_types.driver` 字段;报错文案硬编码中文(违反 i18n 约定)。
- `SqlTaskExecutor` 走 `DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password())`;`ExecutionContext.DataSourceRef` 是 record,只带 `name/typeCode/jdbcUrl/...`,**不带任何驱动信息**。
- `dataweave-api` 是 **WebFlux**(SSE/AG-UI),上传不是 Spring MVC 的 `MultipartFile`,而是 `FilePart`/`PartEvent`;`dataweave-worker` 是 servlet web,且在 `scheduler.mode=distributed` 下是**独立 JVM**(独立 `WorkerApplication` + `classifier=exec` fat jar),api 模块依赖传不进它的 classpath。
- `S3LogArchiveStorage`(master)已配好 `MinioClient`,可复用作 jar 存储后端。
- DataWeave 有强安全约束:**所有写/副作用操作经 `GatedActionService` → `PolicyEngine` L0–L4 门 + 4 张审计表**。上传 jar = 加载可执行代码 = RCE 风险,属于必须过门的高危操作。

## Goals / Non-Goals

**Goals:**
- 内置默认驱动覆盖主流开源库,无上传 jar 的数据源开箱可连(连接测试 + SQL 执行)。
- 支持上传 jar → sha256 去重资产化 → 绑定到具体数据源 → 隔离加载覆盖默认驱动,实现**多版本驱动按数据源共存**。
- 同时覆盖 all-in-one 与 distributed 两种调度模式。
- 上传/替换 jar 经安全门与审计,杜绝裸 RCE。
- 降级路径完整:未绑定 jar → 走原 `DriverManager` 默认路径,现存行为零退化。

**Non-Goals:**
- 不默认打包 HIVE(hive-jdbc 拖入 Hadoop 全家桶,版本冲突高发)与闭源 IMPALA——按需文档化手动安装。
- 不改造非 JDBC 类型(MONGODB/REDIS/ELASTICSEARCH/HBASE)与 STORAGE 类型(S3/HDFS/FTP),它们继续走 `UnsupportedConnectionTester` fallback。
- 不做 SQL 结果集行数据展示(沿用 sql-task-execution 现状)。
- 不做 ClassLoader 的精细热卸载/周期回收等高级生命周期管理(本期 LRU + 进程级缓存即可)。

## Decisions

### D1. 隔离粒度 = 每 jar(版本)一个 `URLClassLoader`,以 `driver_jar_id` 为缓存键
**选择**:同一份上传 jar(同 sha256)共享一个 `URLClassLoader` 与 `Driver` 实例,被多个数据源引用复用;不同版本各自独立 CL。
**理由**:真正解决"数据源 A 用 ojdbc6、B 用 ojdbc11"的多版本共存;资产复用避免重复加载。
**备选**:① 全局单 CL(多版本冲突,否决);② 每数据源一 CL(同版本重复加载,浪费)。

### D2. 绕开 `DriverManager`,隔离路径直接调 `Driver.connect()`
**选择**:绑定了上传 jar 时,`Class.forName(driverClass, true, isolatedCl)` 取 `Driver` 实例,直接 `driver.connect(jdbcUrl, props)`,**不经 `DriverManager`**。
**理由**:`DriverManager.getConnection` 内部对每个注册 Driver 做 `isDriverAllowed(driver, callerClassLoader)` 校验——driver 的 CL 必须是 caller CL 或其祖先。自定义 `URLClassLoader` 加载的驱动,应用 CL 不是其父 → 判"不被允许" → `No suitable driver`。这是隔离加载的经典陷阱,直接持有 `Driver` 实例调 `.connect()` 是唯一干净解法。
**未绑定路径**:继续走 `DriverManager.getConnection`(内置驱动在 classpath,SPI 自动发现),保持原行为。

```
连接一个数据源(测试/执行统一入口):
  if datasource.driver_jar_id != NULL:           ★ 上传覆盖
     jar = driver_jars[id]
     driver = classLoaderCache.get(jar.sha256)  // 按 sha256 复用 CL+Driver
              ?: loadIsolated(jar)               // URLClassLoader → Class.forName → newInstance
     conn = driver.connect(jdbcUrl, props)       // ★ 绕开 DriverManager
  else if 内置驱动在 classpath:                   ★ 默认降级
     conn = DriverManager.getConnection(jdbcUrl, props)
  else:
     报"驱动未安装" (内置/上传均不可用)
```

### D3. 存储 = MinIO 优先 + 本地降级
**选择**:上传 jar 存 MinIO(distributed 模式天然中转,worker 各自拉取);当 MinIO 未启用(all-in-one / H2 零依赖克隆模式)时降级到本地 `libs/jdbc/` 目录。`driver_jars.storage_type` 记录 `'MINIO'|'LOCAL'`。
**理由**:复用已有 `S3LogArchiveStorage` 的 MinIO 基建;降级保证"克隆即跑、CI 零外部依赖"底线不被破坏(SqlTaskExecutor 的方案 A 回退哲学)。
**备选**:仅本地(分布式不友好);仅 MinIO(破坏零依赖克隆)。

### D4. 默认驱动声明在 `master` + `worker` 两个模块(runtime scope)
**选择**:开源驱动(mysql-connector-j、mssql-jdbc、mariadb-java-client、clickhouse-jdbc、ojdbc、db2 jcc)加到 `dataweave-master/pom.xml` 与 `dataweave-worker/pom.xml`,而非只放 api。
**理由**:连接测试在 master、SQL 执行在 worker;distributed 模式 worker 独立 JVM,api 模块依赖传不进 worker fat jar。两个模块都声明才能覆盖两种模式。
**备选**:全放 api(仅 all-in-one 安全,distributed 模式 worker 执行期 `No suitable driver`)。

### D5. 安全门 = MVP 直通 + 审计（审批门 TODO 后补）
**选择**(apply 阶段决策调整,原 D5 L2 审批降级):上传/替换 jar 经校验(强制 `.jar` 后缀、sha256 去重、解析 `META-INF/services/java.sql.Driver` 确认含真实 JDBC 驱动实现)后直接落 `driver_jars.status=ACTIVE` 可绑定,**不强制走 `GatedActionService` 审批闭环**;但 MUST 写 `agent_action` 审计(谁、何时、传了什么、绑给谁),保留轨迹。审批门(PENDING→审批→激活,需扩展 `PlatformActionExecutor`)标为 TODO 后续补。
**理由**:用户核心诉求是「老数据库版本兼容,必须用特定 jar」,先让上传覆盖链路跑通解决实际问题;校验 + 审计已提供基本安全网。完整审批闭环(对齐 node_exec 的 L2 门)作为安全加固后续 change。
**备选**(原):L2 审批经 `GatedActionService`——工作量大(需扩展 `PlatformActionExecutor` 支持 DATASOURCE_DRIVER_UPLOAD action type 的执行回调),降级为 TODO。

### D6. `datasources.driver_jar_id` NULL = 走默认,无破坏性变更
**选择**:新增列默认 NULL;绑定/解绑/清空均为可选子操作。
**理由**:保证现存数据源零退化,降级路径天然成立。

### D7. 顺带清两项技术债
- `resolveDriver()` 硬编码 switch → 读 `DatasourceType.driver` 字段(`datasource_types` 种子数据已填 driver 类名)。**已落地。**
- 报错文案 i18n:**已落地**——`ConnectionTester` 接口加 `Locale` 参数 + `JdbcConnectionTester`/`UnsupportedConnectionTester` 注入 `Messages` + `ConnectionTesterFactory` + controller 透传 `Accept-Language` + 后端 bundle(zh/en `datasource.test.*`)。`ConnectionTesterTest` 用真实 `ResourceBundleMessageSource` 按 locale 验证文案。

## Risks / Trade-offs

- **[RCE 风险] 上传 jar 即执行任意代码** → D5 安全门 + 校验 + 审计;PolicyEngine L2 默认审批把关。
- **[ClassLoader 泄漏 / jar 文件锁] 长驻进程加载大量 jar 导致 Metaspace 膨胀、Windows 文件锁** → CL 缓存设 LRU 上限;加载前将 jar 复制到临时副本再加载(避免锁原文件);本期不做热卸载,进程级缓存。
- **[单 jar 内依赖冲突] 某驱动 jar 自带传递依赖与 Spring Boot 4 冲突** → 隔离 CL 父类加载已缓解大部分;CL 内仍可能冲突时,文档说明"优先用瘦身版/standalone jar"。
- **[distributed 拉 jar 延迟] worker 首次执行需从 MinIO 拉 jar** → 本地缓存命中后零延迟;冷启动一次拉取可接受。
- **[HIVE/IMPALA 不支持] 用户上传对应 jar 仍可能因依赖复杂失败** → Non-Goal,文档说明限制;隔离 CL 已是最好的隔离环境,失败可降级模拟(对齐 SqlTaskExecutor 方案 A)。
- **[WebFlux 上传差异] `FilePart`/`PartEvent` 与 Spring MVC `MultipartFile` API 不同** → 实现期用 WebFlux 原生 API,不引入 servlet 适配。

## Migration Plan

- **DDL(PG + H2 双方言)**:① 改 `dataweave-api/src/main/resources/schema.sql`(H2)新增 `driver_jars` 表 + `datasources.driver_jar_id` 列;② 新增 `dataweave-api/src/main/resources/db/migration/datasource-driver-isolation-pg.sql`(PG)。新列默认 NULL,向后兼容,存量数据源不受影响。
- **种子数据**:可选内置若干常用版本驱动元信息条目(本期以"上传即资产"为主,不强内置 jar 二进制)。
- **依赖**:在 master + worker pom 加默认驱动(runtime scope);首次需 `./mvnw install -DskipTests` 让 sibling 模块拿到新依赖。
- **回滚**:新功能全部可选(未绑定走原路径),回滚仅需移除新增端点/字段;DDL 列删除为破坏性,但 NULL 列对旧代码无害,可保留。

## Open Questions

- HIVE / IMPALA 是否在本期之后单列 change 处理(默认打包 hive-jdbc-standalone + 手动 Impala)?
- CL 缓存 LRU 上限具体数值(初步:按 sha256 条目数上限 32,或按 Metaspace 用量)?
- 本地降级目录是否固定 `backend/dataweave-api/libs/jdbc/`(已加入 `.gitignore`),还是可配 `driver.storage.local-dir`?
