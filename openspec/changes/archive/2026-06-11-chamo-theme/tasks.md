## 1. 主题 token 换血（globals.css）

- [x] 1.1 重写 `:root` 全量颜色变量为茶墨浅色表（design.md D1），含新增 `--link` / `--success` / `--warning` / `--info`
- [x] 1.2 重写 `.dark` 全量颜色变量为同家族暗色表（design.md D2）
- [x] 1.3 `@theme inline` 新增 `--color-link` / `--color-success` / `--color-warning` / `--color-info` 映射
- [x] 1.4 prose 链接/行内代码颜色从 `var(--primary)` 改挂 `var(--link)`（`--tw-prose-links` / `--tw-prose-code` / 行内 code 规则）
- [x] 1.5 `.dw-textarea-thumb` 亮/暗写死色换为茶墨家族暖灰；更新 CopilotKit 对齐层注释中的 taupe/emerald 措辞
- [x] 1.6 `pnpm typecheck` 通过；硬刷新确认 globals.css 生效（遇 HMR 卡死清 `.next` 重启）

## 2. 语法调色板重派生（syntax-palette.ts）

- [x] 2.1 按 design.md D5 的 oklch 目标值换算 hex，重写 `LIGHT` / `DARK` 两套调色板与文件头注释（emerald → 茶墨）
- [x] 2.2 验证 Monaco 编辑器（tasks SQL 工作台）与 chat 代码块高亮一致且 keyword 呈茶墨

## 3. 真相源同步（DESIGN.md）

- [x] 3.1 重写 YAML tokens（colors/colorsDark，含新增四个语义色）与「色彩」章节设计理据（茶墨/钴蓝 link/状态色/五色织线/红色归位）
- [x] 3.2 更新「换主题用 shadcn preset」说明为手工维护；同步 CopilotKit 章节中的颜色措辞
- [x] 3.3 `pnpm design:lint` 通过

## 4. 浏览器验证（Browser Verification Gate）

- [x] 4.1 浅色：对话发消息收流式回复，确认面板暖底、链接/行内代码钴蓝、代码块 keyword 茶墨、卡片浮于纸上、次级文字清晰；console 无 error
- [x] 4.2 暗色：切换后确认深茶墨底 + 驼色 primary，无冷灰跳变；CopilotChat 跟随
- [x] 4.3 截图落 `tmp/`，验证完清理；删除探索用 `tmp/theme-preview.html`

## 5. 用户反馈修复（首轮试用）

- [x] 5.1 左栏 sidebar 加深一档（0.965→0.948，sidebar-accent 0.93→0.91），与 tab/内容面板层次可辨；cpk 作用域 `--muted` 改显式 0.915（输入药丸不融面板）
- [x] 5.2 cpk 浅色块补全 inherit 系列 —— 发送按钮从 cpk 零彩度黑圆回归茶墨
- [x] 5.3 状态徽章语义归位（design.md D9）：Badge 新增 success/info/warning 变体；成功/在线/新鲜→success、运行中/诊断中→info、偏旧/L2→warning；驾驶舱表格链接 text-primary→text-link；approval-card 裸色清理
- [x] 5.4 typecheck + design:lint + 浏览器实测（亮/暗截图核对后清理）；spec/design/DESIGN.md 同步

## 6. 用户反馈修复（第二轮）

- [x] 6.1 发送按钮真修复：cpk 烤死 `cpk:bg-black`（不走 token，5.2 的 inherit 对它无效），改用 `[data-testid="copilot-send-button"]` 覆盖为 primary 实底/禁用态 muted；computed 色实测确认
- [x] 6.2 「打开视图」菜单首帧闪现在视口左上角：anchor 测量从 useEffect（绘制后）移到点击事件内同步执行
- [x] 6.3 按钮颜色统一确认：项目设置主题段钮激活态与发送按钮同为 `--primary` 茶墨
- [x] 6.4 卡片/弹层去刺眼：`--card`/`--popover` 纯白 `oklch(1 0 0)` → 暖白 `oklch(0.99 0.004 80)`，与暖纸底同色相；spec/design/DESIGN.md 同步
