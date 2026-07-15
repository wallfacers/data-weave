# Research: 监督席对话体验企业级打磨（070）

Phase 0 输出。所有 Technical Context 未知项已消解；每项含 Decision / Rationale / Alternatives。
事实来源：本仓前后端代码调研（2026-07-14）+ workhorse-assistant 参考项目调研 + 已确认设计文档 `docs/superpowers/specs/2026-07-14-incident-console-enterprise-ui-design.md`。

## R1. 流式 Markdown 渲染引擎

- **Decision**: 引入 `streamdown`（Vercel 流式 Markdown React 组件），封装为 `components/workspace/shared/chat-markdown.tsx`；代码块高亮接现有 `lib/highlighter.ts` 的 `dataweave-light/dark` 双主题（`defaultColor:false` + `--shiki-light/--shiki-dark` CSS 变量，主题切换零重高亮）。若 streamdown 的 shiki 主题注入点不接受自定义主题对象，则用其 `components` 覆盖 `code` 渲染、桥接既有 `CodeBlock` 组件。安装时锁定与 React 19 / shiki 4 兼容的最新版。
- **Rationale**: ①专为流式设计——未闭合围栏/表格/加粗在 delta 途中不破版式（FR-003 核心）；②DESIGN.md 原有「Streamdown+Shiki」规范（CopilotKit 时代遗留、现悬空），复用规范修复文档漂移；③仓内已有 shiki 4 与自定义双主题、`CodeBlock`、`syntax-palette` 单一真相，避免第二套高亮体系。
- **Alternatives considered**:
  - `react-markdown + remark-gfm`：每个 delta 全量重解析重建 DOM，代码块闪烁（workhorse 注释实测放弃的路线）；需自建未闭合围栏容错。
  - workhorse 的 `marked + morphdom + dompurify` 自研引擎：效果最优但引入三个依赖 + ~400 行自研解析/打补丁代码，维护面大；与 React 声明式模型异质（直接 DOM patch）。
  - 继续自绘 `MarkdownLite`：仅支持 4 种语法，无代码高亮，达不到 FR-003/FR-004。

## R2. 发言者身份来源与 displayName 获取

- **Decision**: Controller 层从 `TenantContext` 捕获 `userId`/`username`（`JwtAuthFilter` 已解析），**在提交后台线程池前**作为参数显式传入 Service（ThreadLocal 不透传后台线程，实测确认）。`actor` 列存 `username`（沿用该列既有语义「ops-agent | 用户名 | system」），新增 `actor_name` 列存显示名。displayName 通过 user 表查询获得（`UserController` 已有 displayName 字段），在 api 模块内做小型进程内缓存（username→displayName，TTL 几分钟即可）；查不到时回退 username。`ChatRequest` 等 body 中的 `actor` 字段废弃忽略。
- **Rationale**: ①JWT claims 目前只有 `sub/tenantId/username/roles`，扩 JWT 要动签发/验签/既有 token 兼容，面大收益小；②user 表查询 + 缓存改动最小且始终反映最新改名；③`actor` 列语义不变保证存量兼容（FR-011 兜底）。
- **Alternatives considered**:
  - 扩 JWT claim 加 displayName：登录面改动 + 旧 token 无此 claim 仍需回退逻辑，双份复杂度。
  - `actor` 直接存 displayName：破坏该列「username」既有语义，且 displayName 可变导致历史归属漂移。
  - 前端展示时按 username 批量反查 displayName：每个客户端各查一遍、离线历史不可读，不如落库。

## R3. Agent 打断机制（最小接缝）

