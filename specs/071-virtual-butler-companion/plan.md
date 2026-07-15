# Implementation Plan: 虚拟管家监督席(Virtual Butler Companion)

**Branch**: `071-virtual-butler-companion` | **Date**: 2026-07-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/071-virtual-butler-companion/spec.md`

## Summary

用原创程序化 3D 机器人管家(Vega)承载新一代监督席:形象由真实系统状态驱动(五形态),四领域巡检例程由平台调度内核模式(guard 表 + SKIP LOCKED claim)触发 workhorse sidecar 的 headless 会话产出结构化汇报,右侧项目级共享的卡片栈 + 全局/锚定双入口对话(流式可打断),一切写动作过 PolicyEngine 闸门。成熟后替代 070 监督席(SC-008 试运行验证)。技术路线已由原型验证(`tmp/companion-prototype/`),全部决策见 [research.md](research.md)。

## Technical Context

**Language/Version**: 前端 TypeScript / Next.js 16(App Router, Turbopack)+ React 19;后端 Java 25 / Spring Boot 4(WebFlux, Jackson 3);sidecar Go(workhorse-agent,既有二进制,本特性不改其代码)

**Primary Dependencies**: 前端新增 `three`(r169+,唯一新依赖;不引入 R3F);复用 shadcn/base-style、hugeicons、next-themes、next-intl、`ChatMarkdown`/`ChatComposer`/`ResizablePanel` 等 070 原语。后端零新依赖:Spring Data JDBC + JdbcTemplate、Redis EventBus、JDK HttpClient(WorkhorseBrainClient)

**Storage**: PostgreSQL / H2 双方言(`schema.sql` 0.20.0→0.21.0,+`patrol_routine`/`patrol_run`/`patrol_report`/`companion_message` 4 表,见 [data-model.md](data-model.md));Redis(SSE 扇出)

**Testing**: 后端 JUnit 5 + AssertJ + WebTestClient(JwtTestSupport 带 Bearer;H2 独立库名防串台);前端 vitest + playwright 浏览器门(SSE/主题切换/多用户同步场景必真浏览器)

**Target Platform**: Web(桌面浏览器,WebGL2 优先,含 2D orb 降级);后端 Linux/WSL2,`scheduler.mode=all-in-one|distributed` 双模式

**Project Type**: Web application(frontend + backend 双项目 + workhorse sidecar 部署面)

**Performance Goals**: 首屏 ≤3s(SC-001);状态推送在线延迟 ≤5s(SC-002);对话首片段 ≤5s、打断 ≤1s(SC-005);3D 场景目标 60fps、`prefers-reduced-motion` 降motion

**Constraints**: SSE 直连 `SSE_BASE` 绕 Next rewrite(缓冲坑);3D 取色仅经语义 token(FR-021 + DESIGN.md 无手写 dark:);workhorse token 不出后端;巡检单轮超时 120s;调度不变量①-④ 全适用于 PatrolScheduler

**Scale/Scope**: 单项目 4 例程、汇报量级 ~百/日;1 个新 Workspace 视图 + ~8 REST 端点 + 1 SSE 端点 + 4 表;070 并存不迁移

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 论证 |
|---|---|---|
| I. Files-First | ✅ N/A-pass | 不触碰任务/工作流定义面;巡检例程是运行态治理数据(表),非任务定义,不产生"DB-only 配置"违例——例程不属于 pull/push 契约范畴 |
| II. Server is Source of Truth | ✅ pass | 汇报/会话/例程全部服务端持久化;项目隔离全覆盖(036 约定);不引入双向同步 |
| III. Two-Legged Debugging | ✅ N/A-pass | 不触碰 CLI/本地运行时/执行器语义 |
| IV. AI Lives in the Local Agent | ⚠️ **justified deviation**(见 Complexity Tracking) | 内核三条逐条核对:① 服务端无 AI 大脑——推理全部在 workhorse **sidecar 进程**,master 只有编排/通道/治理(`CompanionBrain` 端口 + HTTP 客户端,无推理逻辑);② AI 能力由 agent 提供——workhorse 即 BYO-agent,经 MCP 消费 `dataweave__*` 工具;③ 不损伤观测与调度内核——纯增量,PatrolScheduler 是内核模式的新消费者非修改者。偏离点:原则 IV 行文针对"开发者本地 coding agent 的创作面",而管家是**运行态运维 agent**(服务端编排其会话)——此偏离由 069/070(监督席 agent)开创并经北极星方向文档(AI 运维=主页)定调,本特性延续既定航向,非新开口子 |
| V. Reuse the Kernel | ✅ pass | 调度(guard+claim+CAS)、PolicyEngine L0–L4 写闸(管家一切写动作过闸留 `agent_action`)、SSE/EventBus 骨架、070 对话交互原语全复用;零内核重写 |

**Post-Phase-1 re-check**: 设计工件未引入新偏离;IV 的偏离范围未扩大(brain 仍在 sidecar,契约中无服务端推理面)。**GATE 通过**(附 Complexity Tracking 记录)。

## Project Structure

### Documentation (this feature)

```text
specs/071-virtual-butler-companion/
├── plan.md              # 本文件
├── research.md          # Phase 0(R1-R9 决策)
├── data-model.md        # Phase 1(4 表 + 派生状态 + 状态机)
├── quickstart.md        # Phase 1(US1-US4 验证脚本)
├── contracts/
│   └── companion-api.md # Phase 1(SSE 事件集 + REST + CompanionBrain 端口)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 产出(非本命令)
```

### Source Code (repository root)

```text
backend/
├── dataweave-api/src/main/resources/schema.sql          # 0.21.0:+4 表 + seed 例程
├── dataweave-api/src/main/java/com/dataweave/api/
│   └── interfaces/companion/                            # CompanionController(REST) + CompanionStreamHandler(SSE)
└── dataweave-master/src/main/java/com/dataweave/master/
    └── companion/
        ├── domain/          # PatrolRoutine/PatrolRun/PatrolReport/CompanionMessage + CompanionBrain 端口 + CompanionStateResolver
        ├── application/     # PatrolService(触发/聚合/未完成兜底) + CompanionChatService(会话/打断) + ReportService(关闭/已读)
        └── infrastructure/  # Jdbc*Repository + WorkhorseBrainClient + MockBrain + PatrolScheduler(guard+claim) + CompanionEventPublisher(Redis)

