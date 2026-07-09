# Implementation Plan: 调度器节点容错闭环（节点剔除·任务转移·不丢不失败）

**Branch**: `060-node-eviction-failover` | **Date**: 2026-07-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/060-node-eviction-failover/spec.md`

## Summary

在既有对等 master + SKIP LOCKED 认领 + 乐观 CAS 调度内核之上，叠加一套节点容错闭环：① 派发候选加"节点可用性门"（心跳新鲜 + incarnation 稳定窗 + 熔断隔离），把假/坏/未稳定节点挡在下发外；② 拆分 `attempt` 双职——保留 `attempt` 为纯下发纪元栅栏，新增 `business_attempt` 承载业务重试，使基础设施回收不再消耗业务重试、永不因 infra 判死；③ 单实例 infra 重派上限 → 挂起 + 告警（毒任务保护）；④ 无健康节点安全等待 + 节点恢复主动唤醒抽干；⑤ worker 真实上报 running 实例集使租约续约生效，据"续约缺失"区分长跑/丢失，worker 侧失联自我中止防分区双跑；⑥ 外部托管长驻作业（Flink 流式）立为独立任务类别，提交记外部句柄、failover 走 reattach 不重提交、豁免 timeout/自我中止；⑦ 人工停止/重跑兜底并重置双计数。全程复用现有 CAS「单赢」与 `isCurrentDispatch` 栅栏，保持不双跑不误判终态。

## Technical Context

**Language/Version**: Java 25（Spring Boot 4.0 / Spring Framework 7，Jackson 3）

**Primary Dependencies**: WebFlux、Spring Data JDBC + JdbcTemplate、Micrometer/Actuator、Redis（EventBus/LogBus 唤醒通道）、Flink CLI/REST（外部长驻作业提交与探测）

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`，DDL 兼容，测试用）；权威 DDL `dataweave-api/src/main/resources/schema.sql`，当前 `schema_version=0.14.3` → 本特性升 **0.15.0**

**Testing**: JUnit 5 + AssertJ；WebFlux 用 `@SpringBootTest`/WebTestClient；真实并发下发验证（every-minute cron 端到端，见 CLAUDE.md 硬规则）；H2 唯一库 + `@DirtiesContext`（backend 测试隔离不变量）

**Target Platform**: Linux 服务端（distributed 多 master + 多 worker 容器；本机 WSL2 开发）

**Project Type**: 后端多模块（`dataweave-master` 调度内核 + `dataweave-worker` 执行器 + `dataweave-api` 装配/心跳端点）；本特性**不新增模块**，无前端改动（未来实时任务卡片是独立 UI 扩展，本特性只产出并维护其数据挂载点=外部作业句柄）

**Performance Goals**: 节点可用性门只在 `SlotManager` 候选查询处加谓词，单轮 claim 成本增量 ≈ 谓词过滤（无额外网络往返，沿用 045~051 的 O(1) 认领路径）；恢复唤醒复用现有 `WAKE_CHANNEL` 事件总线（毫秒级）；不得使 round 时长退化（对齐 049/051 稳态）

**Constraints**: 硬保持四条调度死锁防御不变量（SKIP LOCKED 认领 / CAS 状态推进 / 固定锁序 task→workflow / 事务内落状态·事务外下发）；保持 `attempt` 严格单调不回退（`isCurrentDispatch` 栅栏语义零改动）；executor 保真（constitution III：Flink detached+reattach 新模式不得改变有界作业的 exit-code/stdout 语义）

**Scale/Scope**: 集群量级 2~N master × 2~N worker；任务实例十万级 WAITING 稳态（对齐 049 R11）；本特性触及约 10 个后端类 + 3 张表列扩展 + 1 处 worker 执行器演进（Flink）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 适用性 | 结论 |
|---|---|---|
| I. Files-First | 无定义文件格式改动 | N/A |
| II. Server is Source of Truth | 无 pull/push 语义改动 | N/A |
| III. Two-Legged Debugging（执行保真）— NON-NEGOTIABLE | **触及 worker 执行器**（Flink detached+reattach + worker 自我中止） | **需守**：有界任务 exit-code/stdout/timeout-abort 语义不得漂移；新增的 detached/reattach 仅对 `long_running` 生效；`dw run` 本地路径与服务端共享执行器行为一致，须测试验证 |
| IV. AI Lives in Local Agent | 不引入服务端 AI | PASS（无 AI 逻辑） |
| V. Reuse the Kernel — NON-NEGOTIABLE | **核心**：复用调度器/CAS/状态机/告警，不重写 | **PASS**：全部在既有 `SchedulerKernel`/`SlotManager`/`LeaseReaper`/`InstanceStateMachine`/`FleetService`/`WorkerReportService`/`RetryService`/`AlertSignal` 上叠加谓词与分支，零内核重写 |

