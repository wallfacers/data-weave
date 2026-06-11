## Context

`workspace.tsx` 内容容器原为 `relative flex … overflow-hidden`（透明）。激活 tab 是 `bg-muted` 圆角药丸。左侧 `agent-rail.tsx` 的对话面板外层是 `rounded-[var(--radius-lg)] border bg-sidebar shadow-lg` 浮起卡。`--sidebar` 亮色 `oklch(0.988 0.003 106.5)`（暖味近白，比纯白 card 略沉）、暗色 `oklch(0.228 0.013 107.4)`。

探索期对比了三种「tab↔内容联动」做法（均不动 tab、无弧角），并在真实 app 截图比对（亮/暗）：
1. **同材质连接**：内容区铺 `bg-muted`、上缘顶到 tab 条，和激活 tab 同材质连一坨。
2. **描边白卡片**：内容区独立白卡 `bg-card`，内缩留边。← **选中**
3. **顶部连接条**：白面板 + emerald 顶条。

用户选 **方案 2**，并要求**内容卡底色对齐左侧「How can I help you today?」面板**（即 `bg-sidebar`，而非 `bg-card`）。

## Goals / Non-Goals

**Goals:**
- 内容区成为一张明确的浮起卡，让激活 tab 下方视图读成「这张卡」。
- 内容卡与左侧 Agent 面板同材质（`bg-sidebar` + 同款 border/shadow/radius），构成左右一对。

**Non-Goals:**
- 不动 tab 药丸样式；不用 Chrome 弧形角。
- 不改 `--sidebar`/`--card` 等任何 token。
- 不碰视图内部的数据卡（保持 `bg-card` 白卡，浮在 `bg-sidebar` 卡面上即可）。

## Decisions

### D1：内容卡用 `bg-sidebar`，与左 Agent 面板对齐（而非 `bg-card`）

- 内容容器最终类：`relative mx-3 mb-3 flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-[var(--radius-lg)] border bg-sidebar shadow-lg`。
- **为何 `bg-sidebar` 而非 `bg-card`**：用户要内容卡底色和左对话面板一致。`agent-rail` 用 `bg-sidebar`，对齐后左右成「同款一对」；且 `bg-sidebar`（0.988）比纯白 `card`（1.0）略沉，内部白数据卡能浮起来，亮色不再白对白。
- **为何对齐 border/shadow-lg/radius-lg**：与左面板同规格 → 视觉上是并列的两张抬升卡，统一语汇。

### D2：方案 2（描边卡）而非方案 1（同材质连接）

- 方案 1（内容区 = 激活 tab 同款 `bg-muted` 连一坨）联动最强，但用户选了更克制的描边卡。
- 方案 2 + `bg-sidebar` 兼顾：既是独立成卡（清晰边界），又通过与左面板同材质获得整体感。

### D3：放弃早期「数据卡内部 surface-raised 浮块」方向

- 早期曾尝试给数据卡**内部**表格加 `--surface-raised` 浮块（新 token + `CardPanel` 组件）。该方向解决的是「卡内表格 vs 卡面」的层次，而非用户真实诉求（tab↔内容联动），且在 `bg-sidebar` 内容卡下形成「白叠白」冗余。**已整体回退**（token、组件、各视图套用全部还原），不进本变更。

## Risks / Trade-offs

- **联动强度弱于方案 1**（描边卡 vs 同材质连一坨）→ 用户已知并选定；如需更强可后续切方案 1。
- **内容卡与左面板顶部不在同一水平线**（左卡从 `p-3` 起、右内容卡在 tab 条之下）→ 属不同区域，可接受。

## Migration Plan

1. 改 `workspace.tsx` 内容容器 className。
2. `pnpm typecheck` + Browser Verification Gate（亮/暗两态）。
- **回滚**：容器 className 还原为透明即可，无数据/接口变更。
