# 监督席对话体验企业级升级 — 设计文档

日期：2026-07-14
状态：已与用户逐节确认定稿
交付切分：两期两特性 — **070 对话体验打磨**（本文详设）＋ **071 聊天室协作**（本文轮廓，届时独立 spec 细化）

## 背景与问题

监督席（`supervision` 视图，069-agent-incident-ops 落地）的 SSE 流式直播层（thinking 呼吸点、工具 chip、delta 打字、断线降级、seq 去重）完成度高，但外围打磨缺失导致整页呈现 demo 感。调研确认的破绽按严重度：

1. **【高】无加载态**：SSE 首帧 snapshot 到达前直接显示空态「暂无事故，系统运行正常」，连接抖动时假装一切正常（违反 DESIGN.md 加载态规范）。
2. **【高】发言者硬编码 `"ui-user"`**：单用户假名，无真实身份/头像，「聊天室」实为一对一。
3. **【中】Agent 气泡纯文本**：无 markdown/代码高亮；DESIGN.md 的 Streamdown+Shiki 规范（原 CopilotKit 节）已悬空未接。
4. **【中】卡片/输入框手写**：`bg-card` 裸 div、手写 `<input>`/`<textarea>`，未复用 `Card`/`Input`（违反 reuse-first）。
5. **【中】固定 40/60 列宽** `w-2/5`，不可调节。
6. **【低】消息无时间戳/日期分组/头像**，信息密度低。

参考对象 `~/workspace/github/workhorse/workhorse-assistant/`（Tauri 2 + Vite + React 19，非 Next.js/shadcn）——**移植设计模式而非代码**：无抖动自动滚动、发送/停止状态机 composer、IME 组字保护、步长自适应流式渲染、hover 操作条、克制阴影/圆角角色制。

## 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 范围 | 完整聊天室（多人实时协作），切成 070/071 两期 |
| 视觉基调 | **DESIGN.md 中性黑白灰主题不变**，只移植 workhorse 的结构性质感（不引入暖调/渐变品牌色） |
| 聊天室能力 | presence、typing、@提及、未读、认领/移交、引用回复、粘贴附件、关键结论置顶 **全部进 071** |
| markdown 引擎 | **Streamdown + Shiki**（复用 DESIGN.md 既有规范，修复文档悬空），不采用 workhorse 的 marked+morphdom 自研引擎 |
| 发送模型 | 服务端回显，**不做乐观插入**（省 clientMsgId 对账；回流延迟 <200ms 可接受） |
| Agent 打断 | 070 新增 `POST /api/incidents/{id}/agent/cancel`，`policy_rules` 种子注册 **L0** 免审批（保留 `agent_action` 留痕） |

---

# 070 对话体验企业级打磨（详设）

目标：消除 demo 感，对话线程达到 workhorse-assistant 交互质感。前端为主；后端仅两处接缝（actor 身份透传、agent cancel）。

## 1. 加载态合规

- `use-incident-stream.ts` 输出 `connectionPhase: "connecting" | "live" | "degraded"`，进 store。
- 首帧 snapshot 前，`LiveFeed` 与 `BriefingBanner` 区域用既有 `LoadingState`（`mode=centered`，最小 1s 防闪）。
- 空态仅在 `live` 且 feed 确实为空时出现。
- `degraded` 时线程顶部细条「连接已断开，重连中」（复用 `LiveDot` 语义），不清空已有消息。

## 2. Agent 气泡流式 markdown

- `AGENT_SAY` / `AGENT_STEP` / `PROPOSAL` 正文与横幅接班报告统一走 **Streamdown**（流式安全，自动处理未闭合代码围栏），删除自绘 `MarkdownLite`。
- 代码块：Shiki 双主题（github-light/dark）跟随 next-themes，语言标签 + 复制按钮。
- 单条气泡包 ErrorBoundary，渲染异常不塌整个线程。

## 3. Composer 升级（重写 `chat-composer.tsx`）

- auto-grow textarea：1→8 行自适应，超出内部滚动（超越 workhorse 固定 3 行）。
- IME 组字保护：`e.nativeEvent.isComposing` 时 Enter 不发送。
- 外层聚焦光环：容器 `focus-within:ring-1`；底部工具条左预留附件位（071 接线）、右发送按钮。
- 发送/停止状态机：Agent 流式中（thinking/delta 活跃）发送键切停止键 → `POST /api/incidents/{id}/agent/cancel`；cancel 失败/超时按钮回弹 + toast。
- 复用 `Input`/`Button` 与 token；禁用态、`aria-label` 补齐；POST 完成前显示发送中状态，失败时输入内容保留。

## 4. 消息时间线信息密度

- 头像：Agent 用品牌图标（中性色）；人类消息用姓名首字母圆形头像。
- 时间戳：hover 显示精确时间；跨日插入日期分隔胶囊；同发言者 5 分钟内连续消息合并分组（仅首条带头像/名字）。
- hover 操作条：Agent 消息下浮现「复制 markdown 原文」，点击 2s 对勾确认态（幂等、卸载清理）。引用/置顶归 071。

