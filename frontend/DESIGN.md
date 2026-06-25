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
- **对话紧致节奏**：Streamdown 的 prose 是长文基线（16px / 行高 1.75、段落上下各 20px，再叠 `.space-y-4` 16px → 段间约 36px），窄对话栏偏松。`globals.css` 把它收成对话节奏（**仅排版量，零颜色改动**）：正文 **13.5px / 行高 1.6**；段落与块级元素**只留下间距 ~10px、清零上间距**（消除 prose margin 与 `space-y-4` 的双重叠加），容器**首/末子元素纵向 margin 清零**；列表项 ~2px、`ul/ol` 左缩进 1rem；标题 h1/h2/h3 = 15/14/13px、字重 600；代码块顶栏 padding 收至 6/12px、表格单元格 6×10px / 12.5px。
  - ⚠️ **间距必须用元素规则直接覆盖**（作用域 `[data-copilotkit] [class*="prose"] <元素>`）：Tailwind 的间距是硬编码进 `prose` 元素规则的，**不走** `--tw-prose-*` 变量（该组变量只控制颜色）。
- **代码块/表格做减法**：Streamdown 默认渲染顶栏（语言标签 + 复制按钮，套 `bg-muted` / `border` token）。对话场景**隐藏下载按钮、保留复制**——Streamdown `controls.code/table` 是整条顶栏开关、无「留复制/关下载」子粒度，故经 `globals.css` 对 `[data-streamdown="code-block-download-button"]` 与表格 `button[title="Download table"]` 置 `display:none`（不动 `agent-chat.tsx` 的 `controls`）。

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

## 数据表格 —— `DataTable`

**设计立场：全项目结构化列表表格只有一种长相。** 任何「管理/监控列表」一律用 `components/ui/data-table.tsx`（`DataTable<T>`）+ `data-table-toolbar.tsx`（`DataTableToolbar`）渲染，禁止再手写 `<table>` 或散落 `TableHead/TableCell` 拼版式。标杆为周期实例面板，规范即其沉淀。

### 版式（三段式固定布局）

- **筛选工具栏 `shrink-0`** → **表格区 `flex-1`** → **分页 `shrink-0`**，三段各自固定，绝不「整个 Tab 一起滚」。
- **固定表头**：表头与数据行拆为两张 `<table>`，**共享同一份 `colgroup` + `table-fixed`**（列宽严格对齐）；表头表在 `DwScroll` 外固定不滚，**仅数据行区域用 `DwScroll`（`direction="vertical"`）纵向滚动**——滚动条只落在数据区，不覆盖表头、不在表头右上缺角。
- 列宽用 `colgroup` 百分比（`ColumnDef.widthPct`），加总≈100；不用逐列 `w-*` 硬编码。
- 单元格：数字/时间列 `tabular-nums text-xs`，长文本 `truncate` + `title`，状态用 `Badge`，操作列 `text-right`。行 hover 用基础 `TableRow` 的 `hover:bg-muted/50`，无斑马纹。

### 筛选工具栏（`DataTableToolbar`）

- **统一筛选词汇（`FilterDef.kind`）**：`search`（防抖多字段文本，左置放大镜）/ `segmented`（二元·小枚举段控，含「全部」）/ `multiSelect`（枚举多选，portal 下拉）/ `dateRange`（双 `DatePicker` 区间）/ `toggle`（布尔快捷 chip，如「只看我的」）。不暴露无使用价值的维度（手机号、精确到秒的时间戳等）。
- **主/高级分层**：`tier:"primary"` 常驻（建议 ≤4 条），`tier:"advanced"` 收进「更多筛选」portal 弹层（带激活计数角标）。工具栏不拥挤、不光秃。
- **激活计数 + 一键清空**：有激活筛选时显示「清空 {n}」。
- **语义化快捷预设**：`FilterPreset` chips，一次点击设好一组组合回答真实问题（「今天失败」「我的草稿」「连接异常」「部分补数失败」）；预设是真实开关，非装饰。
- **克制**：小表（如角色，常 <10 行）只给一个搜索框，不堆下拉。

### 数据获取（双模式）

- `DataTable` 同时支持 `mode="client" | "server"`。**列与筛选声明（`ColumnDef`/`FilterDef`）两模式完全一致**，仅取数方式不同。
- **server 模式**：把 `{filters,page,size}` 交给 `fetcher` 拼后端 query 真实查询（`lib/data-table.ts` 的 `toQueryParams`），不在前端缓存全量再筛选——**禁止 client 端全量兜底假筛选**。
- **client 模式**：仅用于前端已持有的派生小数据，走 `applyClientFilters` + `paginate` 纯函数。
- **智能默认**：每表声明默认筛选值，打开即停在最该看的一屏（实例=今天+未成功优先，新鲜度=最陈旧在前），而非空白全量。

### 其它

- **多选批量**：`selectable` + `bulkActions(selectedIds, reload)`；批量写须按返回 `outcome`（EXECUTED/PENDING_APPROVAL/REJECTED）三态分流，绝不以 `code===0` 判成功。
- **空/加载态**：组件内置——加载显 `Loading`（无 `…`），空显图标 + 标题（`emptyTitle`）+ 提示（`emptyHint`）。
- **i18n**：组件 chrome 文案走 `dataTable` 命名空间；列头、筛选标签、预设名、空提示由调用方传**已翻译**字符串（按其所在 view 的命名空间）。
- **基础件来源**：`Pagination`（`components/ui/pagination.tsx`，分页 chrome 走 `ops` 命名空间）、`DwScroll`、`Checkbox`、`DatePicker`、`Badge` 均复用既有 ui 件，不另造。
