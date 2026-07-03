# Implementation Plan: 调度与运行态总览日期筛选

**Branch**: `040-ops-summary-date-filter` | **Date**: 2026-07-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/040-ops-summary-date-filter/spec.md`

## Summary

运维中心顶条「调度与运行态总览」增加按业务日期（`biz_date`）的单日筛选：后端 `/api/ops/summary` 增可选 `bizDate` 参数，前端顶条增 DatePicker，统计数字随之过滤。SLA 风险不受影响。标签"今日总数"改为"总数"。

## Technical Context

**Language/Version**: 后端 Java 25 + Spring Boot 4.0（WebFlux, Jackson 3）；前端 Next.js 16 + React 19 + TypeScript

**Primary Dependencies**: 后端 Spring Data JDBC；前端 shadcn/ui（DatePicker 已有）+ next-intl

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）— `task_instance` 表 `biz_date` 列已存在，**只读过滤，不改表**

**Testing**: 后端 JUnit5 + AssertJ + WebTestClient（带 JWT）；前端 vitest（如有纯逻辑）+ 浏览器验证

**Target Platform**: Linux server（后端 :8000）/ Web（前端 :4000）

**Project Type**: web-service + web-app

**Performance Goals**: `biz_date` 列已有索引 `idx_task_instance_node_bizdate`，WHERE 条件过滤无额外性能压力

**Constraints**: 不改 schema、不升 schema_version；API 向后兼容（`bizDate` 可选）；SLA 风险端点不改

**Scale/Scope**: 1 个 API 参数 + 1 个 Repository 方法 + 1 个组件改 + 2 行 i18n；极小改动面

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
|------|------|------|
| I. Files-First | 不涉及文件定义，仅统计读路径 | ✅ N/A |
| II. Server is Source of Truth | 统计读 server 数据，仅增量加过滤参数 | ✅ PASS |
| III. Two-Legged Debugging | 不涉及本地运行 | ✅ N/A |
| IV. 不损伤运行态观测 | 本 feature **增强** ops 观测（加日期筛选），非削弱；契合 IV 第 3 条 | ✅ PASS |
| V. Reuse the Kernel | 复用 `OpsService`/`TaskInstanceRepository`/`DatePicker`；无写操作故无 PolicyEngine gate | ✅ PASS |

**Post-design re-check**: data-model 确认无表改动、无新 module、API 向后兼容。**无 violation**。

## Project Structure

### Documentation (this feature)

```text
specs/040-ops-summary-date-filter/
├── spec.md              # /speckit-specify
├── plan.md              # 本文件 (/speckit-plan)
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
└── contracts/
    └── list-api.md      # /api/ops/summary 契约
```

### Source Code (repository root) — 接缝清单

```text
backend/
└── dataweave-master/src/main/java/com/dataweave/master/
    ├── domain/
    │   └── TaskInstanceRepository.java  # +findByProjectIdAndRunModeAndBizDate
    └── application/
        └── OpsService.java              # summary(): +bizDate 参数，instances() 分流
backend/
└── dataweave-api/src/main/java/com/dataweave/api/interfaces/
    └── OpsController.java               # /summary: +@RequestParam bizDate

frontend/
├── components/workspace/views/ops/
│   └── top-strip.tsx                    # +bizDate state + DatePicker + URL 拼接
└── messages/
    ├── zh-CN.json                       # topTotal: "今日总数"→"总数"
    └── en-US.json                       # topTotal: "Today Total"→"Total"
```

**Structure Decision**: 复用现有 backend 四模块 DDD 与 frontend 组件布局。无新模块、无新包、无新组件（DatePicker 已存在）。

## Complexity Tracking

> 无 Constitution violation，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
