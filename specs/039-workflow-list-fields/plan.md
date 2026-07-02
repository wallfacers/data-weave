# Implementation Plan: 周期/手动任务流列表字段增强

**Branch**: `039-workflow-list-fields` | **Date**: 2026-07-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/039-workflow-list-fields/spec.md`

## Summary

为运维中心「周期任务流」「手动任务流」两个卡片补充运维核心字段：周期补「下次触发时间」（相对时间展示）+「优先级」（高优徽标），手动补「优先级」；两表「任务流名称」列增加「描述」副标题；并为「优先级」增加筛选器与列排序。所有新增字段（`next_trigger_time`/`priority`/`description`）均已存在于 `workflow_def` 表 —— **不改 schema、不升 schema_version**；改动集中在后端投影 SQL + DTO/Query 增量扩展、前端两 panel + `unified-data-table` 排序能力 + 新建相对时间 util + i18n。

## Technical Context

**Language/Version**: 后端 Java 25 + Spring Boot 4.0（WebFlux, Jackson 3）；前端 Next.js 16 + React 19 + TypeScript

**Primary Dependencies**: 后端 Spring Data JDBC + JdbcTemplate；前端 shadcn/ui + hugeicons + next-intl

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）— `workflow_def` 表字段已齐，**只读扩展，不改表**

**Testing**: 后端 JUnit5 + AssertJ + WebTestClient（带 JWT，见 MEMORY `backend-fullstack-http-test-jwt`）；前端 vitest（`data-table.ts`/`relative-time.ts` 纯逻辑）+ 浏览器验证

**Target Platform**: Linux server（后端 :8000）/ Web（前端 :4000）

**Project Type**: web-service + web-app

**Performance Goals**: 无新压力——增量加 1 列投影 + 1 个可空 WHERE/ORDER BY，复用现有 server 分页

**Constraints**: server 分页不变；复用 `WorkflowListRow`/`OpsService` 投影；排序字段白名单防注入；相对时间 util 纯函数可测；i18n 两 bundle key 集一致

**Scale/Scope**: 2 个 panel + 1 个公共组件扩展（DataTable 排序）+ 1 个新 util + i18n；后端 1 个 DTO/Query/SQL/Controller 接缝；**无 schema 改动**

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
| --- | --- | --- |
| I. Files-First | 不涉及文件定义，仅列表展示读路径 | ✅ N/A |
| II. Server is Source of Truth | 列表读 server 投影数据，增量加字段；无写操作 | ✅ PASS |
| III. Two-Legged Debugging | 不涉及本地运行 | ✅ N/A |
| IV. 不损伤运行态观测 | 本 feature **增强** ops 列表观测（加字段 + 筛选 + 排序），非削弱；契合 IV 不可让渡内核第 3 条 | ✅ PASS |
| V. Reuse the Kernel | 复用 `WorkflowListRow`/`OpsService` 投影/server 分页框架/`unified-data-table`；无写操作故无 PolicyEngine gate | ✅ PASS |

**Post-design re-check（Phase 1 后）**：data-model 确认无表改动、无新 module、排序扩展向后兼容（`sortable` 缺省 false，现有列/列表不受影响）。**无 violation**。

## Project Structure

### Documentation (this feature)

```text
specs/039-workflow-list-fields/
├── spec.md              # /speckit-specify
├── checklists/requirements.md
├── plan.md              # 本文件 (/speckit-plan)
├── research.md          # Phase 0 (/speckit-plan)
├── data-model.md        # Phase 1 (/speckit-plan)
├── quickstart.md        # Phase 1 (/speckit-plan)
└── contracts/
    └── list-api.md      # 列表查询 API 契约
```

### Source Code (repository root) — 接缝清单

```text
backend/
└── dataweave-master/src/main/java/com/dataweave/master/
    └── application/
        ├── OpsContracts.java     # WorkflowListRow(+nextTriggerTime) / WorkflowQuery(+priorityTier/sortField/sortDir)
        └── OpsService.java       # queryWorkflows(): SELECT/WHERE/ORDER BY 扩展 (137-204)
backend/
└── dataweave-api/src/main/java/com/dataweave/api/interfaces/
    └── OpsController.java        # /periodic-workflows & /manual-workflows @RequestParam 增 priorityTier/sort (118-160)

frontend/
├── lib/
│   ├── data-table.ts            # ColumnDef(+sortable) / FetchQuery(+sort) / toQueryParams(+sort 序列化)
│   ├── data-table.test.ts       # vitest: sort 序列化
│   └── relative-time.ts         # 🆕 纯函数 relativeNextTrigger(iso, now) → {key,values}|null
├── components/ui/
│   └── data-table*.tsx          # 表头 sortable 列点击排序 UI（hugeicons 方向图标，三态切换）
├── components/workspace/views/ops/
│   ├── periodic-workflows-panel.tsx  # +下次触发列 +优先级列 +描述副标题；WorkflowRow(+nextTriggerTime)
│   └── manual-workflows-panel.tsx    # +优先级列 +描述副标题
└── messages/
    ├── zh-CN.json               # ops: 新列头/相对时间/高优徽标/筛选器 keys
    └── en-US.json               # 同 key 集（CI 检一致）
```

**Structure Decision**: 复用现有 backend 四模块 DDD（application 层 `OpsService`/`OpsContracts`，interfaces 层 `OpsController`）与 frontend `unified-data-table` 公共层。无新模块、无新包。排序能力上提为 `data-table.ts` 公共扩展，避免两 panel 各自实现。

## Complexity Tracking

> 无 Constitution violation，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| — | — | — |
