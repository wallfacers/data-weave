# 覆盖 / 差距清单（Adoption Inventory）

**Feature**: 037-shared-ui-kit | **FR-015 验收基线** | 初版 2026-07-02，实现阶段持续维护

清单登记各页面对 9 类原语的采用状态，作为"统一到什么程度"的可核对依据（SC-007）。`待迁移`项按 clarify 决策**增量迁移**，不阻断本特性交付。

图例：✅ 已复用公共组件 · ✋ 手写一次性实现 · ➖ 不涉及 · ⚠️ 小偏差待迁移

## 基线确认（T001）

- **033**（DataTable 边框包裹）：✅ 已合 main（`93aa63d` + `d4dfe06`）
- **035**（LoadingState 居中转圈）：✅ 已合 main（`1673878` + `ecc4b76`）
- **030**（下划线 Tabs 组件）：✅ T003 文件级取用 `tabs.tsx` 入 037 基线，typecheck 绿；ops/alerts 迁移完成（T012/T013）

## 各页面 × 原语采用矩阵

| 页面/view | Tabs | 表格 | 下拉 | 弹框 | 日期 | 加载 | 刷新 | 卡片/间距 | 处置 |
|---|---|---|---|---|---|---|---|---|---|
| `ops-view.tsx` | ✅ Tabs(T003迁移) | ✅ DataTable(各panel) | ✅ DropdownSelect | ✅ Dialog(DAG) | ✅ bizDate | ➖(panel自管) | ➖ | ✅ Card | **已迁移(示范)** |
| `alerts-view.tsx` | ✅ Tabs(T003迁移) | ➖ | ➖ | ➖ | ➖ | ✅ LoadingState | ✅ ViewRefreshControl | ✅ Card | **已迁移(示范)** |
| `metrics-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ ViewStatus | ✅ ViewRefreshControl | ✅ Card | 保持 |
| `reports-view.tsx` | ➖ | ✅ DataTable | ✅ | ✅ | ✅ DatePicker(bizDate) | ✅ | ✅ | ✅ Card | 保持 |
| `datasources-view.tsx` | ➖ | ✅ DataTable | ✅ DropdownSelect | ✅ Dialog | ✅ useFormatDateTime | ✅ | ✅ | ✅ Card | 保持 |
| `freshness-view.tsx` | ➖ | ✅ DataTable | ➖ | ➖ | ✅ useFormatDateTime | ✅ | ✅ ViewRefreshControl | ✅ | 保持 |
| `settings-view.tsx` | ➖ | ✅ DataTable(033边框) | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ Card(p-4) | 保持 |
| `lineage-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ LoadingState | ➖ | ➖ | 保持 |
| `workflow-instance-detail.tsx` | ➖ | ➖ | ➖ | ➖ | ✅ bizDate(直出) | ✅ LoadingState | ➖ | ✅ Card | 保持 |
| `workflow-canvas-view.tsx` | ➖ | ➖ | ➖ | ➖ | ✅ yesterdayBizDate | ✅(local state) | ➖ | ✅ Card | 保持/待迁移 |
| `instance-log-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ✅(manual) | ⚠️ 手写RefreshIcon | ➖ | 待迁移(刷新) |
| `asset-catalog-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘 |
| `event-center-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘 |
| `fleet-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘 |
| `metric-marketplace-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘 |
| `placeholder-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘(骨架) |
| `quality-view.tsx` | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | ➖ | 待盘 |

## 组件级散落/缺口

| 项 | 现状 | 证据 | 处置 |
|---|---|---|---|
| 魔法间距 | `data-table-toolbar.tsx` 硬编码 `px-2.5/py-0.5/gap-*` 无统一刻度引用 | `components/ui/data-table-toolbar.tsx` | 豁免——工具栏控件级微间距（<1rem），已在 DESIGN.md DataTable 条目注明 |
| 卡片内边距 token 文档化 | `--card-spacing` 已用但未进 DESIGN.md 目录 | `components/ui/card.tsx` L15 | ✅ 已完成（T005，DESIGN.md 间距/token 小节） |
| 下划线 Tabs 组件 | 030 分支 `tabs.tsx` 已文件级取入 037 基线 | `components/ui/tabs.tsx` | ✅ 已完成（T003，typecheck 绿） |
| 下划线 Tabs 迁移 | ops-view / alerts-view 手写下划线已迁移到共享 Tabs | `ops-view.tsx` / `alerts-view.tsx` | ✅ 已完成（T012/T013，typecheck 绿） |
| 统一 EmptyState | 各 view 自写 `if(data==null)` 空态 | 15+ views | 待迁移(低优先，非9类原语) |
| 刷新入口不一致 | `instance-log-view.tsx` 手写 RefreshIcon 按钮非 ViewRefreshControl | `instance-log-view.tsx` L77 | 待迁移 |

## 日期口径审计（T018）

全站日期用法均遵守约定：
- **bizDate（`yyyy-MM-dd`）**：后端直出展示（`workflow-instance-detail`、`instance-dag-dialog`、`backfill-panel`）；`yesterdayBizDate()` 作 T-1 默认值（`workflow-canvas-view`）
- **带时间变体（`useFormatDateTime`）**：时间戳统一用 `formatDateTime`（`datasources-view`、`freshness-view`、`settings-dialog`、`backfill-panel`、`node-detail-panel`、`version-history-panel`、`run-logs-tabs`、`periodic-workflows-panel`、`manual-workflows-panel`、`workflow-instances-panel`）
- **无 dayjs / Intl.DateTimeFormat 混用**
- **DatePicker**：用于日期选择入口（reports-view、ops 筛选、backfill 日期范围）
- 结论：**零偏离**，SC-004 已达成

## 加载/刷新审计（T021）

- **LoadingState（居中转圈）**：✅ lineage-view、alerts-view、workflow-instance-detail 均已复用
- **ViewRefreshControl（统一位置）**：✅ freshness-view、metrics-view、alerts-view、workflow-instances-panel 均已复用
- **⚠️ 小偏差**：`instance-log-view.tsx` L77 手写 `RefreshIcon` 按钮，未用 `ViewRefreshControl`——登记待迁移
- **注**：`ops-view` 的 panel 级 loading/refresh 由各子 panel（periodic/manual/workflow-instances/backfill）自行管理，非 ops-view 本级负责——OK

## 收敛/编目就绪度（种子条目）

| 原语 | 规范组件 | 目录就绪 |
|---|---|---|
| 滚动条 | DwScroll | ✅ T008 |
| Tabs(卡片) | TabStrip | ✅ T011 |
| Tabs(下划线) | Tabs（取自030） | ✅ T003+T011 |
| 表格 | DataTable + DataTableToolbar | ✅ T009 |
| 下拉 | DropdownSelect | ✅ T010 |
| 弹框 | Dialog | ✅ T010 |
| 日期 | DatePicker + biz-date/useFormatDateTime | ✅ T017 |
| 加载 | LoadingState | ✅ T019 |
| 刷新 | ViewRefreshControl | ✅ T020 |
| 卡片容器 | Card(--card-spacing) | ✅ T005+T008 |

> 全部 10 条目（含 Tabs 双变体）均已编目，SC-003 达成。剩余 ~6 个视图（asset-catalog/event-center/fleet/metric-marketplace/placeholder/quality）标注「待盘」——登记即算收口（SC-007），不阻断本特性交付。
