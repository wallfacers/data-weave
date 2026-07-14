# Tasks: 监督席对话体验企业级打磨

**Input**: Design documents from `/specs/070-incident-chat-experience/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 包含测试任务（项目硬规则：新功能必须有测试，无测试=未完成；浏览器验证门为收口标准）。

**Organization**: 按用户故事分组，每故事独立可测可交付。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1–US5（对应 spec.md）
- 每任务含确切文件路径

## Path Conventions

Web 双项目：`frontend/`（Next.js 16）+ `backend/`（Maven 多模块，dataweave-api / dataweave-master）。改后端每步跑 `cd backend && ./mvnw -q -pl <module> compile`；改前端每步跑 `cd frontend && pnpm typecheck`（CLAUDE.md 硬门）。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 依赖与设计系统组件就位

- [ ] T001 [P] 前端安装 `streamdown`（锁定 React 19 兼容版本）并确认与既有 `shiki ^4.2.0` 共存，`frontend/package.json`
- [ ] T002 [P] shadcn CLI 添加 `resizable`、`textarea`、`avatar` 到 `frontend/components/ui/`（引入 `react-resizable-panels`）；生成物按 Frontend Stack Gate 校正：lucide 图标换 hugeicons、语义 token、`render` 非 `asChild`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 跨故事共享的类型与测试基建（避免 US2/US4 同文件冲突）

**⚠️ CRITICAL**: 完成后各故事方可开工

- [ ] T003 `frontend/lib/supervision/types.ts` 一次性扩展共享类型：`Message.actorName?: string`（US4 用）+ `ConnectionPhase = "connecting" | "live" | "degraded"` 导出（US2 用）
- [ ] T004 修正 `frontend/e2e/supervision.spec.ts` 登录注入 fixture：`dw.auth.user` 从 `{username, name}` 改为真实 `AuthUser` 结构（`{userId, tenantId, username, displayName, roles, permissions}`，对齐 `frontend/lib/auth.tsx:19-28`），既有 069 场景保持绿

**Checkpoint**: 地基就绪，US1–US5 可按优先级或并行开工

---

## Phase 3: User Story 1 - 可读的 Agent 回复 (Priority: P1) 🎯 MVP

**Goal**: Agent 诊断以流式 Markdown + Shiki 双主题代码高亮渲染，单条渲染失败隔离降级

**Independent Test**: quickstart S2——诱导含代码块回复，验证流式无跳版、复制一致、主题即时切换

### Implementation for User Story 1

- [ ] T005 [P] [US1] 新建 `frontend/components/workspace/shared/chat-markdown.tsx`：Streamdown 封装（`parseIncompleteMarkdown` 流式安全）+ 每消息 ErrorBoundary 降级 + 代码块桥接 `frontend/lib/highlighter.ts` 的 `dataweave-light/dark` 双主题（`--shiki-light/--shiki-dark` CSS 变量方案，见 research R1）+ 代码块语言标签与复制按钮（2s 对勾确认、幂等）
- [ ] T006 [US1] `frontend/components/workspace/views/supervision/incident-thread.tsx`：`AGENT_SAY`/`AGENT_STEP`/`PROPOSAL` 正文与 delta 流式缓冲改经 `<ChatMarkdown>` 渲染（保留既有 streamId 收尾/光标语义）
- [ ] T007 [US1] `frontend/components/workspace/views/supervision/briefing-banner.tsx`：删除自绘 `MarkdownLite`，接班报告改用 `<ChatMarkdown>`
- [ ] T008 [P] [US1] i18n 新键（复制/已复制/渲染失败降级文案）双 bundle 同步：`frontend/messages/zh-CN.json` + `frontend/messages/en-US.json`
- [ ] T009 [P] [US1] vitest：`frontend/components/workspace/shared/chat-markdown.test.tsx`——ErrorBoundary 单条隔离、复制确认态幂等、未闭合围栏不抛错
- [ ] T010 [US1] 浏览器门扩展 `frontend/e2e/supervision.spec.ts`：S2 场景（流式渲染、代码块复制剪贴板一致、明暗主题切换高亮跟随）

**Checkpoint**: US1 独立可演示——对话质感核心落地

---

## Phase 4: User Story 2 - 加载与连接状态真实可信 (Priority: P1)

**Goal**: connecting/live/degraded 三态可信呈现；空态只在确认无事故时出现；断线不丢消息

**Independent Test**: quickstart S1——冷启动见 LoadingState、停后端见降级条、重启自动恢复

### Implementation for User Story 2

- [ ] T011 [US2] `frontend/lib/supervision/use-incident-stream.ts`：输出 `connectionPhase`（初始 connecting；首个 snapshot→live；EventSource error→degraded；重连成功回 live）
- [ ] T012 [US2] `frontend/lib/supervision/store.ts`：纳入 `connectionPhase` 状态与 selector（reducer 既有 seq 去重/收尾机制不动）
- [ ] T013 [US2] `frontend/components/workspace/views/supervision/live-feed.tsx` + `briefing-banner.tsx`：connecting 时渲染既有 `LoadingState`（**prop 名 `variant="centered"`**，内建最小 1s 防闪）；空态仅 `live && feed.length===0`
- [ ] T014 [US2] `frontend/components/workspace/views/supervision/incident-thread.tsx`：degraded 时顶部「连接已断开，重连中」提示条（复用 `LiveDot` 语义），已加载消息保留
- [ ] T015 [P] [US2] i18n 新键（连接中/已断开重连中）双 bundle：`frontend/messages/zh-CN.json` + `frontend/messages/en-US.json`
- [ ] T016 [P] [US2] vitest 扩展 `frontend/lib/supervision/store.test.ts`：三态流转、空态门控（connecting 不出空态）、degraded 消息保留
- [ ] T017 [US2] 浏览器门扩展 `frontend/e2e/supervision.spec.ts`：S1 场景（首帧 LoadingState 非空态断言）

**Checkpoint**: US1+US2 = P1 全部落地，可信性与可读性双修复

---

## Phase 5: User Story 3 - 顺手的发言与打断 (Priority: P2)

**Goal**: composer auto-grow/IME 保护/发送-停止状态机；打断端点过闸门 L0 留痕

**Independent Test**: quickstart S3——IME 组字不误发、长输出可打断且 `agent_action` 留痕无审批等待、发送失败输入保留

### Implementation for User Story 3

**后端（先行，依赖链 T018→T019→T020）**：

- [ ] T018 [US3] `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/agent/LlmChatClient.java`：`streamChat` 增加 `BooleanSupplier cancelled` 参数，阻塞读循环逐行检查、取消即关流退出；既有调用点传 `() -> false`
- [ ] T019 [US3] `backend/dataweave-master/src/main/java/com/dataweave/master/application/incident/IncidentConversationService.java`：`ConcurrentHashMap<UUID, AtomicBoolean>` 取消句柄注册表（respond 注册/finally 清除）；`interrupt(incidentId)`——置位、部分内容 AGENT_SAY 落库（`payload_json.interrupted=true`）、发既有 streamId 收尾事件、无在途轮次返回幂等 false（竞态：先落库者胜，见 data-model §4）
- [ ] T020 [US3] `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/IncidentController.java`：新增 `POST /api/incidents/{id}/agent/cancel`——构造 `ActionRequest(toolName="incident_agent_cancel", actorSource="UI", actor=<TenantContext.username()>)` 经 `GatedActionService.submit` 过闸后调 interrupt，响应 `{code:0,data:{cancelled}}`（契约 contracts/agent-cancel.md）
- [ ] T021 [P] [US3] `backend/dataweave-api/src/main/resources/data.sql`：`policy_rules` 追加 `('TOOL','incident_agent_cancel',NULL,'L0',…)` 行，格式对齐既有 56-60 行
- [ ] T022 [US3] 后端 IT 新建 `backend/dataweave-api/src/test/java/com/dataweave/api/IncidentAgentCancelIT.java`（JwtTestSupport）：打断→部分内容落库带 interrupted 标记→`agent_action` 留痕→L0 无 PENDING_APPROVAL→无在途轮次幂等 `cancelled:false`；H2 跑通并复核 PG 方言

**前端**：

- [ ] T023 [P] [US3] `frontend/lib/supervision/api.ts`：新增 `cancelAgent(incidentId)`
- [ ] T024 [US3] 重写 `frontend/components/workspace/views/supervision/chat-composer.tsx`：shadcn `Textarea`、auto-grow 1→8 行后内滚、`e.nativeEvent.isComposing` IME 保护、容器 `focus-within:ring-1`、idle/sending/streaming 状态机（streaming=该 incident 有活跃 streamId 时发送键切停止键）、发送失败输入保留 + toast 后端消息、cancel 失败/超时回弹可重试、底部工具条预留附件位（071）、`aria-label` 补齐
- [ ] T025 [US3] `frontend/lib/supervision/store.ts` + `incident-thread.tsx`：暴露活跃 streamId 供 composer 判 streaming；`payload.interrupted` 消息渲染「已打断」标记
- [ ] T026 [P] [US3] i18n 新键（停止/已打断/发送失败保留提示）双 bundle：`frontend/messages/zh-CN.json` + `frontend/messages/en-US.json`
- [ ] T027 [P] [US3] vitest 新建 `frontend/components/workspace/views/supervision/chat-composer.test.tsx`：状态机切换、空文本禁用、失败保留输入（IME/auto-grow 归浏览器门，jsdom 不可靠）
- [ ] T028 [US3] 浏览器门扩展 `frontend/e2e/supervision.spec.ts`：S3 场景（停止键流转；IME 场景以手动脚本记录在 quickstart）

**Checkpoint**: 发言体验与打断能力闭环（含后端契约断言）

---

## Phase 6: User Story 4 - 认得出"谁说的、何时说的" (Priority: P2)

**Goal**: 身份服务端认定（actor=username + actor_name=displayName 落库）；头像/时间戳/日期分隔/分组/hover 复制

**Independent Test**: quickstart S4——双账号发言归属正确、curl 伪造 actor 被忽略、5 分钟分组与跨日分隔正确

### Implementation for User Story 4

**后端（依赖链 T029→T030→T031/T032→T033）**：

- [ ] T029 [US4] `backend/dataweave-api/src/main/resources/schema.sql`：`incident_message` 加 `actor_name VARCHAR(128)` 可空列；`schema_version` 0.19.0→0.20.0（文件头 + 种子行同步，遵循「任何表变更即升版」）；DDL 写法 H2/PG 兼容
- [ ] T030 [US4] domain+infra 透传：`IncidentMessage` record 加 `actorName`；`IncidentMessageRepository` INSERT/`map()` 加列；所有 `append` 调用点带新参（`IncidentConversationService`、`IncidentAgentService`、`IncidentSweeper`、`DefaultPlatformActionExecutor`——Agent/system 路径传 NULL）
- [ ] T031 [P] [US4] 新建 displayName 解析器（`backend/dataweave-api` 内，如 `application/user/DisplayNameResolver.java`）：user 表查询 + 进程内 TTL 缓存，查不到回退 username（research R2）
- [ ] T032 [US4] `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/IncidentController.java`：`chat`/`markHandled`/`reverify`/`close` 改为**提交线程池前**从 `TenantContext.username()` 捕获身份 + 经 T031 解析 displayName，作为参数传入 service；body `actor` 字段一律忽略（契约 contracts/chat-identity.md；ThreadLocal 不透传后台线程）
- [ ] T033 [US4] 后端 IT 扩展 `backend/dataweave-api/src/test/java/com/dataweave/api/IncidentControllerIT.java`：落库 actor=token username（"tester"）、body actor="hacker" 被忽略、`actorName` 随 SSE/REST 载荷返回（契约 contracts/sse-message.md）

**前端**：

- [ ] T034 [P] [US4] `frontend/components/workspace/views/supervision/incident-visuals.tsx`：新增 `MessageAvatar`（人类首字母 / Agent 品牌图标，中性色）与 `DateSeparator` 原语
- [ ] T035 [P] [US4] 新建 `frontend/lib/supervision/group-messages.ts` 纯函数（消息序列→DateSeparator/MessageGroup 渲染列表，5 分钟窗口）+ 同名 `group-messages.test.ts`（恰好 5 分钟、跨日、actor 交替边界）
- [ ] T036 [US4] `frontend/components/workspace/views/supervision/incident-thread.tsx`：删除 `currentUser()` 假名兜底；自己/他人/Agent 三分渲染（`msg.actor === useAuth().user.username`）；组首头像+displayName（兜底链见 data-model §5）；悬停精确时间；接入 `groupMessages`；`frontend/lib/supervision/api.ts` 同步移除 chat/markHandled/reverify/close 的 actor 传参
- [ ] T037 [US4] `incident-thread.tsx` 续：Agent 消息 hover 操作条（复制原文、2s 对勾、幂等、卸载清理定时器）
- [ ] T038 [P] [US4] i18n 新键（操作员兜底称谓/复制原文/今天昨天日期格式）双 bundle：`frontend/messages/zh-CN.json` + `frontend/messages/en-US.json`
- [ ] T039 [US4] 浏览器门扩展 `frontend/e2e/supervision.spec.ts`：S4 场景（fixture displayName 正确显示、无 "ui-user" 字样断言）

**Checkpoint**: 身份地基落成（071 多人协作直接受益），时间线信息密度达标

---

## Phase 7: User Story 5 - 稳定的阅读视口与可调布局 (Priority: P3)

**Goal**: rAF 无抖动跟随滚动 + 上滑暂停 + 回底按钮；可拖拽分栏带最小宽与持久化

**Independent Test**: quickstart S5——长输出上滑不被拽回、分栏拖拽刷新后保持

### Implementation for User Story 5

- [ ] T040 [P] [US5] 新建 `frontend/hooks/use-auto-scroll.ts`：rAF 去重跟随、`MutationObserver`+`ResizeObserver` 共享增长回调、wheel/touch 上滑意图暂停（微抖阈值）、`isAtBottom` 输出（research R5）
- [ ] T041 [US5] `frontend/components/workspace/views/supervision/incident-thread.tsx`：接入 hook；回底按钮 opacity 切换不挂卸载；容器 CSS `overflow-anchor:auto` + `scrollbar-gutter:stable`
- [ ] T042 [US5] `frontend/components/workspace/views/supervision-view.tsx`：feed/thread 双列改 `ResizablePanelGroup`（feed `minSize` 护栏 + `autoSaveId="supervision-split"` 内建持久化），移除 `w-2/5` 硬编码
- [ ] T043 [P] [US5] i18n 新键（回到底部 aria-label）双 bundle：`frontend/messages/zh-CN.json` + `frontend/messages/en-US.json`
- [ ] T044 [US5] 浏览器门扩展 `frontend/e2e/supervision.spec.ts`：S5 场景（上滑不拽回断言、分栏拖拽+reload 宽度保持）

**Checkpoint**: 全部五故事独立可验

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 设计系统合规收口 + 全量回归 + 文档同步

- [ ] T045 [P] 组件合规改造：`live-feed.tsx`/`briefing-banner.tsx`/`incident-thread.tsx` 手写 `bg-card` div → `Card` 组件；`incident-thread.tsx` 的 `CloseButton` 手写 `<input>` → `Input`（间距维持 `--card-spacing`/`gap-2.5` token）
- [ ] T046 [P] `frontend/DESIGN.md`：重写悬空 CopilotKit 节（约 :242-259）→「监督席 AI 对话排版规范（Streamdown+Shiki）」；公共组件目录登记 `ChatMarkdown`/`ChatComposer`/`MessageAvatar`/`DateSeparator`/resizable 用法；跑 `pnpm design:lint`
- [ ] T047 前端总门：`cd frontend && pnpm typecheck && pnpm test`（i18n 双 bundle 键集一致由 CI 检查覆盖）
- [ ] T048 后端全量回归：`cd backend && ./mvnw -pl dataweave-api,dataweave-master test`（**setsid 脱离**跑，H2 基线 369 例全绿；调度/事故相关用例无新增失败）；H2 与 PG 方言点检 T029 DDL
- [ ] T049 quickstart.md S1–S5 全场景人工走查 + 浏览器门截图留档（对照 spec SC-001~SC-008 逐项打勾）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖
- **Foundational (Phase 2)**: 依赖 Setup；阻塞所有故事
- **US1–US5 (Phase 3–7)**: 均依赖 Phase 2；故事间**无功能依赖**，可并行（见同文件冲突注意）
- **Polish (Phase 8)**: 依赖全部所需故事完成

### User Story Dependencies

- US1 (P1)、US2 (P1)、US5 (P3)：纯前端，互不依赖
- US3 (P2)：后端链 T018→T019→T020→T022 顺序；T021 可与 T018 并行；前端 T024 依赖 T023
- US4 (P2)：后端链 T029→T030→(T031∥T032)→T033；前端 T036 依赖 T034/T035
- **同文件串行注意**：`incident-thread.tsx` 被 T006/T014/T025/T036/T037/T041 触及，`store.ts` 被 T012/T025 触及，`briefing-banner.tsx` 被 T007/T013 触及，`e2e/supervision.spec.ts` 被各故事门任务触及——**跨故事并行时这些文件按故事优先级串行处理**（或单人按 P1→P2→P3 顺做自然规避）

### Parallel Opportunities

- Phase 1 内 T001∥T002
- Phase 2 内 T003∥T004
- 各故事内标 [P] 任务（如 US4 的 T031∥T034∥T035∥T038）
- 后端 US3 链与前端 US1/US2 完全不同文件，可双线并行

---

## Parallel Example: User Story 4

```bash
# 后端 schema+透传串行到 T030 后，以下可同时开工：
Task: "T031 DisplayNameResolver（api 模块新文件）"
Task: "T034 MessageAvatar/DateSeparator（incident-visuals.tsx）"
Task: "T035 group-messages 纯函数+单测（新文件）"
Task: "T038 i18n 双 bundle 键"
# 之后 T036/T037 串行收口 incident-thread.tsx
```

---

## Implementation Strategy

### MVP First（P1 = US1 + US2）

1. Phase 1 + Phase 2（半天内）
2. US1（对话质感核心）→ 独立验证 S2 → 可演示
3. US2（可信性修复）→ 独立验证 S1 → P1 收口
4. **STOP and VALIDATE**：此时监督席已"不像 demo"，可先行合入

### Incremental Delivery

- US3（发言+打断，后端接缝最多）→ S3 验证 → 交付
- US4（身份地基，071 前置）→ S4 验证 → 交付
- US5（滚动+分栏）→ S5 验证 → 交付
- Phase 8 收口：合规改造 + 全量回归 + DESIGN.md/截图归档

### 实施环境

- 按项目惯例在独立 worktree 进行（`git worktree add ../data-weave-070 -b 070-incident-chat-experience`），shell 内 `export SPECIFY_FEATURE_DIRECTORY=specs/070-incident-chat-experience` 钉住特性指针
- 后端改动后 `./dev-install.sh` 再起 api（否则运行旧 jar）；长跑命令 setsid 脱离（CLAUDE.md 硬规则）

---

## Notes

- [P] = 不同文件且无未完成依赖
- 每任务/逻辑组后提交；任一 Checkpoint 可停下独立验收
- IME/滚动类交互 jsdom 假绿风险高，一律以浏览器门为准
- 后端每步 `./mvnw -q -pl <module> compile`、前端每步 `pnpm typecheck` 零错误再继续
