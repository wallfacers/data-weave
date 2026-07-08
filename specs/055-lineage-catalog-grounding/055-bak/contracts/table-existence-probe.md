# Contract: 三态表存在性探针

## C1. `DatasourceSchemaResolver.probeTable`（新增方法）

```java
/** 连库探表存在性；区分"连上但无表"(ABSENT) 与 "连不上/超时/非JDBC"(UNKNOWN)。永不抛。 */
TableExistence probeTable(long datasourceId, String qualifiedName);
```

- 复用既有 `fetchColumns` 的连接建立链：`datasourceRepository.findById` → typeCode JDBC 校验 → `datasourceResolver.decryptPassword` → 绑定 jar 隔离加载 / 内置驱动 → `DriverManager`，`connectTimeout`/`loginTimeout` = 3s。
- 用 `parseQualifiedName(qn, conn)` 规范化为 catalog/schema/table 三段（与 053 同源）。
- 用 `DatabaseMetaData.getTables(catalog, schema, table, null)`：
  - 结果集**有行** → `PRESENT`。
  - 结果集**无行**（连接成功、权威确认） → `ABSENT`。
- 任一失败（datasource 不存在/非 JDBC/解密失败/连接超时/驱动缺失/SQLException/规范化异常） → `UNKNOWN`（记 debug 日志，绝不抛）。
- 仅只读元数据（`getTables`），绝不执行数据查询。
- 大小写：`getTables` 的 table/schema 匹配按驱动默认（不强制大写），H2 下遵循 H2 大小写语义。

**测试锚点**：H2 建 `dw.orders` → PRESENT；查 `dw.tmp_stage`(不存在) → ABSENT；坏 jdbcUrl/不可达端口 → UNKNOWN；非 JDBC typeCode → UNKNOWN。

## C2. `DatasourceBoundCatalog.probeExistence`（新增方法）

```java
/** 组合链三态存在性：cache/neo4j 命中即 PRESENT；miss 且绑定→live probe；未绑定→UNKNOWN。 */
TableExistence probeExistence(long tenantId, long projectId, String qualifiedName);
```

解析顺序（`datasourceId` 由构造闭包持有）：
1. 进程内 TTL 缓存命中 → `PRESENT`（已知列 schema 即证存在）。
2. `neo4jCatalog.lookupTable` 命中 → 回填缓存 → `PRESENT`。
3. `datasourceId != null` → `schemaResolver.probeTable(datasourceId, qn)` 直接返回其三态；命中 PRESENT 时顺带 `fetchColumns` 回填 neo4j/缓存（复用既有回填路径，失败不阻断）。
4. `datasourceId == null` → `UNKNOWN`（未绑定，不可判定）。

- **不缓存 ABSENT/UNKNOWN**（保证删表/建表下次 push 翻转结论，D7）。
- 永不抛：任一步异常 → 降级下一层，最终 `UNKNOWN`。

**测试锚点**：neo4j 预置列目录命中表 → PRESENT（不连库）；未绑定数据源 → UNKNOWN；绑定但表不存在 → ABSENT（走 live probe）。

## C3. `TableExistence`（新增枚举）

```java
public enum TableExistence { PRESENT, ABSENT, UNKNOWN }
```

## 不变量

- ABSENT 仅在"数据源可达 + 成功查询 + 权威确认缺席"时产生；任何不确定一律 UNKNOWN（SC-003 误杀率 0 的地基）。
- probe 全程零数据查询、零异常逃逸、零对同步 push 路径的调用（仅异步富化线程调用）。
