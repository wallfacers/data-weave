# Contract: `.task.yaml` 列声明

**Feature**: 024-lineage-column-catalog

Agent 在 `.task.yaml` 可选声明的两块列级元数据。两块独立、均可选、都不给则维持现状表级行为（零回归）。声明仅在 **push（项目同步）路径**经 FileContract 解析生效（`createAndOnline` MCP 路径不经文件，本期不支持，见 research.md R2）。

## schema 块（喂 catalog，破循环）

表名 → 有序列 `{name, type}`。
```yaml
schema:
  orders:
    - { name: order_id, type: BIGINT }
    - { name: amount,   type: DECIMAL(18,2) }
  orders_clean:
    - { name: total_amount, type: DECIMAL(18,2) }
```
- **key**：SQL 中出现的表名（含 schema 前缀则带前缀），须与 `lookupTable` 查询名一致。
- **type**：自由文本（SQL 类型字符串），落 `:Column.dataType`。
- **ordinal**：列表顺序隐式（0-based）。
- **语义**：push 时 `recordTaskIo` 独立 MERGE 成 `:Column`（不经 ColumnEdge）→ 019 catalog 可读 → 列血缘能产 CONFIRMED 边。

## columnLineage 块（喂 cross-check，声明期望边）

`{from: 表.列, to: 表.列}` 列表。
```yaml
columnLineage:
  - { from: orders.amount, to: orders_clean.total_amount }
  - { from: orders.order_id, to: orders_clean.order_id }
```
- **from/to**：`表名.列名`；表名与 schema 块 key 同源，列名须在对应 schema 列表内。
- **语义**：与 019 SQL 推导边对账 → 一致=CONFIRMED / 仅声明=DECLARED / 矛盾=CONFLICT / 仅推导=沿用 019。即使 SQL 解析失败，声明边也以 DECLARED 兜底写入。

## 校验与降级

- 非法格式（坏 yaml / 未知类型 / 畸形 from-to）→ 跳过该声明 + WARN，不阻断 push。
- 表名/列名与 SQL 不一致 → catalog 匹配不上 → 列级降级，不崩。
- 两块都不声明 → 现状表级行为，零回归。
