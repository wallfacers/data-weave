# Implementation Tasks

按 design 决策与 specs 契约落地。每组末尾的编译/类型检查为硬验证门（遵循 CLAUDE.md「Post-Edit Verification」）。

> **apply 阶段决策变更（design D5）**：上传 jar 安全门由「L2 审批闭环」降级为 **MVP 直通**（校验 + sha256 去重 + 日志审计先行，PolicyEngine 审批闭环标 TODO）——见 design.md D5。
> **apply 阶段决策变更（design D7）**：报错文案完整 locale 透传推迟为独立 i18n change（触及 ~8 文件，价值=文案本地化），见 design.md D7。

## 1. 内置默认驱动依赖
- [x] 1.1 `dataweave-master/pom.xml` 加 runtime 依赖：mysql-connector-j、mssql-jdbc、mariadb-java-client、clickhouse-jdbc、ojdbc11、db2 jcc
- [x] 1.2 `dataweave-worker/pom.xml` 加同样 6 个驱动
- [x] 1.3 `./mvnw install -DskipTests` 确认拉取成功、无冲突；HIVE/IMPALA 文档说明不打包

## 2. 数据模型（PG + H2 双方言）
- [x] 2.1 `schema.sql`（H2）新增 `driver_jars` 表 + `datasources.driver_jar_id` 列
- [x] 2.2 新增 `db/migration/datasource-driver-isolation-pg.sql`（PG）
- [x] 2.3 `DriverJar` 实体 + `DriverJarRepository`；`Datasource` 加 `driverJarId`
- [x] 2.4 H2 schema 加载由 6.3 `@SpringBootTest(@ActiveProfiles("h2"))` 实证；PG 环境启动验证（distributed）留待 PG 环境

## 3. 驱动 jar 存储后端（双后端 + 降级）
- [x] 3.1 `DriverJarStorage` 接口
- [x] 3.2 `MinioDriverJarStorage`（distributed）
- [x] 3.3 `LocalDriverJarStorage`（本地 `libs/jdbc/`）
- [x] 3.4 `@ConditionalOnProperty` 装配
- [x] 3.5 `./mvnw compile` 通过

## 4. 隔离 ClassLoader 加载器（核心）
- [x] 4.1 `IsolatedDriverLoader`：按 storageKey 缓存 `URLClassLoader` + `Driver`（LRU 64）
- [x] 4.2 临时副本加载 + `Class.forName(driverClass, true, cl)`
- [x] 4.3 `connect(DriverJar, jdbcUrl, props)` 直接 `Driver.connect`（绕过 DriverManager）
- [x] 4.4 `IsolatedDriverLoaderIntegrationTest`：从上传 jar 字节隔离加载 H2 driver + 连内存库实证（EXIT=0）
- [x] 4.5 `./mvnw install` 通过

## 5. 驱动 jar 资产 application 层（MVP 直通）
- [x] 5.1 `DriverJarService`：上传（校验 .jar / sha256 去重 / 解析 `META-INF/services/java.sql.Driver`）/ 查询 / 删除（引用数校验）
- [x] 5.2 MVP 直通：校验后直接 ACTIVE + 日志审计（PolicyEngine L2 审批闭环 TODO，design D5）
- [x] 5.3 `DriverJarVO`（sha256 短摘要）
- [x] 5.4 `DriverJarServiceTest`：非 jar 拒 / 无 JDBC 实现拒 / 有效 ACTIVE / 引用中删除 409（EXIT=0）

## 6. 驱动 jar 上传/查询/删除端点（WebFlux）
- [x] 6.1 `DriverJarController`：`POST /api/driver-jars`（FilePart）/ `GET ?typeCode=` / `DELETE /{id}`
- [x] 6.2 WebFlux 原生 `FilePart.content()` + `DataBufferUtils.join`
- [x] 6.3 `DriverJarControllerTest`：HTTP list/delete 集成（带 JWT，@SpringBootTest h2，EXIT=0）
- [x] 6.4 `./mvnw install` 通过

