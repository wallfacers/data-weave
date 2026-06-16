## Why

数据开发 IDE 的代码编辑器（`components/code-editor.tsx`，Monaco + Shiki）当前底色派生自一套已废弃的「茶墨」暖色主题（亮底 `#f8f5ef` 暖奶油 / 暗底 `#17130f` 暖褐黑），而 app 主题（`globals.css`）早已切到**纯中性灰**（零彩度 `oklch(… 0 0)`）。两者并排有可见色温差——编辑器"看着不是项目的灰"。同时编辑器只暴露键盘级复制/粘贴，缺少可发现的显式操作入口，且浏览器剪贴板权限失败时无优雅降级。本变更把编辑器底色拉回中性灰、补一套可选操作工具栏，让代码编辑器在视觉与交互上都融入数据开发场景。

## What Changes

- **语法调色板归灰**：`lib/syntax-palette.ts` 的**结构槽**（`bg`/`fg`/`comment`/`operator`/`variable` + `editor.background`/`editor.foreground`/`editor.lineHighlightBackground`/括号高亮）从暖「茶墨」色改为对齐 `globals.css` 的中性灰阶；**语义槽**（`keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp`）保留彩色，符合 `DESIGN.md`「功能性语法着色保留彩色 token，不随 UI 主题转黑白」。编辑器与 chat 代码块共用同一调色板，**一并归灰**（保持两端像素级一致）。
- **通用编辑器工具栏（opt-in）**：`components/code-editor.tsx` 新增可选工具栏，提供五组操作——**复制全部**、**粘贴**、**格式化**、**清空**、**查找**。默认关闭（`toolbar` prop 开启），不影响 chat 内联等既有用法。`TaskEditorPane` 复用并开启。
- **剪贴板权限优雅降级**：复制走 `navigator.clipboard.writeText`；粘贴走 `readText()` 写入光标处，失败（无权限/非安全上下文/浏览器禁读）则 toast 提示改用 `Ctrl+V`，编辑器原生快捷键始终兜底。`navigator.clipboard` 不可用时复制/粘贴按钮置灰带 tooltip，不让用户点了才发现失败。
- **readOnly 收敛**：只读编辑器仅保留 复制 / 查找，隐藏 粘贴 / 格式化 / 清空。
- **真相源同步**：更新 `DESIGN.md`「代码语法主题」一节与 `syntax-palette.ts` 顶部注释，把「茶墨派生」改述为「中性灰底/结构 token + 功能性彩色语义 token」。

## Capabilities

### New Capabilities
- `code-editor`: 复用型代码编辑器组件的行为契约——Monaco + Shiki 主题接管、opt-in 操作工具栏（复制/粘贴/格式化/清空/查找）、剪贴板权限失败的优雅降级、readOnly 操作收敛。

### Modified Capabilities
- `design-theme`: 「代码语法高亮与主题同源」需求——调色板的底色/前景/结构 token 改为对齐中性灰 UI（不再从茶墨派生），语义 token 保留彩色；两端共用且一致的约束不变。

## Impact

- **前端代码**：`frontend/components/code-editor.tsx`（新增工具栏 + 剪贴板降级）、`frontend/lib/syntax-palette.ts`（结构槽归灰）、`frontend/components/workspace/task-editor-pane.tsx`（开启工具栏）。
- **真相源文档**：`frontend/DESIGN.md`「代码语法主题」节。
- **连带影响**：chat 代码块（Streamdown）底色随调色板一并变灰——预期内的一致性收敛，非回归。
- **验证门**：属主题/视觉改动，须走 **Browser Verification Gate**——真在浏览器验证编辑器与 chat 代码块两端高亮、工具栏操作、剪贴板降级提示；`pnpm design:lint` 通过。
- **既有漂移（不在本变更范围）**：`design-theme` spec 整体仍描述「茶墨暖色主题」，而 `globals.css` 已是纯中性灰——此漂移早于本变更存在，本变更仅收敛其中「代码语法高亮」一条，不重写整张 spec。
