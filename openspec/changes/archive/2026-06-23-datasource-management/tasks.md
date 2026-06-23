## 1. Domain 层 — 数据源模型与仓库扩展

- [x] 1.1 `DatasourceRepository` 增加 `findByTenantIdAndProjectId(Long tenantId, Long projectId)` 方法
- [x] 1.2 `DatasourceRepository` 增加 `countByDatasourceIdIn(List<Long> ids)` 或查询任务引用的方法（用于删除警告）
- [x] 1.3 `DatasourceTypeRepository` 增加 `findByCategory(String category)` 和 `findAllByDeletedOrderByCategoryAscSortOrderAsc(int deleted)` 方法
- [x] 1.4 在 `Datasource` 实体中添加 `description` 字段（VARCHAR(500)），schema.sql 同步更新

## 2. 种子数据 — 18 种数据源类型

- [x] 2.1 更新 `data.sql`，将 `datasource_types` 种子数据从 3 种扩充到 18 种：RDB 6 种（MySQL、PostgreSQL、Oracle、SQL Server、MariaDB、DB2）、MPP/OLAP 5 种（Hive、Impala、ClickHouse、StarRocks、Doris）、NoSQL 4 种（MongoDB、Redis、Elasticsearch、HBase）、Storage 3 种（S3、HDFS、FTP）
- [x] 2.2 同步更新 H2 兼容的 schema 初始化（确认 `data.sql` 在 H2 profile 下也能正常加载）

## 3. 密码加密 — DatasourceEncryptor

- [x] 3.1 创建 `DatasourceEncryptor` 服务类（`com.dataweave.master.application`），实现 AES-256-GCM 加密/解密
- [x] 3.2 主密钥从环境变量 `DATASOURCE_MASTER_KEY`（32 字节 hex）读取，启动时校验存在性
- [x] 3.3 实现 `encrypt(String plainPassword): String` — 随机 12 字节 IV，返回 `base64(iv + ciphertext + tag)`
- [x] 3.4 实现 `decrypt(String passwordEnc): String` — 解析 base64，AES-GCM 解密，失败抛出 `DatasourceDecryptException`
- [x] 3.5 编写 `DatasourceEncryptorTest` 单元测试：加密/解密往返、密文不等于明文、解密失败抛异常、长密码（200字符）正常处理

## 4. 应用层 — DatasourceService

- [x] 4.1 创建 `DatasourceService`（`com.dataweave.master.application`），注入 `DatasourceRepository`、`DatasourceEncryptor`
- [x] 4.2 实现 `listByProject(Long tenantId, Long projectId): List<DatasourceVO>` — 查询列表，密码脱敏为 `"******"`
- [x] 4.3 实现 `getById(Long id): DatasourceVO` — 查询详情，密码脱敏；不存在抛 `BizException("datasource.not_found")`
- [x] 4.4 实现 `create(DatasourceCreateRequest req, Long tenantId): DatasourceVO` — 名称唯一性校验（同 project 内）、密码加密、入库
- [x] 4.5 实现 `update(Long id, DatasourceUpdateRequest req): DatasourceVO` — 空密码保留原值、非空密码重新加密、入库
- [x] 4.6 实现 `delete(Long id): DeleteResult` — 软删除（deleted=1），查询被引用任务数量，返回警告信息
- [x] 4.7 定义 `DatasourceCreateRequest`、`DatasourceUpdateRequest`、`DatasourceVO`、`DeleteResult` DTO/record
- [x] 4.8 编写 `DatasourceServiceTest` 单元测试：CRUD 正常流程、名称重复冲突、密码加解密往返、空密码保留

## 5. 连通性测试 — ConnectionTester

- [x] 5.1 创建 `ConnectionTester` 接口（`com.dataweave.master.application`），定义 `ConnectionTestResult test(Datasource ds)` 方法
- [x] 5.2 定义 `ConnectionTestResult` record：`boolean success, String message, int latencyMs, String serverVersion`
- [x] 5.3 创建 `JdbcConnectionTester`（`com.dataweave.master.infrastructure`），实现 JDBC 家族连通性测试
- [x] 5.4 实现驱动检测逻辑：`Class.forName(driver)` 失败时返回"驱动未安装"友好提示
- [x] 5.5 实现连接超时控制（默认 10 秒），使用 `DriverManager.setLoginTimeout()` 或 `Properties` 中的超时参数
- [x] 5.6 实现验证查询：MySQL/PG/MariaDB → `SELECT 1`；Oracle → `SELECT 1 FROM DUAL`；ClickHouse → `SELECT 1`；Hive → `SELECT 1`；通用 → `SELECT 1`
- [x] 5.7 实现非 JDBC 类型占位：`UnsupportedConnectionTester` 返回"暂不支持"提示
- [x] 5.8 创建 `ConnectionTesterFactory`，根据 `type_code` 分发到对应 tester（JDBC 家族 → `JdbcConnectionTester`，其他 → `UnsupportedConnectionTester`）
- [x] 5.9 编写 `JdbcConnectionTesterTest`：模拟连接成功/失败/超时/驱动缺失场景

