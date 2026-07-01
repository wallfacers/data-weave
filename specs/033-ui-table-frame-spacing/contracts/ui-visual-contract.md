# UI Visual Contract: 表格边框 frame + 设置间距

本特性对外暴露的"接口"是**渲染后的 DOM 视觉契约**（Web 应用的 UI contract）。以下为可自动/半自动验收的断言，供 quickstart 浏览器门与 vitest DOM 测试引用。

## 契约 A：`DataTable` 边框 frame（组件内建）

**载体**：`components/ui/data-table.tsx` 根 `<div>`。

| 断言 | 期望 | 覆盖 FR / SC |
|------|------|-------------|
| A1 根容器含边框 | 根元素 className 含 `border`、`rounded-xl`、`bg-card`、`overflow-hidden` | FR-006/007，SC-004 |
| A2 三段同框 | toolbar（若有）、表体、分页（若有）均为根容器子节点，同处一条边框内 | FR-008 |
| A3 边框语义色 | 边框色取自语义 `border` token（非裸色值、无手写 `dark:`） | FR-010，SC-007 |
| A4 状态无关 | loading / 空态 / 有数据三态下，根边框恒在、不塌陷、不溢出、不重影 | FR-009，SC-006 |
| A5 圆角裁剪 | `overflow-hidden` 使横向滚动区与四角被 `rounded-xl` 正确裁剪 | Edge：横向溢出 |
| A6 无双层边框 | 任一调用点外层不再另套带 `border` 的 Card/容器包裹表格 | FR-011，SC-006 |

**参照基准**：设置视图「项目」Tab 的表格外观即本契约的样板（唯一"长相"）。

## 契约 B：设置视图间距归一

**载体**：`components/workspace/views/settings-view.tsx`。

| 断言 | 期望 | 覆盖 FR / SC |
|------|------|-------------|
| B1 单层内边距 | 移除 `Card`/`CardContent` 包裹；`SettingsView` 内容仅一层 `p-4`，无"外层 p-4 + 内层 p-4"叠加 | FR-002，SC-003 |
| B2 左边缘对齐 | Tab 条、各 Tab `<h2>` 标题、`DataTable` 三者左边缘 X 坐标一致 | FR-001/003，SC-001 |
| B3 三 Tab 同构 | Users / Roles / Projects 三 Tab 的容器结构（外边距/纵向 gap/标题+表格布局）完全一致 | FR-004 |
| B4 切换零跳动 | 在三 Tab 间切换，表格左上角相对视口位置偏移为 0 | FR-004，SC-002 |
| B5 统一纵向 gap | Tab 条→标题→表格的纵向间距取自单一 `gap-*`/间距刻度，非随意数值 | FR-005 |

## 契约 C：范围边界

| 断言 | 期望 | 覆盖 FR |
|------|------|---------|
| C1 主表全覆盖 | `frontend/` 内所有 `<DataTable>` 调用点渲染的主数据表均落契约 A | FR-007，SC-004 |
| C2 从属小表豁免 | 对话框/抽屉内非 `DataTable` 的手写小表不纳入、不强加边框 | FR-012 |
| C3 数据行为不变 | 列定义、筛选、分页、批量操作、fetcher 语义零改动 | Assumptions |

## 验收判定

- **自动（vitest / DOM）**：A1、A3（class 断言）、B1、B2（渲染后 `getBoundingClientRect().left` 相等）、C1（枚举调用点 render 快照含 frame class）。
- **人工/浏览器门（Playwright）**：A2/A4/A5、B3/B4、SC-005（跨 3 页目测一致）、SC-007（亮/暗双主题目测边框可辨）。
