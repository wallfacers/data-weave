## 1. 基线与准备

- [x] 1.1 确保后端运行（h2 + mock）、前端 dev 在 4000；复用 `tmp/measure-chat.mjs`（登录 admin/admin → 发「查看 orders 表数据」）跑一次，存改动前截图 `tmp/chat-before-*.png` 与计算样式 JSON 作为对照基线
- [x] 1.2 阅读 `frontend/DESIGN.md`「AI 回复的 Markdown 排版」节，确认本次为「补条款」不与现有约束冲突（Design Contract Gate）

## 2. 紧致垂直节奏（globals.css）

- [x] 2.1 在 `frontend/app/globals.css` 既有 `[data-copilotkit] [class*="prose"]` 配色规则同段，新增节奏覆盖：容器 `font-size` ~13.5px、`line-height` ~1.6
- [x] 2.2 段落 `p`：`margin: 0 0 0.5rem`；容器 `> :first-child` margin-top:0、`> :last-child` margin-bottom:0（清零首末）
- [x] 2.3 块级间距：收敛 `space-y-4` 等价的子元素 margin-top 至 ~10px，确保与 p 间距不叠加产生过大空白
- [x] 2.4 列表：`li` margin ~2px；`ul/ol` padding-left ~1rem；标题 h1/h2/h3 ≈ 15/14/13px、font-weight 600
- [x] 2.5 全程用元素直接覆盖（不引入 `--tw-prose-*`），且不写任何颜色/ token

## 3. 代码块与表格精简

- [x] 3.1 验证 Streamdown 1.6.11 `controls` 粒度：能否单独关下载、留复制（查 `controls: {code, table}` 形态或下钻包源）
- [x] 3.2 若支持：在 `frontend/components/agent-chat.tsx` 的 `messageView.assistantMessage.markdownRenderer` 经直通链传 `controls` 隐藏代码块/表格下载按钮；若不支持：globals.css 兜底 `display:none` 打 `[data-streamdown="code-block-download-button"]` 及表格对应下载按钮
- [x] 3.3 代码块顶栏 padding 适度收敛；表格单元格 padding 收紧至 ~6×10px、字号 ~12.5px（仅排版量，不改色）

## 4. 设计契约回写（DESIGN.md）

- [x] 4.1 在 `frontend/DESIGN.md`「AI 回复的 Markdown 排版」节补「对话紧致节奏」子条款，记录定稿数值（字号/行高/段落与块间距/列表/标题/代码块顶栏/表格）
- [x] 4.2 注明间距以 `[data-copilotkit] [class*="prose"] <元素>` 直接覆盖、**不走** `--tw-prose-*`（仅管颜色）这一约定
- [x] 4.3 运行 `pnpm design:lint` 校验 DESIGN.md 结构通过

## 5. 验证（Browser Verification Gate，硬性）

- [x] 5.1 `cd frontend && pnpm typecheck` 零类型错误
- [x] 5.2 改 globals.css 后清 `.next` 重启 + 硬刷新（规避 Turbopack 全局 CSS HMR / chunk 无 hash 缓存）
- [x] 5.3 复用 Playwright 脚本真跑：量改动后字号/段距、存 `tmp/chat-after-*.png`，与基线对比确认段间空白收敛、字号合身、代码块留复制无下载、表格紧致
- [x] 5.4 确认亮/暗两态颜色与改动前一致（仅节奏变化）；CopilotChat 正常渲染输入框、console 无 error、消息能流式收发
- [x] 5.5 清理 `tmp/` 验证产物（截图/脚本不入仓库）
