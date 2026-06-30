# Contract: 列级血缘解析接口(019 ↔ 018/020 接缝)

> 这是 3 份 spec 的**共享接缝契约**之一。019 定义并实现解析;018 实现 `ColumnLineageCatalog` 数据源、并把 `ColumnEdge` 写入 neo4j;020 读取/展示列级血缘。**任何改动必须同步通知 018/020**。

## 1. 解析主入口

```java
package com.dataweave.master.application;

public final class SqlColumnLineageExtractor {
    /**
     * 从一条 SQL 推出列级派生关系。绝不抛阻断异常:失败按降级阶梯退回。
     * @param sql     任务脚本(单条或多条分号分隔)
     * @param catalog 解析期列元数据来源(由 018 提供);可为空 catalog(则全降级)
     * @return ColumnLineageResult(parsed/edges/degraded)
     */
    public ColumnLineageResult extract(String sql, ColumnLineageCatalog catalog);
}
```

## 2. 输出契约

```java
public record ColumnEdge(
        TableRef srcTable, String srcCol,
        TableRef dstTable, String dstCol,
        Transform transform,      // DIRECT | EXPRESSION | AGGREGATE
        Confidence confidence) {} // CONFIRMED | UNVERIFIED | CONFLICT

public record TableRef(String qualifiedName, String datasourceHint) {}

public record ColumnLineageResult(boolean parsed, java.util.List<ColumnEdge> edges, boolean degraded) {}

public enum Transform { DIRECT, EXPRESSION, AGGREGATE }
public enum Confidence { CONFIRMED, UNVERIFIED, CONFLICT }
```

## 3. catalog 输入契约(018 实现)

```java
public interface ColumnLineageCatalog {
    java.util.Optional<TableSchema> lookupTable(String qualifiedName);
}

public record TableSchema(String qualifiedName, java.util.List<ColumnMeta> columns) {}
public record ColumnMeta(String name, String dataType, int ordinal) {}
```

## 4. 行为契约(MUST)

- **C1 韧性**:`extract` 在任何输入下 MUST NOT 抛出异常;无法解析→`parsed=false`、`edges=[]`。
- **C2 降级**:缺元数据/`*` 不可展开/某列溯源空 → 该列 `confidence=UNVERIFIED`,其余列正常;`degraded=true`。
- **C3 规范化**:`ColumnEdge` 的表/列名规范化 MUST 与 `SqlTableExtractor` 表级一致(同表/列得同标识),保证 018 能把列挂到正确 `:Table`/`:Column`。
- **C4 transform**:聚合列→AGGREGATE;标量表达式列→EXPRESSION;1:1 直引→DIRECT。
- **C5 交叉校验**:与 Agent `.task.yaml` 列级声明冲突的边 MUST 标 `CONFLICT`,不静默丢弃。
- **C6 纯函数**:同 `(sql, catalog)` MUST 得到等价结果(便于纯单测与缓存)。

## 5. 消费方约定

- **018**:实现 `ColumnLineagecatalog`(从 neo4j `:Column` 查),在 `LineageStore.recordTaskIo()` 接收 `List<ColumnEdge>` 写 `(:Column)-[:DERIVES_FROM {taskDefId,transform}]->(:Column)`。
- **020**:读 `DERIVES_FROM` 做列级上下游/影响面查询与前端展示;依赖 `transform`/`confidence` 做可信度呈现。
