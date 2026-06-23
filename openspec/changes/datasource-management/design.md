## Context

DataWeave 平台已有数据源的 domain 模型（`Datasource`、`DatasourceType`）、数据库表（`datasources`、`datasource_types`）、种子数据（MySQL/PostgreSQL/Hive 3 种）以及部分执行路径（`InProcessTaskExecutionGateway.resolveDatasource()` → `SqlTaskExecutor`）。但缺少管理面：没有 REST API、没有前端 UI、没有密码加密、没有统一的连接解析服务。

当前状态：
- **schema**：`datasources` 表字段以 JDBC 连接为核心（host/port/databaseName/jdbcUrl/username/passwordEnc/propsJson），可复用语义装非 JDBC 类型
- **执行**：`SqlTaskExecutor` 直接用 `DriverManager.getConnection()`，连接失败回退模拟；`ShellTaskExecutor` 完全不碰数据源；Python 执行器不存在
- **密码**：`password_enc` 列明文存储，无加密层
- **前端**：`integration` 视图是 `PlaceholderView` 占位

约束：
- 必须兼容 H2（开发）和 PostgreSQL（生产）
- 遵循 DDD 分层（domain ← application ← infrastructure ← interfaces）
- i18n 三规则：前端静态文案走 next-intl，后端业务文案走 Messages.get，异常走 BizException
- Jackson 3（`tools.jackson.databind.*`）+ Spring Boot 4

## Goals / Non-Goals

**Goals:**

- 完整的 18 种数据源类型种子数据，对标 DataWorks
- 数据源 CRUD REST API，项目级隔离
- 密码 AES-256-GCM 加密存储，前端永不暴露密文/明文
- JDBC 家族（11 种）连通性测试，策略模式架构便于扩展
- 统一 `DatasourceResolver`：按任务类型输出连接配置（SQL/Shell/Python 三种格式）
- 前端 `datasources` 独立视图：列表 + 类型感知动态表单 + 连通性测试
- Shell 执行器支持数据源环境变量注入
- Python 执行器支持数据源 JSON 配置注入

**Non-Goals:**

- MCP tools / Agent 意图识别（P1+）
- 任务创建 UI 中选择数据源的下拉联动（P1+）
- 非 JDBC 类型连通性测试（MongoDB/Redis/ES/S3/HBase/HDFS/FTP，后续按需求补充）
- Python 执行器完整实现（P0 只做数据源注入部分，Python 执行器骨架可后续补）
- 定时连通性巡检 / 数据源权限控制 / 环境隔离（dev/prod）（P2+）
- 数据同步/集成管道（独立功能，保留 `integration` 视图）

## Decisions

### D1: 种子数据 18 种类型，一表 + props_json 通用模型

**选择**：不新建子表，继续用 `datasources` 单表 + `props_json` 装类型特有参数。种子数据从 3 种扩到 18 种。

**理由**：
- 现有字段（host/port/databaseName/jdbcUrl/username/passwordEnc/propsJson）通过语义复用可以装下所有类型：S3 的 endpoint→host、accessKey→username、secretKey→password_enc；MongoDB 的 replicaSet→propsJson；等等
- 新增子表（datasource_mongo, datasource_s3...）需要为每种类型建表+写 Repository+写 Controller，维护成本高且类型间差异主要在连接参数而非结构
- DataWorks 也是"主表 + 扩展属性"模式

**替代方案**：类型子表（强类型但扩展成本高）——MVP 阶段不值得。

### D2: 密码加密——AES-256-GCM + 环境变量主密钥

**选择**：`DatasourceEncryptor` 服务，AES-256-GCM 对称加密，主密钥从环境变量 `DATASOURCE_MASTER_KEY` 读取（32 字节 hex）。每次加密生成随机 12 字节 IV，密文格式 `base64(iv + ciphertext + tag)`。

**理由**：
- GCM 是认证加密，同时保证机密性和完整性
- 环境变量主密钥是业界标准做法（12-Factor App），配合未来 KMS/Vault 升级路径
- 随机 IV 确保相同密码每次加密结果不同

**替代方案**：
- Vault/KMS 集成（更安全但引入外部依赖，P0 不需要）
- 明文 + 网络层防护（安全隐患，不可接受）

### D3: 连通性测试——策略模式 + P0 只做 JDBC

**选择**：`ConnectionTester` 接口 + `JdbcConnectionTester` 实现。按 `type_code` 分发到不同 tester。P0 覆盖 11 种 JDBC 类型。

**理由**：
- 策略模式让非 JDBC 类型（MongoDB/Redis/ES/S3/HBase）可以后续独立实现 tester 而不改框架
- JDBC 家族覆盖大部分常用类型（MySQL/PG/Oracle/SQL Server/MariaDB/DB2/ClickHouse/StarRocks/Doris/Hive/Impala），StarRocks/Doris 复用 MySQL 驱动
- 非 JDBC 类型需要额外引入 SDK（MongoDB driver、Lettuce、ES REST client、S3 SDK、HBase client），P0 先不引入

