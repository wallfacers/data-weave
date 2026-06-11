---
name: DataWeave
description: AI Agent 原生数据中台的设计系统
colors:
  background: "oklch(0.978 0.006 80)"
  foreground: "oklch(0.17 0.012 80)"
  card: "oklch(0.99 0.004 80)"
  cardForeground: "oklch(0.17 0.012 80)"
  popover: "oklch(0.99 0.004 80)"
  popoverForeground: "oklch(0.17 0.012 80)"
  primary: "oklch(0.45 0.085 60)"
  primaryForeground: "oklch(0.985 0.006 80)"
  secondary: "oklch(0.945 0.009 80)"
  secondaryForeground: "oklch(0.25 0.015 80)"
  muted: "oklch(0.945 0.009 80)"
  mutedForeground: "oklch(0.45 0.02 80)"
  accent: "oklch(0.45 0.085 60)"
  accentForeground: "oklch(0.985 0.006 80)"
  destructive: "oklch(0.55 0.22 25)"
  border: "oklch(0.875 0.014 80)"
  input: "oklch(0.875 0.014 80)"
  ring: "oklch(0.62 0.07 65)"
  link: "oklch(0.49 0.22 264)"
  success: "oklch(0.58 0.155 150)"
  warning: "oklch(0.62 0.14 78)"
  info: "oklch(0.55 0.18 250)"
  chart1: "oklch(0.55 0.12 60)"
  chart2: "oklch(0.49 0.24 264)"
  chart3: "oklch(0.76 0.15 85)"
  chart4: "oklch(0.6 0.155 150)"
  chart5: "oklch(0.55 0.18 300)"
colorsDark:
  background: "oklch(0.16 0.008 65)"
  foreground: "oklch(0.97 0.006 80)"
  card: "oklch(0.215 0.012 65)"
  cardForeground: "oklch(0.97 0.006 80)"
  popover: "oklch(0.215 0.012 65)"
  popoverForeground: "oklch(0.97 0.006 80)"
  primary: "oklch(0.72 0.1 70)"
  primaryForeground: "oklch(0.2 0.012 65)"
  secondary: "oklch(0.27 0.012 65)"
  secondaryForeground: "oklch(0.97 0.006 80)"
  muted: "oklch(0.26 0.012 65)"
  mutedForeground: "oklch(0.74 0.02 75)"
  accent: "oklch(0.6 0.09 65)"
  accentForeground: "oklch(0.985 0.006 80)"
  destructive: "oklch(0.68 0.18 25)"
  border: "oklch(0.95 0.02 80 / 14%)"
  input: "oklch(0.95 0.02 80 / 16%)"
  ring: "oklch(0.62 0.08 65)"
  link: "oklch(0.7 0.15 264)"
  success: "oklch(0.7 0.15 152)"
  warning: "oklch(0.78 0.14 80)"
  info: "oklch(0.7 0.14 250)"
  chart1: "oklch(0.72 0.1 70)"
  chart2: "oklch(0.68 0.16 264)"
  chart3: "oklch(0.8 0.14 85)"
  chart4: "oklch(0.7 0.14 152)"
  chart5: "oklch(0.68 0.15 300)"
rounded:
  base: "0.875rem"
typography:
  fontSans: "var(--font-sans)"
  fontHeading: "var(--font-serif)"
---

## Overview

DataWeave 的设计系统。本文件是**主题真相源**：颜色、圆角、字体的取值集中在上方 YAML tokens 中，配以下文的设计理据。运行 `npx @google/design.md lint DESIGN.md` 校验结构，`npx @google/design.md export` 可导出为 CSS 变量 / Tailwind config。

> 实际生效的 CSS 变量位于 `app/globals.css`。当前主题：**「茶墨编辑部」**（openspec: chamo-theme）—— 暖纸白底（色相 ~80）+ 真黑墨字 + **茶墨 primary（褐调墨，色相 ~60）** + **钴蓝 link（色相 ~264）** + **五色织线 chart**，radius 0.875rem，正文走衬线（Merriweather）。Tailwind v4 通过 `@theme inline` 把这些变量映射为色板 token（`bg-primary`、`text-link`、`text-success` 等）。主题为定制设计（无 shadcn preset）：**YAML tokens 手工维护，与 `globals.css` 双向同步**，改色两边一起改并跑 `pnpm design:lint`。

## 色彩 ——「茶墨编辑部」

