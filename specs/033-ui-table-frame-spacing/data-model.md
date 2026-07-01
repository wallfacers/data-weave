# Phase 1 Data Model: 系统设置间距统一 + 全站表格边框包裹

**N/A —— 本特性无数据实体。**

这是纯前端视觉/版式一致性改造：不新增或修改任何数据结构、DTO、数据库表、后端接口或前端数据状态。不触 `DataTable` 的数据获取（`fetcher`/`mode`/`ColumnDef`/`FilterDef`）语义，仅调整其根容器与内部两段的**呈现 class**。

可类比"实体"的仅为一组**呈现契约常量**（非运行时数据），已在 [contracts/ui-visual-contract.md](contracts/ui-visual-contract.md) 中以视觉契约形式定义：

- **表格边框容器（DataTable frame）**：`rounded-xl` + `border`（语义色）+ `bg-card` + `overflow-hidden`，包裹 toolbar / 表体 / 分页三段。
- **设置视图间距（Settings spacing）**：单层 `p-4` 外边距 + 统一纵向 `gap`，Tab 条/标题/表格左边缘对齐。

以上不产生 migration、不改 `schema.sql`、不影响 `schema_version`。
