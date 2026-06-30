# Implementation Plan: 统一数据健康事件中心

**Branch**: `027-event-center` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/027-event-center/spec.md`

## Summary

把散落的数据健康信号（SLA 违约 / 质量断言失败 / 任务失败·租约过期 / 预留血缘 CONFLICT）汇成一个**持久、可查、可订阅的统一事件中心**。三件事：① **统一信号契约**——发现 `quality.domain.AlertSignal`（record）是 022 在 021 未合并期自建的、且**无人消费**（`AlertSignalListener` 只听 `domain.signal.AlertSignal`），导致质量断言信号是孤儿、从未到达告警引擎；统一为单一 `domain.signal.AlertSignal` 既消重又**修复这个真 bug**。② **旁路持久化**——新增 `HealthEventRecorder`（第二个 `@EventListener`，与告警分发并行、不干扰），把每条信号落 `health_event` 表（按 fingerprint 去重）。③ **视图 + 订阅**——查询 API + 新 Workspace 事件中心视图；`event_subscription` 命中经 026 通道分发（复用 `AlertDispatchService`）。

## Technical Context

**Language/Version**: Java 25（后端）+ TypeScript/React 19（前端）

**Primary Dependencies**: Spring Boot 4.0（Jackson 3）；Spring Data JDBC + JdbcTemplate；复用 master 信号总线（`ApplicationEventPublisher` + `domain.signal.AlertSignal`）与 026 告警分发（`AlertDispatchService`/通道）；前端 Next.js 16 + shadcn/hugeicons

**Storage**: PostgreSQL（默认）/ H2；**新增表 `health_event`、`event_subscription`** → **schema_version 0.3.0 → 0.4.0**（库内/文件头/项目版本三处恒等）

**Testing**: JUnit 5 + AssertJ；H2 独立库名隔离；前端 vitest + 浏览器验证（新视图）

**Target Platform**: Linux server（backend `dataweave-alert` + `dataweave-master`）+ 浏览器（Workspace）

**Project Type**: web（backend + frontend）

**Performance Goals**: 信号产生后事件可即时检索（≤ 数秒）；持久化旁路不拖慢告警分发

**Constraints**: 事件中心为**旁路**，对既有告警即时分发与各信号业务判定**零回归**；按租户隔离；订阅分发失败不阻断持久化

**Scale/Scope**: 多租户；信号源 = 现有 emit 点；改动跨 master（统一信号 + emit 点）+ alert（持久化/订阅/API）+ frontend（新视图）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**: N/A —— 不引入新定义文件格式。
- **II. Server is Source of Truth**: N/A —— 不涉及 pull/push。
- **III. Two-Legged Debugging**: N/A。
- **IV. AI Lives in Local Agent**: ✅ —— 不嵌服务端 AI 大脑；事件中心是运行态观测能力（属"不得损伤的观测面"的增强，非删除）。
- **V. Reuse the Kernel**: ✅ —— 复用 master 信号总线、026 告警分发/通道；统一信号契约是**消重 + 修孤儿 bug**，非重写。订阅分发经 026，无旁路绕闸（事件分发是平台内部运行态行为，与告警一致，不经 PolicyEngine——与 026 同一豁免理由）。

**结论**：无违例。新增表属正常 schema 演进（升版本，非违例）。

## Project Structure

### Documentation (this feature)

```text
specs/027-event-center/
├── plan.md  spec.md  research.md  data-model.md  quickstart.md
├── contracts/  (unified-signal-contract.md · health-event-api.md · subscription-contract.md)
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/                     # 统一信号契约
│   └── .../quality/application/QualitySignalEmitter.java  # 改用 domain.signal.AlertSignal（修孤儿）
│   └── .../quality/domain/AlertSignal.java                # 删除（消重）
├── dataweave-alert/                      # 事件中心主体
│   └── .../application/HealthEventRecorder.java           # 新增：第二 @EventListener，旁路持久化
│   └── .../domain/HealthEvent.java + repository/...       # 新增实体 + repo
│   └── .../domain/EventSubscription.java + repository/...  # 新增订阅实体 + repo
│   └── .../infrastructure/jdbc/...                         # JDBC 实现
│   └── .../interfaces/EventCenterController.java           # 查询 + 订阅 API
└── dataweave-api/src/main/resources/schema.sql            # +health_event +event_subscription, 升 0.4.0

frontend/
├── components/workspace/views/event-center-view.tsx       # 新视图
├── lib/workspace/views.ts + registry.tsx                  # 注册 "event-center"
└── messages/{zh-CN,en-US}.json                             # i18n（两 bundle 同键）
```

**Structure Decision**: 统一信号在 master；持久化/订阅/API 在 alert（依赖 master 信号 + 026 分发）；新视图在 frontend。新增两表，schema_version 升 0.4.0。

## Complexity Tracking

> 无 Constitution 违例，本节留空。
