# Phase 0 Research: 系统设置间距统一 + 全站表格边框包裹

本特性无技术未知量（纯前端、既有栈、既有组件），研究聚焦于**在哪一层落地**才能最一致、最少改动、不违背 `DESIGN.md`。

## Decision 1：边框 frame 归属 —— 下沉到 `DataTable` 根容器（而非逐个调用点套 Card）

**Decision**：在 `components/ui/data-table.tsx` 的根 `<div>`（现 `flex min-h-0 min-w-0 flex-1 flex-col gap-3`）上追加 `rounded-xl border bg-card overflow-hidden`，并给 toolbar / 分页两段补水平内边距，使"toolbar + 表 + 分页"三段被同一条边框包裹。表头 `border-b` 与数据行保持满幅（edge-to-edge），符合"卡片内表格"惯用版式。

**Rationale**：
- `DESIGN.md`「数据表格 — DataTable」明文立场：**"全项目结构化列表表格只有一种长相"**。把外观下沉进组件是该立场的**唯一自洽实现**——一处定义、全站一致，天然满足 FR-006/007 与 SC-004/005。
- 8 个真实调用点**零改动**即获得统一 frame（审计确认即可），把"所有页面统一"从"逐页手工套 + 靠人肉保证一致"降级为"改一处组件"，根除后续新表格忘加边框的漂移。
- 用语义 `border` token + `rounded-xl`（派生自 `--radius: 0.625rem`），亮/暗自动适配（FR-010/SC-007），无需 `globals.css` 或新主题变量，不碰 Design Contract 的颜色红线。

**Alternatives considered**：
- **A. 逐调用点包 `Card`/`<TableFrame>`**：改 8+ 处、易漏、settings 已经这么做且导致双层包裹隐患；与"只有一种长相"的"单一真相"精神相悖。❌
- **B. 新建 `BorderedDataTable` 包装组件**：多一层无谓抽象，仍要逐点替换，收益等于方案 A 的缺点。❌
- **C. 全局 CSS 选择器给所有 `<table>` 加框**：命中 chat/markdown 等非 DataTable 表格，破坏隔离。❌

## Decision 2：frame 内部版式 —— 三段满幅表体 + toolbar/分页水平内缩

**Decision**：根容器**不**统一 padding；改为：表头 `border-b` 与数据行满幅贴边框（保留既有满幅表头设计），仅 `DataTableToolbar` 与 `Pagination` 两段追加水平内边距（量级对齐参照卡片的 `p-4` 家族，最终像素值在实现期用浏览器门定档）。根容器加 `overflow-hidden` 确保 `rounded-xl` 正确裁剪四角与横向滚动区。

**Rationale**：
- 满幅表头/行是 shadcn「bordered table」标准惯用法，行 hover 底色（`hover:bg-muted/50`）延伸到边框内缘才不显突兀；若给根统一 padding，表头 `border-b` 会内缩成"悬浮短线"，观感更差。
- toolbar/分页是控件区，贴边会显拥挤，故仅这两段内缩——与参照（settings `CardContent p-4` 包裹 toolbar+表+分页）视觉等价。

**Alternatives considered**：
- **根统一 `p-4`**：表头 border-b 不满幅、行 hover 不贴边，观感差。❌
- **toolbar 移到边框外**：违背用户"用一个边框包裹起来"（toolbar 也要在框内，与参照一致）。❌

## Decision 3：系统设置间距归一 —— 删冗余 `Card` 层，单层 `p-4` + 统一纵向 gap

**Decision**：`settings-view.tsx` 移除 `Card` / `CardContent p-4` 包裹（frame 已内建于 DataTable，无需外层卡片提供边框）；`SettingsView` 外层保留单一 `p-4`，Tab 条、各 Tab 的 `<h2>` 标题、`DataTable` 共处该单层内边距下、左边缘对齐；三个 Tab 组件（Users/Roles/Projects）已同为 `flex-col gap-*` + h2 + DataTable 结构，保持同构，纵向 gap 取统一刻度值。

**Rationale**：
- 病根是**双层内边距**：外层 `p-4` 让 Tab 条内缩 1rem，`Card p-4` 让表格再内缩 1rem → Tab 条与表格左边缘错开 1rem，即用户所述"边距千奇百怪"。删掉 Card 层，两者归一到同一 `p-4`（FR-001/002/003，SC-001/002/003）。
- 删 Card 不丢边框观感——DataTable 现自带 frame，表格仍"被框住"，且消除"Card 边框 + 表格边框"双层（FR-011/SC-006）。
- 三 Tab 同构 → 切换零跳动（FR-004/SC-002）。

**Alternatives considered**：
- **保留 Card、去掉外层 p-4**：仍是双层结构，且 DataTable 自带 frame 后与 Card 边框叠成双框。❌
- **保留 Card、给 DataTable frame 加"在 Card 内不重复"开关**：引入条件分支破坏"只有一种长相"。❌

## Decision 4：`DESIGN.md` 同步 —— 增补而非改色

**Decision**：在 `DESIGN.md`「数据表格 — DataTable」段增补一条：表格外观统一由组件内建 `rounded-xl border` 边框容器包裹（toolbar+表+分页三段同框），调用点不再自行套边框卡片。**不改** YAML tokens、不改 `globals.css`（复用现有 `border` 语义色与 `--radius`）。

**Rationale**：Design Contract Gate 要求设计系统变更先落 `DESIGN.md`（真相源）。本变更是**版式约定**非 theme/颜色，故仅文字增补，`pnpm design:lint` 结构校验不受影响。

**Alternatives considered**：不更新 `DESIGN.md`——违反 Design Contract Gate，且后续易回退。❌

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 某调用点父容器 `overflow-hidden` + 高度约束致 frame 圆角/滚动异常（freshness `flex-1 overflow-hidden`） | 浏览器门逐页目测；frame 自带 `overflow-hidden` 内部裁剪，DwScroll 仍在框内 |
| `backfill-panel` 内另有 `rounded-lg border` 提示块，勿与表格 frame 混淆 | 审计时区分：提示块非 DataTable，不动 |
| Turbopack 全局 CSS 陈旧缓存致边框不显 | 参照记忆 [[turbopack-global-css-stale-hmr]]：改后清 `.next` 重启再验 |
| 双主题下边框对比不足 | 用语义 `border` token（暗色 `oklch(1 0 0 /10%)` 为 shadcn 标准），浏览器门亮/暗各验一遍（SC-007） |
