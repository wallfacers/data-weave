# Implementation Plan: Incident 域模型 + 监督席队列

**Branch**: `043-incident-queue` | **Date**: 2026-07-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/043-incident-queue/spec.md`

## Summary

把「incident 工单」立为一等领域对象：master 内新增第三个 AlertSignal 消费者（同步 listener、整体异常隔离），将 TASK_FAILED / TASK_TIMEOUT / SLA_BREACH / NODE_OFFLINE 四类信号确定性地转化为带签名去重、同工作流实例归并、自动愈合/自动关闭生命周期的工单；工单自带分诊信息（neo4j 下游数 = 爆炸半径，sla_baseline 基线投影 = 时间预算）；前端新增 `incidents` 视图并置为首个 pinned 视图（= 产品主页），卡片内重跑走 GatedActionService 闸门（新端点，不复用 OpsController 的无闸门旁路）、审批内联复用 `/api/approvals`。诊断/提案为可空槽位，为编队 v1（§10 第三步）预留。无任何 AI/LLM 成分。

## Technical Context

**Language/Version**: 后端 Java 25 + Spring Boot 4.0（WebFlux, Jackson 3）；前端 Next.js 16 + React 19 + TypeScript

**Primary Dependencies**: Spring Data JDBC + JdbcTemplate、Spring `ApplicationEventPublisher`（进程内信号）、neo4j（`LineageQueryService` 下游推导）、shadcn/ui + hugeicons、next-intl

**Storage**: PostgreSQL（默认）/ H2（profiles=h2，DDL 兼容）；新表 `incident`、`incident_event`，`agent_action` 加 `incident_id` 列；schema_version `0.6.3 → 0.7.0`

**Testing**: 后端 JUnit 5 + AssertJ + `@SpringBootTest`(H2 独立库名) + WebTestClient(JwtTestSupport)；前端 vitest + playwright 浏览器验证门

**Target Platform**: Linux server（多 master 对等部署）；浏览器（workspace 多 tab）

**Project Type**: Web application（backend 四模块 DDD + frontend Next.js）

**Performance Goals**: 信号→卡片可见 ≤30s（SC-001，前端 15s 轮询）；恢复→自动已解决 ≤30s（SC-003）；listener 处理单信号 <50ms（同步派发在发布者线程，不得拖慢状态机）

**Constraints**: ① listener 必须整体 try-catch（同步链上异常会冒泡回 InstanceStateMachine/SlaService）；② 状态推进全部乐观 CAS（`WHERE state=?`），多 master 安全；③ 未关闭工单同签名唯一 —— `active_key` 列 + UNIQUE 约束（关闭置 NULL，H2/PG 多 NULL 均不冲突）；④ 不触碰 alerts/event-center 既有行为（FR-014）；⑤ 不修复 OpsController rerun 既有旁路（越界），incident 重跑走独立闸门化端点

**Scale/Scope**: 工单量级 = 十/百级活跃（单团队数据管线），队列查询全取内存排序即可；timeline 追加写、永久保留（v1 不清理）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 裁决 | 依据 |
|---|---|---|
| I. Files-First | ✅ PASS（不适用面） | incident 是运行时运维对象，非 authoring 工件；不新增任何人肉录入元数据界面 |
| II. Server is Source of Truth | ✅ PASS | 工单/时间线由服务端生成与治理；无本地副本语义 |
| III. Two-Legged Debugging | ✅ PASS（不触碰） | 不改动 CLI/localrun/TEST run 任何链路 |
| IV. AI Lives in the Local Agent | ✅ PASS | 043 是纯确定性规则系统（签名/归并/CAS/定时清扫），服务端零推理/零 LLM；诊断/提案仅为**可空数据槽位**。注：编队 v1（§10 第三步）将触及本原则的"服务端无 AI 大脑"条款，届时须先修宪，与本期无关 |
| V. Reuse the Kernel | ✅ PASS | 复用 AlertSignal 信号流、GatedActionService/PolicyEngine 闸门、`agent_action` 审计、LineageQueryService、SlaService、审批端点；一切写动作过闸门留痕，无旁路新建 |

**Post-Phase-1 复评**: 设计产物未引入新违反项；`WorkflowSucceededEvent` 为进程内领域事件（镜像既有 `TaskSucceededEvent`），不属 AI 成分。✅ 全部维持 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/043-incident-queue/
├── plan.md              # 本文件
├── research.md          # Phase 0：11 项决策（模块归属/签名/归并/愈合/分诊/排序/清扫/…）
├── data-model.md        # Phase 1：incident + incident_event + agent_action 扩列 + 状态机
├── quickstart.md        # Phase 1：端到端验证脚本（注入失败→卡片→重跑→自动愈合→自动关闭）
├── contracts/
│   ├── incident-api.md  # /api/incidents REST 契约
│   └── ui-surface.md    # incidents 视图 / 主页 pinned / 卡片剖面 / 深链契约
└── tasks.md             # /speckit-tasks 生成（本命令不产出）
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── domain/incident/
│   │   ├── Incident.java                 # 领域对象（含 IncidentStates 常量）
│   │   └── IncidentEvent.java            # 时间线条目
│   ├── application/incident/
│   │   ├── IncidentService.java          # 开单/附着/归并/状态 CAS/查询排序
│   │   ├── IncidentSignalListener.java   # 第三个 AlertSignal 消费者（同步、整体 try-catch）
│   │   ├── IncidentHealListener.java     # TaskSucceededEvent / WorkflowSucceededEvent → 自动愈合
│   │   ├── IncidentTriageService.java    # 爆炸半径(neo4j) + 时间预算(sla_baseline 投影)
│   │   └── IncidentSweeper.java          # @Scheduled：7 天自动关闭 + NODE 心跳恢复愈合（CAS 幂等）
│   ├── application/WorkerReportService.java   # 改动：workflow SUCCESS 分支发 WorkflowSucceededEvent
│   └── application/GatedActionService.java    # 改动：ActionRequest 透传 incidentId → agent_action.incident_id
├── dataweave-api/src/main/
│   ├── java/com/dataweave/api/interfaces/IncidentController.java  # /api/incidents（ProjectScope）
│   └── resources/schema.sql              # +incident/+incident_event/agent_action+incident_id；0.7.0
frontend/
├── lib/workspace/views.ts                # +"incidents"（VIEW_META 第一位，defaultPinned:true → 主页）
├── lib/workspace/registry.tsx            # +IncidentsView 渲染注册
├── lib/workspace/nav-groups.ts           # incidents 入组（满足 nav-groups.test.ts 不变量）
├── lib/incident-api.ts                   # 队列/详情/重跑/静默/备注 客户端
├── components/workspace/views/incidents-view.tsx      # 监督席队列（活跃区+24h已解决区+历史）
├── components/workspace/views/incident/*.tsx          # 卡片/时间线抽屉/分诊徽标子组件
└── messages/{zh-CN,en-US}.json           # +incidents 命名空间（双 bundle 键集一致，CI 硬门）
```

**Structure Decision**: incident 域落 **dataweave-master**（深耦合 LineageQueryService / SlaService / GatedActionService / InstanceStates；alert 模块按 FR-014 零改动）；HTTP 面在 dataweave-api interfaces（与 OpsController/FreshnessController 同层）；前端沿 workspace 视图注册三件套（views/registry/nav-groups）+ 独立视图组件目录。详细取舍见 [research.md](research.md) D1。

## Complexity Tracking

无 Constitution 违反项，无需豁免记录。