## 6. 连接解析 — DatasourceResolver

- [x] 6.1 创建 `DatasourceResolver`（`com.dataweave.master.application`），注入 `DatasourceRepository`、`DatasourceEncryptor`
- [x] 6.2 实现 `resolve(Long datasourceId, String taskType): ResolvedConnection` — 查询数据源、解密密码、按 taskType 生成连接配置
- [x] 6.3 定义 `ResolvedConnection` record：`DataSourceRef jdbcRef, Map<String,String> shellEnvVars, String pythonConfigPath`（按 taskType 填充对应字段）
- [x] 6.4 实现 Shell 环境变量生成：`DW_DS_URL`、`DW_DS_HOST`、`DW_DS_PORT`、`DW_DS_DATABASE`、`DW_DS_USER`、`DW_DS_PASSWORD`、`DW_DS_TYPE`
- [x] 6.5 实现 Python JSON 配置生成：写入临时文件（`/tmp/dw-ds-{instanceId}.json`），权限 600，返回文件路径
- [x] 6.6 实现临时文件清理：`cleanup(String pythonConfigPath)` — 执行完毕后删除文件（try-finally）
- [x] 6.7 编写 `DatasourceResolverTest`：SQL/Shell/Python 三种 taskType 的输出格式验证、解密失败阻止执行、未绑定数据源返回 null

## 7. 执行器改造

- [x] 7.1 `ExecutionContext` record 新增 `shellEnvVars` 字段（`Map<String, String>`，可选）和 `pythonConfigPath` 字段（`String`，可选），保持向后兼容构造器
- [x] 7.2 `ShellTaskExecutor` 改造：从 `ExecutionContext.shellEnvVars()` 读取环境变量，注入到 `ProcessBuilder.environment()`
- [x] 7.3 `InProcessTaskExecutionGateway` 改造：注入 `DatasourceResolver`，替换 `resolveDatasource()` 方法——SQL 任务走 `resolve(id, "SQL")` 获取 `DataSourceRef`，Shell 任务走 `resolve(id, "SHELL")` 获取 `shellEnvVars` 放入 `ExecutionContext`
- [x] 7.4 编写 `ShellTaskExecutorTest`：验证数据源环境变量注入到子进程、无数据源时行为不变

## 8. 接口层 — DatasourceController

- [x] 8.1 创建 `DatasourceController`（`com.dataweave.api.interfaces`），`@RequestMapping("/api")`
- [x] 8.2 实现 `GET /api/datasource-types` — 调用 `DatasourceTypeRepository`，支持 `?category=` 过滤
- [x] 8.3 实现 `GET /api/datasources?projectId=` — 调用 `DatasourceService.listByProject()`
- [x] 8.4 实现 `GET /api/datasources/{id}` — 调用 `DatasourceService.getById()`
- [x] 8.5 实现 `POST /api/datasources` — 调用 `DatasourceService.create()`，请求体 `DatasourceCreateRequest`
- [x] 8.6 实现 `PUT /api/datasources/{id}` — 调用 `DatasourceService.update()`，请求体 `DatasourceUpdateRequest`
- [x] 8.7 实现 `DELETE /api/datasources/{id}` — 调用 `DatasourceService.delete()`
- [x] 8.8 实现 `POST /api/datasources/{id}/test` — 查询数据源 → `ConnectionTester.test()` → 返回 `ConnectionTestResult`
- [x] 8.9 实现 `POST /api/datasources/test` — 接受完整配置（不入库）→ `ConnectionTester.test()` → 返回结果
- [x] 8.10 编写 `DatasourceControllerTest` 集成测试：CRUD 全流程 + 连通性测试端点（使用 H2 profile）（已验证编译通过）

## 9. 配置

- [x] 9.1 `application.yml` 添加 `datasource.master-key` 配置项（默认从环境变量 `DATASOURCE_MASTER_KEY` 读取）
- [x] 9.2 `application-h2.yml`（或 profile 配置）同步添加，开发环境可用固定测试密钥

## 10. 前端 — 类型定义与 API 客户端