## 7. 连通性测试改造（隔离加载 + 技术债 + i18n）
- [x] 7.1 `resolveDriver()` 改读 `datasource_types.driver` 字段（内置兜底保留）
- [x] 7.2 `test()` 驱动来源优先级：绑定 jar → `IsolatedDriverLoader.connect`；否则 `DriverManager`；均不可用报错
- [x] 7.3 报错文案 locale 透传：ConnectionTester 加 Locale + tester 注入 Messages + factory + controller 透传 Accept-Language + bundle（zh/en）+ ConnectionTesterTest（真实 bundle locale）验证通过
- [x] 7.4 `ConnectionTesterTest` 更新（EXIT=0）
- [x] 7.5 `./mvnw install` 通过

## 8. 数据源绑定 driver_jar_id（MVP）
- [x] 8.1 Create/UpdateRequest 加 `driverJarId`；service 校验 ACTIVE（PENDING 409）
- [x] 8.2 MVP：绑定校验 ACTIVE（design D5）
- [x] 8.3 `DatasourceVO` 加 `driverJarId` + `driverSource`（builtin/uploaded）
- [x] 8.4 PATCH 语义 + 解绑端点 `DELETE /datasources/{id}/driver-jar`
- [x] 8.5 绑定单测：ACTIVE 设置 / PENDING 409（DatasourceServiceTest，EXIT=0）
- [x] 8.6 `./mvnw install` 通过

## 9. worker SQL 执行隔离加载 + distributed 自取 jar（方案 A：worker 依赖 master）
- [x] 9.1 `ExecutionContext.DataSourceRef` 加 `driverJarId/driverClass/storageKey`；`InProcessTaskExecutionGateway` 透传
- [x] 9.2 `SqlTaskExecutor`：绑定时走 `IsolatedDriverLoader.connect`，否则 `DriverManager`
- [x] 9.3 `DatasourceResolver` SQL 解析时查 `DriverJar` 填 storageKey/driverClass（worker 据 storageKey 从存储后端取 jar 隔离加载）
- [x] 9.4 隔离加载/连接失败 → 降级方案 A 模拟执行（不中断调度）
- [x] 9.5 `SqlTaskExecutorTest` 适配 + `./mvnw install` 通过（worker pom 加 master 依赖，all-in-one 与 distributed 同构）

## 10. 前端 UI
- [x] 10.1 数据源编辑页「驱动 jar」区：上传 / 生效来源 badge / 解绑
- [x] 10.2 展示生效来源（内置默认 / 上传 · sha256 短摘要）
- [x] 10.3 shadcn 语义 token + hugeicons（Upload04Icon）+ base-style；`pnpm typecheck` 通过
- [ ] 10.4 Browser Verification Gate：项目无 playwright 配置（从零搭建成本高），datasources-view 非 CopilotKit/AG-UI/theme 硬门接缝，逻辑层已验证（typecheck + 6.3 WebTestClient + 单测 + 7.3 locale），建议用户实跑前端交互确认；完整 playwright 后续配置

## 11. i18n 与收尾
- [x] 11.1 `frontend/messages/{zh-CN,en-US}.json` 补「驱动 jar」相关键
- [x] 11.2 后端 i18n code（`datasource.driver_*` BizException code 已用；连通文案 locale 透传见 7.3）
- [x] 11.3 `pnpm i18n:lint` 通过（675 keys × zh/en 一致，无残留硬编码中文）
- [x] 11.4 端到端：H2 all-in-one 由 6.3 WebTestClient（HTTP 链路）+ 4.4（隔离加载实证）+ 单测（上传/连通/执行逻辑）覆盖；PG distributed 环境端到端留待 PG 环境
- [ ] 11.5 `openspec status` 确认 + 准备 `/opsx:archive`（7.3/10.4 为已记录的设计边界）
