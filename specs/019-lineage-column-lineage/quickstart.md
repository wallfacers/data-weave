# Quickstart: 列级 SQL 血缘解析

## 这是什么

`SqlColumnLineageExtractor` 从一条 SQL 推出 `目标列 ← 源列` 派生关系(`ColumnEdge`)。纯组件,**不需要 neo4j**——用 fixture catalog 即可单测。

## 本地验证(纯单测)

```bash
cd backend
./mvnw -q -pl dataweave-master -am test -Dtest=SqlColumnLineageExtractorTest
# 期望全绿;无需 docker / neo4j
```

## 解析示例

输入(catalog 已知 `ods_order(id,user_id,amount)`):
```sql
INSERT INTO dwd_order (id, uid, amt)
SELECT id, user_id, amount * 1.1 FROM ods_order
```
输出 `ColumnEdge`:
| dst | ← src | transform | confidence |
|-----|-------|-----------|------------|
| dwd_order.id | ods_order.id | DIRECT | CONFIRMED |
| dwd_order.uid | ods_order.user_id | DIRECT | CONFIRMED |
| dwd_order.amt | ods_order.amount | EXPRESSION | CONFIRMED |

## 降级示例

- `INSERT INTO t SELECT * FROM unknown_tbl`(catalog 无 `unknown_tbl`)→ `parsed=true, degraded=true`,列级留空或 UNVERIFIED,**退表级**(`SqlTableExtractor` 给出 reads/writes);不抛异常。
- `CREATE TABLE ...` / 动态 SQL → `parsed=false, edges=[]`,完全退表级。

## 如何被集成(接缝)

1. 018 实现 `ColumnLineageCatalog`(从 neo4j `:Column` 查列元数据)。
2. 建任务/push 时,`recordTaskIo` 调 `extract(sql, catalog)` 得 `List<ColumnEdge>`,经 `LineageStore` 写 `DERIVES_FROM`。
3. 020 读 `DERIVES_FROM` 做列级上下游/前端展示。

契约见 [contracts/column-lineage-contract.md](./contracts/column-lineage-contract.md)。
