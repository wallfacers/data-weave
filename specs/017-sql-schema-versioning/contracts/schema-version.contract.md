# Schema 契约：schema_version 与版本戳规则

**Feature**: 017 | 守护测试：`backend/dataweave-api/src/test/java/com/dataweave/api/schema/SchemaVersionIT.java`

本契约把 spec 的版本/收口/清理要求固化为可执行断言。任一条被破坏即视为回归。

---

## C1. 库内可查唯一版本（FR-006 / SC-003）

- **Given** 后端以默认 schema 初始化数据库
- **When** 执行 `SELECT version, applied_at FROM schema_version`
- **Then** 恰好返回 **1 行**；`version` 匹配 `^\d+\.\d+\.\d+$`（合法 SemVer）；本基线 `version = '0.0.1'`；`applied_at` 非空。

```
SELECT count(*) FROM schema_version        -- = 1
SELECT version FROM schema_version          -- 匹配 SemVer，= '0.0.1'
```

## C2. 三处同源恒等（FR-005 / R3）

- 库内 `schema_version.version` == `schema.sql` 头部注释声明的 `Schema Version` == 当前项目发布版本（`0.0.1`）。
- 守护：测试断言库内值等于基线常量 `0.0.1`（= 项目版本）。
- 可选增强（R3）：若启用 build-info，断言库内值 == `BuildProperties.getVersion()` 去 `-SNAPSHOT` 后的发布号，实现 pom↔schema 自动等值。

## C3. 启动建库成功 + 双库兼容（FR-012 / SC-002 / SC-007）

- **Given** 空 H2 库（`MODE=PostgreSQL`，测试隔离不变量）
- **When** `@SpringBootTest` 上下文启动
- **Then** 上下文成功加载（即权威 schema 全部表/索引/约束在 H2 建成）；应用健康。
- PostgreSQL 侧由 quickstart 手工 `spring-boot:run` 验证（CI 默认 H2）。

## C4. 无孤立增量脚本（FR-002 / SC-001 / SC-005）

- **Then** classpath/资源目录下 **不存在** `db/migration/` 目录（防回退重现死目录）。
- 守护：测试断言 `db/migration` 资源不可定位（或文件系统路径不存在）。

## C5. AI 拆除残留已清（FR-008 / 边界回归）

- **Then** `schema.sql` 文本中不含 `task_diagnosis`、`finding` 表定义；启用 demo profile（若保留）时启动不报「缺表」。
- 守护：测试读取 schema.sql 内容，断言不含已移除表名；若保留 demo-data.sql，则其 `INSERT` 目标表在 schema 中均存在。

## C6. 无 schema 漂移（FR-013 / SC-004）

- **Then** 从零启动后实际建出的表集合 ⊇ 应用各 Repository/实体所需的表；上下文与既有 api 测试套件全绿即代表结构与代码一致、无漂移。
- 守护：复用既有 api 集成测试套件作为「结构满足代码」的回归基线（不新增重复断言）。

---

### 退出标准

C1–C6 全绿 + 既有 api 测试套件保持全绿（无 masked-red，遵守 backend 测试隔离不变量）= 本特性结构契约满足。
