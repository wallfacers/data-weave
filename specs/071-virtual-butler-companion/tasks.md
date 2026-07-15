# Tasks: 虚拟管家监督席(Virtual Butler Companion)

**Input**: Design documents from `/specs/071-virtual-butler-companion/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/companion-api.md, quickstart.md

**Tests**: 仓库硬规则"no test = not done",每个 US 含测试与浏览器门任务。

**Organization**: 按用户故事分组;**双 Agent 分工标注**:🅰 = 后端 Agent(backend/ + deploy/),🅱 = 前端 Agent(frontend/)。契约冻结在 `contracts/companion-api.md`,两轨以它为唯一接缝,谁都不得单方面改契约(要改先找主 Claude 仲裁)。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件、无未完成依赖)
- **[Story]**: 所属用户故事(US1-US4)

## Phase 1: Setup

- [ ] T001 [P] 🅱 前端引入 `three`(r169+)与 `@types/three`:`cd frontend && pnpm add three && pnpm add -D @types/three`,`pnpm typecheck` 零错基线
- [x] T002 [P] 🅰 新增管家 agent 角色 `deploy/workhorse/agents/companion.yaml`:管家人设 system prompt(值班运维管家 Vega、结构化汇报要求)+ 工具白名单(只读 `dataweave__*` 优先,写工具依赖 MCP 细闸),参照 `deploy/workhorse/config.runtime.yaml` 既有双闸门约定

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ 两轨的用户故事任务都依赖本阶段;T003 是 🅰 全部任务的前置,T007/T008/T009 是 🅱 全部任务的前置。**

- [x] T003 🅰 `backend/dataweave-api/src/main/resources/schema.sql` 升 **0.21.0**:+`patrol_routine`/`patrol_run`/`patrol_report`/`companion_message` 4 表(列/约束/索引严格按 data-model.md;UNIQUE(routine_id, scheduled_fire_time) 幂等;隔离列+索引)+ seed 四领域默认例程(research R7 频率)+ 文件头与 `schema_version` INSERT 同步;PG/H2 双方言兼容(IF NOT EXISTS、无 `||` 拼接)
- [x] T004 [P] 🅰 领域对象与仓储:`backend/dataweave-master/src/main/java/com/dataweave/master/companion/domain/`(PatrolRoutine/PatrolRun/PatrolReport/CompanionMessage + 状态枚举)+ `infrastructure/`(Jdbc 四仓储,自增主键用 GeneratedKeyHolder——CALL IDENTITY 跨方言坑)+ H2 仓储 IT(`@TestPropertySource` 独立库名防串台)
- [x] T005 [P] 🅰 大脑端口与适配器:`domain/CompanionBrain.java`(openChat/runPatrol/healthy,契约见 companion-api.md 内部端口节)+ `infrastructure/WorkhorseBrainClient.java`(HTTP 8300:POST /v1/sessions、GET/POST stream SSE 消费、cancel;JDK HttpClient)+ `infrastructure/MockBrain.java`(降级固定话术/未完成产出)+ 健康探测选择逻辑 + 单测
- [x] T006 🅰 SSE 通道骨架:`backend/dataweave-api/.../interfaces/companion/CompanionStreamHandler.java`(`GET /api/companion/stream?projectId&token`:鉴权、snapshot 事件、心跳、Last-Event-ID)+ `master/companion/infrastructure/CompanionEventPublisher.java`(Redis EventBus 扇出,套 incident stream 骨架);依赖 T003/T004
- [ ] T007 [P] 🅱 视图注册三处:`frontend/lib/workspace/views.ts`(ViewType `companion` + VIEW_META,复用监督席权限 key)、`registry.tsx`(VIEW_RENDER + hugeicons 图标)、`nav-groups.ts`(导航分组,过 nav-groups.test.ts 全集不变量)+ `messages/{zh-CN,en-US}.json` 补 `views.companion` 与 `companion.*` 命名空间(双 bundle key 齐全)+ 空视图壳 `components/workspace/views/companion-view.tsx`(ViewContainer)
- [ ] T008 [P] 🅱 数据层:`frontend/lib/companion/types.ts`(ReportView/MessageView/CompanionState/Briefing,严格对齐契约)+ `api.ts`(authFetch + X-Project-Id,close/read/chat/cancel/messages/routines 全端点)+ `store.ts`(reports/state/briefing/messages 归约)+ `use-companion-stream.ts`(EventSource 直连 SSE_BASE,7 类事件,断线不清数据)
- [ ] T009 🅱 设计契约先行:`frontend/DESIGN.md` 登记 companion 沉浸式 surface 豁免条目(全出血 canvas/字幕气泡;Input/Button/severity 色仍强制复用)+ 定义 `--companion-*` 五状态色 token;`app/globals.css` 亮/暗两套值同步;`pnpm design:lint` 通过

**Checkpoint**: 契约两侧骨架就绪——US 阶段可双轨并行。

## Phase 3: User Story 1 - 管家视图与状态呈现 (Priority: P1) 🎯 MVP

**Goal**: 打开视图见形象,形象由真实系统状态驱动,主题兼容,权限与降级健全。

**Independent Test**: quickstart US1 六步(首屏 3s/异常→警觉/回落/主题切换/权限/WebGL 降级)。

- [x] T010 🅰 [US1] 状态归一:`master/companion/domain/CompanionStateResolver.java`(alert/patrol/think/speak/idle 优先级规则,data-model.md 派生状态节)+ 状态变更时经 EventPublisher 发 `state` 事件;单测覆盖优先级矩阵
- [x] T011 [P] 🅰 [US1] 概况统计:`application/CompanionBriefingService.java`(今日 run 数/未关闭 DANGER+WARN 数/启用例程最近下次触发时间)接入 snapshot 与 `briefing` 事件
- [ ] T012 [P] 🅱 [US1] 形象移植:`components/workspace/views/companion/bot-model.ts`(程序化机器人构建+五状态动画,自 `tmp/companion-prototype/index.html` 移植,顶部注版权说明源自 NOTICE.md)+ `face-screen.ts`(CanvasTexture 表情:眨眼/微笑/警觉/思考/波形嘴)
- [ ] T013 🅱 [US1] 场景组件:`companion-stage.tsx`("use client" + next/dynamic ssr:false;three 场景生命周期含 dispose;**token 取色**:getComputedStyle 读 `--companion-*`/语义 token → THREE.Color,`useTheme().resolvedTheme` 变化重取色不重建;WebGL 探测失败切 T014)
- [ ] T014 [P] 🅱 [US1] 降级形象:`companion/orb-fallback.tsx`(CSS 能量球,同一状态机换色/脉动)+ `prefers-reduced-motion` 停粒子摆动
- [ ] T015 🅱 [US1] 视图总装:`companion-view.tsx` 接 use-companion-stream(state→stage、briefing→`briefing-bar.tsx`、播报→`speech-bubble.tsx`);连接三态(加载用 LoadingState/断线提示/空态);权限不足走 view-gate 既有守卫
- [x] T016 🅰 [US1] 后端测试:StateResolver 优先级单测 + stream 接入 IT(WebTestClient+JwtTestSupport:snapshot 结构、异常种子→state=alert)
- [ ] T017 🅱 [US1] 浏览器门:playwright 跑 quickstart US1 全六步,**亮/暗主题各截图一张**,vitest 补 store 归约单测

**Checkpoint**: US1 可独立交付演示(MVP)。

## Phase 4: User Story 2 - 主动巡检与汇报卡片 (Priority: P2)

**Goal**: 四领域例程巡检产出结构化汇报,卡片栈项目级共享,零静默丢失。

**Independent Test**: quickstart US2 六步(触发产出/播报/双浏览器关闭同步/未完成兜底/离线补看)。

- [x] T018 🅰 [US2] 巡检调度器:`master/companion/infrastructure/PatrolScheduler.java`——cron 解析 + `patrol_run` UNIQUE(routine_id, scheduled_fire_time) 幂等落 CLAIMED + SKIP LOCKED 认领 + CAS 状态推进 + 超时 reaper(→TIMEOUT);**遵守调度不变量①-④,持久化在事务内、brain 调用在事务外**
- [x] T019 🅰 [US2] 巡检编排:`application/PatrolService.java`——runPatrol(领域提示词模板→CompanionBrain.runPatrol→结构化 JSON 解析落 patrol_report;解析失败/超时/brain 不可用→INFO"未完成"汇报;同领域 10 分钟聚合窗口 aggregate_count)+ 手动触发入口;单测覆盖三条失败路径
- [x] T020 🅰 [US2] 汇报服务与接口:`application/ReportService.java`(close 项目级幂等含 closed_by/closed_at、read、列表)+ `interfaces/companion/CompanionController.java` 挂 `GET /reports`、`POST /reports/{id}/close`、`POST /reports/{id}/read` + `report` SSE 事件(created/closed)
- [x] T021 🅰 [US2] 调度测试:双实例同 fire_time 只执行一次的幂等 IT + 未完成兜底 IT + close 幂等/同步 IT(H2 独立库名)
- [ ] T022 🅱 [US2] 卡片栈:`companion/report-stack.tsx`(倒序堆叠/未读徽标/整栈收起展开)+ `report-card.tsx`(severity 色点/时间/摘要/aggregate_count/关闭按钮/「查看详情」跳转监督席或对象详情——FR-019 直达处置口径);关闭调 API 后以 SSE `report:closed` 为准移除
- [ ] T023 🅱 [US2] 浏览器门:playwright 双 context 验项目级关闭同步 + 离线补看 + 新汇报播报联动(quickstart US2);store 单测补 report 归约

**Checkpoint**: US1+US2 = 可试运行的"主动值班管家"。

## Phase 5: User Story 3 - 与管家对话并派活 (Priority: P3)

**Goal**: 全局+锚定双入口对话,流式可打断,写操作过闸门且状态如实回报。

**Independent Test**: quickstart US3 五步(流式/锚定/打断/审批回报/降级)。

- [x] T024 🅰 [US3] 会话服务:`application/CompanionChatService.java`——chat(actor 服务端认定沿 070 标准;reportId 非空时注入该汇报巡检上下文到 brain 会话;身份透传注意 ThreadLocal 不过线程池)+ cancel(L0 免审批,1s 内生效)+ 消息落库 + `message/delta/end` SSE;`CompanionController` 挂 chat/cancel/messages 端点;brain 不可用返回 `companion.brain_unavailable`(BizException 本地化)
- [x] T025 🅰 [US3] 会话测试:chat 流式 IT + 打断 IT(end.interrupted=true)+ 降级错误码 IT + 锚定上下文注入单测
- [ ] T026 🅱 [US3] 对话 UI:`report-card.tsx` 内迷你对话 + 视图底部全局 composer——复用 `ChatMarkdown`(流式富文本/崩溃隔离)与 `ChatComposer`(auto-grow/IME 组字保护/发送-停止状态机);写操作回复按 outcome 分流展示审批状态(勿只看 code===0);形象联动 think/speak
- [ ] T027 [P] 🅱 [US3] 语音占位:composer 内禁用态麦克风按钮 + 「规划中」i18n tooltip(FR-020)
- [ ] T028 🅱 [US3] 浏览器门:playwright 跑 quickstart US3 五步(流式首片段/中途打断/锚定 vs 全局回答差异/停 brain 降级提示非空白)

## Phase 6: User Story 4 - 巡检例程的平台化治理 (Priority: P4)

**Goal**: 例程可启停/调频/手动触发,执行历史可追溯,严格项目隔离。

**Independent Test**: quickstart US4 四步。

- [x] T029 🅰 [US4] 治理接口:`CompanionController` 挂 `GET /routines`、`PATCH /routines/{id}`(缺失=不改/显式 null=清空 scope;确认 CORS allowedMethods 含 PATCH)、`POST /routines/{id}/trigger`、`GET /routines/{id}/runs`;变更落 updated_by 审计;IT 覆盖 PATCH 语义与隔离
- [ ] T030 🅱 [US4] 治理面板:`companion/routine-panel.tsx`(视图内设置抽屉:四领域启停开关/cron 编辑/手动触发按钮/执行历史列表),briefing「下轮巡检」随改动联动
- [ ] T031 🅱 [US4] 浏览器门:quickstart US4 前三步(停用不产出/改 cron 联动/历史可见)

## Phase 7: Polish & Cross-Cutting

- [x] T032 🅰 分布式回归:`scheduler.mode=distributed` 双 master 实跑,`patrol_run` 无重复无 straggler(quickstart US4 步 4 + 回归门);长跑注意 H2 心跳过期坑
- [ ] T033 [P] 🅱 前端收口:`pnpm typecheck` + `pnpm design:lint` + i18n 双 bundle diff 校验 + vitest 全绿;检查无 `…` 表加载态、无手写 dark:
- [ ] T034 [P] 🅰 后端收口:`mvnd -pl dataweave-master,dataweave-api`(禁 build-cache,grep "Cache disabled" 确认真编译)compile+test 全绿;H2 与 PG 双方言各起一遍验 DDL
- [ ] T035 主Claude 兜底:跨轨集成验收(quickstart 全量)+ /code-review 双轨 diff + FR-019 处置闭环核对 + 070 监督席并存回归(既有 IT 不红)+ constitution IV MINOR 修订提案落 `.specify/memory/constitution.md` 议题

## Dependencies

```
Phase 1 (T001,T002) ──┐
Phase 2: T003 → T004/T005/T006(🅰);T007/T008/T009(🅱,互相可并行)
US1 (P1): 🅰 T010/T011 依赖 T003-T006;🅱 T012-T015 依赖 T007-T009;T016/T017 收口
US2 (P2): 🅰 T018→T019→T020→T021;🅱 T022 依赖 T008+契约,T023 依赖后端 T020 联调
US3 (P3): 🅰 T024→T025;🅱 T026/T027 依赖 T008,T028 依赖 T024 联调
US4 (P4): T029(🅰) ∥ T030(🅱 UI 可先行,联调依赖 T029) → T031
Polish:  T032-T034 双轨各自收口 → T035 主Claude 终审
故事间:US1 是演示基线;US2/US3 互不依赖可换序;US4 依赖 US2 的 run/report 存在
```

## Parallel Execution Examples

- **Phase 2 全程双轨并行**:🅰 T003→T004/T005/T006 与 🅱 T007/T008/T009 无共享文件。
- **US1 内**:T011∥T010(不同服务文件);T012∥T014(模型 vs 降级);T012 完成后 T013 接。
- **跨故事**:🅰 做 US2 调度(T018-T021)时,🅱 可先行 US3 对话 UI(T026 用 mock 流)。
- **唯一串行点**:T003(schema)先于所有 🅰 任务;联调任务(T023/T028/T031)等对侧端点就绪。

## Implementation Strategy

MVP = Phase 1+2+US1(T001-T017):有形象、有真实状态驱动、主题兼容——即可给用户演示定调。此后 US2(价值主体)→ US3 → US4 增量交付,每个 US 收口跑自己的浏览器门,最后 T035 主 Claude 统一评审收口。双 Agent 各自在独立 git worktree 工作(仓库硬规则),契约冻结、越界改动先仲裁。
