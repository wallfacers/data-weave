## 1. 调色板归灰（design-theme）

- [x] 1.1 读 `frontend/DESIGN.md`「代码语法主题」节，确认采用「结构槽归灰 + 语义槽留色」约束（设计契约 gate）
- [x] 1.2 把 `globals.css` 中性灰锚点（亮 bg≈oklch(0.985 0 0)/fg≈oklch(0.205 0 0)/comment≈oklch(0.556 0 0)；暗 bg≈oklch(0.18 0 0)/fg≈oklch(0.93 0 0)/comment≈oklch(0.62 0 0)）换算为 hex
- [x] 1.3 改 `lib/syntax-palette.ts` 的 LIGHT/DARK：结构槽 `bg`/`fg`/`comment`/`operator`/`variable` 归零彩度灰；语义槽 `keyword`/`string`/`number`/`func`/`type`/`constant`/`regexp` 保留彩色
- [x] 1.4 改 `buildSyntaxTheme()` 的 `editor.background`/`editor.foreground`/`editor.lineHighlightBackground`（茶墨微染→灰色微染）/括号高亮，使其取自归灰后的结构槽
- [x] 1.5 更新 `syntax-palette.ts` 顶部注释：「茶墨派生」→「中性灰底/结构 token + 功能性彩色语义 token」
- [x] 1.6 同步 `frontend/DESIGN.md`「代码语法主题」节描述，跑 `pnpm design:lint` 通过

## 2. 通用编辑器工具栏（code-editor）

- [x] 2.1 `CodeEditorProps` 增 `toolbar?: boolean`（默认 false），并加 `onMount` 存 editor 实例 ref
- [x] 2.2 工具栏容器渲染在编辑器上方，按钮用 hugeicons + base `Button size="sm" variant="ghost"`（前端栈 gate）
- [x] 2.3 接「格式化」`editor.action.formatDocument`、「查找」`actions.find`；formatter 不存在的语言（如 bash）按钮置灰
- [x] 2.4 接「清空」`setValue('')` + 二次确认 + onChange 回流
- [x] 2.5 默认（不开 toolbar）渲染与现状一致，chat/内联用法零回归

## 3. 剪贴板操作与降级（code-editor）

- [x] 3.1 「复制全部」走 `navigator.clipboard.writeText(getValue())` + 成功 toast，失败 toast 提示 Ctrl+C
- [x] 3.2 「粘贴」走 `readText()` → `executeEdits` 插光标处；try/catch 失败 toast「浏览器拦截了读取剪贴板，请用 Ctrl+V」
- [x] 3.3 能力探测 `navigator.clipboard`：不存在则复制/粘贴按钮 `disabled` + tooltip
- [x] 3.4 `readOnly` 时仅渲染 复制 / 查找，隐藏 粘贴 / 格式化 / 清空

## 4. 落点接线（TaskEditorPane）

- [x] 4.1 `task-editor-pane.tsx` 的 `<CodeEditor>` 开启 `toolbar`，与既有 运行/保存/发布 工具栏不冲突
- [x] 4.2 确认运行日志、参数预览等既有能力不受影响

## 5. 测试与验证门

- [x] 5.1 `cd frontend && pnpm typecheck` 零类型错误
- [x] 5.2 为工具栏/剪贴板降级补 vitest（复制成功、粘贴失败降级提示、readOnly 收敛、能力探测置灰）
- [x] 5.3 Browser Verification Gate（已验证）：亮/暗两态像素级确认——编辑器底色中性灰（亮 `#fafafa` / 暗 `#121212`，无暖色偏）、语义 token 仍彩色（keyword 亮 `#864e18` 茶墨 / 暗 `#dfad6d` 驼金）、五组操作渲染且 SQL 格式化正确置灰、console 无新增 error（仅既有 401 鉴权，注入 JWT 后消除）
- [ ] 5.3b 补验（浏览器会话中断未跑到）：① chat 代码块随共用调色板一并归灰（需触发含代码块的 Agent 回复）；② 复制成功 toast 与粘贴被拦截降级 toast 的点击实测（决策逻辑已被 5.2 单测覆盖）
- [x] 5.4 验证产物（截图/trace）写 `tmp/`，验证完清理
