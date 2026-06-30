# Phase 1 Data Model: 列级 SQL 血缘解析

> 本特性不持久化。以下是**解析期的内存实体与输出契约**。持久化(写 neo4j `:Column`/`DERIVES_FROM`)由 018 负责。

## 输出实体

### ColumnEdge(核心产物)

一条列级派生关系 `dstTable.dstCol ← srcTable.srcCol`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `srcTable` | `TableRef` | 源表(规范化 qualifiedName + 可选 datasource 坐标) |
| `srcCol` | `String` | 源列名(规范化) |
| `dstTable` | `TableRef` | 目标表 |
| `dstCol` | `String` | 目标列名(规范化) |
| `transform` | `Transform` | DIRECT / EXPRESSION / AGGREGATE |
| `confidence` | `Confidence` | CONFIRMED / UNVERIFIED / CONFLICT |

**校验规则**:四个名字字段非空白(空白边丢弃,沿用表级);`srcCol`/`dstCol` 经统一规范化;同一 `(src,dst)` 列对去重,冲突时 confidence 取更弱(CONFLICT > UNVERIFIED > CONFIRMED 的可见性优先)。

### TableRef

| 字段 | 类型 | 说明 |
|------|------|------|
| `qualifiedName` | `String` | 规范化表名(保留 schema 前缀) |
| `datasourceHint` | `String?` | 可选数据源提示(供 018 关联 `:Datasource`;本特性可留空) |

### 枚举

- **Transform**: `DIRECT`(1:1 直引)/ `EXPRESSION`(标量表达式,可能多源)/ `AGGREGATE`(聚合算子)。
- **Confidence**: `CONFIRMED`(元数据齐全且解析成功,或纯解析可信)/ `UNVERIFIED`(降级推断)/ `CONFLICT`(与 Agent 声明冲突)。

### ColumnLineageResult(extract 返回)

| 字段 | 类型 | 说明 |
|------|------|------|
| `parsed` | `boolean` | 是否进入了列级解析(false=完全降级到表级) |
| `edges` | `List<ColumnEdge>` | 列级派生边(可空列表) |
| `degraded` | `boolean` | 是否发生过降级(部分列 UNVERIFIED 或 `*` 未展开) |

## 解析期输入实体

### ColumnLineageCatalog(接口,由 018 适配实现)

供 Calcite validator 解析列引用、展开 `*`、消歧同名列。

| 方法 | 返回 | 说明 |
|------|------|------|
| `lookupTable(qualifiedName)` | `Optional<TableSchema>` | 查某表的列元数据;缺失→`empty`→触发降级 |

### TableSchema / ColumnMeta

- **TableSchema**: `qualifiedName` + `List<ColumnMeta>`(有序,支持 `*` 按序展开)。
- **ColumnMeta**: `name`(规范化)+ `dataType`(可空)+ `ordinal`。

## 关系与流向

```
SQL(任务脚本) ──parse/validate/relnode──▶ getColumnOrigins
        │                                        │
   ColumnLineageCatalog(018 提供列元数据)         ▼
        └────────────────────────────────▶ List<ColumnEdge> ──▶ 018 LineageStore.recordTaskIo()
                                                                  └─▶ neo4j (:Column)-[:DERIVES_FROM]->(:Column)
```

## 状态转换

无持久状态机。单次 `extract(sql, catalog)` 为纯函数式:同输入→同输出(便于纯单测)。
