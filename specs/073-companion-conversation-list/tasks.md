---
description: "Task list for 073 管家会话列表模式 + Markdown 回复"
---

# Tasks: 管家会话列表模式 + Markdown 回复

**Input**: Design documents from `specs/073-companion-conversation-list/`

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓

**Tests**: 含测试任务（constitution「no test = not done」+ 项目质量门要求）。

**范围**: 纯前端。所有路径相对仓库根，均在 `frontend/` 下。后端零改动。

## 硬约束（每个 UI 任务都适用）

- **滚动区一律 `DwScroll`**（`frontend/components/ui/dw-scroll.tsx`）——**禁止手写 `overflow-y:auto` / WebKit 滚动条伪元素**。会话线程、问题列表两处滚动区必须 `<DwScroll>…</DwScroll>` 包裹。
- **前端原语复用 DESIGN.md 目录**：`Button`（base-style，`render` 非 `asChild`）、`Input`、图标 hugeicons（`HugeiconsIcon` + `@hugeicons/core-free-icons`）、语义 token（`bg-primary`/`text-muted-foreground`）、`gap-*` / `size-*`，无手写 `dark:`。实现前先查 DESIGN.md；缺能力才新建并回填目录。
- **文案双语**：新增键 `zh-CN`/`en-US` 成对，键集一致；时间走 `useFormatDateTime`（无裸 ISO `T`/微秒）；进行中态禁用 `…`。
- **管家视图 DESIGN.md 071 豁免**：会话面板复用半透明玻璃容器（`bg-card/70 backdrop-blur-md`），不套标准 `Card`；机器人中央地位/氛围层/字幕不动。
- **每次前端改动后**：`pnpm typecheck` 零错误、`pnpm design:lint` 通过。

---

## Phase 1: Setup

- [ ] T001 校对基线：worktree 内 `cd frontend && pnpm install && pnpm typecheck` 零错误；通读 `frontend/DESIGN.md` 的「滚动条/滚动区 `DwScroll`」「卡片容器」「视图间距 `ViewContainer`」条目与 companion 071 豁免段，记录采纳约束。

---

## Phase 2: Foundational（阻塞所有 US，先行单人完成）

**⚠️ 本阶段完成前，任何 US 不得开工。**

- [ ] T002 扩展 `frontend/lib/companion/store.ts`：新增 `setMessages(list)`（按 `id` 去重整表写入）、`addMessage` 改为幂等（已存在 `id` 覆盖，否则按 `createdAt` 有序插入）、新增 `anchorReportId: string|null` + `setAnchor(id|null)`；`removeReport(id)` 命中 `anchorReportId` 时回落 `null`（见 data-model.md 不变量）。
- [ ] T003 [P] 会话历史加载接线：管家视图挂载 / SSE `snapshot` 后调 `fetchMessages({ limit })` → `setMessages` 合并去重（改 `frontend/lib/companion/use-companion-stream.ts` 或在 `companion-view.tsx` 加 effect）。实时 `message` 事件经幂等 `addMessage` 与历史合并，重连不重复。
- [ ] T004 新建 `frontend/components/workspace/views/companion/conversation-panel.tsx`：右侧面板容器，上区挂 `<ProblemList/>`、下区挂 `<ConversationThread/>`（半透明玻璃容器，不套 `Card`）；同时创建 `conversation-thread.tsx` / `problem-list.tsx` / `problem-row.tsx` **最小可编译桩**（后续 US 各自填充，避免双 Agent 同文件冲突）。
- [ ] T005 改 `frontend/components/workspace/views/companion-view.tsx`：右侧 `ReportStack` 换成 `ConversationPanel`；保留 `SpeechBubble` 仅作一句话即时播报（完整回复以线程为准）。
- [ ] T006 退役 `frontend/components/workspace/views/companion/report-stack.tsx` 与 `report-card.tsx`（删除；清理所有 import；其裸 `overflow-y-auto` 随之消除）。
- [ ] T007 [P] i18n 键脚手架 `frontend/messages/{zh-CN,en-US}.json`：`companion.conversation.*`（空态/锚定头/取消锚定/加载更多/历史加载失败重试）、`companion.problem.*`（标题/未读/折叠/空态/关闭/查看详情/锚定）。双语成对。
- [ ] T008 [P] `frontend/lib/companion/store.test.ts`：`setMessages` 去重、`addMessage` 幂等+有序、`setAnchor`、`removeReport` 命中锚定回落——单测先行并通过。

