# Phase 0 Research: 管家会话列表模式 + Markdown 回复

纯前端改造，无技术未知项（NEEDS CLARIFICATION 全部在 spec 阶段由用户当面拍板消解）。以下记录承接实现的关键设计决策。

## D1：会话历史加载 + SSE 实时消息合并去重

**Decision**: 管家视图挂载（或 SSE `snapshot` 到达）后调用既有 `fetchMessages({ limit })` 拉取当前项目历史消息，写入 store；SSE `message` 实时事件与历史消息在 store 内**按 id 合并去重**。新增 `setMessages(list)`（整表替换+按 id 去重）与让 `addMessage` 幂等（已存在 id 则忽略/覆盖）。

**Rationale**: 现状 `use-companion-stream.ts` 的 `snapshot` 仅设 state/briefing/reports，**不含消息**；`message` 事件只 push 实时消息且 `addMessage` 无去重。若不加历史加载，刷新/重开后线程为空（违反 FR-004「历史可回看」）。合并去重避免重连时实时+历史重复条目（Edge Case「消息去重」）。

**Alternatives rejected**:
- 让后端把消息塞进 snapshot：需改后端 SSE 契约，违反「零后端改动」边界。
- 只显示 SSE 实时消息不加载历史：直接违反 FR-004。

**风险点**: 历史消息与流式中的占位消息（`appendDelta` 早于 `message` 事件创建的 placeholder）id 一致性——合并以 messageId 为主键，placeholder 用真实 messageId 建，天然对齐。

## D2：统一线程 vs 分问题迷你对话

**Decision**: 下半区渲染**一条统一线程**，展示全部消息（全局 + 各问题锚定），按 `createdAt` 正序。每条消息用既有 `ChatMarkdown` 渲染（USER/AGENT/SYSTEM 三角色区分头像/样式）。锚定某问题时，线程头部显示锚定条（问题标题 + 取消锚定），发送消息带该 `reportId`；不做「按问题过滤只看该问题」的强隔离（统一历史优先，符合用户「一个连续对话不再一张张卡」的诉求）。

**Rationale**: 用户明确要「统一会话历史、不想逐个卡片对话」。统一线程 + 锚定头是最小满足：既有全局连续历史，又能针对某问题带上下文追问。

**Alternatives rejected**:
- 每问题独立线程（现状卡片迷你对话的翻版）：正是用户要废弃的模型。
- 锚定即过滤只显示该问题消息：割裂统一历史，回到「一个一个看」。US3 的「切换锚定加载该问题往来」通过按 `reportId` 拉取补齐并入统一线程即可，无需过滤视图。

## D3：Markdown 渲染复用既有 ChatMarkdown

**Decision**: 直接复用 `components/workspace/shared/chat-markdown.tsx`（070 交付）——react-markdown + remark-gfm，代码块桥接项目 `CodeBlock`（复用 `dataweave-light/dark` Shiki 双主题）、`completePartialMarkdown` 流式安全闭合围栏、`MarkdownBoundary` 单条崩溃降级纯文本。流式中传 `streaming` 开启安全闭合。

**Rationale**: 该组件已在 report-card 迷你对话中在用、已过 070 收口；契合 constitution V「复用内核」与 DESIGN.md「复用既有高亮体系」。零新增重型 Markdown 库。

**Alternatives rejected**: 引入 streamdown / 自带 mermaid+katex+第二套 Shiki 的库——071/070 已弃用（见 memory `incident-chat-070-learnings`）。

## D4：机器人中心地位 + 字幕气泡保留

**Decision**: 3D `CompanionStage`、氛围层、顶部概况条、底部全局输入框**保持不变**；中央 `SpeechBubble` 保留，仅作一句话即时播报（最新回复要点/新汇报）。完整回复内容以右侧线程 Markdown 为准。会话面板作为半透明玻璃 overlay 悬浮右侧（复用现有 `bg-card/70 backdrop-blur-md` 面板样式，DESIGN.md 071 豁免范围内）。

**Rationale**: 用户拍板「机器人保持中心、面板悬浮右侧」；071 身份与 DESIGN.md 豁免条目不改，避免触发 DESIGN.md 变更流程。

**Alternatives rejected**: 机器人降为头像、聊天为主区——用户明确否决（与 071 身份冲突、需改 DESIGN.md 豁免）。

## D5：问题列表行 vs 卡片

**Decision**: 每条未关闭汇报压缩为一行（严重度色点 + 标题（缺省回落领域名）+ 领域 + 聚合计数 + 本地化时间 + 关闭按钮 + 详情/锚定入口），按 `createdAt` 倒序，整块可折叠、显未读计数徽标。复用 store 既有 `reports`/`removeReport` 与 `closeReport` API（项目级共享关闭语义，071 不变）。退役 `report-card.tsx`（含迷你对话）与 `report-stack.tsx`。

**Rationale**: 压缩成行才能「一屏扫读多个问题」，是用户「不想一张张卡」的核心。关闭/详情语义 071 已定，平移即可。

**Alternatives rejected**: 保留卡片但缩小——仍占高、仍需逐张展开，未解决痛点。

## D6：锚定态归属前端

**Decision**: 锚定当前问题的 `anchorReportId: string | null` 存于 `useCompanionStore`（纯前端 UI 态）。`sendChat` 依据它决定是否带 `reportId`。取消锚定/切换/锚定问题被他人关闭（SSE `report:closed` 命中当前锚定）→ 回落 `null`。

**Rationale**: 后端 `sendChat` 已支持可选 `reportId`，锚定纯属前端交互态，无需后端参与。被关闭回落满足 Edge Case「锚定的问题被他人关闭」。

**Alternatives rejected**: 后端记「当前会话锚定」——多余，且 `sendChat` 每次显式带 reportId 已足够。

## 复用清单（零改动）

| 资产 | 用途 |
|---|---|
| `GET /api/companion/messages`（`chatService.history`）| D1 历史加载（全局 + 按 reportId）|
| `GET /api/companion/reports` | 问题列表离线补看 |
| SSE `/api/companion/stream`（message/delta/end/report/state/briefing）| 实时消息/流式/汇报增量 |
| `POST /chat` `sendChat({content, reportId?})` / `POST /chat/cancel` | 发送/打断（走既有写闸门）|
| `ChatMarkdown` / `CodeBlock` | Markdown + 双主题代码高亮 |
| `ChatComposer` | 底部输入（auto-grow/IME/发送-停止状态机）|
| `useFormatDateTime` | 时间本地化（无裸 ISO）|
