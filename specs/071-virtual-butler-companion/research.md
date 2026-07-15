# Phase 0 Research: 虚拟管家监督席

**Feature**: 071-virtual-butler-companion | **Date**: 2026-07-15

原型已先行验证(`tmp/companion-prototype/`,playwright 实证:五状态动画/卡片栈/对话交互零报错),本文固化其技术结论并补齐 spec 留给 plan 的决策点。

## R1. 3D 形象渲染路线

**Decision**: 原生 `three` npm 包(r169+)+ 纯代码程序化构建机器人(移植原型 `buildBot` 逻辑),封装为单一客户端组件 `CompanionStage`(`"use client"` + dynamic import, SSR 关闭);不引入 react-three-fiber。

**Rationale**: 原型已验证该路线:零模型资产(版权归项目,见 `tmp/companion-prototype/NOTICE.md`)、体积零增量(无 .vrm/.glb)、表情由 CanvasTexture 实时绘制完全可编程(未来 TTS 播报直接复用波形嘴,无需音素口型)。场景只有一个且生命周期简单(单 canvas、单 render loop),R3F 的声明式抽象收益小于其依赖成本(R3F+drei 链条对 React 19/Next 16 的兼容跟进风险)。

**Alternatives considered**: ① react-three-fiber——声明式优雅但多两个重依赖,拒绝;② VRM 数字人(three-vrm + VRoid)——已在原型阶段被用户否决(观感+样例许可受限);③ 2D Lottie/CSS 动画——表现力不足以承载"科幻虚拟人"的定位,仅保留为 WebGL 不可用时的降级形态(R6)。

## R2. 主题兼容策略(FR-021)

**Decision**: 3D 场景不硬编码任何颜色——启动与主题切换时从 `getComputedStyle(document.documentElement)` 读取语义 token(`--background`/`--primary`/`--muted-foreground` 及新增的 `--companion-*` 状态色 token),转为 `THREE.Color` 注入材质;用 `next-themes` 的 `resolvedTheme` 变化触发重取色(无需重建场景,材质 `color.set` 即可)。氛围层(网格地板/径向光晕)同样用 CSS 变量表达。新增 token 在 `DESIGN.md` 定义并同步 `app/globals.css`(亮/暗两套值),跑 `pnpm design:lint`。

**Rationale**: 满足"随主题即时切换、无需刷新";与 DESIGN.md「语义 token、禁手写 dark: 覆盖」规则的正确共处方式就是把 3D 场景当成 token 的消费者。暗色 = 深空科幻;亮色 = 浅色全息实验室(白底、青色描边加深以保对比)。管家视图作为沉浸式 surface 需在 DESIGN.md 登记豁免条目(参照监督席先例):允许全出血 canvas 背景、字幕气泡等自定义原语,但 `Input`/`Button`/severity 色仍强制复用既有 token。

**Alternatives considered**: 管家视图固定深色(theme-exempt)——被用户在 clarify 阶段明确否决;为 3D 单独维护一份 JS 色表——与 token 真相源脱钩,主题改版必然漂移,拒绝。

## R3. 对话大脑集成(workhorse sidecar)

**Decision**: workhorse-agent 以 **sidecar 进程**提供大脑(HTTP `127.0.0.1:8300`,复用 `deploy/workhorse/` 既有配置与双闸门权限模型)。后端 master 定义领域端口 `CompanionBrain`(接口),基础设施层提供 `WorkhorseBrainClient` 适配器:每个管家会话映射一个 workhorse session(`POST /v1/sessions` + `GET/POST /v1/sessions/{id}/stream` SSE 双向),巡检用 headless session(消费到 turn 结束聚合结构化产出)。打断 → `POST /v1/sessions/{id}/cancel`。workhorse 不可用(健康探测失败)→ 降级 `MockBrain`(固定话术 + 巡检"未完成"汇报),对齐 069 已有的 mock 降级先例。前端永不直连 workhorse(token 不出后端)。

**Rationale**: 探索结论:workhorse 具备多 session、SSE 断线重放(Last-Event-ID)、MCP 客户端(接 `dataweave__*` 工具即获得平台操作能力,写操作天然落入 PolicyEngine 细闸)、agent 角色定制(`agents/*.yaml` 定义"管家"人设与工具白名单)。后端只做编排/治理/通道,推理全部在 sidecar——满足 constitution IV 内核"服务端无 AI 大脑"。

