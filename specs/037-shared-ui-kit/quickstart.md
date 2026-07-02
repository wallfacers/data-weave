# Quickstart: 复用优先的公共组件目录

**Feature**: 037-shared-ui-kit | 面向：构建/改造 `frontend/` 页面的开发者与 AI agent

本特性的目标是让你**不再为样式细节反复对话**。一句话工作流：**先查目录 → 命中复用 → 未命中才新建并回填目录**。

## 我要做一个新页面 / 改一个页面

1. **先查目录**：打开 `frontend/DESIGN.md` → `## 公共组件目录（先查此处 · reuse-first）`。
2. **对号入座**（9 类原语的规范组件）：

   | 你要的 | 用这个 | 位置 |
   |---|---|---|
   | 可滚动区域 | `DwScroll` | `components/ui/dw-scroll.tsx` |
   | 主 tab（可关闭） | `TabStrip` | `components/ui/tab-strip.tsx` |
   | 页内子 tab（下划线） | `Tabs` | `components/ui/tabs.tsx`（依赖 030） |
   | 数据表格 | `DataTable` + `DataTableToolbar` | `components/ui/data-table.tsx` |
   | 下拉选择 | `DropdownSelect` | `components/ui/select.tsx` |
   | 弹框 | `Dialog` | `components/ui/dialog.tsx` |
   | 业务日期选择 | `DatePicker` + `yesterdayBizDate()` | `components/ui/date-picker.tsx` · `lib/workspace/biz-date.ts` |
   | 带时间格式化 | `useFormatDateTime()` | `hooks/use-format-date-time.ts` |
   | 加载态（居中转圈） | `LoadingState` | `components/workspace/shared/loading-state.tsx` |
   | 刷新入口 | `ViewRefreshControl` | `components/workspace/views/view-refresh-control.tsx` |
   | 主内容卡片 | `Card`（内边距走 `--card-spacing`） | `components/ui/card.tsx` |

3. **命中就复用**，按条目的"关键 props/变体"配置——不要手写同类原语，不要手填内边距魔法值。
4. **确实没有** → 才新建组件，并在**同一改动内**回填 DESIGN.md 目录（满足 `contracts/catalog-entry.schema.md`）。
5. **交付前自查**：走一遍 `contracts/reuse-first-checklist.md`。

## 几条硬约定（省掉你反复被问的那些）

- **卡片内边距**：一律 `--card-spacing`（默认 24px / `size=sm` 16px）。别再写 `p-5`/`20px`。
- **加载/刷新**：`LoadingState`（垂直+水平居中转圈）+ `ViewRefreshControl`（统一位置）。别写"加载中"纯文字。
- **日期**：主键默认业务日期 `yyyy-MM-dd`（`yesterdayBizDate()` 兜底 T-1）；需要时间才用 `useFormatDateTime`。
- **Tabs**：可关闭主 tab → `TabStrip`；页内非关闭子 tab → 下划线 `Tabs`。别手写下划线、别写死等分。
- **语义 token + 明暗一致**：`bg-primary`/`text-muted-foreground`/`gap-*`，无手写 `dark:`。

## 验证（本特性的门禁）

```bash
cd frontend
pnpm typecheck          # 零错误
pnpm design:lint        # 改了 DESIGN.md/token 时必过
# 迁移/新建组件：补 vitest；跨页明暗主题浏览器抽查外观一致
```

## 我在实现本特性（037）本身

- 主交付：在 `DESIGN.md` 建 `## 公共组件目录` 章节（索引 + 复用优先约定 + 间距 token 说明）。
- **先协调 030**：下划线 `Tabs` 组件在 030 分支未合 main，Tabs 收敛依赖它落地（见 `research.md` R3）——否则"下划线统一"无处闭环。
- 维护 `adoption-inventory.md` 差距清单直至各页 `保持`/`待迁移` 登记齐（SC-007）。
- 存量迁移按**增量**（clarify）：本特性只迁移 `ops-view`/`alerts-view` 手写下划线 Tab 作示范，其余登记待迁移。
