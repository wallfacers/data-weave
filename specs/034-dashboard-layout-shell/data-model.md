# Phase 1 Data Model: Dashboard-01 外壳布局迁移（保留多 Tabs）

**无持久化数据实体。** 这是纯前端布局/导航上下文特性：不新增或修改任何数据库表、后端接口、DTO，也不新增前端持久化状态（不进 `WorkspaceSnapshot`、不进 `localStorage`）。

可类比"实体"的仅为两组**无状态派生模型**（渲染时由既有数据计算得出，本身不被存储），已在 [contracts/breadcrumb-visual-contract.md](contracts/breadcrumb-visual-contract.md) 中以视觉契约形式定义验收断言：

## 面包屑路径（Breadcrumb Path）

单个激活 Tab 在某一渲染时刻对应的层级节点序列，**由现有数据实时计算，不单独存储**：

| 节点 | 类型 | 派生来源 |
|------|------|---------|
| 项目节点 | 固定 1 个 | `useProjectContext` 的 `current?.name` |
| 分组节点 | 0 或 1 个 | `resolveActiveHighlight(view).group` → `NAV_GROUPS` 查 `titleKey`；无归属分组时省略该级（FR-003） |
| 视图节点 | 固定 1 个 | `VIEW_META[view].title` |
| 动态参数节点 | 0 或 1 个 | `tab.params` 首个值，复用 `tab-bar.tsx` 的 `tabLabel()` 约定（详情类 Tab 如 `instance-log` 会带此级） |

不变量：
- 节点序列随 `activeTabId` 变化而重新计算，不同 Tab（即使同视图不同 `params`）各自独立求值，互不覆盖（对应 spec Edge Case「同视图不同参数」）。
- 视图缺少分组归属时序列退化为 `[项目, 视图]`，不产生空节点或报错（FR-003）。

## 视图布局区块（View Layout Section）

视图内容区中的卡片网格区块与数据表格区块，是本次审计对齐的作用对象，**不改变其承载的原始业务数据**：

| 区块类型 | 现有标杆 | 审计要点 |
|---------|---------|---------|
| 统计卡片网格 | `metrics-view.tsx` 的 `MetricCard` + `grid gap-3 sm:grid-cols-2 lg:grid-cols-4` | 其余含卡片视图是否已对齐该栅格惯例（FR-006） |
| 数据表格 | `components/ui/data-table.tsx`（边框 frame 由并行分支 033 交付） | 调用点是否已呈现统一版式，不要求新增拖拽排序等超出现状能力（FR-007） |

以上不产生 migration、不改 `schema.sql`、不影响 `schema_version`。
