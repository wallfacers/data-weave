# UI Visual Contract: 面包屑 + 卡片/表格栅格审计

本特性对外暴露的"接口"是**渲染后的 DOM 视觉/结构契约**（Web 应用的 UI contract）。以下为可自动/半自动验收的断言，供 quickstart 浏览器门与 vitest 单测引用。

## 契约 A：`WorkspaceBreadcrumb` 面包屑组件

**载体**：`components/workspace/breadcrumb.tsx`，挂载于 `workspace.tsx` 的 `WorkspaceTabBar` 与内容渲染区之间。

| 断言 | 期望 | 覆盖 FR / SC |
|------|------|-------------|
| A1 层级路径完整 | 入口视图的面包屑渲染「项目 > 分组 > 视图」三级，与 `resolveActiveHighlight` 结果一致 | FR-001，SC-001 |
| A2 无分组兜底 | 无分组归属的视图（如 `settings`）面包屑渲染「项目 > 视图」二级，不报错、不留白级、不渲染空节点 | FR-003 |
| A3 动态参数级 | 携带 `params` 的详情类 Tab（如 `instance-log`），面包屑末级追加动态值，格式与该 Tab 在 `TabStrip` 上的标签一致（同一 `tabLabel()` 来源） | FR-002，Edge Case（同视图多参数） |
| A4 随 Tab 切换更新 | 切换 `activeTabId` 后面包屑在同一渲染周期更新为新 Tab 内容，不残留上一个 Tab 的节点 | FR-002，SC-003 |
| A5 与侧栏折叠解耦 | 侧边导航折叠为 icon rail 后，面包屑内容/可读性不受影响 | US1 Acceptance Scenario 2 |
| A6 无分割线 | 面包屑行与上方 Tab 条、下方内容区之间不含 `border-b`/`border-t`/`<Separator>`/`<hr>` | DESIGN.md「无分割线」条款 |
| A7 语义 token | 面包屑背景/文字色均为既有语义 token（如 `bg-foreground/[0.04]`、`text-muted-foreground`），无裸色值、无手写 `dark:` | FR-009 |

## 契约 B：多 Tabs 行为零回退

**载体**：`lib/workspace/store.ts`（不改）、`components/workspace/tab-bar.tsx`。

| 断言 | 期望 | 覆盖 FR / SC |
|------|------|-------------|
| B1 Tab 操作不变 | 新开/关闭/固定/取消固定/切换/keep-alive 的行为与改造前逐项一致 | FR-005，SC-005 |
| B2 keep-alive 状态保留 | 后台保活 Tab 切回后内部状态（筛选条件、滚动位置）不因本次改造丢失 | US2 Acceptance Scenario 2 |
| B3 `tabLabel` 复用 | `tab-bar.tsx` 的 `tabLabel()` 导出后被 `breadcrumb.tsx` 复用，两处渲染同一 Tab 的动态参数文案完全一致 | Decision 2（research.md） |

## 契约 C：卡片/表格栅格审计范围边界

| 断言 | 期望 | 覆盖 FR |
|------|------|---------|
| C1 无新图表 | 全项目视图渲染结果中不出现新增的面积图/折线图等可视化图表，`package.json` 不新增图表库依赖 | FR-008，SC-004 |
| C2 卡片栅格对齐 | 含统计卡片的视图使用与 `metrics-view.tsx` 一致的响应式栅格类（`grid gap-3 sm:grid-cols-2 lg:grid-cols-4` 或同量级变体），卡片圆角/阴影/间距为既有 `Card` 组件默认值 | FR-006，SC-002 |
| C3 表格版式对齐 | `<DataTable>` 调用点渲染结果符合并行分支 033 交付的边框 frame 规范（审计确认，非本特性改动该组件本身） | FR-007 |
| C4 空视图不强插区块 | 无统计卡片/无表格的视图（画布、表单类）渲染结果中不出现本特性新增的空卡片容器 | FR-010 |
| C5 数据不变 | 所有卡片/表格审计改动仅涉及呈现 class，不改变其展示的业务数据、列定义、筛选、分页语义 | Assumptions |

## 验收判定

- **自动（vitest / DOM）**：A1/A2/A3（面包屑派生函数纯函数单测，类比 `nav-groups.test.ts`）、B3（`tabLabel` 导出后两处调用结果相等断言）。
- **人工/浏览器门（Playwright）**：A4/A5/A6/A7、B1/B2、C1-C4（跨多个视图 Tab 目测，双主题各验一遍）。