**Checkpoint**: store + 历史加载 + 面板骨架就位，可编译、typecheck 绿。US1/US2 可并行开工。

---

## Phase 3: User Story 1 — 统一会话线程 + Markdown（P1）🎯 MVP

**Goal**: 一条留存、可回看、Markdown 版式的统一会话线程。

**Independent Test**: 发含表格/代码块指令 → 线程内 Markdown 版式渲染、时间正序、可滚动；刷新后历史非空；流式可打断。

- [ ] T009 [US1] 填充 `frontend/components/workspace/views/companion/conversation-thread.tsx`：`useCompanionStore` 取 `messages` 时间正序渲染，每条经既有 `ChatMarkdown`（`frontend/components/workspace/shared/chat-markdown.tsx`）渲染，USER/AGENT/SYSTEM 三角色区分头像/对齐；**滚动区用 `DwScroll`**；空态文案（`companion.conversation.empty`）；流式态传 `streaming`（安全闭合围栏）、`streamingId` 对应条目实时刷新、中断标记。
- [ ] T010 [US1] 确认发送/打断闭环：`companion-view.tsx` 底部 `ChatComposer` → `sendChat`/`cancelChat` 已就绪；线程随 `streamingId` 反映在途流；新最新回复要点仍经 `SpeechBubble` 一句话播报（不承载完整内容）。
- [ ] T011 [US1] 测试：`conversation-thread` vitest —— Markdown 元素渲染（table/pre）、历史消息可见、SYSTEM 兜底报错入线程可见、去重不重复条目。

**Checkpoint**: US1 独立可用即构成 MVP。

---

## Phase 4: User Story 2 — 待处理问题列表（P2）

**Goal**: 卡片栈退役，未关闭汇报压缩为可扫读的问题列表。

**Independent Test**: 多严重度汇报 → 列表行倒序 + 色点 + 计数 + 未读徽标 + 可折叠；关闭行消失刷新不复现；详情跳转正确。

- [ ] T012 [P] [US2] 填充 `frontend/components/workspace/views/companion/problem-row.tsx`：单条 `ReportView` → 一行（severity 色点 + `title||domainName` + 领域 + `×N`（`aggregateCount>1`）+ `useFormatDateTime` 时间 + 关闭按钮 `closeReport` + 「查看详情」`openView("supervision",{reportId})`）。图标 hugeicons，语义 token。
- [ ] T013 [P] [US2] 填充 `frontend/components/workspace/views/companion/problem-list.tsx`：`reports` 倒序 map 为 `ProblemRow`；整块可折叠 + 未读计数徽标（`status==="UNREAD"` 计数）；**滚动区用 `DwScroll`**；空态文案；离线补看 `fetchReports`。
- [ ] T014 [US2] 测试：`problem-list` vitest —— 行数=汇报数、折叠切换、关闭移除行 + 未读计数减一、空态。

**Checkpoint**: US1 + US2 各自独立可用。

---

## Phase 5: User Story 3 — 点问题锚定进会话追问（P3）

**Goal**: 打通「问题 → 对话」上下文闭环。

**Independent Test**: 点问题 → 线程头显锚定 → 追问获上下文回答 → 取消锚定回落全局 → 切换问题加载其往来。

