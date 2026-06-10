---
name: DataWeave
description: AI Agent 原生数据中台的设计系统
colors:
  background: "oklch(1 0 0)"
  foreground: "oklch(0.153 0.006 107.1)"
  card: "oklch(1 0 0)"
  cardForeground: "oklch(0.153 0.006 107.1)"
  popover: "oklch(1 0 0)"
  popoverForeground: "oklch(0.153 0.006 107.1)"
  primary: "oklch(0.508 0.118 165.612)"
  primaryForeground: "oklch(0.979 0.021 166.113)"
  secondary: "oklch(0.967 0.001 286.375)"
  secondaryForeground: "oklch(0.21 0.006 285.885)"
  muted: "oklch(0.966 0.005 106.5)"
  mutedForeground: "oklch(0.58 0.031 107.3)"
  accent: "oklch(0.508 0.118 165.612)"
  accentForeground: "oklch(0.979 0.021 166.113)"
  destructive: "oklch(0.577 0.245 27.325)"
  border: "oklch(0.93 0.007 106.5)"
  input: "oklch(0.93 0.007 106.5)"
  ring: "oklch(0.737 0.021 106.9)"
  chart1: "oklch(0.871 0.15 154.449)"
  chart2: "oklch(0.723 0.219 149.579)"
  chart3: "oklch(0.627 0.194 149.214)"
  chart4: "oklch(0.527 0.154 150.069)"
  chart5: "oklch(0.448 0.119 151.328)"
colorsDark:
  background: "oklch(0.153 0.006 107.1)"
  foreground: "oklch(0.988 0.003 106.5)"
  card: "oklch(0.228 0.013 107.4)"
  cardForeground: "oklch(0.988 0.003 106.5)"
  popover: "oklch(0.228 0.013 107.4)"
  popoverForeground: "oklch(0.988 0.003 106.5)"
  primary: "oklch(0.432 0.095 166.913)"
  primaryForeground: "oklch(0.979 0.021 166.113)"
  secondary: "oklch(0.274 0.006 286.033)"
  secondaryForeground: "oklch(0.985 0 0)"
  muted: "oklch(0.286 0.016 107.4)"
  mutedForeground: "oklch(0.737 0.021 106.9)"
  accent: "oklch(0.432 0.095 166.913)"
  accentForeground: "oklch(0.979 0.021 166.113)"
  destructive: "oklch(0.704 0.191 22.216)"
  border: "oklch(1 0 0 / 10%)"
  input: "oklch(1 0 0 / 15%)"
  ring: "oklch(0.58 0.031 107.3)"
rounded:
  base: "0.875rem"
typography:
  fontSans: "var(--font-sans)"
  fontHeading: "var(--font-serif)"
---

## Overview

DataWeave 的设计系统。本文件是**主题真相源**：颜色、圆角、字体的取值集中在上方 YAML tokens 中，配以下文的设计理据。运行 `npx @google/design.md lint DESIGN.md` 校验结构，`npx @google/design.md export` 可导出为 CSS 变量 / Tailwind config。

> 实际生效的 CSS 变量位于 `app/globals.css`，由 shadcn preset 生成。当前：中性色阶 **taupe**（近无彩中性灰，色相 ~106–107）+ **翠绿 primary（emerald，色相 ~166）** + **绿色 chart（色相 ~149–154）**，radius 0.875rem，正文走衬线（Merriweather）。Tailwind v4 通过 `@theme inline` 把这些变量映射为色板 token（`bg-primary`、`text-muted-foreground` 等）。换主题统一用 `pnpm dlx shadcn@latest apply --preset <code>`，再把上方 tokens 与本文同步。

## 色彩

- **primary**：翠绿/emerald（`oklch(0.508 0.118 166)`，色相 ~166），承载品牌与主操作（发送、上线、确认）。暗色下降明度，避免刺眼。
- **中性色阶：taupe（近无彩中性灰，色相 ~106–107，极低饱和）**。亮色近纯白底 + 近黑前景；暗色为中性深灰底 + 近白前景。冷感极弱的中性灰与翠绿 primary、绿色 chart 搭配，整体沉稳，契合数据中台长时间注视的低疲劳诉求。
- **muted**：极低饱和的中性灰，用于次级信息、占位、hover 面，不与 primary 抢视觉。
- **accent**：与 primary 同为翠绿（emerald），用于强调态/选中态，构成与品牌一致的高亮语汇。
- **destructive**：红色，仅用于删除/阻断/失败态（如质量阻断、任务失败）。
- **chart-1..5**：绿色系渐进色阶（色相 ~149→154，由亮到深），给指标趋势、血缘图、运行统计等可视化使用，保证同一图表内色相一致、可区分。与 primary 同绿色家族，构成统一的绿色数据语汇。
- **sidebar-***：侧边导航独立一套 token，使中台左栏与主区在亮/暗下都有清晰层次。

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

CopilotKit v2 在自身作用域 `[data-copilotkit]` 内用一套**零彩度中性灰**重定义了整套 shadcn 变量（`--background`/`--muted`/…）。若放任不管，暗色下对话面板会是冷中性黑，与本应用 taupe 主题不一致（面板像一块"冷黑矩形"嵌在暖黑 app 里）。