设计立场：**印刷品级清晰，拒绝灰雾与 AI 味**。旧主题的病根（背景层挤在 L 0.93–1.0、card 与 background 同为纯白、muted-foreground L 0.58、border L 0.93 形同虚设）在本主题中以三条硬约束根治：① 三个承载层明度一眼可分 —— sidebar（Agent 左栏 0.948，最深）< 页面底（暖纸 0.978）< card（暖白 0.99，浮于页面；**刻意不用纯白** —— 纯白在暖纸上刺眼，用户反馈后压回暖调）；② `muted-foreground` L ≤0.46；③ `border` L ≤0.88（看得见的线）。

- **primary / accent**：**茶墨**（褐调墨，`oklch(0.45 0.085 60)`），与暖纸同色温家族，承载主操作与选中态（发送、上线、确认）。暗色态提亮为**驼色**（`oklch(0.72 0.1 70)`），深底上的奶咖色按钮。**primary 永不红色** —— 红色语义独占给 destructive。
- **中性色阶：暖纸（色相 ~80，亮）/ 深茶墨（色相 ~65，暗）**。亮色为微着色暖纸底 + 真黑墨字（L 0.17）；暗色为深茶墨底 + 暖白前景。亮暗同一暖色相家族，切换不发生气质跳变。
- **link（新增一等公民）**：**钴蓝**（`oklch(0.49 0.22 264)`），承载链接、行内代码与辅助强调 —— 茶墨偏沉，"可点击/引用"的提神职能交给钴蓝。页面活跃度不足时优先扩大 link 的使用面，而非提高 primary 彩度。
- **success / warning / info（新增一等公民）**：任务状态四态编码 —— 成功/在线/新鲜=success（绿）、运行中/诊断中=info（蓝）、预警/偏旧=warning（金，浅色已压深至 L 0.62 保证小字可读）、失败/陈旧=destructive。徽章统一「色字 + 同色 10–15% 淡底」形态（Badge 已内置 `success`/`info`/`warning` 变体）。**状态语义禁止借用 primary**（旧主题 primary=绿曾被当成功色用，茶墨下会成黑块）；`Badge variant="default"`（茶墨实底）只用于品牌/计数等非状态场景。
- **destructive**：深红（`oklch(0.55 0.22 25)`），**仅**用于删除/阻断/失败态。
- **chart-1..5**：**五色织线**（茶 · 钴蓝 · 金 · 翠 · 紫）—— DataWeave「编织数据」的品牌隐喻，分类数据五根纱线互异色相，辨识度优于旧绿色单色渐变。单变量渐变场景另行从茶墨家族派生，不复用 chart token。
- **sidebar-***：侧边导航独立一套 token（暖纸系），使中台左栏与主区在亮/暗下都有清晰层次。

## 圆角

`--radius = 0.875rem`，适中偏大圆角，干净利落的中台观感；`globals.css` 据此派生 sm/md/lg/xl 等梯度。

## 字体

字体由 Next.js `next/font` 注入，三套：

- **正文 / 标题：衬线 Merriweather（`--font-serif`）**。`<html>` 默认挂 `font-serif`，`@theme inline` 把 `--font-heading` 也映射到 `--font-serif`，因此正文与标题统一走衬线，给数据中台一点编辑式的稳重质感。
- **`--font-sans`：Inter**，需要无衬线时显式用 `font-sans`（如高密度表格/数字）。
- **`--font-mono`：Geist Mono**，代码、SQL、ID 等等宽场景。

## 布局：无分割线（项目偏好）

页面采用 **header / content(body) / footer** 结构。**header 与 content、content 与 footer 之间不放任何分割线**——不要 `border-b` / `border-t` / 横向 `<Separator>` / `<hr>`。靠留白（padding/间距）和背景层次区分区域，不靠线。

- ❌ `<header className="... border-b ...">`、footer 上的 `border-t`、区域之间的横向 `Separator`。
- ✅ header 直接是 `flex h-14 items-center gap-2 px-4`（无边框），content 紧随其后。
- 此规则适用于所有页面与 shell，新增布局一律遵守。

## 用法约束（与 shadcn 规则一致）

- 用语义 token，不写裸色值：`bg-primary` 而非 `bg-[#...]`。
- 暗色靠 `.dark` 下的变量覆盖，不手写 `dark:` 颜色覆盖。
- 间距用 `gap-*`，等宽高用 `size-*`。

## CopilotKit 主题对齐（`/agent` 对话）

CopilotKit v2 在自身作用域 `[data-copilotkit]` 内用一套**零彩度中性灰**重定义了整套 shadcn 变量（`--background`/`--muted`/…）。若放任不管，暗色下对话面板会是冷中性黑，与本应用茶墨暖调主题不一致（面板像一块"冷黑矩形"嵌在暖黑 app 里）。

