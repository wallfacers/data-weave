# Quickstart: 声明驱动的列血缘 Catalog 验证

**Feature**: 024-lineage-column-catalog

可运行的端到端验证场景。前置：neo4j（`docker compose up -d`）+ `lineage.column-catalog.type=neo4j`；集成测试用 testcontainers-neo4j。详见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)。

## 场景 1 — 破循环（US1）

1. 写 `.task.yaml`（声明 schema）：
   ```yaml
   script: etl.sql
   schema:
     orders: [{name: order_id, type: BIGINT}, {name: amount, type: DECIMAL(18,2)}]
     orders_clean: [{name: total_amount, type: DECIMAL(18,2)}]
   ```
   `etl.sql`：`INSERT INTO orders_clean(total_amount) SELECT amount FROM orders`
2. `dw push`（或集成测试直接调 `ProjectSyncService.push`）。
3. 断言：
   - neo4j 出现 `(:Table{name:orders})-[:HAS_COLUMN]->(:Column{name:amount,dataType:DECIMAL(18,2),ordinal:1})`。
   - 出现 `:DERIVES_FROM` 边 `orders.amount → orders_clean.total_amount`，confidence=CONFIRMED。
4. 对照：删掉 `schema` 块重 push → 列边为空/UNVERIFIED（现状表级）。

## 场景 2 — cross-check（US2）

1. 场景 1 基础上加 `columnLineage: [{from: orders.amount, to: orders_clean.total_amount}]` → confidence=CONFIRMED（声明∩推导）。
2. 改成 `columnLineage: [{from: orders.order_id, to: orders_clean.total_amount}]`（与 SQL 推导矛盾）→ 该边 confidence=CONFLICT，push 仍成功（不阻断）。
3. 只给 `columnLineage`、SQL 改成 019 解析不了的 DDL → 边以 confidence=DECLARED 写入（兜底建图）。

## 自动化测试

- **单测**（无 neo4j）：`TaskMapper.fromYaml` 解析 `schema`/`columnLineage` 块（合法/非法/缺失）；`ColumnLineageCrossCheck.crossValidate` 四情形置信度（D∩R / D\R / R\D / 冲突）。
- **集成**（testcontainers-neo4j）：seed `:Column` → `Neo4jColumnLineageCatalog.lookupTable` round-trip；端到端 push → 断言节点/边/confidence；CONFLICT 不阻断 push；DECLARED 兜底；无声明零回归。

## 降级验证

- H2 profile（`lineage.column-catalog.type` 缺失）→ 装配 `EmptyColumnLineageCatalog`，启动不崩、`lookupTable` 恒 empty、push 不阻断。
- neo4j 不可达（`type=neo4j` 但无 neo4j）→ `lookupTable` try-catch → empty → 退表级。

## 排序不变量（FR-009）

当前代码 `TaskService` 是 extract(485)→recordTaskIo(494) 逆序；本期须把声明 schema 的 seed 提至 extract 之前（验证：同次 push 内当前任务自身声明的表即被解析出 CONFIRMED 边，而非等下一次 push）。
