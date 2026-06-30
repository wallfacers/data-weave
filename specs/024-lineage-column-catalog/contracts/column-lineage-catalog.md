# Contract: ColumnLineageCatalog 接口

**Feature**: 024-lineage-column-catalog

## 签名变更（内部接口，破坏性）

```java
// 既有（019 假设存在但缺实现）
Optional<TableSchema> lookupTable(String qualifiedName);

// 本期改为（research.md R3：显式 tenant 隔离，不用 TenantContext）
Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName);
```
- **返回**：表存在 → `Optional.of(TableSchema(qualifiedName, List<ColumnMeta>))`（按 ordinal 有序）；不存在或查询失败 → `Optional.empty()`。
- **不变量**：永不抛异常（查询失败 → empty）。下游 `SqlColumnLineageExtractor` 依赖此契约做降级（C1）。

## 实现与装配（research.md R5）

| 实现 | 装配 | 行为 |
|---|---|---|
| `Neo4jColumnLineageCatalog`（新增） | `@ConditionalOnProperty(name="lineage.column-catalog.type", havingValue="neo4j")` | Cypher 查 `(:Table{tenantId,projectId,qualifiedName})-[:HAS_COLUMN]->(:Column)` 有序回组；neo4j 不可达 → try-catch → empty |
| `EmptyColumnLineageCatalog`（既有） | `@ConditionalOnProperty(name="lineage.column-catalog.type", havingValue="empty", matchIfMissing=true)` | 恒 empty（H2/默认 fallback） |

不用裸 `@Primary`（会让两 Bean 在 H2 同时注册且查询必失败）；H2 默认走 Empty 零风险。

## Confidence 枚举（落 `:DERIVES_FROM.confidence`）

`{CONFIRMED, UNVERIFIED, CONFLICT, DECLARED}`（DECLARED 为 024 新增，其余 019 FR-004 既有，不重定义）。

对账（D=声明边集，R=SQL 推导边集）：D∩R=CONFIRMED；D\R=DECLARED；R\D=沿用 019；映射矛盾=CONFLICT。
