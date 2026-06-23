# datasource-driver-jars Specification

## Purpose
TBD - created by archiving change datasource-driver-isolation. Update Purpose after archive.
## Requirements
### Requirement: 驱动 jar 上传与校验
系统 SHALL 提供上传端点（WebFlux multipart，接受 `FilePart`）上传 JDBC 驱动 jar。上传 MUST 校验：① 文件以 `.jar` 结尾；② 计算内容 `sha256` 并据此去重（已存在同 sha256 资产则复用，不重复存储）；③ 解析 jar 内 `META-INF/services/java.sql.Driver`，确认含至少一个 `java.sql.Driver` 实现，否则拒绝。上传 MUST 记录原始文件名、适用 `type_code`、`sha256`、存储位置（`storage_type`）、大小、上传人。

#### Scenario: 上传有效驱动 jar
- **WHEN** 客户端上传 `mysql-connector-java-5.1.49.jar` 并指定 `type_code=MYSQL`
- **THEN** 创建 driver_jars 资产（含 sha256、storage_type、status=PENDING_APPROVAL），返回资产 id 与 sha256

#### Scenario: sha256 去重复用
- **WHEN** 上传内容 sha256 与已存在资产相同的 jar
- **THEN** 不重复存储，复用既有 driver_jars 记录，返回同一资产 id

#### Scenario: 非 jar 文件被拒
- **WHEN** 上传 `driver.zip`
- **THEN** 返回 400，提示仅接受 `.jar`

#### Scenario: 非 JDBC 驱动 jar 被拒
- **WHEN** 上传不含 `META-INF/services/java.sql.Driver` 的普通 jar
- **THEN** 返回 400，提示未检测到 JDBC 驱动实现

### Requirement: 驱动 jar 上传安全门与审计
上传与替换驱动 jar 属任意代码加载（RCE 风险），MUST 经 `GatedActionService` → `PolicyEngine`。无显式 `policy_rules` 时默认 L2，返回 `PENDING_APPROVAL`，资产在审批通过前 `status` 不可用（不能被数据源绑定生效）。每次上传/审批 MUST 落 `agent_action` 审计。

#### Scenario: 首次上传触发审批
- **WHEN** 用户上传一个新 jar 且无对应 policy_rules
- **THEN** 返回 `PENDING_APPROVAL`，资产 `status=PENDING`，不可被数据源绑定生效

#### Scenario: 审批通过后可用
- **WHEN** `PENDING` 的 driver_jars 资产被审批通过
- **THEN** `status` 转为 `ACTIVE`，可被数据源绑定生效

### Requirement: 驱动 jar 存储双后端与降级
系统 SHALL 将上传 jar 存 MinIO；当 MinIO 未配置（all-in-one / H2 零依赖模式）时 SHALL 降级到本地目录存储。`storage_type` 字段记录实际后端。降级 MUST 保证「克隆即跑、CI 零外部依赖」底线不被破坏。

#### Scenario: distributed 模式存 MinIO
- **WHEN** 在配置了 MinIO 的环境上传 jar
- **THEN** jar 存入 MinIO，`storage_type=MINIO`

#### Scenario: 零依赖模式降级本地
- **WHEN** 在未配置 MinIO 的 H2 模式上传 jar
- **THEN** jar 存入本地 libs/jdbc 目录，`storage_type=LOCAL`，功能可用

### Requirement: 驱动 jar 隔离类加载
系统 SHALL 按 jar 的 sha256 缓存一个 `URLClassLoader` 与 `Driver` 实例供复用；同一 sha256 被多数据源引用时共享同一 ClassLoader。隔离加载时 MUST 直接调用 `Driver.connect(url, props)`，MUST NOT 经 `DriverManager.getConnection`（其 ClassLoader 校验会拒绝非系统加载器加载的驱动）。加载前 SHALL 将 jar 复制为临时副本以避免原文件锁。

#### Scenario: 多版本驱动共存
- **WHEN** 数据源 A 绑定 ojdbc6、数据源 B 绑定 ojdbc11，同时执行连通性测试
- **THEN** 两者各自用独立 ClassLoader 加载，互不冲突，均能成功建立连接

#### Scenario: 同 sha256 复用 ClassLoader
- **WHEN** 多个数据源绑定同一份上传 jar
- **THEN** 共用同一个 `URLClassLoader` 与 `Driver` 实例

### Requirement: 驱动 jar 查询与删除
系统 SHALL 提供按 `type_code` 列出可用资产的查询端点与 `DELETE /api/driver-jars/{id}` 端点。删除被数据源引用的资产 MUST 拒绝并提示引用数（不静默破坏绑定）。删除 MUST 经安全门。

#### Scenario: 按类型列出资产
- **WHEN** 客户端请求某 `type_code` 的驱动 jar 列表
- **THEN** 返回该类型下所有 `ACTIVE` 资产（含原始文件名、sha256、上传人、时间）

#### Scenario: 删除被引用的资产被拒
- **WHEN** 删除一个已被数据源绑定的 driver_jars 资产
- **THEN** 返回 409，提示「N 个数据源仍引用，请先解绑」

