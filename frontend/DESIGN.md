---
name: DataWeave
description: AI Agent 原生数据中台的设计系统
colors:
  background: "oklch(1 0 0)"
  foreground: "oklch(0.145 0 0)"
  card: "oklch(1 0 0)"
  cardForeground: "oklch(0.145 0 0)"
  popover: "oklch(1 0 0)"
  popoverForeground: "oklch(0.145 0 0)"
  primary: "oklch(0.205 0 0)"
  primaryForeground: "oklch(0.985 0 0)"
  secondary: "oklch(0.97 0 0)"
  secondaryForeground: "oklch(0.205 0 0)"
  muted: "oklch(0.97 0 0)"
  mutedForeground: "oklch(0.556 0 0)"
  accent: "oklch(0.97 0 0)"
  accentForeground: "oklch(0.205 0 0)"
  destructive: "oklch(0.577 0.245 27.325)"
  border: "oklch(0.922 0 0)"
  input: "oklch(0.922 0 0)"
  ring: "oklch(0.708 0 0)"
  link: "oklch(0.49 0.22 264)"
  success: "oklch(0.58 0.155 150)"
  warning: "oklch(0.62 0.14 78)"
  info: "oklch(0.55 0.18 250)"
  chart1: "oklch(0.646 0.222 41.116)"
  chart2: "oklch(0.6 0.118 184.704)"
  chart3: "oklch(0.398 0.07 227.392)"
  chart4: "oklch(0.828 0.189 84.429)"
  chart5: "oklch(0.769 0.188 70.08)"
colorsDark:
  background: "oklch(0.145 0 0)"
  foreground: "oklch(0.985 0 0)"
  card: "oklch(0.205 0 0)"
  cardForeground: "oklch(0.985 0 0)"
  popover: "oklch(0.205 0 0)"
  popoverForeground: "oklch(0.985 0 0)"
  primary: "oklch(0.922 0 0)"
  primaryForeground: "oklch(0.205 0 0)"
  secondary: "oklch(0.269 0 0)"
  secondaryForeground: "oklch(0.985 0 0)"
  muted: "oklch(0.269 0 0)"
  mutedForeground: "oklch(0.708 0 0)"
  accent: "oklch(0.269 0 0)"
  accentForeground: "oklch(0.985 0 0)"
  destructive: "oklch(0.704 0.191 22.216)"
  border: "oklch(1 0 0 / 10%)"
  input: "oklch(1 0 0 / 15%)"
  ring: "oklch(0.556 0 0)"
  link: "oklch(0.7 0.15 264)"
  success: "oklch(0.7 0.15 152)"
  warning: "oklch(0.78 0.14 80)"
  info: "oklch(0.7 0.14 250)"
  chart1: "oklch(0.488 0.243 264.376)"
  chart2: "oklch(0.696 0.17 162.48)"
  chart3: "oklch(0.769 0.188 70.08)"
  chart4: "oklch(0.627 0.265 303.9)"
  chart5: "oklch(0.645 0.246 16.439)"
rounded:
  base: "0.625rem"
typography:
  fontSans: "var(--font-sans)"
  fontHeading: "var(--font-sans)"
---

## Overview

DataWeave 的设计系统。本文件是**主题真相源**：颜色、圆角、字体的取值集中在上方 YAML tokens 中，配以下文的设计理据。运行 `npx @google/design.md lint DESIGN.md` 校验结构，`npx @google/design.md export` 可导出为 CSS 变量 / Tailwind config。

> 实际生效的 CSS 变量位于 `app/globals.css`。当前主题：**shadcn/ui 默认 neutral 黑白主题**。承载层（background / card / popover / muted / secondary / accent / border / sidebar）全部为**零彩度中性灰阶**，primary 为近黑（亮）/ 近白（暗）的单色按钮 —— 回归最本质的黑白观感，**不再为定制暖调主题做全局兼容适配**（自定义主题适配成本过高、得不偿失）。radius `0.625rem`，全局字体走无衬线（Inter）。Tailwind v4 通过 `@theme inline` 把这些变量映射为色板 token（`bg-primary`、`text-muted-foreground` 等）。语义状态色与 chart 五色作为**功能色**保留（见下）。

## 色彩 —— shadcn neutral

设计立场：**纯黑白、零装饰彩度，把视觉精力从"调主题"挪回"做功能"**。承载层取 shadcn 标准 neutral 取值，亮/暗对称：

