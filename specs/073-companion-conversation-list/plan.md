# Implementation Plan: 管家会话列表模式 + Markdown 回复

**Branch**: `worktree-073-companion-conversation-list` | **Date**: 2026-07-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/073-companion-conversation-list/spec.md`

## Summary

把管家视图（071 交付）右侧的「汇报卡片栈」重构为**单一会话列表面板**：上半区可折叠「待处理问题」列表（原卡片压缩为行），下半区一条**留存的统一会话线程**（全历史 + Markdown 渲染）。Vega 回复复用既有 `ChatMarkdown`（react-markdown + remark-gfm + 项目 Shiki 双主题）留存于线程；点问题列表某项可将其锚定进会话上下文追问。**纯前端改造**：复用既有 `GET /api/companion/messages` 历史端点、SSE 单通道（message/delta/end）、`sendChat({content, reportId?})` / `cancelChat()`，不新增或变更任何后端接口、DB schema、SSE 事件契约。技术核心是三处前端接线：① 挂载时加载历史 + SSE 实时消息按 id 合并去重；② 统一线程渲染（Markdown + 流式安全 + 崩溃隔离，均已在 `ChatMarkdown` 具备）；③ 前端锚定态（`anchorReportId`）驱动 `sendChat` 是否带 reportId 与线程头部展示。

## Technical Context

**Language/Version**: TypeScript 5 / React 19 / Next.js 16（App Router, Turbopack）

**Primary Dependencies**: next-intl（双语文案）、zustand（`useCompanionStore`）、react-markdown + remark-gfm（既有 `ChatMarkdown`，复用项目 Shiki `CodeBlock`）、shadcn/ui base-style（Button/半透明玻璃面板）、hugeicons、three.js（既有 `CompanionStage`，本特性不改）

**Storage**: 前端内存态（zustand store）；数据源为既有后端端点 `GET /api/companion/messages`（`reportId?`/`before?`/`limit?`）、`GET /api/companion/reports`、SSE `/api/companion/stream`。无新增持久化。

**Testing**: vitest（store 合并去重/锚定纯逻辑 + 组件渲染）+ Playwright 浏览器门（真实登录注入 token，验证会话线程/Markdown/问题列表/锚定/主题切换）

**Target Platform**: 现代浏览器（管家视图 `companion` Tab，`/?open=companion`）

**Project Type**: Web application（frontend 单侧改造，backend 零改动）

**Performance Goals**: 会话线程首屏历史加载 ≤ 1 次请求即可见（默认 limit）；流式 delta 逐字渲染不卡顿（复用既有安全闭合）；主题切换即时无重高亮（复用双主题 CodeBlock）

**Constraints**: 复用既有后端契约零改动；管家视图为 DESIGN.md 071 书面豁免沉浸式表面（会话面板复用半透明玻璃容器，不套标准 `Card`）；文案双语键集一致、时间走 `useFormatDateTime`、进行中态禁用省略号 `…`；亮/暗双主题即时切换。

**Scale/Scope**: 单个 Tab 视图；新增约 4 个前端组件 + store 3 处扩展 + i18n 双语键；退役 `report-stack.tsx`/`report-card.tsx`（迷你对话卡片）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 依据 |
|---|---|---|
| I. Files-First | ✅ 不适用/不违反 | 无任务/工作流/目录定义变更 |
| II. Server is Source of Truth | ✅ 不违反 | 复用既有端点，遵守项目隔离（`X-Project-Id`/TenantContext、SSE `projectId`+token）；无新写路径 |
| III. Two-Legged Debugging | ✅ 不适用 | 与 CLI 运行时无关 |
| IV. AI Lives in Local Agent（含 1.3.0 sidecar 例外面）| ✅ 不违反 | 纯前端改造，**零后端推理**；复用已获批的 companion sidecar 编排；sidecar 凭据仍只在后端（本特性不触碰凭据）；对话写操作仍经既有 PolicyEngine 写闸门（`sendChat` 走既有 `/chat`，闸门语义不变）；sidecar 不可用时线程展示既有 SYSTEM 兜底报错、观测/调度内核不受损 |
| V. Reuse the Kernel | ✅ 遵守 | 复用既有 `ChatMarkdown`、SSE hook、store、端点，不重写 |

**质量门（开发工作流）**：新增组件与 store 逻辑 MUST 带测试（vitest）+ 浏览器门验证（no test = not done）；前端改动后 `pnpm typecheck` 零错误、`pnpm design:lint` 通过、i18n 双语键集一致（CI 校验）。

**结论**：无违规，无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/073-companion-conversation-list/
├── plan.md              # 本文件
├── research.md          # Phase 0：关键设计决策
├── data-model.md        # Phase 1：前端状态模型（消息合并/锚定/问题行）
├── quickstart.md        # Phase 1：验证脚本 + 浏览器门
├── contracts/
│   └── consumed-contracts.md   # 本特性消费的既有后端契约（零新增）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
frontend/
├── components/workspace/views/
│   ├── companion-view.tsx                         # 改：ReportStack → ConversationPanel；保留 SpeechBubble 一句话播报
│   └── companion/
│       ├── conversation-panel.tsx                 # 新：右侧面板容器（上问题列表 + 下会话线程）
│       ├── problem-list.tsx                       # 新：可折叠「待处理问题」列表 + 未读计数
│       ├── problem-row.tsx                        # 新：单条汇报压缩成行（色点/标题/领域/计数/时间/关闭/详情/锚定）
│       ├── conversation-thread.tsx                # 新：统一会话线程（ChatMarkdown 渲染/空态/锚定头/上滚加载）
│       ├── report-stack.tsx                       # 退役（删除）
│       └── report-card.tsx                        # 退役（删除，迷你对话并入统一线程）
├── lib/companion/
│   ├── store.ts                                   # 改：setMessages/mergeMessages 按 id 去重；anchorReportId + setter
│   ├── api.ts                                     # 复用 fetchMessages/fetchReports/sendChat/cancelChat（无改）
│   ├── use-companion-stream.ts                    # 改：snapshot 后触发历史加载（或在 view 挂载时 fetchMessages 合并）
│   └── store.test.ts                              # 改/增：合并去重、锚定态用例
├── components/workspace/shared/chat-markdown.tsx  # 复用（无改）
└── messages/{zh-CN,en-US}.json                    # 增：companion.conversation.* / problem.* 双语键
```

**Structure Decision**: Web application 前端单侧改造。新增组件集中在既有 `components/workspace/views/companion/` 目录，与 071 现有组件同级；状态扩展落在既有 `lib/companion/store.ts`；渲染复用 `components/workspace/shared/chat-markdown.tsx`。后端目录零改动。

## Complexity Tracking

> Constitution Check 无违规，本节留空。
