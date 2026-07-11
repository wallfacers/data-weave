# Implementation Plan: 监督席

**Branch**: `064-supervisor-desk` | **Date**: 2026-07-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/064-supervisor-desk/spec.md`

## Summary

重构现有 `IncidentsView`（监督席）为符合设计系统规范的统一视图：使用标准 `Tabs` 组件切换"信号流"和"工单队列"两个子面板；将手写 `fixed` 定位的 `TimelineDrawer` 替换为复用 `DetailPanelShell` 的 Dialog 侧面板模式；修复滚动条与边框重合问题（全面采用 `DwScroll`）；后端新增 `heal_by_type`/`heal_by_ref_id` 列支持精确指纹愈合匹配。

**关键发现**：后端自动开单/愈合逻辑已存在（043 实现），但现用 `failureClass` 规范化（TIMEOUT/EXIT_NONZERO/…）而非原始 `failureReason`，愈合按 `sourceRefId` 全量匹配而非精确指纹。本次改动范围：前端全面重构 + 后端签名/愈合逻辑微调。

## Technical Context

**Language/Version**: TypeScript 5 + React 19 (frontend), Java 25 (backend)
**Primary Dependencies**: Next.js 16 (App Router), shadcn/ui (base-ui), OverlayScrollbars, Spring Boot 4.0 / WebFlux, Spring Data JDBC
**Storage**: PostgreSQL (primary) / H2 (dev), schema version → 0.11.0
**Testing**: vitest + React Testing Library (frontend), JUnit 5 + AssertJ (backend)
**Target Platform**: Web browser (Linux server backend)
**Project Type**: Web application (Next.js SPA + Spring Boot API)
**Performance Goals**: 时间线抽屉首屏 <1s, 15s 自动刷新无感知
**Constraints**: 复用现有 API 契约 (incident-api.ts), 纯前端重构 + 后端仅 schema 列新增 + 签名/愈合逻辑微调
**Scale/Scope**: 单页面重构 (~5 组件), 2 个新 DB 列, 现有 4 个后端文件修改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Files-First | ✅ N/A | 纯 UI 重构 + 后端 schema 扩展，不涉及文件定义 |
| II. Server Source of Truth | ✅ Pass | 复用现有 incident API（server 侧），前端仅消费 |
| III. Two-Legged Debugging | ✅ N/A | 不涉及 CLI/本地执行 |
| IV. AI Lives in Local Agent | ✅ Pass | 无服务端 AI 逻辑；自动开单/愈合是确定性规则引擎 |
| V. Reuse the Kernel | ✅ Pass | 复用现有 IncidentService/IncidentHealListener/IncidentSweeper；schema 扩展不重写内核 |

**Post-Design Re-check**: 所有设计决策复用现有内核（DetailPanelShell、IncidentService、现有 API 端点）。无违规。

## Project Structure

### Documentation (this feature)

```text
specs/064-supervisor-desk/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── components/workspace/views/
│   ├── incidents-view.tsx              # [REWRITE] 主视图：Tabs(信号流+工单队列)
│   └── incident/
│       ├── actions.tsx                 # [REWRITE] TimelineDrawer→IncidentTimelineDialog
│       ├── triage.tsx                  # [REFACTOR] Badge→Badge 语义变体
│       └── signal-stream-panel.tsx     # [NEW] 信号流面板（DwScroll + 筛选 + 信号卡片）
├── components/workspace/
│   └── incident-timeline-dialog.tsx    # [NEW] Dialog+DetailPanelShell 时间线抽屉
├── lib/
│   └── incident-api.ts                # [EXTEND] +healByType/healByRefId 字段
└── messages/
    └── zh-CN.json                      # [EXTEND] +signalStream 命名空间
    └── en-US.json                      # [EXTEND] parity

backend/
├── dataweave-api/src/main/resources/
│   └── schema.sql                      # [MODIFY] incident +heal_by_type/heal_by_ref_id, bump→0.11.0
├── dataweave-master/src/main/java/.../
│   ├── domain/incident/
│   │   └── Incident.java               # [MODIFY] +healByType/healByRefId 字段
│   └── application/incident/
│       ├── IncidentService.java        # [MODIFY] openOrAttach 存愈合条件; healByTask 按精确指纹
│       └── IncidentSignalListener.java # [MODIFY] signature 改用原始 failureReason 替代 failureClass
```

**Structure Decision**: 纯增量修改——不新建模块/包。前端在现有 `incident/` 目录扩展；后端仅修改 schema + 4 个既有 Java 文件。

## Complexity Tracking

> 无 Constitution 违规需要 justify。
