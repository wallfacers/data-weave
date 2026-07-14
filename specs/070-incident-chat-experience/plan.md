# Implementation Plan: 监督席对话体验企业级打磨

**Branch**: `070-incident-chat-experience` | **Date**: 2026-07-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/070-incident-chat-experience/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

消除监督席（069 落地）的 demo 感：①加载/连接/空态三态可信呈现；②Agent 回复接入流式 Markdown + Shiki 双主题代码高亮；③composer 升级（auto-grow / IME 组字保护 / 发送-停止状态机）；④发言者真实身份服务端认定（`actor`+`actor_name` 落库，废除 body 自报）；⑤时间戳/头像/分组/hover 复制；⑥无抖动跟随滚动 + 可拖拽分栏。后端仅两处接缝：actor 身份透传（含 `incident_message` 加列 + schema_version 升版）与 Agent 打断端点（`LlmChatClient` 读循环取消检查点 + 每 incident 取消句柄 + 写闸门 L0 规则）。多人协作（presence/@/未读等）划归 071。设计决策记录：`docs/superpowers/specs/2026-07-14-incident-console-enterprise-ui-design.md`。

## Technical Context

**Language/Version**: 前端 TypeScript（React 19.2.4 / Next.js 16.2.9, App Router+Turbopack）；后端 Java 25（Spring Boot 4 / WebFlux / Jackson 3）

**Primary Dependencies**:
- 前端既有：`shiki ^4.2.0`（自定义 `dataweave-light/dark` 双主题，`lib/highlighter.ts` 单例 + `CodeBlock` 组件）、`next-themes`、`next-intl`、shadcn base-style（已有 `card/input/tooltip/dw-scroll`）
- 前端新增：`streamdown`（流式 Markdown）、shadcn `resizable`（引入 `react-resizable-panels`）、shadcn `textarea` + `avatar`
- 后端既有：`LlmChatClient`（JDK HttpClient 阻塞流式）、`GatedActionService`/`PolicyEngine`、`IncidentEventPublisher`（SSE）、`JwtAuthFilter`/`TenantContext`

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）——`incident_message` 加 `actor_name` 列；`schema_version` 0.19.0 → 0.20.0（单 DDL `schema.sql`，无迁移脚本）

**Testing**: 前端 vitest 4（jsdom + testing-library）+ Playwright 浏览器门（全局 `@playwright/test`，`frontend/e2e/`）；后端 JUnit 5 + AssertJ + WebTestClient（`JwtTestSupport` 伪造 Bearer），H2 与 PG 双方言验证

**Target Platform**: Web（桌面浏览器为主）；后端 Linux 服务

**Project Type**: Web 应用（Next.js 前端 + Spring Boot 四模块 DDD 后端，dataweave-api / dataweave-master 受影响）

**Performance Goals**: 流式渲染全程无版式跳动（未闭合围栏稳定渲染）；跟随滚动每帧 ≤1 次（rAF 去重）；SSE 回显延迟感知 <200ms（服务端回显、无乐观插入）

**Constraints**: DESIGN.md 中性主题不变（不引入 workhorse 暖调/渐变）；卡片间距走 `--card-spacing`/`gap-2.5` token；加载态必用 `LoadingState`（`variant`，内建最小 1s 防闪）；i18n 双 bundle 键集一致；禁用省略号表加载；打断为写操作必须过闸门留痕且策略 L0

**Scale/Scope**: 前端 ~6 个组件改造 + 2 个新 hook + 1 个新渲染组件；后端 1 个新端点 + 5 个类的 actor 透传改造 + 1 列 DDL + 1 行 policy 种子；两条 SSE 语义微扩（message.actorName、cancel 后的收尾事件）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|---|---|---|
| I. Files-First | 不触碰任务/工作流定义文件面 | N/A，通过 |
| II. Server is Source of Truth | 强化：发言者身份由服务端依据 JWT 认定，废除客户端自报 actor；消息以落库回显为唯一真相 | 通过（正向强化） |
| III. Two-Legged Debugging | 不触碰 CLI/本地运行时 | N/A，通过 |
| IV. AI Lives in Local Agent | **不新增服务端 AI 大脑**。069 已落地的事故运维 Agent 是既有 sanctioned 面（AI 运维北极星方向，069 spec 已过章程检查）；070 仅为其对话面做 UI 打磨与"打断"控制能力，不扩大服务端推理面。不可让渡内核第 3 条（不损伤观测/调度）满足：不动调度内核，SSE 观测面仅增益 | 通过（附注记录） |
| V. Reuse the Kernel | 打断走 `ActionRequest → GatedActionService → PolicyEngine` + `agent_action` 留痕，策略经 `policy_rules` 种子（L0）配置而非代码绕行；复用既有 SSE/消息/审批骨架，不重写 | 通过 |