- 修复在 `app/globals.css`：把这些变量改回继承/对齐 app 主题，让 CopilotChat **跟随 app 主题**（文字、边框、primary 全部跟随）。其中**对话面填充 `--background` / `--card` / `--popover` 对齐 `var(--sidebar)`**——让 Agent 悬浮面板的消息区与外层卡片、侧栏品牌区**同底色**，避免「标题条 sidebar 色、消息区却是页面底色」的割裂。
- 选择器分两态：亮色用 `html [data-copilotkit]`（0,1,1）胜过 cpk base `[data-copilotkit]`（0,1,0）；暗色用 `html.dark [data-copilotkit]`（0,2,1）胜过 cpk 的 `.dark [data-copilotkit]`（0,2,0）。
- Agent 面板（`agent-rail.tsx` 展开态）外层卡片用 `bg-sidebar` + `border` + `shadow-lg` 构成悬浮抬升层，故对话面填充也对齐 sidebar 以保持一体。
- 输入框小药丸 `.copilotKitInput` 用的是写死的 arbitrary class（`cpk:dark:bg-[#303030]`，不读变量），保留为抬升输入区，未强行对齐。
- ⚠️ dev 调试坑：globals.css 编译后的 CSS chunk 文件名**不带 hash**，浏览器会缓存旧版；改完用**硬刷新**（Ctrl+Shift+R / DevTools Disable cache）确认，**无需清 `.next`**。

### AI 回复的 Markdown 排版

AI 回复用 **Streamdown**（react-markdown + Tailwind `prose`，CopilotKit 内置，无需额外装库）渲染，元素带 `data-streamdown="…"`。默认是系统字体 + 冷灰阶配色，不跟随项目，已在 `globals.css` 统一接管：

- **字体跟随项目**：正文/标题/列表/表格走衬线 `--font-serif`（Merriweather），代码（行内 + 代码块）走 `--font-mono`（Geist Mono）。
- **配色映射到主题 token**（改 `--tw-prose-*`，一套规则覆盖亮/暗，因 token 本身随模式切）：正文/标题/加粗 = `--foreground`；**链接 / 行内代码 = `--primary`（emerald）**；行内代码加 `--muted` 淡底；代码块底 = `--muted`（替掉默认纯黑）；列表点/引用/分隔线/表格边框 = `--muted-foreground` / `--border`。
- 选择器用 `html [data-copilotkit] [class*="prose"]`（特异度高于 `.prose` / `.dark .prose-invert`）。
- **代码块高亮（项目语法主题，emerald/taupe 派生）**：chat 代码块与 **Monaco 编辑器**共用同一套 Shiki 主题对象，两端高亮**像素级一致**。详见下「代码语法主题」一节。chat 端经 `agent-chat.tsx` 的 `messageView → assistantMessage → markdownRenderer` slot 把主题透传给 Streamdown 的 Shiki dual-theme；`globals.css` 仅保留暗色 token 前景/底色切到 `--shiki-dark` / `--shiki-dark-bg` 的 swap 规则。
- 已知小 artifact：Streamdown 的 `parseIncompleteMarkdown` 会对 `count(*)` 这类裸 `*` 误判为未闭合强调、流式补一个 `*`（cosmetic）。后端 `IntentRouter` 兜底文案的反引号转义 bug 已修（`\\\`` → `` ` ``）。

## 代码语法主题（编辑器 + chat 共用）

代码高亮有两个消费者 —— **Monaco 编辑器**（`tasks` 的 SQL 工作台）与 **chat 代码块**（Streamdown）。两者共用 **同一套 Shiki 主题对象**，因此高亮像素级一致。

- **真相源：`lib/syntax-palette.ts`（具体色，非 CSS 变量）**。`buildSyntaxTheme(mode)` 由调色板生成一套 Shiki 主题（VSCode 主题形态）：
  - Monaco 端 `lib/code-highlighter.ts` 起单例 highlighter → `shikiToMonaco` 接管 tokenizer（`components/code-editor.tsx`）。
  - chat 端把 `[light, dark]` 两套主题对象经 slot 透传给 Streamdown 的 Shiki dual-theme（`components/agent-chat.tsx`）。
- **调色板（从 emerald primary + taupe 中性阶派生）**：`keyword` 用品牌 **emerald**（最高频 token，锚定主题身份），辅以 `blue`(func) / `amber`(string) / `cyan`(number) / `teal`(type) / `violet`(constant) / `rose`(regexp) 低饱和点缀；`comment` / `operator` 走 taupe 灰阶。亮/暗各一套，暗色提亮避免刺眼。具体取值见 `LIGHT` / `DARK`。
- Monaco 默认的「彩虹括号」配色（`editorBracketHighlight.*`）在主题 `colors` 里统一压成 operator 灰，与 emerald 主题一致。
- **刻意偏离（对早期设想）**，理由如下：
  1. **编辑器选 Monaco（非 CodeMirror）** —— 要完整 IDE 体感。
  2. **调色板放 TS 具体色（非 `css-variables` CSS 变量）** —— Monaco 主题只吃具体十六进制色（吃不了 `var(--x)`），且两个消费者都在 JS 侧；放 TS 让两端共用同一个 `buildSyntaxTheme()`，是单一真相源 + 像素级一致的最稳形态。`globals.css` 仅保留 chat 暗色的 `--shiki-dark*` swap（Shiki dual-theme 的 baked 机制需要）。
