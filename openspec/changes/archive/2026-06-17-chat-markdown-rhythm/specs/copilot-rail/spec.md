## ADDED Requirements

### Requirement: AI 回复 Markdown 对话紧致排版

左栏 Agent 主驾渲染 AI 回复 Markdown 时，SHALL 采用适配窄对话栏的**紧致垂直节奏**，而非继承的长文 `prose` 基线。正文字号 SHALL 收敛至约 13–13.5px、行高约 1.6；段落 SHALL 只保留下间距（约 8px）且不保留上间距，容器首/末子元素的纵向 margin SHALL 清零；块级元素间距 SHALL 与段落间距协调，不得与段落 margin 叠加产生过大空白；列表项 SHALL 紧凑排列、列表保留适度左缩进；标题 SHALL 按正文字号比例下调（不喧宾夺主）。所有调整 SHALL 仅改排版量（字号 / 行高 / margin / padding / radius），**MUST NOT** 修改任何颜色或主题 token——neutral 黑白主题保持不变。间距覆盖 SHALL 以元素规则直接覆盖（作用域 `[data-copilotkit] [class*="prose"]`），不得依赖 `--tw-prose-*` 变量（该组变量仅控制颜色）。

#### Scenario: AI 回复段落紧致呈现

- **WHEN** 用户发送消息并收到含多段落的 Markdown 回复
- **THEN** 正文字号约 13–13.5px、行高约 1.6，相邻段落间距明显小于继承的长文基线（约 8–10px，而非 ~20px 叠加 ~36px），气泡顶/底无多余纵向留白

#### Scenario: 排版调整不改变主题配色

- **WHEN** 应用紧致排版后在亮色与暗色主题下查看 AI 回复
- **THEN** 正文/标题/链接/行内代码/代码块的颜色与改动前一致（仍走既有主题 token），仅字号、行高与间距变紧致

### Requirement: AI 回复代码块与表格对话化精简

AI 回复中的代码块 SHALL 沿用 Streamdown 默认的容器（圆角 + `border` + 顶栏含语言标签与复制按钮，均套用主题 token），并 SHALL 隐藏对话场景无意义的下载按钮（保留复制）；顶栏 padding SHALL 适度收敛。表格 SHALL 收紧单元格 padding 与字号以匹配对话节奏，并同样隐藏浮动下载按钮。

#### Scenario: 代码块保留复制、隐藏下载

- **WHEN** AI 回复渲染出一个带语言标识的代码块
- **THEN** 代码块顶栏显示语言标签与复制按钮、可一键复制，且不显示下载按钮

#### Scenario: 表格紧致且无下载入口

- **WHEN** AI 回复渲染出 Markdown 表格
- **THEN** 表格单元格内边距与字号与对话节奏一致（较默认更紧），且不显示浮动下载按钮
