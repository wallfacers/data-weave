# Phase 1 Data Model: 复用优先的公共前端组件契约与目录

**Feature**: 037-shared-ui-kit | **Date**: 2026-07-02

本特性**无数据库、无后端实体**。此处"数据模型"指本特性引入的**文档态实体**——它们是纯文本结构（DESIGN.md 章节、markdown 清单），human/agent 可读可 diff（契合宪法 Files-First）。定义其字段与约束，供 Phase 1 契约与 tasks 结构化生成。

---

## 实体 1：公共组件目录（Component Catalog）

**承载**：`frontend/DESIGN.md` 的 `## 公共组件目录（先查此处 · reuse-first）` 章节（单一真相源，FR-001）。

**结构**：
- **序言（复用优先约定，FR-002）**：一段规则文本——"实现任何界面原语前先查本目录；命中必复用、禁止手写同类；未命中方可新建并回填本目录"。
- **目录表**：N 条 `Catalog Entry`（见实体 2），覆盖至少 9 类原语（FR-004）。
- **间距/token 小节（FR-010）**：卡片内容内边距 = `--card-spacing`（默认 24px / `size=sm` 16px）；引用 `globals.css` 与 Tailwind `--spacing()`；声明"页面禁止手填内边距魔法值"。

**约束**：
- 每类原语在目录中**唯一条目**（FR-005，无同类多规范）。
- 目录变更须与组件实现同步（Edge Case：目录-实现漂移）——"改公共组件即更新目录"写入序言。
- `pnpm design:lint` 通过（章节结构合法）。

---

## 实体 2：公共组件条目（Catalog Entry / Primitive）

**承载**：目录表的一行 + 必要时链接到 DESIGN.md 既有深章节（TabStrip/滚动条/DataTable）。

**字段**：
| 字段 | 说明 | 必填 |
|---|---|---|
| `原语类别` | 滚动条/Tabs/表格/下拉/弹框/日期/加载/刷新/卡片容器 之一 | ✅ |
| `规范组件` | 唯一公共组件名 + 真实路径（如 `DwScroll` @ `components/ui/dw-scroll.tsx`）| ✅ |
| `用途/何时用` | 一句话适用场景 | ✅ |
| `何时不用/禁止手写` | 明确"存在该组件时禁止手写同类原语"的边界 | ✅ |
| `关键 props/变体` | 如 Tabs 的 closable→卡片 / 非closable→下划线；Loading 的 centered/overlay | ✅ |
| `token/间距引用` | 若涉及间距，引用 `--card-spacing` 等，不写字面魔法值 | 视情况 |
| `深章节链接` | 指向 DESIGN.md 既有规范段（避免重复） | 视情况 |

**9 类原语的规范组件（种子条目，据 R1）**：
1. 滚动条/滚动区 → `DwScroll`（`components/ui/dw-scroll.tsx`）
2. Tabs（closable/卡片）→ `TabStrip`（`components/ui/tab-strip.tsx`）
3. Tabs（非closable/下划线）→ `Tabs`（`components/ui/tabs.tsx`，**依赖 030 落地**）
4. 表格 → `DataTable`（`components/ui/data-table.tsx`，含 `DataTableToolbar`）
5. 下拉框 → `DropdownSelect`（`components/ui/select.tsx`）
6. 弹框 → `Dialog`（`components/ui/dialog.tsx`）
7. 日期 → `DatePicker` + `biz-date`/`useFormatDateTime`（见实体 4）
8. 加载 → `LoadingState`（`components/workspace/shared/loading-state.tsx`）
9. 刷新 → `ViewRefreshControl`（`components/workspace/views/view-refresh-control.tsx`）
10. 卡片容器 → `Card`（`components/ui/card.tsx`，`--card-spacing`）

**状态/生命周期**：`已编目`（种子条目就绪）→ 若新增组件则 `待回填`→`已编目`。

---

## 实体 3：覆盖/差距清单（Adoption Inventory）

**承载**：`specs/037-shared-ui-kit/adoption-inventory.md`（FR-015；验收基线 R6）。

**字段**（每行 = 一个页面对一类原语的采用状态）：
| 字段 | 说明 |
|---|---|
| `页面/view` | 如 `ops-view.tsx` |
| `原语类别` | 对应实体 2 类别 |
| `现状` | `已复用公共组件` / `手写一次性实现` / `不涉及` |
| `处置` | `保持` / `待迁移` / `本特性迁移(示范)` |
| `证据` | 文件:行号 |

**已知初值（据 R1 盘点）**：
- `ops-view.tsx` · Tabs · 手写下划线(L96–132) · 本特性迁移(示范)
- `alerts-view.tsx` · Tabs · 手写下划线 · 待迁移/示范
- `data-table-toolbar.tsx` · 间距 · 魔法间距硬编码 · 待迁移
- 多 views · EmptyState · 手写空态 · 待迁移(低优先，非 9 类原语)
- `metrics/reports/datasources/settings` 等 · 表格/下拉/加载/刷新/日期 · 已复用 · 保持

**约束**：清单须可核对（SC-007）；`待迁移`项不阻断本特性交付（clarify：存量增量迁移）。

---

## 实体 4：日期口径约定（Date Convention）

**承载**：目录"日期"条目的展开说明（FR-011，R4）。非独立文件。

**规则**：
- **默认口径 = 业务日期 bizDate**：格式 `yyyy-MM-dd`；默认值 `yesterdayBizDate()`（T-1）；选择器用 `DatePicker`；展示直出后端字符串（已是 `yyyy-MM-dd`，不过 `formatDateTime`）。
- **带时间变体**：`useFormatDateTime()` + `date-format-store`；格式 `yyyy-MM-dd HH:mm:ss`（dash/slash 用户偏好）；仅用于时间戳（启动/完成时间、日志）。
- **单一实现**：date-fns；禁止 dayjs / `Intl.DateTimeFormat` 混用。

---

## 关系图

```
公共组件目录(实体1) ──1:N── 公共组件条目(实体2)
       │                         │
       │                         └── 日期条目 ──展开── 日期口径约定(实体4)
       │
       └──验收依据── 覆盖/差距清单(实体3) ──引用── 各页面对条目的采用状态
```

无外键、无持久化、无迁移脚本——全部为可 diff 的纯文本文档。
