# chamo-theme 设计文档

## Context

浅色主题灰雾问题的量化诊断（详见 proposal）：背景层挤在 L 0.93–1.0、card 与 background 同为纯白、`muted-foreground` L 0.58、`border` L 0.93。经 `tmp/theme-preview.html` 四方向实景对比（朱墨 / 青墨 / 孔雀青 / 茶墨 / 蓝墨），用户选定**茶墨**：暖纸白底 + 褐调墨 primary，与纸同色温家族、整体最融；鲜艳度由钴蓝链接、四态状态色、五色织线图表承担；红色语义归位（仅失败/删除）。

现有耦合面（已核实）：业务组件层全部使用语义 token（无写死灰/绿）；硬编码颜色仅在 `globals.css` 的 `.dw-textarea-thumb` 与 `lib/syntax-palette.ts`；`chart-1..5` 当前无消费者。CopilotKit 对齐层全部经 `inherit`/`var()` 实现，token 换血自动跟随。

## Goals / Non-Goals

**Goals:**

- 消除灰雾：背景层明度拉开（页面底着色、卡片纯白浮起）、border 可见、次级文字可读。
- 茶墨品牌色体系落地：primary/accent 茶墨家族，链接与辅助强调钴蓝，状态色四色分明，图表五色织线。
- 亮/暗两态同一色相家族，气质统一。
- Monaco 与 chat 代码块语法高亮与新主题像素级一致。
- DESIGN.md 真相源与 globals.css 同步，`pnpm design:lint` 通过。

**Non-Goals:**

- 不动字体（Merriweather/Inter/Geist Mono）、圆角（0.875rem）、无分割线布局、间距体系。
- 不动任何交互行为、组件结构、AG-UI 协议。
- 不强行对齐 CopilotKit 内部写死样式（输入药丸 `cpk:dark:bg-[#303030]` 维持现状）。
- ~~不在本变更内给业务组件补用 success/warning/info token~~（原计划仅定义不消费；实施后用户反馈旧「primary 当状态色」用法在茶墨下成黑块，已纳入本变更，见 D9）。

## Decisions

### D1 浅色 token 全表（`:root`）

清晰脚手架三原则：① 背景层明度差 ≥3%（bg 0.978 / sidebar 0.965 / muted 0.945 / border 0.875，card 纯白 1.0 浮在纸上）；② 次级文字 L ≤0.45；③ 全部中性色统一暖色相（~80，与现 taupe 的 106 区分）。

| Token | 取值 | 说明 |
|---|---|---|
| `--background` | `oklch(0.978 0.006 80)` | 暖纸白 |
| `--foreground` | `oklch(0.17 0.012 80)` | 真黑墨字 |
| `--card` / `--popover` | `oklch(0.99 0.004 80)` | 暖白，浮于纸上（用户反馈：纯白太刺眼，压回暖调） |
| `--card-foreground` / `--popover-foreground` | `oklch(0.17 0.012 80)` | |
| `--primary` | `oklch(0.45 0.085 60)` | **茶墨**（褐调墨） |
| `--primary-foreground` | `oklch(0.985 0.006 80)` | 纸白 |
| `--secondary` | `oklch(0.945 0.009 80)` | 暖浅面 |
| `--secondary-foreground` | `oklch(0.25 0.015 80)` | |
| `--muted` | `oklch(0.945 0.009 80)` | |
| `--muted-foreground` | `oklch(0.45 0.02 80)` | 灰雾主犯的解药（0.58→0.45） |
| `--accent` | `oklch(0.45 0.085 60)` | 沿用「accent=primary」既有模式 |
| `--accent-foreground` | `oklch(0.985 0.006 80)` | |
| `--border` / `--input` | `oklch(0.875 0.014 80)` | 看得见的线（0.93→0.875） |
| `--ring` | `oklch(0.62 0.07 65)` | 茶墨家族 focus 环 |
| `--link` | `oklch(0.49 0.22 264)` | **新增**：钴蓝，链接/行内代码/辅助强调 |
| `--success` | `oklch(0.58 0.155 150)` | **新增**：任务成功 |
| `--warning` | `oklch(0.62 0.14 78)` | **新增**：质量预警（压深保证小字可读） |
| `--info` | `oklch(0.55 0.18 250)` | **新增**：运行中 |
| `--destructive` | `oklch(0.55 0.22 25)` | 深红，仅失败/删除 |
| `--chart-1..5` | 茶 `oklch(0.55 0.12 60)` · 钴蓝 `oklch(0.49 0.24 264)` · 金 `oklch(0.76 0.15 85)` · 翠 `oklch(0.60 0.155 150)` · 紫 `oklch(0.55 0.18 300)` | 五色织线 |
| `--sidebar` | `oklch(0.948 0.01 80)` | 比页面底深一档（用户反馈：左栏要与 tab/内容面板可辨区分） |
| `--sidebar-foreground` | `oklch(0.17 0.012 80)` | |
| `--sidebar-accent` | `oklch(0.91 0.014 78)` | |
| `--sidebar-accent-foreground` | `oklch(0.23 0.015 80)` | |
| `--sidebar-border` / `--sidebar-ring` | 同 `--border` / `--ring` | |
| `--sidebar-primary` | `oklch(0.52 0.10 60)` | 茶墨提亮一档 |
| `--sidebar-primary-foreground` | `oklch(0.985 0.006 80)` | |

