## Why

左栏 Agent 主驾的 AI 回复直接继承 CopilotKit 内置 Streamdown 的 Tailwind `prose` 默认排版——这是为「长文阅读」调的基线（实测：正文 16px / 行高 1.75、段落上下各 20px margin，再叠加块级 `space-y-4` 16px，段间实际空白可达 ~36px）。塞进窄对话栏后字大、行松、段落之间留白过多，观感「松垮、不够紧致」。本次把 AI 消息排版从「文档节奏」调成「对话节奏」，仅改中性的排版量（字号 / 行高 / 间距 / padding），不触碰任何颜色与主题 token。

## What Changes

- 在 `globals.css` 为 AI 回复 Markdown 容器（`[data-copilotkit] [class*="prose"]`）新增一组**紧致垂直节奏**覆盖：正文字号 16px→13.5px、行高 1.75→1.6；段落 margin 由「上下各 20px」改为「上 0 / 下 ~8px」并清零首/末子元素 margin；块级间距与段间距协调（约 10px），消除双重叠加；列表项贴紧（~2px）、列表左缩进收敛；标题按字号比例下调（h1/h2/h3 ≈ 15/14/13px，字重 600）。
- 代码块**已合规**（Streamdown 默认渲染 `rounded-xl border-border` 容器 + `bg-muted/80` 顶栏含语言标签与复制按钮，自动套用主题 token）——本次仅做减法微调：经 `markdownRenderer` slot 的 `controls` 配置**隐藏下载按钮**（对话场景下载 SQL/表格片段无意义，保留复制），并略收顶栏 padding。
- 表格收紧单元格 padding 与字号，与对话节奏一致；同样隐藏浮动下载按钮。
- DESIGN.md「AI 回复的 Markdown 排版」节**补一条「对话紧致节奏」子条款**，并注明间距须以 `[data-copilotkit] [class*="prose"] <元素>` 直接覆盖元素规则（Tailwind 间距硬编码，**不走** `--tw-prose-*` 变量，后者仅管颜色）。

非目标（明确不做）：不改任何颜色 / 主题 token（neutral 黑白主题不可动）；不替换 Streamdown 渲染器；不引入新依赖；不改 AG-UI 事件协议。

## Capabilities

### New Capabilities
<!-- 无新增能力 -->

### Modified Capabilities

- `copilot-rail`: 「直连后端 AG-UI 流式回复」需求下，AI 回复的 Markdown 渲染从继承的「长文 prose 基线」改为「对话紧致节奏」——新增对字号 / 行高 / 段落与块间距 / 列表 / 标题 / 代码块顶栏的可验证排版约束。仅排版量变化，渲染管线（CopilotKit v2 + Streamdown + AG-UI 序列）不变。

## Impact

- **代码**：`frontend/app/globals.css`（新增紧致节奏 CSS 覆盖，沿用现有 `[data-copilotkit] [class*="prose"]` 作用域）；`frontend/components/agent-chat.tsx`（`messageView.assistantMessage.markdownRenderer` 经直通链向 Streamdown 传 `controls` 以隐藏下载按钮）。
- **文档**：`frontend/DESIGN.md`（设计契约真相源，补「对话紧致节奏」子条款）。
- **不影响**：主题 token / 颜色、AG-UI 协议与事件序列、后端、其它视图。
- **验证**：Browser Verification Gate——复用探索阶段的 Playwright 实测脚本（登录 admin/admin → 发「查看 orders 表数据」触发代码块+表格+段落），对比改动前后字号/段距与截图；console 无 error、消息能流式收发。