**替代方案**：P0 全覆盖（引入所有 SDK）——依赖过重，且非 JDBC 类型的使用场景尚不明确。

### D4: DatasourceResolver——按任务类型输出不同格式

**选择**：`DatasourceResolver.resolve(datasourceId, taskType)` 返回统一的 `ConnectionProfile`，按 taskType 适配：
- SQL → `DataSourceRef`（jdbcUrl, username, password）——与现有 `ExecutionContext.DataSourceRef` 兼容
- Shell → `Map<String, String>` 环境变量（`DW_DS_URL`, `DW_DS_HOST`, `DW_DS_PORT`, `DW_DS_DATABASE`, `DW_DS_USER`, `DW_DS_PASSWORD`, `DW_DS_TYPE`）
- Python → 临时 JSON 文件路径，通过 `DW_DATASOURCE_CONFIG` 环境变量注入

**理由**：
- 环境变量是 Shell 脚本获取配置的最自然方式（`mysql -h $DW_DS_HOST`）
- Python 用 JSON 文件而非环境变量，因为：① 环境变量对复杂结构不友好 ② `json.load()` 直接喂给连接库 ③ 文件权限 600 比环境变量更安全（`env` 命令可看到所有环境变量）
- 临时文件执行完即删，路径含 instanceId 防冲突

**替代方案**：Shell 也用 JSON 文件——但 Shell 脚本解析 JSON 需要 `jq`，不如环境变量直接。

### D5: 前端视图——新增 `datasources`，不复用 `integration`

**选择**：新增 `ViewType = "datasources"`，注册到 views.ts + registry.tsx。保留 `integration` 给未来的数据同步/集成。

**理由**：
- DataWorks 中"数据源管理"和"数据集成"是两个独立模块
- 数据源管理是 CRUD 页面（注册/编辑/测试连接）
- 数据集成是同步管道配置页面（源→目标的 ETL 任务）
- 职责不同，混在一起会导致 UI 臃肿

### D6: 前端表单——类型感知动态表单，配置存前端代码

**选择**：每种数据源类型在前端有一份配置（`DATASOURCE_TYPE_CONFIG`），定义字段列表、是否必填、默认值、JDBC URL 拼接模板。新增/编辑对话框根据选中类型动态渲染表单。

**理由**：
- 不同类型的连接参数差异大（MySQL 有 database，S3 有 bucket，MongoDB 有 replicaSet）
- 配置存前端代码（非数据库）：类型元数据变化频率低，不需要动态管理
- `props_json` 的高级字段用折叠面板收起，普通用户不需要关心

**替代方案**：
- 后端下发表单配置（JSON Schema）——灵活但 P0 不需要，增加前后端联调成本
- 所有类型统一表单——无法体现类型差异，用户体验差

### D7: JDBC 驱动依赖策略

**选择**：P0 只在 `datasource_types` 种子数据中注册所有 18 种类型。JDBC 驱动 jar 按需引入：
- 已有：MySQL（mysql-connector-j）、PostgreSQL（postgresql）
- P0 新增：不新增。连通性测试时，如果 driver class 不存在，返回"驱动未安装，请先安装"的友好提示
- 用户通过 `pom.xml` 或 `lib/` 目录按需添加驱动 jar

**理由**：
- Oracle JDBC 驱动有许可证限制，不能直接 Maven 拉取
- DB2、Impala 驱动需要从厂商下载
- 全部引入会让 fat jar 膨胀数百 MB
- 更务实的做法：P0 框架先就位，驱动按需加

**替代方案**：全部驱动都引入——jar 体积爆炸，且部分驱动有许可证问题。

## Risks / Trade-offs

**[风险] 主密钥泄露 → 所有数据源密码泄露**
→ 缓解：主密钥只存环境变量，不入库/不入代码；日志中不打印密码明文；未来可升级到 KMS/Vault per-租户密钥

**[风险] JDBC 驱动缺失导致连通性测试失败**
→ 缓解：测试前先 `Class.forName(driver)` 检测，缺失时返回明确提示而非 ClassNotFoundException 堆栈；文档列出各类型需要的驱动 jar

**[风险] Shell 环境变量注入密码，`env` 命令可见**
→ 缓解：Shell 进程的环境变量只对该进程及其子进程可见；在文档中提示用户注意安全；未来可改为临时文件方案（与 Python 一致）

**[风险] 临时 JSON 文件残留泄露密码**
→ 缓解：try-finally 确保执行完删除；文件权限 600；文件名含 instanceId 防冲突；异常时也要清理

**[权衡] 一表通用模型 vs 类型子表**
→ 选择了通用模型，换取扩展简单性；代价是前端表单需要类型感知配置来弥补弱类型

**[权衡] P0 不引入非 JDBC 驱动**
→ 选择了快速交付，代价是非 JDBC 类型的连通性测试不可用（类型注册、CRUD 不受影响）

**[权衡] Shell 用环境变量、Python 用 JSON 文件**
→ 选择了各取所长（Shell 直接、Python 安全结构化），代价是两种任务类型的注入方式不统一