**Gate 结论**：PASS。唯一需持续守护项 = 原则 III 执行保真（Flink 执行器演进），已在 research/data-model/tasks 中列为专项验证。无违规，`Complexity Tracking` 留空。

## Project Structure

### Documentation (this feature)

```text
specs/060-node-eviction-failover/
├── plan.md              # 本文件
├── research.md          # Phase 0：未决技术点决议
├── data-model.md        # Phase 1：表列/状态/计数模型
├── quickstart.md        # Phase 1：端到端验证脚本
├── contracts/           # Phase 1：接口契约（心跳/执行/reattach/ops）
│   └── scheduler-failover-contracts.md
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/
│   │   ├── SlotManager.java              # ← 加节点可用性谓词（唯一候选收口，FR-001/002/003/005）
│   │   ├── FleetService.java             # ← incarnation_since 落时刻 + 恢复唤醒 + 熔断复位（FR-002/004/014）
│   │   ├── NodeHealthService.java        # ★新增：熔断计数原子/单调更新（FR-003/006）
│   │   ├── LeaseReaper.java              # ← infra 回收改走 business_attempt 不增 + infra 上限挂起 + incarnation 真比对（FR-008/012/016/019）
│   │   ├── RetryService.java             # ← 业务重试改比 business_attempt（FR-009）
│   │   ├── InstanceStateMachine.java     # ← 新增 business_attempt/infra_count CAS 助手 + SUSPENDED；attempt 语义不动（FR-007/011/029/030）
│   │   ├── WorkerReportService.java      # ← reportFailed 增 business_attempt（仅 started_at≠null）（FR-009）
│   │   ├── SchedulerKernel.java          # ← 下发失败 casRequeue 归 infra（不烧业务）（FR-008）
│   │   ├── StuckInstanceSweeper.java     # ★新增：无节点等待超阈值告警 + 单实例 infra 超限挂起告警（FR-012/015）
│   │   ├── TimeoutSweeper.java           # ★新增：RUNNING 超 timeout_sec 判 TIMEOUT（long_running 豁免）（FR-020）
│   │   └── OpsService.java               # ← kill 对 long_running 取消集群作业；rerun 重置双计数（FR-027/028）
│   └── domain/WorkerNode.java            # ← +incarnation_since/consecutive_infra_failures/quarantined_until
├── dataweave-worker/src/main/java/com/dataweave/worker/
│   ├── application/WorkerExecService.java        # ← 维护 Set<UUID> running + 暴露；失联自我中止（FR-016/021）
│   ├── infrastructure/HeartbeatReporter.java     # ← runningInstanceIds 真上报（去硬编码 []）；探测续约失败触发自我中止（FR-016/021）
│   └── infrastructure/FlinkTaskExecutor.java     # ← long_running：detached 提交 + 记 job id + 轮询/reattach（FR-022~026）
└── dataweave-api/src/main/resources/schema.sql   # ← 3 表列扩展 + task_def.long_running + schema 0.15.0（FR-031）
```

**Structure Decision**：沿用 backend 四模块 DDD 布局，改动集中在 `dataweave-master/application`（调度容错主体）+ `dataweave-worker`（续约信号/自我中止/Flink 执行器）+ `schema.sql`（列扩展）。新增 3 个应用服务类（`NodeHealthService`/`StuckInstanceSweeper`/`TimeoutSweeper`）保持单一职责、便于独立测试；其余全为既有类的分支叠加。无新模块、无跨 DDD 层反向依赖。

## Phased Delivery（按 User Story 优先级切片，可独立交付验证）

- **切片 A（US1，P1，MVP）**：节点可用性门（FR-001~006）+ 计数拆分与转移（FR-007~011）+ 毒任务上限（FR-012）。交付即消除"假节点承接 + infra 烧业务重试"两大痛点。
- **切片 B（US2，P2）**：无节点安全等待（FR-013）+ 恢复主动唤醒抽干（FR-014）+ stuck 告警（FR-015）。
- **切片 C（US3，P3）**：真续约（FR-016~019）+ max-runtime（FR-020）+ worker 自我中止（FR-021）+ 外部长驻作业 reattach（FR-022~026）+ 人工兜底双计数重置（FR-027/028）。C 内 Flink reattach 是最重子项，与 059 共享 `FlinkTaskExecutor`，须先做 cross-feature 对账。
- 横切（FR-029~031）随各切片就地满足；schema 0.15.0 一次性列扩展在切片 A 落。

## Complexity Tracking

> 无 Constitution 违规，留空。
