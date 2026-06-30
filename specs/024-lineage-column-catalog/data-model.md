# Data Model: 声明驱动的列血缘 Catalog

**Feature**: 024-lineage-column-catalog | **Phase**: 1 | **Date**: 2026-06-30

实体分三类：【新增】/【既有-改】/【既有-不改】。研究依据见 [research.md](./research.md)。

## 1. 声明表面（`.task.yaml`，【新增】可选块）

### schema 块
表限定名 → 有序列 `{name, type}`。
```yaml
schema:
  orders:
    - { name: order_id, type: BIGINT }
    - { name: amount,   type: DECIMAL(18,2) }
```
- key = SQL 中出现的表名（与 `lookupTable` 查询的 qualifiedName 同源）。
- `type` 自由文本（SQL 类型，落 `:Column.dataType`）；`ordinal` 由列表顺序隐式决定（0-based）。
- 缺失整块 = 不 seed 该任务任何列（现状表级行为）。

### columnLineage 块
`{from: 表.列, to: 表.列}` 边列表。
```yaml
columnLineage:
  - { from: orders.amount, to: orders_clean.total_amount }
```
- 声明期望列边（cross-check 的 D 集 + DECLARED 兜底建图来源）。
- `from`/`to` 的表名须与 schema 块 key 一致；列名须在对应 schema 列表内。

## 2. 解析透传结构（【既有-改】，零动表）

| 结构 | 现状 | 本期 |
|---|---|---|
| `TaskDoc`（`filecontract/dto/TaskDoc.java:14`） | yaml 解析中间 record | 【改】加 `declaredSchema` + `declaredColumnLineage` 两字段 |
| `ProjectImport.Builder`（`ProjectMapper`） | 已有 `taskDatasourceCode` 透传 map | 【改】加 `taskDeclaredSchema` / `taskDeclaredColumnEdges` 两透传 map（taskId→声明） |
| `TaskDef` / `task_def` 表 | — | 【不改】声明不落 task_def（R2） |

## 3. catalog 接口（【既有-改】签名变更）

```java
// ColumnLineageCatalog（application/lineage/ColumnLineageCatalog.java:13）
// 【改】签名加 (tenantId, projectId)（R3）
Optional<TableSchema> lookupTable(long tenantId, long projectId, String qualifiedName);
```
- `EmptyColumnLineageCatalog`：【改】签名同步，恒返回 empty（H2/默认 fallback）。
- `Neo4jColumnLineageCatalog`：【新增】Cypher 查 `(:Table)-[:HAS_COLUMN]->(:Column)` 有序回组 `TableSchema`；内部 try-catch 返回 empty。

## 4. 既有领域模型（【既有-不改】）

- `TableSchema`(record)：`qualifiedName + List<ColumnMeta>`。
- `ColumnMeta`：`name, dataType, ordinal`（已建模，本期从声明填实）。
- `ColumnEdge`：`srcTable, srcCol, dstTable, dstCol, transform, confidence`。declared edges 复用此结构（confidence=DECLARED）。

## 5. Confidence 枚举（【既有-改】+DECLARED）

```java
enum Confidence { CONFIRMED, UNVERIFIED, CONFLICT, DECLARED }
//                                       新增 ↑（019 FR-004 既有 {CONFIRMED,UNVERIFIED,CONFLICT}）
```
对账语义（D=声明边集，R=SQL 推导边集）：
- D∩R → CONFIRMED
- D\R → DECLARED（新增；声明兜底，推导未印证）
- R\D → 沿用 019（CONFIRMED-calcite / UNVERIFIED-启发式）
- 声明与推导映射矛盾 → CONFLICT

## 6. neo4j 图模型（【既有-不改】，仅新写入路径）

| 节点/边 | 属性 | 本期 |
|---|---|---|
| `:Column` | `columnKey, name, dataType, ordinal, tenantId, projectId` | 【新写入路径】`recordTaskIo` 事务内由声明 schema 独立 MERGE（不再仅 Edge 副产品）；dataType/ordinal 从声明填实 |
| `:Table` -[:HAS_COLUMN]-> `:Column` | — | 【新写入路径】seed 时 `ensureTable`+`ensureColumn` 建 |
| `:Column` -[:DERIVES_FROM]-> `:Column` | `confidence, taskDefId, transform` | 【改】confidence 可为 DECLARED/CONFLICT；declared edges 兜底写入 |

## 7. 校验与状态规则

- 声明格式非法 → 跳过该声明 + WARN 日志，不阻断 push（FR-010）。
- 声明表名 key 与 SQL 表名不一致 → catalog 匹配不上 → 该表列级降级（可告警）。
- `lookupTable` 查询失败 → empty（永不抛）。
- 多任务同表声明类型不一致 → latest-wins seed；漂移检测出范围（后续债）。
- **pull 序列化**：`TaskMapper.toYaml`（序列化侧，与 `fromYaml` 对称）MUST 把 `declaredSchema`/`declaredColumnLineage` 写回 yaml——这是 Constitution II round-trip integrity / SC-005 的实现落点，不可漏（否则 push→pull 丢声明字段）。
