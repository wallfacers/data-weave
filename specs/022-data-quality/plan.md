# Implementation Plan: 数据质量中心 —— 断言定义、执行集成与阻断/告警

**Branch**: `022-data-quality` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/022-data-quality/spec.md`

## Summary

对数据集(表)定义**质量断言**(8 类:`ROW_COUNT`/`NULL_RATE`/`UNIQUENESS`/`FRESHNESS`/`RANGE`/`REFERENTIAL`/`CUSTOM_SQL`/`SCHEMA`),在**三入口**(post-task 门禁 / 独立调度 / on-demand)触发执行,产出可追溯 `quality_check_run` + 每断言 `quality_check_result`(PASS/FAIL/WARN + 实测值 + 失败样本引用)。失败按动作 `BLOCK`(**阻断该任务下游 DAG 节点**)或 `WARN`(仅记录),并把每条 FAIL 经 `QUALITY_FAILED` 事件**喂入 021 告警引擎**。聚合 `quality_scorecard`(质量分 + 趋势)。新增 4 表(+1 guard)升 `schema_version`(MINOR,占位 `0.2.0`)。**执行复用 worker 执行器**(扩展 `SqlTaskExecutor` 为只读度量 `QualityProbeExecutor`,不另起引擎);**阻断复用既有 DAG 状态机**(标 `SKIPPED`/`FAILED`,不新增状态);写操作过 `PolicyEngine` 闸门;全表 `tenant_id` 隔离。

**技术路径**(复用内核,不重写,逐条见 [research.md](./research.md)):断言 → `QualityRuleCompiler` 编译为度量 SQL → 经既有 `TaskExecutionGateway`(all-in-one `InProcessTaskExecutionGateway` / distributed `WorkerExecService`)下发 `QualityProbeExecutor`(继承 `SqlTaskExecutor` 建连/驱动隔离/SKIPPED,仅读回标量度量值)→ `QualityCheckRunner` 写 run/result;post-task 门禁挂在 `InstanceStateMachine.casTaskTerminal` 成功后(应用内 `ApplicationEvent`,master 不反向依赖质量服务);独立调度复用 `cron_fire` 同款 SKIP LOCKED + `quality_fire` guard 防重;BLOCK 用 `casTaskState(下游, WAITING→SKIPPED)` 借既有就绪门(`SchedulerKernel` claim `pred.state='SUCCESS'`)拦下游;FAIL 复用 021 `AlertSignalPublisher` publish `QUALITY_FAILED`;写闸门复用 `GatedActionService.submit → PolicyEngine → DefaultPlatformActionExecutor` case;i18n 三规则;`QualityMetrics` 镜像 `SchedulerMetrics`。

## Technical Context

**Language/Version**: Java 25(release 25,须 mvnd 或 export JDK25 编译)

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7、WebFlux、Spring Data JDBC + JdbcTemplate、Jackson 3(`tools.jackson.databind.*`)、Micrometer、worker `TaskExecutor`/`ExecutionContext`(经既有 `TaskExecutionGateway` 接缝复用)、021 `AlertSignalPublisher`(跨特性接缝)

**Storage**: PostgreSQL(默认)/ H2(`profiles=h2` in-memory,DDL 双方言兼容);业务数据源经 `datasources` + `DatasourceResolver` 解析(只读会话跑断言);失败样本以引用存(MinIO 日志归档范式),不另引存储

**Testing**: JUnit 5 + AssertJ;WebFlux 用 `@SpringBootTest`/WebTestClient(带 JWT,`JwtTestSupport`);H2 净库测试用 `@TestPropertySource` 独立库名(防共享内存库串台);契约 200 + `$.code/$.data`;8 类断言逐类集成测试

**Target Platform**: Linux server(WSL2 本地开发,长跑命令须 `setsid` 脱离,见 CLAUDE.md Working Rules)

**Performance Goals**: post-task 门禁裁决在下游被 claim 前完成(不卡调度);断言执行支持采样/分区限代价,不拖垮数据源;事件驱动 `QUALITY_FAILED` p95 < 1s(信号到 alert)

**Constraints**: 全表 `tenant_id` 隔离;**不另起查询引擎**(复用 worker 执行器,原则 III/V);**不新增 DAG 状态机状态**(BLOCK 借既有 SKIPPED/FAILED,红线);基础设施失败(SKIPPED→ERROR)与断言失败(FAIL)语义分离;`CUSTOM_SQL` 安全解析不弱化;失败样本不无差别明文落库;`schema_version` 三处恒等;依赖方向 domain←application←infrastructure←interfaces

**Scale/Scope**: 4 张业务表(+1 guard) + `dataweave-master` 质量域 DDD 四层 + worker `QualityProbeExecutor` + `/api/quality/*` REST + 1 前端 quality 视图;v1 八类断言 × 三入口 × 两动作(BLOCK/WARN)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**:质量断言**不是任务/工作流定义**,属平台治理配置(类 metrics/policy_rules/告警规则),不进 pull/push 文件契约——与原则 I 不冲突(I 约束 task/workflow/catalog 文件表示,非治理配置)。✅
- **II. Server is Source of Truth**:质量断言/结果/评分卡全在服务端治理,租户隔离强制,无双向同步;进行中 run 用快照定义收尾(D11,与版本快照治理一致)。✅
- **III. Two-Legged Debugging**:**强相关**——断言执行**MUST 复用平台 SQL 执行器语义,不 fork 第二引擎**(原则 III 不可让渡)。本特性以 `QualityProbeExecutor` **继承 `SqlTaskExecutor`** 的建连/驱动隔离/超时/SKIPPED 不变量,仅读回标量度量值,经既有 `TaskExecutionGateway` 下发——零语义漂移(D1)。CLI 本地 runtime 不在本特性范围(质量是服务端治理面),但执行复用同一 worker 执行器,与 CLI `dw run` 复用同款执行器代码级一致。✅
- **IV. AI Lives in the Local Agent**:不嵌服务端 AI 大脑;质量断言是规则/SQL 驱动(非 AI 决策);agent 经既有写闸门发起断言写/on-demand 触发,不新增 agent 脑;拆除不损伤运行态观测与调度内核(BLOCK 借既有状态机不侵入)。✅
- **V. Reuse the Kernel**:复用调度 claim/guard(`cron_fire` 范式做独立调度防重)、既有 DAG 状态机(`InstanceStateMachine` CAS 做 BLOCK)、worker SQL 执行器(`SqlTaskExecutor`)、`PolicyEngine` L0-L4 写闸门 + `agent_action` 审计、021 `AlertSignalPublisher` 接缝、`SchedulerMetrics` 同款 Micrometer。**任何 agent 发起的断言写 / on-demand 触发必过写闸门留审计**(`QUALITY_RULE_WRITE` L1 / `QUALITY_RUN` L2);`CUSTOM_SQL` 安全解析经 `PolicyEngine` 不弱化。✅

**结论**:无违规,无需 Complexity Tracking。设计后复检见末尾。

## Project Structure

### Documentation (this feature)

```text
specs/022-data-quality/
├── plan.md              # 本文件
├── research.md          # Phase 0:技术决策(执行复用/三入口/BLOCK 阻断/QUALITY_FAILED 接缝/写闸门/类型编译)
├── data-model.md        # Phase 1:4 表(+1 guard)DDL + 状态/枚举 + 索引/约束 + schema_version 升版 + policy_rules seed
├── quickstart.md        # Phase 1:端到端验证(8 类断言/三入口/BLOCK 阻断/QUALITY_FAILED/语义分离/采样/租户)
├── contracts/
│   ├── quality-api.md   # /api/quality/* 端点契约
│   └── seam.md          # 执行接缝(TaskExecutionGateway 复用)+ post-task 钩子 + BLOCK 阻断 + QUALITY_FAILED(喂 021)
└── tasks.md             # Phase 2:/speckit-tasks 生成(本命令不产)
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/quality/   # 质量治理域(与 metrics/lineage 同模块)
├── domain/
│   ├── QualityRule.java QualityCheckRun.java QualityCheckResult.java QualityScorecard.java
│   ├── AssertionType.java (ROW_COUNT/NULL_RATE/UNIQUENESS/FRESHNESS/RANGE/REFERENTIAL/CUSTOM_SQL/SCHEMA)
│   ├── RuleAction.java (BLOCK/WARN) · CheckStatus.java (PASS/FAIL/WARN/ERROR) · CheckTrigger.java (POST_TASK/SCHEDULED/ON_DEMAND)
│   └── repository/ (Quality*Repository 接口,含 findByTenantId*)
├── application/
│   ├── QualityCheckRunner.java     # 三入口统一执行:编译→经 gateway 下发 probe→读度量→写 run/result→裁决
│   ├── QualityRuleCompiler.java    # 8 类断言 → 度量 SQL + 期望比较 + 采样/分区(D6)
│   ├── QualityGateService.java     # BLOCK:遍历 workflow_edge 下游传递闭包 → InstanceStateMachine.casTaskState(SKIPPED)
│   ├── QualityScheduleTrigger.java # 独立调度:cron_fire 同款 SKIP LOCKED + quality_fire guard 防重(D2.2)
│   ├── QualitySignalEmitter.java   # FAIL → 复用 021 AlertSignalPublisher publish QUALITY_FAILED(D4)
│   ├── QualityScorecardService.java# 评分卡聚合(通过率 + 加权 severity + 趋势)
│   ├── QualityMetrics.java         # Micrometer 仪表(镜像 SchedulerMetrics)
│   ├── TaskSucceededListener.java  # @EventListener 消费 master 终态事件 → 触发 POST_TASK 门禁(D2.1)
│   └── QualityRuleService.java QualityCheckService.java  # CRUD/查询
├── infrastructure/jdbc/ (Quality*RepositoryImpl,JdbcTemplate)
└── interfaces/QualityController.java  # /api/quality/* (WebFlux + TenantContext)

backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/
└── QualityProbeExecutor.java       # [新] type=QUALITY_PROBE,继承/复用 SqlTaskExecutor 建连+驱动隔离+SKIPPED,读回标量度量值(D1)

backend/dataweave-master/.../application/
├── InstanceStateMachine.java       # [改] casTaskTerminal 成功(SUCCESS)后 publish TaskSucceededEvent(应用内事件,master 不依赖质量服务)
└── DefaultPlatformActionExecutor.java  # [改] +case QUALITY_RULE_WRITE / QUALITY_RUN(镜像 PROJECT_PUSH,file:94)

backend/dataweave-api/src/main/resources/
├── schema.sql                      # [改] +4 quality 表(+1 quality_fire guard),升 schema_version 占位 0.2.0
└── data.sql                        # [改] +policy_rules seed(QUALITY_RULE_WRITE=L1 / QUALITY_RUN=L2)

frontend/
├── components/workspace/views/quality-view.tsx   # [新] 断言/执行历史/失败明细/评分卡分区(参考 lineage-view)
├── lib/workspace/registry.tsx + views.ts         # [改] 注册 quality 视图(两处各加一行,参考 lineage 注册)
└── messages/{zh-CN,en-US}.json                   # [改] quality 命名空间(双语 key 等集)
```

**Structure Decision**:web 双项目。后端**质量治理面落 `dataweave-master`**(与 metrics/task/lineage 同模块,严守 DDD 四层方向);**执行面复用 worker** 新增 `QualityProbeExecutor`(继承 `SqlTaskExecutor`)。跨模块接缝两处:① 执行——`QualityCheckRunner`(master)经既有 `TaskExecutionGateway` 接口下发 probe(同 `InProcessTaskExecutionGateway` 的既有 master→worker 复用范式);② 信号——post-task 门禁用 master 内 `ApplicationEvent`(master 只 publish,不依赖质量 application),`QUALITY_FAILED` 复用 021 `AlertSignalPublisher`(master 只 publish,不依赖 alert 消费者)。前端新增一个 view 注册进 registry(registry.tsx + views.ts 两处)。

## Complexity Tracking

> 无 Constitution 违规,本节空。

## 跨特性接缝(Cross-Feature,SDD 闭环)

- **本特性(022)→ 021(告警引擎)**:022 是 `QUALITY_FAILED` 信号的**产生方**;021 已预留 `AlertSignal.Type.QUALITY_FAILED` + 消费路径 + 接缝测试桩(`specs/021-alert-engine/contracts/signal-seam.md:18,40-43`)。022 在断言 FAIL 时复用 021 `AlertSignalPublisher` publish。**合并期**:先合 021(它定义 `AlertSignal`/`AlertSignalPublisher`),022 复用其契约;re-run 021 场景 7 接缝测试(造真实 022 断言 FAIL → 021 规则触发告警),证明 seam 闭合(SC-004)。详见 [contracts/seam.md](./contracts/seam.md)。
- **本特性(022)→ 023(资产/指标)**:023 的资产质量徽章复用 `quality_scorecard`(FR-009);023 落地时读 022 评分卡。022 不依赖 023。
- **共享面**:022 改 `InstanceStateMachine`(+post-task 事件发射点)+ `schema.sql` + `data.sql` 是与 021/023 的共享面。021 也改 `InstanceStateMachine`(发 `AlertSignal` 终态点);**合并时**两特性在同一 `casTaskTerminal` 点各加自己的 publish,互不冲突但须一并 re-run 状态机测试确认两发射点都在、不互相吞。
- **schema_version 并行升版**:021=`0.1.0`、022 占位 `0.2.0`、023 顺延;**合并按落地顺序定终值**(见 [research.md](./research.md) D12 + [data-model.md](./data-model.md))。
- **并行实现**:022 与 021/023 各开独立 git worktree,避免 speckit 单指针抢占(CLAUDE.md Parallel-Feature Isolation)。

## Post-Design Constitution Re-Check

Phase 1 设计后复检:
- **III/V 复用执行器**:`QualityProbeExecutor` 继承 `SqlTaskExecutor`,建连/驱动隔离/超时/SKIPPED 不变量零漂移,经既有 `TaskExecutionGateway` 下发——**不另起引擎**守住。✅
- **V 不新增状态机状态**:BLOCK 借既有 `casTaskState(WAITING→SKIPPED)` + 既有就绪门拦下游,**零新增 DAG 状态**(红线守住)。✅
- **依赖方向**:post-task 钩子用 `ApplicationEvent`(master 不反向依赖质量服务);`QUALITY_FAILED` 用 021 publisher(master 不依赖 alert 消费者);质量域 DDD 四层 outer→inner only。✅
- **写闸门零旁路**:断言写 / on-demand 触发经 `GatedActionService`;`CUSTOM_SQL` 经 `PolicyEngine` 安全解析不弱化。✅
- **语义分离**:基础设施失败(probe SKIPPED→result ERROR,不发信号/不阻断)与断言失败(FAIL)在执行器一层分清(D1/SC-005)。✅
- **租户/样本**:4 表全 `tenant_id`;失败样本以引用存、受权限控制不明文泄露。✅

**仍全部通过,无新增违规。**