- **Decision**: 三点改造：
  1. `LlmChatClient.streamChat(...)` 增加 `BooleanSupplier cancelled` 参数，阻塞读循环（`reader.readLine()` 逐行处）每行检查，取消即关闭流退出；
  2. `IncidentConversationService` 内置每 incident 取消句柄注册表（`ConcurrentHashMap<UUID, AtomicBoolean>`），`respond(...)` 开始时注册、结束时清除；新增 `interrupt(incidentId)`：置位句柄，将已产出的部分内容以 AGENT_SAY 落库（payload 带 `interrupted:true`）并发既有收尾事件（带 streamId），使前端按既有 streamId 机制清空 delta 缓冲；无在途流时返回「无可打断」的幂等成功；
  3. `IncidentController` 新增 `POST /api/incidents/{id}/agent/cancel`，构造 `ActionRequest(toolName="incident_agent_cancel", actorSource="UI", actor=<username>)` 经 `GatedActionService.submit` 过闸（复用 `submitGated` 既有模式），`policy_rules` 种子新行 `('TOOL','incident_agent_cancel',NULL,'L0',...)`。
- **Rationale**: ①`chatPool.submit` 的 Future 当前被丢弃、`streamChat` 无取消入参——句柄注册表 + 读循环检查点是侵入最小的组合（不引入 reactor 化重写）；②收尾复用既有 streamId 语义，前端 store 零新概念；③L0 直执行 + `agent_action` 留痕满足「停止键即时生效且可审计」（FR-007/FR-008），闸门种子行格式照抄 data.sql:485-492 现例。
- **Alternatives considered**:
  - `Future.cancel(true)` 中断线程：JDK HttpClient 阻塞读对 interrupt 响应不可靠，且粗暴中断跳过落库收尾，前端 delta 缓冲悬挂。
  - HTTP 层 abort（关闭底层连接）：需持有 HttpClient 内部句柄，API 不友好；读循环检查点行为可预期且可测。
  - 不过闸门直接调 Service：违反「所有副作用操作过闸门」硬规则与章程原则 V，不可行。

## R4. 可拖拽分栏

- **Decision**: 通过 shadcn CLI 添加 `resizable` 组件（底层 `react-resizable-panels`），`supervision-view.tsx` 的 feed/thread 双列改 `ResizablePanelGroup`；feed 面板 `minSize` 护栏 + `autoSaveId="supervision-split"`（库内建 localStorage 持久化，满足 FR-015 免手写存取）。
- **Rationale**: base-style 官方组件、依赖成熟（workhorse 同款库）；`autoSaveId` 原生满足宽度记忆。
- **Alternatives considered**: 手写 drag handler（重复造轮子、可访问性差）；CSS resize 属性（无法双向、无持久化）。

## R5. 无抖动跟随滚动

- **Decision**: 新建 `frontend/hooks/use-auto-scroll.ts`，实现 workhorse 验证过的模式子集：rAF 去重（每帧 ≤1 次跟随滚动）、`MutationObserver`+`ResizeObserver` 共享增长回调、wheel/touch 上滑意图检测暂停跟随（微抖阈值）、回底按钮以 opacity 切换不挂卸载；容器 CSS `overflow-anchor: auto` + `scrollbar-gutter: stable`。
- **Rationale**: 该 hook 框架无关、模式已被 workhorse 实测（253 行原型可裁剪）；opacity 切换避免 mount 抖动。
- **Alternatives considered**: `scrollIntoView` 每 delta 调用（每秒数十次强制布局、用户上滑被拽回）；`IntersectionObserver` 哨兵元素（对流式内容增长的粒度不足，仍需 rAF 合并）。

## R6. 头像 / 时间戳 / 消息分组

- **Decision**: shadcn CLI 添加 `avatar.tsx` + `textarea.tsx`；封装 `MessageAvatar`（人类=首字母、Agent=品牌图标，中性色）与 `DateSeparator`，连同升级版 `ChatComposer` 登记进 DESIGN.md 公共组件目录。分组/日期分隔为纯函数（输入消息序列，输出带分组标记的渲染列表），放 `lib/supervision/`，vitest 直测。自己/他人判定 = `message.actor === useAuth().user.username`。
- **Rationale**: reuse-first 硬规则要求缺失原语先补进设计系统再用；纯函数分组便于单测（5 分钟窗口、跨日边界）。
- **Alternatives considered**: 消息渲染时内联计算分组（每次 render 重算 + 无法单测边界）；引入完整 chat UI 库（如 assistant-ui：过重、主题冲突、绑定其数据模型）。

## R7. 加载/连接三态

