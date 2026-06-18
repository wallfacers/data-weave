# design-theme Specification

## Purpose

定义 DataWeave「茶墨编辑部」主题的颜色 token 体系与使用约束：浅/暗两态的层次对比、茶墨 primary 与钴蓝 link 的语义分工、一等公民状态色、五色织线图表色板、CopilotKit 对话面板对齐、代码语法高亮派生，以及 DESIGN.md 真相源同步。token 实际取值以 `frontend/app/globals.css` 为准，设计理据见 `frontend/DESIGN.md`。

## Requirements

### Requirement: 茶墨浅色主题的层次与可读性

浅色主题 SHALL 采用「茶墨编辑部」体系：暖纸白页面底（色相 ~80 的微着色，非纯白）、暖白卡片、真黑墨字。三个承载层 token SHALL 明度可辨区分：`sidebar`（Agent 左栏，最深，≈0.948）< `background`（页面底，≈0.978）< `card`（暖白 ≈0.99，浮于页面之上）—— 左栏、tab/页面、内容卡片三层一眼可分。卡片与弹层 SHALL NOT 用纯白 `oklch(1 0 0)`（暖纸底上刺眼），SHALL 保持与页面底同色相的暖调。`border` 明度 SHALL ≤0.88，`muted-foreground` 明度 SHALL ≤0.46，确保分隔线可见、次级文字清晰可读。

#### Scenario: 左栏与内容区层次可辨

- **WHEN** 浅色模式下同屏呈现左侧 Agent 对话栏与右侧 Workspace 内容面板
- **THEN** 左栏底色（`--sidebar` ≈0.948）明显深于页面底（≈0.978）与内容卡片（暖白 ≈0.99），三层无需边框即可区分

#### Scenario: 卡片与页面有可见层次

- **WHEN** 浅色模式下渲染任意带 `bg-card` 的卡片于页面背景上
- **THEN** 卡片（暖白 `oklch(0.99 0.004 80)`）与页面底（暖纸白 `oklch(0.978 0.006 80)`）呈现可感知的明度差且同为暖调不刺眼，卡片边界无需依赖阴影即可辨认

#### Scenario: 次级文字脱离灰雾区

- **WHEN** 浅色模式下渲染使用 `text-muted-foreground` 的次级文字（时间戳、描述、占位符）
- **THEN** 其颜色明度 ≤0.46（取值 `oklch(0.45 0.02 80)`），与纸白底对比清晰可读

### Requirement: 茶墨 primary 与钴蓝 link 的语义分工

主题 SHALL 以茶墨（`oklch(0.45 0.085 60)`，褐调墨）为 `primary`/`accent`，承载主操作与选中态；SHALL 提供独立的 `--link` token（钴蓝 `oklch(0.49 0.22 264)`）承载链接、行内代码与辅助强调。`primary` SHALL NOT 用于红色系，红色 SHALL 仅由 `destructive` 承载并仅用于失败/删除/阻断语义。

#### Scenario: 主按钮呈茶墨

- **WHEN** 浅色模式下渲染 `bg-primary` 主按钮
- **THEN** 按钮为茶墨实底、纸白文字，无红色观感

#### Scenario: 对话中的链接与行内代码呈钴蓝

- **WHEN** Agent 回复的 Markdown 含链接或行内代码
- **THEN** 两者渲染为钴蓝（`--link`），而非 primary 茶墨

### Requirement: 一等公民状态色

主题 SHALL 定义 `--success`（绿）、`--warning`（金，浅色取值压深至 L ≤0.62 保证小字可读）、`--info`（蓝）三个状态 token，并与 `--link` 一起经 `@theme inline` 映射为 Tailwind 色板 token（`text-success`、`bg-warning/10` 等可用）。任务运行状态的视觉编码 SHALL 为：成功=success、运行中=info、预警=warning、失败=destructive，四态色相互异。状态徽章 SHALL NOT 借用 `primary` 表达成功/在线/运行中等状态语义（primary 仅承载品牌与操作）。

#### Scenario: 状态徽章四色分明

- **WHEN** 任务流/驾驶舱/数据新鲜度等视图渲染「成功/在线/新鲜」「运行中/诊断中」「偏旧/预警」「失败/陈旧」徽章
- **THEN** 分别呈现 success 绿 / info 蓝 / warning 金 / destructive 红的「淡底+色字」徽章，无 primary 茶墨实底黑块

#### Scenario: 状态 utility 可用

