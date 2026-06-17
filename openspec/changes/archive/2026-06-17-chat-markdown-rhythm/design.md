## Context

左栏 Agent 主驾用 CopilotKit v2 的 `CopilotChat` 渲染 AI 回复；内部经 `messageView.assistantMessage.markdownRenderer` slot 链到 **Streamdown**（Vercel `streamdown@1.6.11`，react-markdown + Tailwind `prose` + Shiki）。当前 `agent-chat.tsx` 只经该 slot 透传了 `shikiTheme`。

浏览器实测（登录 admin/admin → 发「查看 orders 表数据」触发 text-to-SQL，返回段落 + ```sql 代码块 + markdown 表格）测得真实计算样式：

| 元素 | 实测当前值 | 评估 |
|------|-----------|------|
| prose 容器 | `font-size:16px; line-height:28px(1.75)` | 窄栏偏大、偏松 |
| 段落 `p` | `16px/1.75; margin:20px 0` | **松垮主因** |
| 块级容器 | `space-y-4`（16px，给子元素加 margin-top） | 与 p 的 20px **双重叠加** → 段间 ~36px |
| 代码块 wrapper | `data-streamdown="code-block"`：`my-4 rounded-xl border border-border overflow-hidden` | 已合规 |
| 代码块顶栏 | `data-streamdown="code-block-header"`：`flex justify-between bg-muted/80 p-3 text-muted-foreground text-xs`，含 `<span lowercase>sql</span>` + 下载按钮 + 复制按钮 | 已合规，多一个下载按钮 |
| 代码块正文 | `data-streamdown="code-block-body"`（`<pre>`）：`14px/24px; padding:12px 16px; border-t border-border` + 行号 counter | 可接受 |
| 表格 | `14px`，上方浮动复制/下载工具栏 | cell 偏大、下载按钮多余 |

关键发现：**代码块顶栏（语言标签 + 复制按钮 + neutral token）是 Streamdown 默认就渲染好的**，无需自造。真正待修的只有「prose 长文节奏」。

约束（硬性）：
- **neutral 黑白主题不可改**——本变更只动排版量（字号/行高/margin/padding/radius），零颜色 / 零 token 改动。
- **Design Contract Gate**：DESIGN.md 是设计真相源，改前已读；本次为「补新条款」而非偏离（现有「AI 回复 Markdown 排版」节只规定颜色，未规定节奏）。
- **Browser Verification Gate**：build 通过 ≠ 渲染正确，完成后须真在浏览器验证。

## Goals / Non-Goals

**Goals:**
- AI 回复 Markdown 在窄对话栏内呈现「紧致对话节奏」：字号、行高、段落/块间距、列表、标题按比例收敛，消除段间双重叠加空白。
- 代码块/表格做减法：隐藏对话场景无意义的下载按钮（保留复制），顶栏 padding 略收。
- 把节奏约束沉淀进 DESIGN.md，并写明「间距走元素直接覆盖、不走 `--tw-prose-*`」这一易踩坑点。

**Non-Goals:**
- 不改任何颜色 / 主题 token / 暗色映射（已对齐，保持）。
- 不替换 Streamdown，不自写 marked/morphdom 渲染器。
- 不引入新依赖、不改 AG-UI 协议与事件序列、不改后端。
- 不改用户消息气泡形态（仅在节奏字号上与助手协调，不重构其布局）。

## Decisions

### 决策 1：用 CSS 元素覆盖调间距，而非 `--tw-prose-*` 变量

Tailwind Typography 把**颜色**做成 `--tw-prose-*` 变量（项目已用于配色映射），但**间距/字号是硬编码**在 `.prose :where(p){margin-top:1.25em…}` 规则里，无对应变量。因此节奏覆盖只能用更高特异度直接打元素：

```
html [data-copilotkit] [class*="prose"]            { font-size; line-height }
html [data-copilotkit] [class*="prose"] p          { margin: 0 0 .5rem }
html [data-copilotkit] [class*="prose"] > :first-child { margin-top: 0 }
html [data-copilotkit] [class*="prose"] > :last-child  { margin-bottom: 0 }
html [data-copilotkit] [class*="prose"] ul,ol,li,h1..h3, …
```

沿用现有 globals.css 里同款 `[data-copilotkit] [class*="prose"]` 作用域，与既有配色规则同段维护。**备选**：给容器加 `prose-sm` 修饰类——被否，因 CopilotKit 硬编码 `cpk:prose` 基类，无注入点，且 `prose-sm` 数值不一定贴合目标。

### 决策 2：隐藏下载按钮经 Streamdown `controls`，不靠 CSS 隐藏

CopilotKit 默认 `MarkdownRenderer = ({content,className,...props}) => <Streamdown {...props}/>`，slot 是到 Streamdown props 的**直通管道**（`shikiTheme` 已验证可达）。Streamdown 的 `controls: boolean | {table?, code?, mermaid?}` 控制内置工具栏。直接经 `markdownRenderer` 配置关掉下载、保留复制最干净。**备选**：CSS `display:none` 打 `[data-streamdown="code-block-download-button"]`——作为兜底（若该版本 `controls` 粒度不支持单独关下载按钮，则退回 CSS 隐藏）。Apply 阶段先验证 Streamdown 1.6.11 的 `controls` 粒度，据此二选一。

### 决策 3：目标数值（中性量，可在浏览器微调）

| 元素 | 目标 | 借鉴/依据 |
|------|------|-----------|
| prose font-size | 13.5px | workhorse 12.5px（桌面），浏览器取 13.5 更稳 |
| prose line-height | 1.6 | workhorse 1.625 |
| `p` margin | `0 0 0.5rem`（下 8px、上 0） | workhorse 节奏；配合首/末清零 |
| 块间距 space-y | ~10px（与 p 协调，避免叠加） | — |
| `li` margin | ~2px；`ul/ol` padding-left 1rem | workhorse |
| 标题 h1/h2/h3 | 15 / 14 / 13px，字重 600 | 按本项目字号比例缩放，非硬抄暖色系数值 |
| 代码块顶栏 padding | `p-3`→略收（~6–8px） | 可选微调 |
| 表格 cell padding | 收紧至 ~6×10px，字号 ~12.5px | workhorse |

数值为基线；Apply 的 Browser Verification 环节据实测截图微调后定稿，并回写 DESIGN.md。

## Risks / Trade-offs

- [字号过小伤可读] 13.5px 在浏览器栏宽下若偏小 → 浏览器实测对比后微调（13–13.5），以实际渲染而非桌面应用数值为准。
- [Streamdown/CopilotKit 升级回归] 选择器依赖 `[data-copilotkit]`、`[class*="prose"]`、`data-streamdown="…"` 等内部标记，升级可能变 → 覆盖规则集中在 globals.css 同一段并加注释；升级时随 Browser Gate 复验。
- [`controls` 粒度不足] 若 1.6.11 不支持单独关下载按钮 → 退回 CSS 隐藏 `[data-streamdown="code-block-download-button"]`（决策 2 兜底）。
- [双重叠加未清干净] p margin 与 `space-y-4` 同时作用，只调一处仍松 → 两处都纳入覆盖，实测验证段间最终值。
- [改 globals.css 的 HMR 陷阱] 改后可能命中 Turbopack 全局 CSS HMR 卡死 / CSS chunk 无 hash 缓存 → 清 `.next` 重启 + 硬刷新验证（见项目既有记录）。