- **Decision**: `use-incident-stream.ts` 输出 `connectionPhase: "connecting" | "live" | "degraded"`（初始 connecting；收到首个 snapshot → live；EventSource error → degraded，重连成功回 live），进 store。`LiveFeed`/`BriefingBanner` 在 connecting 时渲染既有 `LoadingState`（注意其 prop 名为 **`variant`**，`"centered"`，内建 `useLoadingMinSpin` 最小 1s）；空态仅 `live && feed.length===0`；degraded 时线程顶部提示条 + 消息保留。
- **Rationale**: 既有 `LoadingState` 完全满足 DESIGN.md 加载规范（含防闪），零新组件；三态与既有 SSE 断线降级逻辑正交叠加。
- **Alternatives considered**: 骨架屏（DESIGN.md 规定异步区统一 LoadingState，骨架屏需新设计约定）；以 store 是否为空猜测加载（正是当前假空态 bug 的根源）。

## R8. IME 组字保护与 composer 状态机

- **Decision**: `onKeyDown` 中 `if (e.nativeEvent.isComposing) return;` 再判 Enter/Shift+Enter；auto-grow 用 `textarea` 高度自适应（重置 height 后取 scrollHeight，上限 8 行转内部滚动）；状态机三态：idle（发送键，空文本禁用）→ sending（POST 在途）→ streaming（该 incident 有活跃 streamId：停止键）。停止键调 `api.cancelAgent(id)`，失败/超时回弹 + toast。
- **Rationale**: `isComposing` 是 CJK 误发的唯一可靠信号（workhorse 两处实践）；streaming 判定复用 store 中 delta/streamId 既有状态，无需新后端信号。
- **Alternatives considered**: keyCode 229 检测（遗留 API，React 19 下不保证）；固定 3 行 textarea（workhorse 现状，放弃其短板）。

## R9. schema 与种子变更

- **Decision**: `schema.sql`：`incident_message` 加 `actor_name VARCHAR(128)`（可空，存量行为 NULL）；`schema_version` 0.19.0 → **0.20.0**（DB 种子行与文件头同步改，遵循「任何表变更即升版」硬约定）。`data.sql`：`policy_rules` 追加 `incident_agent_cancel` 行，`base_level='L0'`，格式对齐现有 56-60 行。DDL 用 `IF NOT EXISTS`/H2-PG 兼容写法（既有方言坑约定：不用 `||` 拼接、两库各测）。
- **Rationale**: 单 DDL 无迁移是项目既定模式；`actor_name` 可空保证存量零迁移（前端兜底「操作员」）。
- **Alternatives considered**: 新建 actor 维表（本期仅展示需要，规范化收益不抵 JOIN 复杂度，071 若做成员体系再议）；复用 payload_json 塞显示名（不可索引、语义藏匿、SSE DTO 还得解包）。

## R10. 测试与验收基建

- **Decision**: 前端 vitest 沿用 `vitest.config.ts`（jsdom+testing-library）：store 三态流转、分组纯函数、composer 状态机；浏览器门扩展既有 `frontend/e2e/supervision.spec.ts`（全局 @playwright/test 绝对路径 import + `addInitScript` 注入 `dw.auth.*`，环境 `DW_WEB`/`DW_TOKEN`），注意其注入的 user JSON 需同步为含 `displayName/username` 的真实结构。后端扩 `IncidentControllerIT`（JwtTestSupport：username="tester"）断言 actor 落库=token 身份、body actor 被忽略；新增打断 IT（注册句柄→cancel→部分内容落库带 interrupted 标记→agent_action 留痕→policy L0 无 PENDING_APPROVAL）。H2/PG 双方言各跑。
- **Rationale**: 全部复用既有测试基建，无新工具链；e2e 注入 user 结构当前是 `{username,name}`，与 `AuthUser`（`displayName`）不一致，070 顺带修正（也正是 `currentUser()` 读 `u.name` 兜底假名 bug 的根源之一）。
- **Alternatives considered**: 为 IME/滚动写 jsdom 单测（jsdom 无真实合成事件/布局，假绿风险，归浏览器门）。