- **承载层（中性灰阶）**：亮色为纯白底（`oklch(1 0 0)`）+ 近黑前景（`oklch(0.145 0 0)`）；暗色为近黑底 + 近白前景。card / popover / muted / secondary / accent / border / sidebar 全为零彩度灰阶，明度梯度区分层次，不引入任何色相。
- **primary / accent-foreground**：**单色** —— 亮色近黑（`oklch(0.205 0 0)`）、暗色近白（`oklch(0.922 0 0)`），承载主操作与选中态。这是 shadcn 黑白主题的标志性观感：黑底白字 / 白底黑字的按钮。
- **语义状态色（功能色，保留）**：success（绿）/ warning（金）/ info（蓝）/ destructive（红）编码任务状态四态 —— 成功·在线=success、运行中·诊断中=info、预警·偏旧=warning、失败·删除·阻断=destructive。这些是**功能信息**而非装饰，黑白主题同样需要可分辨的状态色（shadcn 本身也保留 destructive 红）。徽章统一「色字 + 同色淡底」形态（Badge 内置 `success`/`info`/`warning` 变体）。**状态语义禁止借用 primary**（primary 现为中性黑/白，不承载语义）。
- **link**：**钴蓝**（`oklch(0.49 0.22 264)`），承载链接、行内代码与"可点击/引用"的辅助强调 —— 中性黑无法表达可点击性，交给 link 蓝。
- **chart-1..5**：shadcn 标准五色，分类数据可视化需要互异色相，辨识度优先。单变量渐变场景另行从中性阶派生。
- **sidebar-***：侧边导航 token 与主区同为中性灰阶，仅明度略有差异（亮色 sidebar `0.985` 略深于页面 `1.0`）。

## 圆角

`--radius = 0.625rem`，shadcn 默认圆角；`globals.css` 据此派生 sm/md/lg/xl 等梯度。

## 字体

字体由 Next.js `next/font` 注入，统一走无衬线：

- **正文 / 标题：无衬线 Inter（`--font-sans`）**。`@theme inline` 把 `--font-heading` 也映射到 `--font-sans`，`--font-serif` CSS 变量同样指向 Inter —— 因此**散落各处的显式 `font-serif` / `font-heading` 用法统一渲染为 sans**，无需逐个改。不再使用衬线字体（已移除 Merriweather）。
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

CopilotKit v2 在自身作用域 `[data-copilotkit]` 内用一套**零彩度中性灰**重定义了整套 shadcn 变量。本应用已是 neutral 黑白主题、与 cpk 自带中性阶同家族，故 `app/globals.css` 仅做**最小对齐**：

- **对话面填充 `--background` / `--card` / `--popover` 对齐 `var(--sidebar)`**——让 Agent 悬浮面板的消息区与外层卡片、侧栏品牌区**同底色**，避免「标题条 sidebar 色、消息区却是页面底色」的割裂。其余语义 token 一律 `inherit` 跟随 app 主题。
- **发送按钮**对齐 `--primary`（cpk 烤死 `cpk:bg-black cpk:dark:bg-white`，覆盖为 token）；输入框小药丸用略深 `--muted` 底。
- ⚠️ dev 调试坑：globals.css 编译后的 CSS chunk 文件名**不带 hash**，浏览器会缓存旧版；改完用**硬刷新**（Ctrl+Shift+R / DevTools Disable cache）确认。

### AI 回复的 Markdown 排版

AI 回复用 **Streamdown**（react-markdown + Tailwind `prose`，CopilotKit 内置）渲染，元素带 `data-streamdown="…"`。在 `globals.css` 统一接管：

- **字体**：正文走 sans，代码（行内 + 代码块）走 `--font-mono`（Geist Mono）。
- **配色映射到主题 token**（改 `--tw-prose-*`，一套规则覆盖亮/暗）：正文/标题/加粗 = `--foreground`；**链接 / 行内代码 = `--link`（钴蓝）**；行内代码加 `--muted` 淡底；代码块底 = `--muted`。
- **代码块高亮**：chat 代码块与 **Monaco 编辑器**共用同一套 Shiki 主题对象（`lib/syntax-palette.ts`），两端高亮一致。`globals.css` 仅保留暗色 token 前景/底色切到 `--shiki-dark` / `--shiki-dark-bg` 的 swap 规则。

## 代码语法主题（编辑器 + chat 共用）

代码高亮有两个消费者 —— **Monaco 编辑器**（`tasks` 的 SQL 工作台）与 **chat 代码块**（Streamdown）。两者共用 **同一套 Shiki 主题对象**，因此高亮像素级一致。

- **真相源：`lib/syntax-palette.ts`（具体色，非 CSS 变量）**。`buildSyntaxTheme(mode)` 由调色板生成一套 Shiki 主题（VSCode 主题形态）：
  - Monaco 端 `lib/code-highlighter.ts` 起单例 highlighter → `shikiToMonaco` 接管 tokenizer（`components/code-editor.tsx`）。
  - chat 端把 `[light, dark]` 两套主题对象经 slot 透传给 Streamdown 的 Shiki dual-theme（`components/agent-chat.tsx`）。