### D2 暗色 token 全表（`.dark`）—— 同色相家族派生

派生规则：中性阶保持暖色相（~65–80）；primary 提亮为**驼色**（茶墨的暗色态自然延伸，深底上奶咖色按钮）；link/状态色/织线整体提亮一档；border 维持半透明白模式但带暖色相。

| Token | 取值 |
|---|---|
| `--background` | `oklch(0.16 0.008 65)` |
| `--foreground` | `oklch(0.97 0.006 80)` |
| `--card` / `--popover` / `--sidebar` | `oklch(0.215 0.012 65)` |
| `--primary` | `oklch(0.72 0.10 70)`（驼色），fg `oklch(0.20 0.012 65)` |
| `--secondary` | `oklch(0.27 0.012 65)`，fg `oklch(0.97 0.006 80)` |
| `--muted` | `oklch(0.26 0.012 65)`，`--muted-foreground` `oklch(0.74 0.02 75)` |
| `--accent` | `oklch(0.60 0.09 65)`，fg `oklch(0.985 0.006 80)` |
| `--border` | `oklch(0.95 0.02 80 / 14%)`，`--input` 16% 同色 |
| `--ring` | `oklch(0.62 0.08 65)` |
| `--link` | `oklch(0.70 0.15 264)` |
| `--success` / `--warning` / `--info` | `oklch(0.70 0.15 152)` / `oklch(0.78 0.14 80)` / `oklch(0.70 0.14 250)` |
| `--destructive` | `oklch(0.68 0.18 25)` |
| `--chart-1..5` | `oklch(0.72 0.10 70)` / `oklch(0.68 0.16 264)` / `oklch(0.80 0.14 85)` / `oklch(0.70 0.14 152)` / `oklch(0.68 0.15 300)` |
| `--sidebar-accent` | `oklch(0.27 0.014 65)`，fg `oklch(0.97 0.006 80)` |
| `--sidebar-primary` | `oklch(0.72 0.10 70)`，fg `oklch(0.20 0.012 65)` |
| `--sidebar-border` / `--sidebar-ring` | 同 `--border` / `--ring` |

### D3 新 token 的 Tailwind 映射

`@theme inline` 新增 `--color-link` / `--color-success` / `--color-warning` / `--color-info` 四条映射，使 `text-link`、`bg-success/10` 等 utility 可用。**备选**（不采纳）：用 Tailwind 任意值 `text-[var(--link)]` —— 违反「语义 token，不写裸值」的项目规则。

### D4 链接/行内代码从 `--primary` 改挂 `--link`

`globals.css` 中 prose 的 `--tw-prose-links` / `--tw-prose-code` 与行内代码颜色当前映射 `var(--primary)`。茶墨 primary 偏沉，承担"可点击/代码"的视觉提神职能不如钴蓝。改为 `var(--link)`。**理由**：探索阶段实景对比确认茶墨页面需要钴蓝作"活跃像素"；这也让 primary（操作）与 link（导航/引用）语义分离。

### D5 语法调色板派生（`lib/syntax-palette.ts`）

保持「TS 具体色、Monaco/chat 共用 `buildSyntaxTheme()`」架构不变，仅换调色板取值。派生锚点：**keyword = 茶墨家族**（最高频 token 锚定品牌），func = 钴蓝，string = 金，number = 青蓝，type = 深青，constant = 紫，regexp = 玫红，comment/operator = 暖灰阶；底色亮态贴暖纸（较 `--muted` 略亮）、暗态贴暖深底。oklch 目标值在实现时换算为 hex（Monaco 只吃 hex），亮/暗两套，暗色整体提亮。

| 角色 | 亮（oklch 目标） | 暗（oklch 目标） |
|---|---|---|
| keyword | `0.48 0.10 60` | `0.78 0.10 72` |
| func | `0.50 0.19 264` | `0.72 0.14 264` |
| string | `0.60 0.13 85` | `0.80 0.12 88` |
| number | `0.55 0.13 220` | `0.75 0.11 220` |
| type | `0.50 0.10 195` | `0.74 0.10 195` |
| constant | `0.52 0.16 300` | `0.72 0.13 300` |
| regexp | `0.55 0.17 0` | `0.74 0.13 0` |
| comment | `0.58 0.02 80` | `0.62 0.02 75` |
| fg / bg | `0.20 0.012 75` / `0.97 0.008 80` | `0.93 0.008 80` / `0.19 0.01 65` |