frontend/
├── lib/workspace/{views.ts,registry.tsx,nav-groups.ts}  # 注册 companion 视图(三处 + i18n key)
├── lib/companion/           # api.ts / store.ts / types.ts / use-companion-stream.ts(套 supervision 骨架)
├── components/workspace/views/companion-view.tsx        # 视图总装(ViewContainer)
├── components/workspace/views/companion/
│   ├── companion-stage.tsx  # three.js 场景(dynamic import, SSR off;token 取色;WebGL 探测)
│   ├── bot-model.ts         # 程序化机器人构建 + 状态动画(移植原型)
│   ├── face-screen.ts       # CanvasTexture 表情系统
│   ├── orb-fallback.tsx     # 2D 降级形象
│   ├── report-stack.tsx     # 右侧卡片栈(关闭/未读/聚合)
│   ├── report-card.tsx      # 卡片 + 锚定迷你对话(复用 ChatMarkdown/ChatComposer)
│   ├── briefing-bar.tsx     # 顶部概况
│   └── speech-bubble.tsx    # 播报字幕
├── messages/{zh-CN,en-US}.json                          # companion.* 命名空间双 bundle
├── DESIGN.md                                            # +companion surface 豁免条目 + --companion-* 状态色 token
└── app/globals.css                                      # token 亮/暗两套值同步

deploy/workhorse/
└── agents/companion.yaml    # 管家 agent 角色(人设 + 工具白名单)
```

**Structure Decision**: Web 双项目既有结构的纯增量:后端按 DDD 四层在 master 落 `companion` 领域包、api 只挂 interfaces;前端按 070 监督席同构模式(views/ + lib/ 成对)落 `companion`,3D 相关全部收敛在 `companion/` 子目录内以便未来替换形象层。070 的 `supervision` 目录与 `incident_*` 表在并存期零改动。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 原则 IV 的运维面偏离:服务端编排一个(sidecar)运维 agent 的会话与巡检,而非仅由开发者本地 coding agent 经 MCP/CLI 操作平台 | 监督席/虚拟管家是**运行态值班场景**:巡检必须在无人在场时持续发生并留汇报,不能依赖某个开发者的编辑器在线;069/070 已确立该航向,北极星方向文档将 AI 运维监督席定为主页 | ① 纯本地 agent 轮询平台(loops 由每个用户自己跑)——值班连续性无保障、汇报无共享真相源、审计闸门旁路化,拒绝;② 服务端内嵌 agent 循环——违反 IV 不可让渡内核第 1 条,拒绝。采用折中:推理在 sidecar、服务端零推理仅编排,IV 的三条内核逐条保持成立。**建议**:实现收口后对 constitution IV 做一次 MINOR 修订,把"运行态运维 agent(sidecar 编排)"明示为例外面,消除后续特性的重复论证 |