- 调色板分两类槽：**结构槽**（`bg`/`fg`/`comment`/`operator`/`variable` 及 `editor.background`/`editor.foreground`/`editor.lineHighlightBackground`/括号高亮）跟随 `globals.css` 的**中性灰阶**（零彩度），与 app 灰主题无可见色温差，暗色底色凹陷一档于 `card`；**语义槽**（`keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp`）作为**功能性语法着色**保留彩色（标准编辑器即便在黑白 UI 主题下也彩色高亮），不随 UI 主题转黑白。亮/暗各一套，暗色语义色提亮避免刺眼。
- **刻意偏离（对早期设想）**：① 编辑器选 Monaco（非 CodeMirror）—— 要完整 IDE 体感；② 调色板放 TS 具体色（非 CSS 变量）—— Monaco 主题只吃具体十六进制色，且两个消费者都在 JS 侧，放 TS 让两端共用同一个 `buildSyntaxTheme()`。

## 统一标签条 —— `TabStrip`（Chrome 卡片风格）

项目所有 Tab（工作区主 tab 条 / 底部日志面板 / 侧面板 mini tab）统一走 `components/ui/tab-strip.tsx`：

- **Chrome 卡片观感**：激活态用内容面色（`surface` prop，默认 `sidebar`）+ **底角向外凸的弧度**（`.dw-tab-active` 伪元素 radial-gradient，见 `globals.css`）与下方内容连体；非激活相邻处显竖分隔线（任一激活/悬浮则隐藏）。
- **自动压缩、无滚动条**：标签 `flex 0 1 220px` + `min-w` + `overflow-hidden`，标签多了像 Chrome 一样不断压缩、不出横向滚动。
- **右键菜单**：关闭 / 关闭其他 / 关闭右侧 / 关闭左侧 / 关闭全部（+ 调用方注入项，如工作区的固定/取消固定）。
- **轨道色**：strip 用 `bg-foreground/[0.04]` 极淡叠加，比内容面深一丝以体现 Chrome 标签栏语义，亮/暗自适应。

## 滚动条

**设计立场：无箭头、细条浮叠、中性灰、亮/暗双态。** Windows Chrome 的原生 WebKit 滚动条箭头由系统绘制、CSS 无法可靠去除；统一用两套方案接管全部滚动区（基础设施已封装，保留沿用，仅颜色随主题改为中性灰）：

### 通用可滚动容器（OverlayScrollbars）

所有视图的滚动容器与表格水平滚动区、侧面板 tab 栏，用 **`overlayscrollbars` + `overlayscrollbars-react`**（`DwScroll` 组件封装）：

- **尺寸**：`--os-size: 4px`，padding 归零，thumb 全圆角（`9999px`）。
- **颜色**：亮色 `oklch(0.556 0 0 / 85%)`，悬浮 `oklch(0.556 0 0)`；暗色 `oklch(0.708 0 0 / 85%)`，悬浮 `oklch(0.708 0 0)`。均取自 `--muted-foreground` 中性灰，不引入色相。
- **主题**：基于 `os-theme-dark`（OS 内置），在 `globals.css` 用 `html .os-theme-dark`（特异度 0,1,1）覆盖变量。
- **封装**：`components/ui/dw-scroll.tsx`（`DwScroll`）。外层传 `className` 控尺寸，内层布局 class 传 `innerClassName`；方向默认垂直，水平传 `direction="horizontal"`。
- **不要用原生 `overflow-auto / overflow-x-auto`** 替代 `DwScroll`——会退回带箭头的原生滚动条。

### AI 输入框（自绘指示条）

`textarea` 被 CopilotKit 强制进入 WebKit 自定义滚动条模式。解法：① `scrollbar-width: none` + `::-webkit-scrollbar { display: none }` **完全隐藏**原生条；② `agent-chat.tsx` 用 JS 自绘 `.dw-textarea-thumb`（绝对定位 div）反映滚动位置——同款 4px 圆角中性灰条，`pointer-events: none`，仅做视觉提示。

### 颜色对照表

| 状态 | 亮色 | 暗色 |
|------|------|------|
| 默认 | `oklch(0.556 0 0 / 85%)` | `oklch(0.708 0 0 / 85%)` |
| hover | `oklch(0.556 0 0)` | `oklch(0.708 0 0)` |
| active | `oklch(0.45 0 0)` | `oklch(0.6 0 0)` |