- [ ] T015 [US3] 锚定接线：`problem-row.tsx` 加「锚定/追问」入口 → `setAnchor(report.id)`；`conversation-thread.tsx` 顶部渲染锚定条（`reports.find(id)` 取标题 + 取消锚定 `setAnchor(null)`）；`companion-view.tsx` 的 `handleSend` 带 `reportId = anchorReportId ?? undefined`。
- [ ] T016 [US3] 锚定切换加载：`setAnchor(id)` 时 `fetchMessages({ reportId:id })` → `setMessages` 合并去重并入统一线程；边界——当前锚定问题被他人关闭（SSE `report:closed` → `removeReport` 回落）时线程提示「该问题已处置」并回落全局（`companion.conversation.anchorClosed`）。
- [ ] T017 [US3] 测试：vitest —— `setAnchor` 设/清、`sendChat` 携带 reportId、`removeReport` 命中锚定回落、切换锚定合并历史不重复。

**Checkpoint**: 三 US 全部独立可用。

---

## Phase 6: Polish & 收口（跨 US）

- [ ] T018 [P] i18n 双语键集一致校验 + `pnpm design:lint`（零裸 `overflow`/硬编码间距）+ `pnpm typecheck` 全绿。
- [ ] T019 [P] 浏览器门（Playwright，登录注入 token）：验 ① 线程含 Markdown 元素（table/pre）② 问题列表行数=汇报数 + 可折叠 + 关闭 ③ 锚定头文案 + 追问 ④ 亮/暗主题即时切换可读 ⑤ 滚动区为 `DwScroll`（无裸 `overflow-y-auto` 残留）⑥ 无 `pageerror`、时间无裸 ISO、占位符无 `…`。截图入 `tmp/073-*.png`。
- [ ] T020 后端零改动自证：`git diff --stat -- backend/` 为空；`schema.sql` 版本不变。跑 `quickstart.md` 全流程。
- [ ] T021 DESIGN.md 收口：确认未新增界面原语（复用 `DwScroll`/`Button`/`Input`/`ChatMarkdown`）；更新 DESIGN.md「071 豁免条目」把「汇报卡片栈」改述为「会话列表面板（上问题列表 + 下统一会话线程）」，保持目录与实现不漂移。

---

## Dependencies & Execution Order

- **Phase 1 Setup** → **Phase 2 Foundational（阻塞）** → US 并行。
- **US1（P1）** 与 **US2（P2）**：Foundational 后可**并行**（文件所有权隔离：US1 拥 `conversation-thread.tsx`；US2 拥 `problem-list.tsx`/`problem-row.tsx`）。
- **US3（P3）**：依赖 US1（线程锚定头）+ US2（问题行入口），二者完成后开工。
- **Phase 6 Polish**：全部 US 完成后。

### 文件所有权（防双 Agent 冲突）

| 文件 | 阶段/所有者 |
|---|---|
| `lib/companion/store.ts` / `use-companion-stream.ts` / `companion-view.tsx` / `conversation-panel.tsx` | Foundational（单人）|
| `conversation-thread.tsx` | US1（Agent A）|
| `problem-list.tsx` / `problem-row.tsx` | US2（Agent B）|
| 锚定跨 `thread`+`row`+`view` | US3（US1/US2 后单人接线）|

---

## Parallel Example（Foundational 完成后）

```text
# Agent A（US1）：
Task: "填充 conversation-thread.tsx：ChatMarkdown 渲染 + DwScroll + 流式 + 空态"（T009→T011）

# Agent B（US2）：
Task: "填充 problem-row.tsx + problem-list.tsx：压缩行 + DwScroll + 折叠 + 未读计数"（T012→T014）
```

---

## Implementation Strategy

- **MVP = Phase 1 + 2 + US1**：一条能回看、有 Markdown 版式的统一会话线程即交付核心价值。
- **增量**：+US2（问题清单可扫读）→ +US3（锚定追问闭环）。
- **两 Agent**：Foundational 单人先行 → US1/US2 并行 → US3 接线 → 主 Claude 兜底评审 + 浏览器门收口。

## Notes

- `[P]` = 不同文件、无依赖，可并行。
- 每完成一个任务或逻辑组即提交。
- 测试先行并确认失败再实现（US 内单测）。
- 后端零 diff 是硬边界（T020 自证）。