**Post-design 复查（Phase 1 后）**: 设计工件未引入新违反项——cancel 端点契约（contracts/agent-cancel.md）明确走闸门；data-model 仅加列不改既有语义；前端渲染层选型不触及后端内核。**GATE PASS**。

## Project Structure

### Documentation (this feature)

```text
specs/070-incident-chat-experience/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── chat-identity.md
│   ├── agent-cancel.md
│   └── sse-message.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
frontend/
├── components/
│   ├── ui/
│   │   ├── resizable.tsx                    # 新增（shadcn，引入 react-resizable-panels）
│   │   ├── textarea.tsx                     # 新增（shadcn）
│   │   └── avatar.tsx                       # 新增（shadcn）
│   └── workspace/
│       ├── shared/
│       │   ├── loading-state.tsx            # 复用（不改）
│       │   ├── code-block.tsx               # 复用/桥接进 streamdown 代码块
│       │   └── chat-markdown.tsx            # 新增：Streamdown 封装（流式安全 + ErrorBoundary + 双主题）
│       └── views/
│           ├── supervision-view.tsx         # 改：Resizable 分栏 + 加载态门
│           └── supervision/
│               ├── chat-composer.tsx        # 重写：auto-grow/IME/发送-停止状态机
│               ├── incident-thread.tsx      # 改：气泡 markdown、头像/时间戳/分组、hover 复制、滚动接入
│               ├── live-feed.tsx            # 改：LoadingState 门 + Card 复用
│               ├── briefing-banner.tsx      # 改：MarkdownLite → chat-markdown；LoadingState 门
│               └── incident-visuals.tsx     # 增：MessageAvatar、DateSeparator（登记 DESIGN.md）
├── hooks/
│   └── use-auto-scroll.ts                   # 新增：rAF 去重跟随 + 上滑暂停 + 回底
├── lib/
│   ├── auth.tsx                             # 复用（useAuth 取 username/displayName）
│   ├── highlighter.ts / syntax-palette.ts   # 复用：streamdown 代码块接现有双主题
│   └── supervision/
│       ├── types.ts                         # 改：Message.actorName、ConnectionPhase
│       ├── store.ts                         # 改：connectionPhase、打断收尾
│       ├── api.ts                           # 改：chat 不再传 actor；新增 cancelAgent()
│       └── use-incident-stream.ts           # 改：connectionPhase 输出
├── messages/{zh-CN,en-US}.json              # 新增键（双 bundle 同步）
└── e2e/supervision.spec.ts                  # 扩：070 浏览器门场景

backend/
├── dataweave-api/src/main/
│   ├── java/com/dataweave/api/interfaces/IncidentController.java   # 改：chat/markHandled/reverify/close 身份取 TenantContext；新增 POST /{id}/agent/cancel
│   └── resources/
│       ├── schema.sql                       # 改：incident_message + actor_name；schema_version 0.20.0
│       └── data.sql                         # 改：policy_rules 新行 incident_agent_cancel L0
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/incident/
│   │   ├── IncidentConversationService.java # 改：actor/actorName 参数化；取消句柄注册与打断收尾
│   │   └── IncidentAgentService.java        # 改：actorOrDefault 调用点透传
│   ├── domain/.../IncidentMessage.java      # 改：+actorName
│   ├── infrastructure/incident/IncidentMessageRepository.java  # 改：INSERT/映射 +actor_name
│   └── application/lineage/agent/LlmChatClient.java            # 改：streamChat 取消检查点（BooleanSupplier）
└── src/test/…                               # IncidentControllerIT 扩身份/cancel 用例；新 InterruptIT
```

**Structure Decision**: 沿用既有 Web 双项目结构与 DDD 分层（domain ← application ← infrastructure ← interfaces）。前端改造收敛在 `supervision` 视图目录 + 三个可复用原语（`chat-markdown`、`use-auto-scroll`、`MessageAvatar`/`DateSeparator`，均登记 DESIGN.md 公共组件目录）；后端改造收敛在 incident 应用服务与一处 `LlmChatClient` 接缝，不触碰调度内核。

## Complexity Tracking

> 无章程违反项需要豁免。唯一附注（原则 IV 的 069 既有服务端事故 Agent）已在 Constitution Check 表内记录，非本特性引入。
