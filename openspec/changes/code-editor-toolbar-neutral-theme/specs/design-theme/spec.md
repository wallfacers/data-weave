## MODIFIED Requirements

### Requirement: 代码语法高亮与主题同源

Monaco 编辑器与 chat 代码块 SHALL 继续共用 `lib/syntax-palette.ts` 的同一套 `buildSyntaxTheme()` 主题对象，两端高亮像素级一致。调色板 SHALL 分两类槽处理：**结构槽**（`bg`/`fg`/`comment`/`operator`/`variable` 及 `editor.background`/`editor.foreground`/`editor.lineHighlightBackground`/括号高亮）SHALL 对齐 `globals.css` 的中性灰阶（零彩度），不再从茶墨暖色派生；**语义槽**（`keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp`）SHALL 保留彩色，作为功能性语法着色不随 UI 主题转黑白。亮/暗各一套，暗色语义色提亮避免刺眼，暗色底色凹陷一档于 `card`。

#### Scenario: 两端高亮一致且底色为中性灰
- **WHEN** 同一段 SQL 分别在 Monaco 编辑器与 chat 代码块中渲染
- **THEN** 两端 token 颜色一致，编辑器底色/前景/注释为中性灰阶（与 app 灰主题无可见色温差），keyword/string/number 等语义 token 仍呈彩色

#### Scenario: 结构 token 不再暖色
- **WHEN** 在亮色或暗色主题下查看编辑器
- **THEN** 底色不再是暖奶油 `#f8f5ef` / 暖褐黑 `#17130f`，注释与标点呈零彩度灰，行高亮为灰色微染而非茶墨微染
