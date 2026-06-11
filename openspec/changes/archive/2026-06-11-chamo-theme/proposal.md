## Why

当前浅色主题的灰雾问题有结构性根源：背景层（`background`/`sidebar`/`muted`/`border`）全部挤在 oklch 明度 0.93–1.0 的 7% 带内且 `card` 与 `background` 同为纯白（卡片在页面上不可见）；次级文字 `muted-foreground`（L 0.58 / C 0.031）是典型灰雾色；`border`（L 0.93）对白底对比度约 1.2:1 形同虚设。叠加低彩度 emerald primary，整体呈现「朦胧看不清」的 AI 工具脸。经探索与 HTML 实景对比（`tmp/theme-preview.html`，已验证四方向），选定 **「茶墨」方向**：暖纸白底 + 真黑墨字 + 褐调茶墨 primary + 钴蓝链接/副声部 + 多色织线图表 —— 印刷品级清晰、鲜艳用在信息刀刃上（状态/链接/图表）、红色语义彻底归位（只表失败/删除）。

## What Changes

- **重定义浅色主题全部颜色 token**（`frontend/app/globals.css` `:root`）：
  - 清晰脚手架：页面底改暖纸白（约 L 0.978，色相 ~80）、`card` 保持纯白形成可见层次；`border` 提深到约 L 0.875；`muted-foreground` 提深到约 L 0.45。
  - primary 从低彩 emerald 换为**茶墨**（褐调墨，约 `oklch(0.45 0.085 60)`），与暖纸同色温家族。
  - 新增一等公民语义色 token：`--link`（钴蓝，链接/行内代码/辅助强调）、`--success` / `--warning` / `--info`（任务状态徽章专用），`destructive` 调整为更深的红。
  - `chart-1..5` 从绿色单色渐变改为**五色织线**（茶·钴蓝·金·翠·紫），呼应「编织数据」品牌隐喻。
- **暗色主题同步换血**（`.dark`）：同色相家族派生（深茶墨底 + 提亮版茶墨 primary + 提亮钴蓝 link），避免亮暗两态精神分裂。
- **语法高亮调色板重新派生**（`lib/syntax-palette.ts`）：keyword 锚定色从 emerald 换为茶墨家族，func 用钴蓝，其余点缀色按新主题微调；Monaco 编辑器与 chat 代码块两端自动保持一致。
- **CopilotKit 对齐层跟随**：`globals.css` 中 CopilotKit 作用域规则以 `inherit`/`var()` 实现，token 换血后自动跟随；需更新写死的 `.dw-textarea-thumb` 滚动条颜色与注释中的 taupe/emerald 措辞。
- **DESIGN.md 真相源重写**：YAML tokens 与「色彩」章节按茶墨主题重写，记录设计理据与语义色使用规则（红色仅失败/删除）。
- 字体（Merriweather 衬线 + Inter + Geist Mono）、圆角（0.875rem）、无分割线布局**全部保留不动**。

## Capabilities

### New Capabilities

- `design-theme`: 「茶墨编辑部」主题 token 体系 —— 浅/暗两态颜色 token 取值与层次对比约束、语义色（link/success/warning/info/destructive）使用规则、五色织线图表色板、CopilotKit 作用域对齐、语法调色板派生关系。

### Modified Capabilities

（无 —— 现有 specs 均为行为规范，不含颜色取值要求；本变更不改任何交互行为。）

## Impact

- **仅前端**，无后端/协议改动：
  - `frontend/app/globals.css`：`:root` 与 `.dark` 全量 token、`@theme inline` 新增 `--color-link`/`--color-success`/`--color-warning`/`--color-info` 映射、`.dw-textarea-thumb` 写死色、CopilotKit 对齐层注释措辞。
  - `frontend/DESIGN.md`：YAML tokens + 色彩/CopilotKit 章节重写。
  - `frontend/lib/syntax-palette.ts`：LIGHT/DARK 调色板重新派生。
  - 业务组件零改动（已核实组件层全部使用语义 token，无写死灰色/绿色）；`chart-1..5` 当前无消费者，可安全重定义。
- **验证**：`pnpm typecheck` + `pnpm design:lint` + Browser Verification Gate（真浏览器跑 CopilotChat 对话、代码块高亮、亮暗切换）。
- **风险**：暗色同步为新增设计决策（用户原诉求为浅色），若暗色效果不及预期可单独回调；Streamdown/CopilotKit 内部个别写死样式（如输入药丸 `cpk:dark:bg-[#303030]`）维持现状不强行对齐。
