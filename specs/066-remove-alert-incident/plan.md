# Implementation Plan: 移除人工告警/事件/质量/工单体系

**Branch**: `066-remove-alert-incident` | **Date**: 2026-07-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/066-remove-alert-incident/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command.

## Summary

移除告警中心、AlertSignal 故障信号桥、数据质量模块，并收尾 incident/event/health 残留，为 Agent 智能运维清场。删除只动旁路信号/告警/质量钩子，不碰调度核心（`InstanceStateMachine` 的 CAS/锁/状态转移）与 PolicyEngine 闸门不变量。quality 模块删除的并行工作并入本特性接手完成与验证。

## Technical Context

**Language/Version**: Java 25 (backend) + TypeScript / React 19 (frontend)

**Primary Dependencies**: Spring Boot 4.0 / WebFlux / Spring Data JDBC / Jackson 3；Next.js 16 (App Router) / shadcn/ui / next-intl

**Storage**: PostgreSQL (default) + H2 (`profile=h2`) + Redis；`schema.sql` 单一权威 DDL，无 migration 脚本，`sql.init.mode=always` DROP+CREATE

**Testing**: JUnit 5 + AssertJ + WebTestClient（backend）；vitest + `pnpm typecheck`（frontend）

**Target Platform**: Linux server (backend WebFlux :8000) + web (frontend :4000)

**Project Type**: web-service（Maven 四模块后端 + Next.js 前端）

**Performance Goals**: N/A（删除特性，不引入新性能目标；守现有调度吞吐不退化）

**Constraints**: 删除波及 `InstanceStateMachine` 须守调度死锁四不变量（SKIP LOCKED 认领 / 乐观 CAS / 固定锁序 / 事务内持久）；H2/PG 双存储启动；前端 i18n 两 bundle key 集合 parity（CI 检查）

**Scale/Scope**: 删 ~70 文件 + 改 schema 11 表（7 alert_* + 4 已删 incident/event/health，quality_* 由并行工作删）+ 前端 2 视图；建议 4 个聚焦提交

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**: N/A — 删除不涉及文件定义契约（task/workflow 文件表示不动）
- **II. Server is the Source of Truth**: PASS — 不改 pull/push/版本快照治理
- **III. Two-Legged Debugging** (NON-NEGOTIABLE): PASS — 不碰 CLI 本地运行时与 executor 复用
- **IV. AI Lives in the Local Agent** (NON-NEGOTIABLE) — 内核第 3 条「拆除不得损伤运行态观测与调度内核」: **PASS**。删除对象（告警中心 / AlertSignal 信号桥 / 数据质量模块 / 工单残留）均非运行态观测（ops overview / metrics / run logs / DAG instance views 全保留）亦非调度内核（`InstanceStateMachine` 只删旁路 `publishEvent(AlertSignal)`，CAS/锁/状态转移不变）。反而推进 IV——拆除人工运维介入机制，AI 归位本地 agent。
- **V. Reuse the Kernel**: PASS — PolicyEngine 闸门机制保留（仅删 alert/quality 的策略规则种子，不删闸门本身）；调度器 / SQL·Shell executor / 版本快照 / MCP 框架不动。

**结论**: 无违规，Complexity Tracking 留空。Post-design 复核同此结论（删除范围未触及任何原则边界）。

## Project Structure

### Documentation (this feature)

```text
specs/066-remove-alert-incident/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output（待删实体清单）
├── quickstart.md        # Phase 1 output（验证命令）
├── contracts/           # Phase 1 output（被删 API 端点清单）
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── dataweave-alert/                         # 【整模块删除】pom + 全部 src/test
├── dataweave-api/
│   ├── quality/QualityController.java       # 【删】
│   ├── infrastructure/*QualityProbeGateway  # 【删】Distributed/InProcess
│   ├── test/.../AlertSeamIT.java            # 【删】
│   ├── test/.../AlertCrossProjectGuardTest.java # 【删】
│   ├── resources/schema.sql                 # 【改】删 alert_* 表 + 升版本
│   ├── resources/data.sql                   # 【改】删 ALERT_*/QUALITY_* policy_rule
│   ├── resources/ops-messages.properties    # 【改】删 ops.alert.*
│   ├── resources/application.yml            # 【改】删 stuck-wait-alert-ms + alert webhook 超时
│   └── pom.xml                              # 【改】删 dataweave-alert 依赖
├── dataweave-master/
│   ├── application/InstanceStateMachine.java        # 【改】删 AlertSignal publish + 2 helper（守 CAS/锁）
│   ├── application/DefaultPlatformActionExecutor.java # 【改】删 alert/quality case（quality 部分并行已删）
│   ├── application/LeaseReaper.java                 # 【改】删 NODE_OFFLINE publish
│   ├── application/SlaService.java                  # 【改】删 SLA_BREACH publish
│   ├── application/StuckInstanceSweeper.java        # 【改】删 NODE_STARVATION/TASK_SUSPENDED publish
│   ├── application/TimeoutSweeper.java              # 【改】删 TASK_TIMEOUT publish
│   ├── domain/signal/AlertSignal.java               # 【删】
│   ├── quality/                                     # 【整包删】并行工作已删
│   └── resources/messages*.properties               # 【改】删 incident.* 孤儿 key
├── dataweave-worker/
│   └── infrastructure/QualityProbeExecutor.java     # 【删】
backend/pom.xml                                       # 【改】删 dataweave-alert 模块声明

frontend/
├── components/workspace/views/alerts-view.tsx        # 【删】
├── components/workspace/views/quality-view.tsx       # 【删】（并行已删）
├── lib/workspace/registry.tsx                        # 【改】删 alerts/quality 注册
├── lib/workspace/views.ts                            # 【改】删 view key
├── lib/workspace/nav-groups.ts                       # 【改】删导航分组
└── messages/{zh-CN,en-US}.json                       # 【改】删 alerts/quality i18n 块（parity）
```

**Structure Decision**: 沿用既有 backend 四模块 + frontend 布局，纯删除无新增结构。被删/被改路径如上，未列出的模块（worker 主体、neo4j 血缘、调度其余组件）不动。

## Complexity Tracking

> 无 Constitution Check 违规，本节留空。
