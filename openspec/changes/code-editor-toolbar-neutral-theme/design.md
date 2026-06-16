## Context

数据开发 IDE 的代码编辑器 `components/code-editor.tsx` 是 Monaco + Shiki 的复用封装，主题随 `next-themes` 切换，被 `task-editor-pane.tsx`（任务编辑子 Tab）与 chat 代码块（经 `CHAT_SHIKI_THEME`）共用。调色板真相源 `lib/syntax-palette.ts` 自带两套 `Palette`（LIGHT/DARK），从一套已废弃的「茶墨」暖色主题派生——而 app 主题 `globals.css` 早已切到纯中性灰（零彩度 `oklch(… 0 0)`，注释明言「回归最本质的黑白观感」）。`DESIGN.md`「代码语法主题」节已规定「功能性语法着色保留彩色 token，不随 UI 主题转黑白」——本变更与该既定方向一致，纠正的是 palette 底色相对中性 UI 的漂移。

约束：① 调色板被编辑器与 chat 双消费者共用，改一处两端都变；② Monaco 主题只吃具体十六进制色（不吃 `var(--x)`），故 palette 仍须留在 TS 具体色；③ 浏览器剪贴板读权限不可靠（非安全上下文 / Firefox 默认禁读 / 无用户手势）；④ 前端栈 gate（CopilotKit v2、base 风格 `render` prop、hugeicons）与设计契约 gate（先读 DESIGN.md、改主题同步 DESIGN.md + design:lint）。

## Goals / Non-Goals

**Goals:**
- 编辑器底色/前景/结构 token 对齐中性灰 UI，与 app 主题无可见色温差。
- 语义 token 保留彩色，代码可读性不退化。
- 编辑器获得可发现的显式操作（复制/粘贴/格式化/清空/查找），且粘贴对浏览器剪贴板权限失败优雅降级。
- 工具栏做进通用 `CodeEditor`，opt-in，老用法零回归。

**Non-Goals:**
- 不重写整张 `design-theme` spec（其「茶墨暖色」整体描述与 globals.css 的漂移是既有问题，本变更只收敛「代码语法高亮」一条）。
- 不改 app 主题 token（globals.css 已是目标中性灰，本变更只让 palette 跟上）。
- 不引入新的剪贴板/编辑器第三方依赖（复用 Monaco 原生 action + 浏览器 `navigator.clipboard`）。
- 不为 chat 代码块单独开一套底色（一并归灰，维持两端一致）。

## Decisions

**D1：结构槽归灰、语义槽留色（而非整套灰阶或维持暖色）。**
Palette 12 槽分两类：结构槽 `bg`/`fg`/`comment`/`operator`/`variable` + `editor.*` 系列改为零彩度灰，锚定 globals.css（亮 `bg≈oklch(0.985 0 0)`、`fg≈oklch(0.205 0 0)`、`comment≈oklch(0.556 0 0)`；暗 `bg≈oklch(0.18 0 0)` 凹一档于 card 0.205、`fg≈oklch(0.93 0 0)`、`comment≈oklch(0.62 0 0)`），换算为 hex 写入 LIGHT/DARK。语义槽 `keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp` 维持现彩色。备选「整套灰阶」被否：牺牲语法可读性且违背 DESIGN.md 第 132 行。行高亮 `editor.lineHighlightBackground` 由茶墨微染改为灰色微染。

**D2：工具栏做进 `CodeEditor`、opt-in（而非新建独立组件或改 TaskEditorPane 私有实现）。**
`CodeEditorProps` 增 `toolbar?: boolean`（或 `actions` 细粒度开关），默认 `false`。工具栏渲染在编辑器容器上方，按钮经 base `Button size="sm" variant="ghost"` + hugeicons。理由：用户选「通用 CodeEditor 工具栏」，任何编辑器消费者都能复用；chat 内联用法默认不开，零回归。

**D3：操作映射——本地能力走 Monaco action，剪贴板走浏览器 API。**
- 格式化：`editor.getAction('editor.action.formatDocument')?.run()`（SQL/JSON 有内建 formatter）。
- 查找：`editor.getAction('actions.find')?.run()`（弹原生面板）。
- 清空：`editor.setValue('')` + 二次确认（避免误清），经 onChange 回流。
- 复制：`navigator.clipboard.writeText(editor.getValue())` + 成功 toast。
- 粘贴：`navigator.clipboard.readText()` → `editor.executeEdits` 插入光标处。
需在 mount 时拿到 editor 实例（`onMount` 回调存 ref）。

**D4：剪贴板降级策略——能力探测 + 失败兜底（而非假定一定可用）。**
渲染时探测 `typeof navigator !== 'undefined' && navigator.clipboard`：不存在则复制/粘贴按钮 `disabled` + tooltip。存在时点击仍可能抛（读权限尤甚），故 `readText()` 包 try/catch，catch 内 toast「浏览器拦截了读取剪贴板，请用 Ctrl+V」。编辑器原生 Ctrl+C/V 不经 API，永远兜底。理由：用户明确点出「浏览器权限不一定生效」，降级是核心需求而非边角。

**D5：真相源同步顺序——先读 DESIGN.md，改 palette 同步 DESIGN.md「代码语法主题」+ syntax-palette.ts 注释，跑 design:lint。**
遵守设计契约 gate。DESIGN.md 第 132 行已支持「保留彩色 token」，故主要补一句「底色/结构 token 跟随中性灰 UI」。

## Risks / Trade-offs

- **[chat 代码块底色一并变灰，可能有人预期不到]** → 这是预期内一致性收敛（DESIGN.md 要求两端像素级一致）；proposal/Impact 已记，Browser Verification Gate 两端都验。
- **[暗色底 `oklch(0.18 0 0)` 与彩色语义 token 对比度可能不足]** → 暗色语义槽本就提亮版，验证时实跑确认 keyword/string 在新灰底上清晰；不足则微调语义槽明度（仍属语义槽，不影响 D1 结构归灰）。
- **[`navigator.clipboard.readText` 在部分浏览器即便有按钮也总失败]** → 正是 D4 要解决的；按钮失败即提示 Ctrl+V，不承诺粘贴按钮一定成功。
- **[design:lint 因 palette 改动报不一致]** → palette 在 TS 具体色、不在 globals.css token，lint 校验的是 DESIGN.md↔globals.css；syntax 章节为叙述性，需手工对齐文字，跑 lint 确认通过。
- **[Monaco formatter 对 bash/shell 无内建支持]** → 格式化按钮对无 formatter 的语言应优雅无操作或置灰（探测 `getAction` 是否存在）。

## Migration Plan

纯前端、无数据/接口变更，无需迁移。回滚 = 还原 `syntax-palette.ts` / `code-editor.tsx` / `task-editor-pane.tsx` / `DESIGN.md` 改动。部署前过 `pnpm typecheck` + `pnpm design:lint` + Browser Verification Gate。

## Open Questions

- 工具栏开关粒度：单一 `toolbar?: boolean` 够用，还是需要 `actions?: ('copy'|'paste'|'format'|'clear'|'find')[]` 细粒度（便于不同消费者裁剪）？倾向先上 `toolbar?: boolean`，按需再细化。
- 字号调节（A±）是否纳入本次工具栏？探索阶段画过但用户未勾选，暂列为后续可选，不在本变更范围。
