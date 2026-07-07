# Contract: DatasourceSchemaResolver + DatasourceBoundCatalog

## C1. DatasourceSchemaResolver（实时抓取）

```java
class DatasourceSchemaResolver {
    /** 连库取表列；失败/超限返回 empty（永不抛）。 */
    Optional<TableSchema> fetchColumns(long datasourceId, String qualifiedName);
}
```

- 复用 `JdbcConnectionTester` 连接建立 + `IsolatedDriverLoader` 驱动隔离 + `DatasourceResolver.decryptPassword`。
- 用 `DatabaseMetaData.getColumns(catalog, schema, table, null)` 取列名/序号(`ORDINAL_POSITION`)/类型(`TYPE_NAME`)；视图经同一 API 解析（metadata 视表/视图等同）。
- **限定名规范化（FR-015）**：裸表名按 `Connection.getSchema()`/`getCatalog()` 补全。
- **上限保护（FR-014）**：列数 > `max_columns` → 截断/降级 + hint；连接/查询超时（默认 3s）→ empty。
- 仅只读元数据；绝不执行数据查询。

## C2. DatasourceBoundCatalog（组合链，implements ColumnLineageCatalog）

```java
Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName)
```

解析顺序（datasourceId 由构造闭包持有，接口签名不变）：
1. 进程内 TTL 缓存命中 → 返回。
2. `Neo4jColumnLineageCatalog`（既有持久列目录）命中 → 回填缓存 → 返回。
3. `DatasourceSchemaResolver.fetchColumns(datasourceId, qn)` 命中 → 回填 neo4j（`Neo4jColumnBackfillWriter`）+ 缓存 → 返回。
4. 全 miss → `Optional.empty()`（触发既有列级降级，绝不抛）。

未绑定数据源（datasourceId=null）→ 跳过步骤 3，退化为纯 neo4j catalog（FR-013 场景 6）。

## C3. 新鲜度（FR-018 / 澄清 Q4）

- push 时 evict 该任务候选表（reads+writes）缓存条目后再解析（重 push 失效）。
- 每条目 TTL 兜底（默认 6h，可配）。
- neo4j 列目录随步骤 3 回填刷新 `dataType/ordinal`（结构变更覆盖）。

## C4. 装配接缝

`TaskService.recordLineage` / `ProjectSyncService` 在解析 SQL 列级血缘前，用任务 `datasource_id`（读侧）与 `target_datasource_id`（写侧）构造 `DatasourceBoundCatalog`，替代当前直接注入的 `columnLineageCatalog`（neo4j-only）。`SqlColumnLineageExtractor.extract(sql, boundCatalog, ...)` 无感获益，Calcite `SELECT *` 展开自动用上真实列。