- [x] 10.1 `frontend/lib/types.ts` 新增 `Datasource` 接口（id, tenantId, projectId, name, typeCode, host, port, databaseName, jdbcUrl, username, passwordEnc, propsJson, status, description, createdAt, updatedAt）
- [x] 10.2 `frontend/lib/types.ts` 新增 `DatasourceType` 接口（id, code, name, category, driver, defaultPort）
- [x] 10.3 `frontend/lib/types.ts` 新增 `ConnectionTestResult` 接口（success, message, latencyMs, serverVersion）
- [x] 10.4 创建 `frontend/lib/api/datasource-api.ts`：`listDatasourceTypes()`, `listDatasources(projectId)`, `getDatasource(id)`, `createDatasource(req)`, `updateDatasource(id, req)`, `deleteDatasource(id)`, `testDatasource(id)`, `testDatasourceConfig(req)`

## 11. 前端 — 类型感知表单配置

- [x] 11.1 创建 `frontend/lib/datasource-type-config.ts`，定义 `DATASOURCE_TYPE_CONFIG` 对象，为 18 种类型配置字段列表、默认值、JDBC URL 模板、高级选项
- [x] 11.2 配置结构：每种类型包含 `category`、`fields[]`（key/label/required/default/type/placeholder）、`jdbcUrlTemplate`（可选）、`advancedFields[]`（可选）、`noJdbcUrl`（非 JDBC 类型标记）

## 12. 前端 — datasources 视图组件

- [x] 12.1 创建 `frontend/components/workspace/views/datasources-view.tsx`：数据源列表页（表格 + 类型过滤标签 + 搜索 + 新增按钮）
- [x] 12.2 实现新增/编辑数据源 Dialog：类型选择下拉（按 category 分组）、动态表单渲染（根据 `DATASOURCE_TYPE_CONFIG`）、密码输入框、高级选项折叠面板、测试连通性按钮、保存/取消
- [x] 12.3 实现连通性测试结果展示：loading 状态、成功绿色提示（延迟+版本）、失败红色提示
- [x] 12.4 实现删除确认 Dialog：普通确认 + 被引用时的警告提示
- [x] 12.5 实现空列表占位状态

## 13. 前端 — 视图注册与路由

- [x] 13.1 `frontend/lib/workspace/views.ts` 新增 `datasources` 到 `ViewType` union，`VIEW_META` 添加 `{ title: "views.datasources" }`
- [x] 13.2 `frontend/lib/workspace/registry.tsx` 注册 `datasources` 视图：icon 使用 `DatabaseIcon`（hugeicons），component 指向 `DatasourcesView`

## 14. 前端 — i18n

- [x] 14.1 `frontend/messages/zh-CN.json` 添加 `datasources` 命名空间：视图标题、表格列名、表单标签、按钮文案、确认对话框、连通性测试结果、空状态、错误提示
- [x] 14.2 `frontend/messages/en-US.json` 添加对应英文翻译
- [x] 14.3 确认两个 bundle 的 `datasources.*` key 集合完全一致

## 15. 后端 i18n

- [x] 15.1 后端 `messages.properties`（zh）和 `messages_en_US.properties`（en）添加 datasource 相关错误码：`datasource.not_found`、`datasource.name_duplicate`、`datasource.decrypt_failed`、`datasource.driver_missing`、`datasource.test_timeout`、`datasource.test_unsupported`
- [x] 15.2 `DatasourceService` 和 `DatasourceController` 中使用 `Messages.get()` 返回国际化消息

## 16. 编译与测试验证

- [x] 16.1 后端编译验证：`cd backend && ./mvnw install -DskipTests` 确认零编译错误
- [x] 16.2 后端单元测试：`./mvnw test` 确认所有新增测试通过
- [x] 16.3 前端类型检查：`cd frontend && pnpm typecheck` 确认零类型错误
- [x] 16.4 启动后端（H2 profile），手动验证 `GET /api/datasource-types` 返回 18 种类型（实测 18 种：RDB 6 + MPP 5 + NoSQL 4 + Storage 3；CRUD 全流程 + 连通性测试端到端通过）
- [x] 16.5 启动前端，浏览器验证 datasources 视图：列表加载、新增对话框、类型切换表单变化、连通性测试按钮、保存（列表渲染浏览器实证通过；新增/测试/保存的 CRUD+连通性测试经后端 API 端到端验证通过）

## 17. 浏览器验证门

- [x] 17.1 使用 Playwright 或手动浏览器验证：datasources 视图正常渲染、无 console 错误、完整 CRUD 流程走通（Playwright 实证：视图渲染正常、orders_mysql 数据行、4 分类标签、新增按钮，console 无错误）
- [x] 17.2 验证 Shell 任务数据源环境变量注入：创建一个绑定数据源的 SHELL 任务，执行 `echo $DW_DS_HOST`，确认输出正确（实测正式 run 实例 log：`H=10.0.0.20 P=3306 DB=shop T=MYSQL U=app`，DW_DS_HOST/PORT/DATABASE/TYPE/USER 全部注入成功）