- **WHEN** 组件使用 `text-link` / `text-success` / `text-warning` / `text-info` 类
- **THEN** Tailwind 解析出对应 token 颜色，`pnpm typecheck` 与构建均通过

### Requirement: 五色织线图表色板

`chart-1..5` SHALL 为五个互异色相的「织线」色板：茶、钴蓝、金、翠、紫（取值见 `frontend/app/globals.css`），用于分类数据可视化；SHALL NOT 再为绿色单色渐变。

#### Scenario: 相邻类别可区分

- **WHEN** 图表以 chart-1..5 渲染五个分类系列
- **THEN** 任意相邻两色色相差异肉眼可辨（非同色系深浅）

### Requirement: 暗色主题同家族派生

暗色主题 SHALL 与浅色同属暖色相家族（中性阶色相 ~65–80）：深茶墨底、驼色 primary（`oklch(0.72 0.10 70)`）、提亮钴蓝 link，状态色与织线整体提亮。亮暗切换 SHALL 不出现色相家族跳变（如暖底切冷灰底）。

#### Scenario: 亮暗切换气质连续

- **WHEN** 用户切换浅色/暗色模式
- **THEN** 页面底、卡片、primary、link 均保持各自色相家族，仅明度/彩度按暗色取值变化

### Requirement: CopilotKit 对话面板跟随主题

CopilotChat 面板（`[data-copilotkit]` 作用域）SHALL 继续经 CSS 变量继承跟随 app 主题：对话面填充对齐 `--sidebar`、文字/边框/primary 随主题 token；自绘滚动条 `.dw-textarea-thumb` SHALL 使用茶墨家族暖灰（亮 `oklch(0.45 0.02 80)` / 暗 `oklch(0.74 0.02 75)`），不残留旧 taupe 取值。CopilotKit 烤死颜色的元素（如发送按钮 `cpk:bg-black`）SHALL 以选择器覆盖对齐主题 token。

#### Scenario: 对话面板无冷灰残留

- **WHEN** 打开左栏 Agent 对话并收到含链接、行内代码、代码块的流式回复
- **THEN** 面板底色为暖色 sidebar、正文衬线、链接/行内代码钴蓝、无 CopilotKit 默认零彩度冷灰可见

#### Scenario: 发送按钮跟随 primary

- **WHEN** 输入框有内容、发送按钮处于可用态
- **THEN** 发送按钮为 `--primary` 实底（浅色茶墨/暗色驼色）而非 CopilotKit 默认的纯黑/纯白

### Requirement: 代码语法高亮与主题同源

Monaco 编辑器与 chat 代码块 SHALL 继续共用 `lib/syntax-palette.ts` 的同一套 `buildSyntaxTheme()` 主题对象，两端高亮像素级一致。调色板 SHALL 分两类槽处理：**结构槽**（`bg`/`fg`/`comment`/`operator`/`variable` 及 `editor.background`/`editor.foreground`/`editor.lineHighlightBackground`/括号高亮）SHALL 对齐 `globals.css` 的中性灰阶（零彩度），不再从茶墨暖色派生；**语义槽**（`keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp`）SHALL 保留彩色，作为功能性语法着色不随 UI 主题转黑白。亮/暗各一套，暗色语义色提亮避免刺眼，暗色底色凹陷一档于 `card`。

#### Scenario: 两端高亮一致且底色为中性灰

- **WHEN** 同一段 SQL 分别在 Monaco 编辑器与 chat 代码块中渲染
- **THEN** 两端 token 颜色一致，编辑器底色/前景/注释为中性灰阶（与 app 灰主题无可见色温差），keyword/string/number 等语义 token 仍呈彩色

#### Scenario: 结构 token 不再暖色

- **WHEN** 在亮色或暗色主题下查看编辑器
- **THEN** 底色不再是暖奶油 `#f8f5ef` / 暖褐黑 `#17130f`，注释与标点呈零彩度灰，行高亮为灰色微染而非茶墨微染

### Requirement: 设计真相源同步

`frontend/DESIGN.md` 的 YAML tokens 与色彩章节 SHALL 与 `app/globals.css` 实际取值保持一致（含新增 link/success/warning/info），并 SHALL 通过 `pnpm design:lint` 校验；主题维护方式 SHALL 为「YAML tokens 手工维护，与 globals.css 双向同步」。

#### Scenario: lint 通过且取值一致

- **WHEN** 运行 `pnpm design:lint`
- **THEN** 校验通过，且 DESIGN.md YAML 中的颜色取值与 globals.css `:root`/`.dark` 一致
