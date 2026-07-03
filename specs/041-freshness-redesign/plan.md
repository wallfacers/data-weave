# Implementation Plan: 数据新鲜度页面重新设计

**Branch**: `041-freshness-redesign` | **Date**: 2026-07-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/041-freshness-redesign/spec.md`

## Summary

将数据新鲜度页面从单张扁平的 5 列表格升级为"概览区 + 增强表格"两层结构。概览区包含 4 个时效档位统计卡片（横排等宽）、ResourceBar 同款数据健康度进度条、日环比趋势和分布文字。表格新增调度周期列（6 种 Cron 模式人读转换）和 7 天自适应火花图列；时效 Badge 和概览卡片支持点击联动筛选；行末提供 5 个图标操作按钮（icon+tooltip，运营中心同款）。后端新增两张快照表 + 每日 02:00 定时任务，为日环比和火花图提供历史数据。

## Technical Context

**Language/Version**: TypeScript (React 19, Next.js 16 App Router) + Java 25 (Spring Boot 4.0, Jackson 3)

**Primary Dependencies**: shadcn/ui (base style), next-intl, hugeicons (frontend); Spring Data JDBC, JdbcTemplate (backend)

**Storage**: PostgreSQL (schema 0.6.0 → 0.6.1, 新增 freshness_daily_snapshot + freshness_task_daily 两张表); H2 for local/tests

**Testing**: vitest (frontend), JUnit 5 + AssertJ + WebTestClient (backend)

**Target Platform**: Linux server (Docker Compose / direct boot)

**Project Type**: Web application — Next.js frontend + Spring Boot backend

**Performance Goals**: 页面加载不慢于当前新鲜度页面（freshness query 0.6.0 基准）; 快照任务不阻塞 master 调度

**Constraints**: 复用现有 ResourceBar、DataTable、ViewRefreshControl、AssetSubscription；火花图纯内联 SVG 不引入图表库

**Scale/Scope**: 单项目任务量通常 < 500；快照 90 天保留

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Task-as-Code — 任务=本地文件+CLI/MCP | N/A | 本 feature 不涉及任务定义变更 |
| II. 前端优先 + 复用公共组件 | ✅ PASS | 复用 ResourceBar、DataTable、ViewRefreshControl、Card |
| III. 后端 DDD 四模块 | ✅ PASS | FreshnessService/FreshnessSnapshotJob 在 master 模块内，不改 api/worker/alert 边界 |
| IV. AI Lives in the Local Agent | N/A | 不涉及 Agent/MCP 能力扩展 |
| V. No Ellipsis for Progress | ✅ PASS | 无 `…` 用于加载态 |

## Project Structure

### Documentation (this feature)

```text
specs/041-freshness-redesign/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── freshness-api.yaml
└── tasks.md             # Phase 2 output (speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── components/
│   ├── ui/
│   │   └── resource-bar.tsx              # NEW: 从 fleet-card.tsx 提取
│   ├── cockpit/
│   │   └── fleet-card.tsx                # MOD: 改用公共 ResourceBar
│   └── workspace/
│       └── views/
│           ├── freshness-view.tsx         # MOD: 重构为概览区 + 增强表格
│           ├── freshness-summary-strip.tsx # NEW: 概览区组件
│           ├── freshness-sparkline.tsx     # NEW: SVG 火花图
│           ├── freshness-tier-badge.tsx    # NEW: 时效 Badge（提取+联动）
│           ├── freshness-age-display.tsx   # NEW: 距今人读（提取）
│           └── view-refresh-control.tsx    # MOD: 保持复用
├── lib/
│   └── workspace/
│       └── freshness-api.ts              # NEW: dashboard + table fetcher
└── messages/
    ├── zh-CN.json                         # MOD: 新增 freshness 命名空间 key
    └── en-US.json                         # MOD: 同上

backend/
├── dataweave-api/
│   ├── src/main/java/com/dataweave/api/interfaces/
│   │   └── FreshnessController.java       # MOD: 新增 /dashboard + 扩展 / 端点
│   ├── src/main/resources/
│   │   └── schema.sql                     # MOD: 新增两张表 + 版本号 0.6.1
│   └── src/test/java/com/dataweave/api/
│       └── FreshnessDashboardTest.java    # NEW: dashboard 端点测试
├── dataweave-master/
│   └── src/main/java/com/dataweave/master/application/
│       ├── FreshnessService.java          # MOD: 扩展 FreshnessRow + SQL
│       └── FreshnessSnapshotJob.java      # NEW: 每日凌晨快照定时任务
```

**Structure Decision**: 单项目 web 应用，前后端分离。新增组件在现有模块边界内扩展，不改 DDD 分层结构。

## Complexity Tracking

无 constitution 违规，无需填写。
