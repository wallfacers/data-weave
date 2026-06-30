# Implementation Plan: 告警引擎 —— 规则评估、多通道通知与告警生命周期

**Branch**: `021-alert-engine` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/021-alert-engine/spec.md`

## Summary

填充 `dataweave-alert` 空骨架模块:把平台已产出但无人消费的信号(任务 FAILED/超时、SLA 破线、节点离线、metric 阈值越界)经**规则评估**转成**有生命周期的告警事件**(`FIRING→RESOLVED/ACKED/SUPPRESSED`),按 fingerprint 去重 + `for_duration` 去抖 + 抑制窗口防刷屏,经**多通道**(EMAIL/WEBHOOK/钉钉/企微/飞书)分发并留投递审计,distributed 下 metric 轮询复用 `cron_fire` UNIQUE 冲突范式保证单点评估。写操作过 `PolicyEngine` 闸门,全表 `tenant_id` 隔离,新增 6 表升 `schema_version` 0.0.1→0.1.0。

**技术路径**:复用现有内核接缝(不重写)——在 `InstanceStateMachine` CAS 终态点 / `SlaService.recordCompletion` 破线点 / `LeaseReaper` 心跳过期点注入**应用内信号发布**(`AlertSignalPublisher`,Spring `ApplicationEvent`,非 per-instance Redis 频道);metric 轮询用调度内核同款 SKIP LOCKED + guard 表;分发用现有 `WebClientConfig` 的自建 `WebClient` @Bean;写闸门复用 `GatedActionService.submit → PolicyEngine → DefaultPlatformActionExecutor` case 分发;i18n 通知模板走 `Messages.get`。

## Technical Context

**Language/Version**: Java 25(release 25,须 mvnd 或 export JDK25 编译)

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7、WebFlux、Spring Data JDBC + JdbcTemplate、Jackson 3(`tools.jackson.databind.*`)、Micrometer、自建 `WebClient` @Bean(无 `WebClient.Builder` auto-config)

**Storage**: PostgreSQL(默认)/ H2(`profiles=h2` in-memory,DDL 双方言兼容);Redis(EventBus,本特性用应用内 `ApplicationEvent` 非 Redis 频道)

**Testing**: JUnit 5 + AssertJ;WebFlux 用 `@SpringBootTest`/WebTestClient(带 JWT,`JwtTestSupport`);H2 净库测试用 `@TestPropertySource` 独立库名;契约 200 + `$.code/$.data`

**Target Platform**: Linux server(WSL2 本地开发,长跑命令须 `setsid` 脱离)

**Project Type**: web(后端 `dataweave-alert` 模块 DDD 四层 + 前端 Workspace view)

**Performance Goals**: 事件驱动告警评估 p95 < 1s(从信号到 alert_event);metric 轮询周期可配(≥30s);通道分发异步退避,不阻塞评估

**Constraints**: 全表 `tenant_id` 隔离;HA 单点评估(无重复通知);通道密钥脱敏;`schema_version` 三处恒等;依赖方向 domain←application←infrastructure←interfaces

**Scale/Scope**: 6 张新表 + `dataweave-alert` DDD 四层填充 + `/api/alert/*` REST + 1 前端 alerts 视图;v1 五信号源 × 五通道

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**:告警**规则定义不是任务/工作流定义**,属平台治理配置(类 metrics/policy_rules),不进 pull/push 文件契约——与原则 I 不冲突(I 约束 task/workflow/catalog,非告警配置)。✅
- **II. Server is Source of Truth**:告警全在服务端治理,租户隔离强制,无双向同步。✅
- **III. Two-Legged Debugging**:不涉 CLI 本地 runtime(告警是服务端治理面)。✅ 不适用。
- **IV. AI Lives in Local Agent**:不嵌服务端 AI 大脑;告警规则驱动(非 AI 决策);agent 经现有写闸门发起告警写,不新增 agent 脑。✅
- **V. Reuse the Kernel**:复用调度 claim/guard(HA 单点)、`PolicyEngine` L0-L4 写闸门 + `agent_action` 审计、`SchedulerMetrics` 同款 Micrometer、现有 `WebClient` @Bean。**任何 agent 发起的告警写/test-send 必过写闸门留审计**。✅

**结论**:无违规,无需 Complexity Tracking。设计后复检见末尾。

## Project Structure

### Documentation (this feature)

```text
specs/021-alert-engine/
├── plan.md              # 本文件
├── research.md          # Phase 0:技术决策(信号注入/HA 去重/通道抽象/状态机/去重语义)
├── data-model.md        # Phase 1:6 表 DDL + 状态机 + 索引/约束 + schema_version 升版
├── quickstart.md        # Phase 1:端到端验证(五信号→评估→去重→分发→审计)
├── contracts/
│   ├── alert-api.md     # /api/alert/* 端点契约
│   └── signal-seam.md   # AlertSignalPublisher 内部接缝 + QUALITY_FAILED/ASSET_CHANGED 预留
└── tasks.md             # Phase 2:/speckit-tasks 生成(本命令不产)
```

### Source Code (repository root)

```text
backend/dataweave-alert/src/main/java/com/dataweave/alert/
├── domain/
│   ├── AlertRule.java AlertChannel.java AlertRoute.java
│   ├── AlertEvent.java AlertNotification.java AlertSilence.java
│   ├── AlertState.java (FIRING/RESOLVED/ACKED/SUPPRESSED) + 状态机不变量
│   ├── signal/AlertSignal.java (TASK_FAILED/TASK_TIMEOUT/SLA_BREACH/WORKFLOW/METRIC/NODE_OFFLINE/QUALITY_FAILED/ASSET_CHANGED)
│   └── repository/ (Alert*Repository 接口)
├── application/
│   ├── AlertEvaluator.java         # 条件评估 + for_duration 去抖 + fingerprint
│   ├── AlertLifecycleService.java  # 状态机推进 + 去重/抑制/自动恢复/ACK
│   ├── AlertDispatchService.java   # 路由 + 退避重试 + 速率限制 + 投递审计
│   ├── MetricPollEvaluator.java    # 定时轮询 + alert_poll_fire guard 单点
│   ├── AlertSignalListener.java    # @EventListener 消费 AlertSignalPublisher
│   ├── AlertMetrics.java           # Micrometer 仪表
│   └── AlertRuleService/AlertChannelService/AlertSilenceService.java (CRUD)
├── infrastructure/
│   ├── jdbc/ (Alert*RepositoryImpl,JdbcTemplate)
│   ├── channel/ (ChannelDispatcher 策略: EmailDispatcher/WebhookDispatcher + 钉钉/企微/飞书子类)
│   └── AlertActionExecutor 接入(ALERT_RULE_WRITE/ALERT_TEST_SEND case)
└── interfaces/AlertController.java  # /api/alert/* (WebFlux + TenantContext)

backend/dataweave-master/.../application/
├── InstanceStateMachine.java       # [改] casTaskTerminal/publishTaskState 后 publish AlertSignal
├── SlaService.java                 # [改] recordCompletion 破线点 publish AlertSignal
└── LeaseReaper.java                # [改] 心跳过期 FAILED 点 publish AlertSignal(NODE_OFFLINE)

backend/dataweave-api/src/main/resources/
├── schema.sql                      # [改] +6 alert 表(+1 guard 表),升 schema_version 0.0.1→0.1.0
└── data.sql                        # [改] +policy_rules seed(ALERT_RULE_WRITE/ALERT_TEST_SEND)

backend/dataweave-alert/src/main/resources/messages.properties  # 通知模板 i18n(规则②)

frontend/
├── components/workspace/views/alerts-view.tsx   # [新] 规则/活跃/历史/通道/静默
├── lib/workspace/registry.tsx + views.ts        # [改] 注册 alerts 视图(两处各加一行)
└── messages/{zh-CN,en-US}.json                   # [改] alerts 命名空间(双语 key 等集)
```

**Structure Decision**:web 双项目。后端填充既有空骨架 `dataweave-alert`(严守 DDD 四层方向)。跨模块接缝 `AlertSignalPublisher`/`AlertSignal` 用 Spring `ApplicationEvent`:master 只 `publish`、不依赖 alert 模块(不违依赖方向);alert 侧 `@EventListener` 订阅。前端新增一个 view 注册进 registry(registry.tsx + views.ts 两处)。

## Complexity Tracking

> 无 Constitution 违规,本节空。

## 跨特性接缝(Cross-Feature,SDD 闭环)

- **份2(022 数据质量)→ 本特性**:`QUALITY_FAILED` 是本特性预留的 `AlertSignal` 类型;本特性 v1 定义类型与消费路径,产生方在 022。
- **份3(023 资产/指标)→ 本特性**:`ASSET_CHANGED` 信号同理,本特性预留类型;023 落地产生方。
- **并行实现**:021 与 022/023 各开独立 git worktree,避免 speckit 单指针抢占。本特性改 master 三发射点 + schema.sql + data.sql 是共享面,022/023 合并时 re-run 接缝测试确认 seam 闭合。

## Post-Design Constitution Re-Check

Phase 1 设计后复检:6 表全 `tenant_id`;`AlertSignal` 用 ApplicationEvent 不让 master 反向依赖 alert(依赖方向守住);HA 单点用 guard 表(复用调度不变量);写闸门零旁路(rule/channel/silence 写 + test-send 经 `GatedActionService`);无新增状态机状态侵入调度内核。**仍全部通过,无新增违规。**