**Alternatives considered**: ① 复用 070 的 `LlmChatClient` 直连 LLM API——无工具循环/技能体系,"主动做事"能力弱,后期仍要迁移,拒绝;② 前端直连 workhorse——token 暴露 + 绕开项目隔离与审计,拒绝;③ 后端内嵌 agent 循环——违宪(原则 IV),拒绝。

## R4. 巡检循环的调度承载(FR-006/007)

**Decision**: 新增 `patrol_routine` 表(领域/启停/cron/范围)+ master 内 `PatrolScheduler`,完全套用调度内核既有模式:cron guard 表防重(参照 `cron_fire`)+ SKIP LOCKED 认领 + 每轮落 `patrol_run` 执行历史;多 master 对等,无单点。每轮 run 调 `CompanionBrain.runPatrol(domain, scope)`(headless workhorse 会话,提示词按领域模板化,读数走 `dataweave__*` 只读工具),产出结构化 `patrol_report` 落库并经 EventBus 推 SSE。手动触发 = 同一入口的即时 run。

**Rationale**: 满足 FR-007"平台自身调度体系触发、非旁路定时器":复用的是调度内核的**机制**(guard 表 + claim + 历史可追溯),而非把巡检硬塞成用户可见的 task-flow。Claude loops 的 proactive 模式映射:计划触发(cron)+ 事件触发(预留 alert 信号接口)+ 停止条件(单轮 turn 结束/超时)。

**Alternatives considered**: ① 建成系统项目里的真 task-flow——最纯粹的自举,但把 AI 外呼塞进 SQL/Shell 执行器语义不符,且系统项目对用户可见造成污染,本期拒绝(US4 治理界面已提供等价可观测性);② Spring `@Scheduled`——单点+无历史+多实例重复执行,违反调度不变量,拒绝。

## R5. 实时通道与状态推送

**Decision**: 单一 SSE 端点 `GET /api/companion/stream?projectId&token`(直连 `SSE_BASE`,绕过 Next rewrite——既有硬约定),事件集:`snapshot`(连接即全量:管家状态+概况+未关闭汇报)/`state`(形态变更)/`report`(新汇报/关闭同步)/`message`/`delta`/`end`(对话流式)。复用 incident stream 的实现骨架(Redis EventBus 扇出、Last-Event-ID 续传、心跳)。管家形态在服务端归一(异常计数>0→alert 等优先级规则),前端只渲染不推断。

**Rationale**: 070 已验证该骨架企业级可用;服务端归一状态保证多客户端一致(边界用例:多人/多标签)。

**Alternatives considered**: WebSocket——平台无既有设施,SSE 已满足单向推送+POST 回传模式,拒绝;轮询——SC-002 的 5 秒实时性做得到但浪费,拒绝。

## R6. WebGL 不可用/低配设备降级(clarify 遗留)

**Decision**: 挂载时探测 WebGL2/WebGL 上下文;失败则渲染 2D 降级形象(CSS 发光能量球 orb + 状态色/脉动,复用同一状态机),布局、卡片、对话完全不受影响。`prefers-reduced-motion` 时停用粒子与摆动动画,保留状态换色。

**Rationale**: 形象是表达层,巡检/对话是价值层——降级只降表达不降功能;能量球正是 brainstorm 阶段方案 B 的现成设计。

## R7. 巡检默认配置(clarify 遗留)

**Decision**: 四领域例程 seed 默认:任务失败 15 分钟/机器状态 30 分钟/数据质量 60 分钟/代码质量 每日 02:00;默认全部启用,管理员可改 cron 与启停(US4)。单轮巡检超时 120s,超时即产出"未完成"汇报(FR-008)。同领域 10 分钟窗口内异常聚合为一张卡片(FR-011)。

**Rationale**: 频率与领域数据的变化速率匹配(任务失败最敏感,代码质量最惰性);数值均为 seed 可调,不是硬编码。

## R8. Schema 与版本

**Decision**: `schema.sql` 0.20.0 → **0.21.0**:+`patrol_routine`/`patrol_run`/`patrol_report`/`companion_message` 4 表(详见 data-model.md);DDL 兼容 PG/H2(IF NOT EXISTS、CONCAT、无方言函数);`schema_version` 行/文件头同步。070 的 `incident_*` 表不动(并存期)。

## R9. 版权与命名

**Decision**: 形象沿用原型的原创程序化机器人;管家名沿用占位名 **Vega**(i18n 文案中作为产品名不翻译);`NOTICE.md` 的版权说明随实现移入 `frontend/components/workspace/views/companion/` 目录注释与 DESIGN.md 条目;产品文案不得关联任何影视角色名(NOTICE 既有约束)。