## 5. 无抖动滚动（移植 workhorse `use-auto-scroll` 模式）

- rAF 去重跟随滚动；wheel/touch 上滑意图检测暂停跟随；回底按钮 opacity 切换（不挂卸载）。
- CSS `overflow-anchor: auto` + `scrollbar-gutter: stable`。

## 6. 布局与组件合规

- 三个手写 `bg-card` div → `Card` 组件；`CloseButton` 手写 `<input>` → `Input`。
- 40/60 固定分割 → shadcn resizable（react-resizable-panels），feed 面板 `minSize` 护栏，宽度偏好存 localStorage。
- 间距维持 `gap-2.5` / `--card-spacing` token 体系（现状合规，不动）。

## 7. 真实身份透传（actor 地基，服务 071）

- 真相源在后端：`POST /api/incidents/{id}/chat` 从 JWT 认证主体（`TenantContext`）解析 `actor { userId, displayName }` 落库，不信任前端 body。前端删除 `currentUser()` 的 `"ui-user"` 兜底。
- 消息模型：保留既有 `actor` 字符串字段（存 `userId`，兼容存量），**并列新增 `actorName`**（显示名）；SSE `message` 事件与 REST 历史均携带两字段。存量消息 actor 为空/`"ui-user"` 时前端显示中性兜底（「操作员」）。
- 渲染规则：自己右对齐 `bg-primary`；他人左对齐 + 首字母头像 + 名字；Agent 左对齐 + 品牌图标。判定 = 消息 `userId` 与当前登录用户比对。

## 8. cancel 端点与写闸门

- 按硬规则走 `ActionRequest → GatedActionService → PolicyEngine` + `agent_action` 留痕。
- `policy_rules` 种子显式注册 cancel 为 **L0**（打断 AI 输出属低风险防护性操作），避免默认 L2 审批使停止键失效。

## 9. 数据流不变量

- store reducer 的 seq 去重、streamId 收尾机制**不动**（已验证骨架）。
- 错误消息：toast 直出后端 `BizException` 本地化消息（i18n 规则③，无硬编码兜底）。

## 10. DESIGN.md 同步（Design Contract Gate）

- 重写悬空的 CopilotKit 节（约 :242-259）→「监督席 AI 对话排版规范（Streamdown+Shiki）」。
- 公共组件目录登记：`ChatComposer`（升级版）、`MessageAvatar`、`DateSeparator`、resizable 分栏用法。
- 新增文案进 `zh-CN`/`en-US` 双 bundle，键集一致（CI 检查）。

## 11. 测试策略

**前端**
- vitest：`connectionPhase` 流转、actor 自己/他人/Agent 判定、5 分钟合并分组、日期分隔计算、composer 状态机。
- 浏览器验证门（Playwright，注入 `dw.auth.token`+`dw.auth.user` 绕登录）：①首帧前 LoadingState；②流式 markdown（代码块高亮+复制）；③发送→SSE 回显闭环；④滚动跟随与上滑暂停；⑤resizable 拖拽。IME 保护归浏览器门手动脚本（jsdom 不可靠）。
- `pnpm typecheck` 零错误。

**后端**
- JUnit + WebTestClient（`JwtTestSupport`）：①actor 从 JWT 解析落库；②cancel 走闸门、L0 直执行、`agent_action` 留痕断言；③H2 与 PG 各跑一遍。
- 全量回归保持绿（当前 H2 369 例）。

**验收线**：DESIGN.md 合规复查（LoadingState/Card 复用/无手写原语）+ 浏览器门截图留档。

---

# 071 聊天室协作（轮廓，届时独立 spec）

- **SSE 事件扩展**：`presence` / `typing` / `mention` / `read` / `assignee` / `pin` / `attachment` 七类新事件进 incident 流。
- **表结构**（`schema.sql` 单文件 + `schema_version` 升版）：`incident_read_position`（用户×线程读位置）、`incident_attachment`（元数据，文件复用 MinIO）、通知落点表（@提及）、`incident` 处置人列、消息置顶标记。
- **UI 落点**：feed 卡未读角标、线程头像堆（presence）+ 处置人徽章、composer @选择器与附件接线（070 预留位）、关键结论栏（置顶收纳）、Workspace 壳层通知铃铛。
- **依赖**：actor 地基、composer 工具条、SSE 事件分发骨架由 070 就位。

## 不做清单（YAGNI）

- 不引入 workhorse 暖调配色/渐变品牌元素（DESIGN.md 中性主题不变）。
- 不做乐观插入与 clientMsgId 对账。
- 不自研 marked+morphdom 流式引擎。
- 070 不做消息撤回/编辑、会话内搜索、消息导出（视 071 后需求再议）。