- 修复在 `app/globals.css`：把这些变量改回继承/对齐 app 主题，让 CopilotChat **跟随 app 主题**（文字、边框、primary 全部跟随；浅色块同样补全了 inherit 系列 —— cpk 浅色自带零彩度近黑 primary，否则发送按钮是黑圆而非茶墨）。其中**对话面填充 `--background` / `--card` / `--popover` 对齐 `var(--sidebar)`**——让 Agent 悬浮面板的消息区与外层卡片、侧栏品牌区**同底色**，避免「标题条 sidebar 色、消息区却是页面底色」的割裂。**`--muted` 在 cpk 作用域给显式深一档值（0.915）而非 inherit**：sidebar 加深至 0.948 后与 app muted（0.945）几乎同明度，输入药丸/行内代码底若 inherit 会融进面板。
- 选择器分两态：亮色用 `html [data-copilotkit]`（0,1,1）胜过 cpk base `[data-copilotkit]`（0,1,0）；暗色用 `html.dark [data-copilotkit]`（0,2,1）胜过 cpk 的 `.dark [data-copilotkit]`（0,2,0）。
- Agent 面板（`agent-rail.tsx` 展开态）外层卡片用 `bg-sidebar` + `border` + `shadow-lg` 构成悬浮抬升层，故对话面填充也对齐 sidebar 以保持一体。
- 输入框小药丸 `.copilotKitInput` 用的是写死的 arbitrary class（`cpk:dark:bg-[#303030]`，不读变量），保留为抬升输入区，未强行对齐。**发送按钮则强行对齐了**：cpk 烤死 `cpk:bg-black cpk:dark:bg-white`（亮色纯黑圆钮，与茶墨割裂），`globals.css` 以 `[data-testid="copilot-send-button"]` 覆盖为 `--primary` 实底 + `--primary-foreground` 图标，禁用态对齐 `--muted`。
- ⚠️ dev 调试坑：globals.css 编译后的 CSS chunk 文件名**不带 hash**，浏览器会缓存旧版；改完用**硬刷新**（Ctrl+Shift+R / DevTools Disable cache）确认，**无需清 `.next`**。

### AI 回复的 Markdown 排版

AI 回复用 **Streamdown**（react-markdown + Tailwind `prose`，CopilotKit 内置，无需额外装库）渲染，元素带 `data-streamdown="…"`。默认是系统字体 + 冷灰阶配色，不跟随项目，已在 `globals.css` 统一接管：