### D6 CopilotKit 对齐层：浅色块补全 inherit + muted 显式深一档

对齐层以 `inherit`/`var()` 跟随主题。实改三处：① `.dw-textarea-thumb` 写死的 taupe 换为暖褐灰（亮 `oklch(0.45 0.02 80)` / 暗 `oklch(0.74 0.02 75)`）；② 注释中 taupe/emerald 措辞更新为茶墨；③ **浅色块 `html [data-copilotkit]` 补全 inherit 系列**（foreground/primary/secondary/accent/border/input/ring/destructive）—— cpk 浅色作用域自带零彩度近黑 primary，发送按钮会是黑圆，inherit 后回茶墨（用户反馈修复）。其中 `--muted` **不用 inherit 而给显式深一档值 `oklch(0.915 0.012 80)`**：sidebar 加深至 0.948 后与 app muted（0.945）几乎同明度，输入药丸/行内代码底若 inherit 会融进面板。④ **发送按钮单独覆盖**：cpk 把颜色烤死在工具类（`cpk:bg-black cpk:dark:bg-white`，不走 token，inherit 无效），以 `[data-testid="copilot-send-button"]` 选择器强制 `--primary` 实底（禁用态 `--muted`）。

### D7 手写 token 替代 shadcn preset

DESIGN.md 现注明「换主题统一用 `shadcn apply --preset`」。茶墨是定制主题，无现成 preset —— 本次手写 `:root`/`.dark` 全量变量，并在 DESIGN.md 更新该说明（真相源改为「YAML tokens 手工维护，与 globals.css 双向同步」）。**备选**（不采纳）：造一个自定义 preset JSON —— 多一层工具链却只服务一次替换。

### D8 主题切换的探索史与否决项（留档）

朱墨（红 primary 与失败语义冲突，否决）→ 墨锭（纯黑 primary 过重，否决）→ 青墨/孔雀青/茶墨/蓝墨四选一 → **茶墨胜出**。若未来觉得页面"活跃度"不足，第一调节旋钮是 `--link` 的使用面（扩大到更多辅助强调），而非提高 primary 彩度。

### D9 状态徽章语义归位（用户反馈轮）

旧主题 primary=emerald≈成功色，组件层大量「拿 primary 当状态色」（`Badge variant="default"`、`bg-primary/10 text-primary`）；茶墨 primary 下这些全变深褐"黑块"。修复：Badge 新增 `success`/`info`/`warning` 变体（镜像 destructive 的「淡底+色字」形态），消费点全部按 spec 四态编码归位 —— 成功/在线/新鲜→success、运行中/诊断中→info、偏旧/L2 审批→warning、失败/陈旧/L3→destructive；驾驶舱表格内 `#id` 链接 `text-primary`→`text-link`。涉及：badge.tsx、instance-table、task-def-list、cockpit-view、freshness-view、fleet-card、fix-actions、approval-card（顺带清掉 amber-500/red-500 裸色）。`Badge variant="default"`（茶墨实底）保留给真正的品牌/计数场景。

## Risks / Trade-offs

- [暗色为推导而非用户逐项确认] → 浏览器验证阶段亮暗都截图；暗色不满意可在不动浅色的前提下单独调 `.dark` 块。
- [warning 金色小字对比度临界] → 浅色取值已压深至 L 0.62；徽章类用法建议「色字 + 同色 12–16% 淡底」组合；若 lint/目检仍不达标再压深。
- [oklch→hex 换算引入手工误差（syntax palette）] → 换算后在浏览器里与 chat/Monaco 实景核对，偏差肉眼可辨即调。
- [globals.css 编译 chunk 无 hash、浏览器缓存旧版] → 验证一律硬刷新（DESIGN.md 已记录此坑）；Turbopack 全局 CSS HMR 卡死时清 `.next` 重启。
- [chart 五色织线未来消费者期望渐变] → DESIGN.md 写明：分类数据用 chart-1..5 织线；单变量渐变场景另行从茶墨家族派生，不复用 chart token。

## Migration Plan

纯 CSS/TS 常量替换，无数据迁移。回滚 = revert 单次提交。实施顺序：globals.css → syntax-palette.ts → DESIGN.md → lint/typecheck → 浏览器验证（亮/暗 × 对话/代码块/工作区）。

## Open Questions

（无 —— 取值已在 `tmp/theme-preview.html` 实景确认，暗色派生规则已定，剩余微调留给浏览器验证阶段。）