- **字体跟随项目**：正文/标题/列表/表格走衬线 `--font-serif`（Merriweather），代码（行内 + 代码块）走 `--font-mono`（Geist Mono）。
- **配色映射到主题 token**（改 `--tw-prose-*`，一套规则覆盖亮/暗，因 token 本身随模式切）：正文/标题/加粗 = `--foreground`；**链接 / 行内代码 = `--link`（钴蓝）**；行内代码加 `--muted` 淡底；代码块底 = `--muted`（替掉默认纯黑）；列表点/引用/分隔线/表格边框 = `--muted-foreground` / `--border`。
- 选择器用 `html [data-copilotkit] [class*="prose"]`（特异度高于 `.prose` / `.dark .prose-invert`）。
- **代码块高亮（项目语法主题，茶墨/钴蓝派生）**：chat 代码块与 **Monaco 编辑器**共用同一套 Shiki 主题对象，两端高亮**像素级一致**。详见下「代码语法主题」一节。chat 端经 `agent-chat.tsx` 的 `messageView → assistantMessage → markdownRenderer` slot 把主题透传给 Streamdown 的 Shiki dual-theme；`globals.css` 仅保留暗色 token 前景/底色切到 `--shiki-dark` / `--shiki-dark-bg` 的 swap 规则。
- 已知小 artifact：Streamdown 的 `parseIncompleteMarkdown` 会对 `count(*)` 这类裸 `*` 误判为未闭合强调、流式补一个 `*`（cosmetic）。后端 `IntentRouter` 兜底文案的反引号转义 bug 已修（`\\\`` → `` ` ``）。

## 代码语法主题（编辑器 + chat 共用）

代码高亮有两个消费者 —— **Monaco 编辑器**（`tasks` 的 SQL 工作台）与 **chat 代码块**（Streamdown）。两者共用 **同一套 Shiki 主题对象**，因此高亮像素级一致。

- **真相源：`lib/syntax-palette.ts`（具体色，非 CSS 变量）**。`buildSyntaxTheme(mode)` 由调色板生成一套 Shiki 主题（VSCode 主题形态）：
  - Monaco 端 `lib/code-highlighter.ts` 起单例 highlighter → `shikiToMonaco` 接管 tokenizer（`components/code-editor.tsx`）。
  - chat 端把 `[light, dark]` 两套主题对象经 slot 透传给 Streamdown 的 Shiki dual-theme（`components/agent-chat.tsx`）。
- **调色板（从茶墨 primary + 暖纸中性阶派生）**：`keyword` 用品牌**茶墨**（最高频 token，锚定主题身份；暗色提亮为驼金），`func` 用**钴蓝**（与 `--link` 同家族），辅以金(string) / 青蓝(number) / 深青(type) / 紫(constant) / 玫红(regexp) 点缀；`comment` / `operator` 走暖灰阶。亮/暗各一套，暗色提亮避免刺眼。具体取值见 `LIGHT` / `DARK`（oklch 目标值注释在行尾）。
- Monaco 默认的「彩虹括号」配色（`editorBracketHighlight.*`）在主题 `colors` 里统一压成 operator 灰，与茶墨主题一致。
- **刻意偏离（对早期设想）**，理由如下：
  1. **编辑器选 Monaco（非 CodeMirror）** —— 要完整 IDE 体感。
  2. **调色板放 TS 具体色（非 `css-variables` CSS 变量）** —— Monaco 主题只吃具体十六进制色（吃不了 `var(--x)`），且两个消费者都在 JS 侧；放 TS 让两端共用同一个 `buildSyntaxTheme()`，是单一真相源 + 像素级一致的最稳形态。`globals.css` 仅保留 chat 暗色的 `--shiki-dark*` swap（Shiki dual-theme 的 baked 机制需要）。

## 滚动条

**设计立场：无箭头、细条浮叠、暖灰色、亮/暗双态。** Windows Chrome 的原生 WebKit 滚动条箭头（倒三角）由系统绘制，CSS 无法可靠去除；统一用两套方案接管全部滚动区：

### 通用可滚动容器（OverlayScrollbars）

所有视图的滚动容器（任务流、驾驶舱、数据新鲜度、失败诊断、业务报表等）以及表格水平滚动区、侧面板 tab 栏，改用 **`overlayscrollbars` + `overlayscrollbars-react`**（`OverlayScrollbarsComponent` / `DwScroll` 组件封装）：

- **尺寸**：`--os-size: 4px`（与 AI 输入框指示条等宽），padding 归零，thumb 全圆角（`9999px`）。
- **颜色**：亮色 `oklch(0.45 0.02 80 / 85%)`，悬浮 `oklch(0.45 0.02 80)`；暗色 `oklch(0.74 0.02 75 / 85%)`，悬浮 `oklch(0.74 0.02 75)`。均取自 `--muted-foreground` 暖灰家族，不独立引入新色相。
- **主题**：基于 `os-theme-dark`（OS 内置，有完整尺寸/定位 CSS），在 `globals.css` 用 `html .os-theme-dark`（特异度 0,1,1）覆盖变量，确保 OS 组件内懒加载的样式表（0,1,0）不会把颜色倒回默认黑色。
- **封装**：`components/ui/dw-scroll.tsx`（`DwScroll`）。用法：外层传 `className` 控制尺寸（`flex-1` / `min-h-0`），内层布局 class 传 `innerClassName`；方向默认垂直，水平传 `direction="horizontal"`。
- **不要用原生 `overflow-auto / overflow-x-auto`** 替代 `DwScroll`——会退回带箭头的原生滚动条。

### AI 输入框（自绘指示条）

`textarea` 被 CopilotKit 强制进入 WebKit 自定义滚动条模式，同样无法用 CSS 去除箭头，且该模式下 `OverlayScrollbarsComponent` 不适用（输入框行为特殊）。解法：

1. `scrollbar-width: none` + `::-webkit-scrollbar { display: none }` **完全隐藏**原生滚动条（`globals.css`）。
2. `agent-chat.tsx` 用 JS 自绘 `.dw-textarea-thumb`（绝对定位 div）反映滚动位置——同款 4px 圆角暖灰条，`pointer-events: none`，仅做视觉提示，不可拖拽。

### 颜色对照表

| 状态 | 亮色 | 暗色 |
|------|------|------|
| 默认 | `oklch(0.45 0.02 80 / 85%)` | `oklch(0.74 0.02 75 / 85%)` |
| hover | `oklch(0.45 0.02 80)` | `oklch(0.74 0.02 75)` |
| active | `oklch(0.38 0.02 80)` | `oklch(0.62 0.02 75)` |
