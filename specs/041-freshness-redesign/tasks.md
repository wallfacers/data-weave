# Tasks: 数据新鲜度页面重新设计

**Feature**: 041-freshness-redesign | **Branch**: `041-freshness-redesign` | **Date**: 2026-07-03

**Input**: [spec.md](spec.md) · [plan.md](plan.md) · [data-model.md](data-model.md) · [research.md](research.md) · [contracts/freshness-api.md](contracts/freshness-api.md)

## Implementation Strategy

按后端→前端顺序推进。先完成 schema + 快照基础设施（US5），确保数据管线可用；然后前端从下往上搭建：提取公共组件 → 概览区（US1）→ 增强表格（US2）→ 点击联动（US3）→ 行操作（US4）。US1 完成后即可独立演示 MVP。

## Phase 1: Setup（基础设施）

> 共享前置任务，所有 User Story 都依赖此阶段完成。

- [x] T001 [P] 在 `backend/dataweave-api/src/main/resources/schema.sql` 新增 `freshness_daily_snapshot` 表 DDL（含 UNIQUE 约束和索引），schema_version 升到 0.6.3
- [x] T002 [P] 在 `backend/dataweave-api/src/main/resources/schema.sql` 新增 `freshness_task_daily` 表 DDL（含 UNIQUE 约束和 `idx_ftd_task_dates` 索引）
- [x] T003 [P] 从 `frontend/components/cockpit/fleet-card.tsx` 提取 ResourceBar 组件到 `frontend/components/ui/resource-bar.tsx`，增加 `threshold` 和 `highIsBad` props
- [x] T004 修改 `frontend/components/cockpit/fleet-card.tsx` 使用公共 `@/components/ui/resource-bar` 的 ResourceBar
- [x] T005 [P] 在 `frontend/messages/zh-CN.json` 新增 freshness 概览区和操作相关的 i18n key
- [x] T006 [P] 在 `frontend/messages/en-US.json` 新增 freshness 概览区和操作相关的 i18n key（与 zh-CN key 集完全一致）

## Phase 2: US5 — 每日快照与历史对比（后端数据基础）

**Goal**: 每日凌晨 02:00 自动拍摄所有任务新鲜度快照，为日环比和火花图提供历史数据。

**Independent Test**: 快照表在每日 02:00 后存在当天数据；手动调用 `takeDailySnapshot()` 可立即生成快照。

- [x] T007 [US5] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessSnapshotJob.java` 创建 `@Scheduled` 定时任务，cron `0 0 2 * * ?`，使用 PG advisory lock 保证单例执行
- [x] T008 [US5] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 新增 `takeSnapshot(tenantId, projectId)` 方法：执行当前聚合查询 + INSERT INTO freshness_task_daily/freshness_daily_snapshot（ON CONFLICT DO NOTHING）
- [x] T009 [US5] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 新增 `cleanupOldSnapshots()` 方法：DELETE WHERE snapshot_date < NOW() - 90 days
- [x] T010 [P] [US5] 在 `frontend/lib/workspace/freshness-api.ts` 创建 API 封装：`fetchDashboard(projectId)` 和 `fetchFreshnessTable(query, projectId, externalTier)`
- [x] T011 [US5] 在 `backend/dataweave-api/src/test/java/com/dataweave/api/FreshnessDashboardTest.java` 新增集成测试：快照写入 + 查询 + 幂等 + 清理

**Checkpoint**: 快照管线可用——执行 `takeDailySnapshot()` 后 `freshness_task_daily` 和 `freshness_daily_snapshot` 有数据。

## Phase 3: US1 — 新鲜度全局概览（P1 · MVP 核心）

**Goal**: 页面顶部展示 4 个横排统计卡片 + 数据健康度进度条 + 日环比 + 分布文字。

**Independent Test**: 打开页面 → 概览区显示 4 卡片、进度条、分布文字；有快照时显示日环比箭头。

- [x] T012 [US1] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/FreshnessController.java` 新增 `GET /api/freshness/dashboard` 端点，返回 `FreshnessDashboard`（summary + trend + snapshotDate）
- [x] T013 [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 新增 `getDashboard(tenantId, projectId)` 方法：查当前分布 + 前一天快照 → 计算 delta
- [x] T014 [P] [US1] 在 `frontend/components/workspace/views/freshness-summary-strip.tsx` 创建概览区组件：4 个横排卡片（新鲜/偏旧/陈旧/从未成功）、点击联动预留 `onTierClick` prop
- [x] T015 [US1] 在 `frontend/components/workspace/views/freshness-summary-strip.tsx` 实现数据健康度进度条（复用 ResourceBar，threshold=70，highIsBad=false）
- [x] T016 [US1] 在 `frontend/components/workspace/views/freshness-summary-strip.tsx` 实现日环比箭头和百分比（↑↓→），无快照时不显示
- [x] T017 [US1] 在 `frontend/components/workspace/views/freshness-summary-strip.tsx` 实现分布文字（"新鲜 72% · 偏旧 20% · 陈旧 6.7% · 从未成功 1.7%"）
- [x] T018 [US1] 重构 `frontend/components/workspace/views/freshness-view.tsx`：集成 freshness-summary-strip 和现有 DataTable，使用新 fetcher

**Checkpoint**: MVP 可演示——页面顶部概览区完整显示，下方保留原有表格。

## Phase 4: US2 — 增强表格（P2）

**Goal**: 表格新增调度周期列和 7 天火花图列，任务名副标题显示工作流名。

**Independent Test**: 表格每行含 7 列，调度周期人读显示，火花图自适应数据点数，当天点实心。

- [x] T019 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 的 `query()` SQL 中 JOIN `workflow_node` + `workflow_def`，获取 `workflow_name`、`schedule_type`、`cron`、`schedule_interval_ms`
- [x] T020 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 新增 `toHumanSchedule(scheduleType, cron, intervalMs)` 静态方法，实现 6 种 Cron 模式 + FIXED_RATE 人读转换
- [x] T021 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 的 `query()` SQL 中 LEFT JOIN LATERAL `freshness_task_daily`（最近 7 天），组装 `trend7Days` int 数组
- [x] T022 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/FreshnessService.java` 扩展 `FreshnessRow` record：新增 `workflowName`、`scheduleType`、`scheduleHuman`、`trend7Days` 字段
- [x] T023 [P] [US2] 在 `frontend/components/workspace/views/freshness-sparkline.tsx` 创建 SVG 火花图组件：自适应 1-7 个数据点、当前点实心 r=2、历史点空心 r=1.5、颜色 `var(--chart-1)`、无数据时返回 "—"
- [x] T024 [P] [US2] 在 `frontend/components/workspace/views/freshness-tier-badge.tsx` 从 freshness-view.tsx 提取时效 Badge（含 `onClick` prop 预留联动）
- [x] T025 [P] [US2] 在 `frontend/components/workspace/views/freshness-age-display.tsx` 从 freshness-view.tsx 提取距今人读显示
- [x] T026 [US2] 修改 `frontend/components/workspace/views/freshness-view.tsx` 的 `columns` 定义：使用新的 tierBadge/ageDisplay/sparkline 组件，添加 schedule 列和工作流副标题
- [x] T027 [US2] 在 `backend/dataweave-api/src/test/java/com/dataweave/api/FreshnessDashboardTest.java` 新增测试：扩展字段非空、trend7Days 长度 ≤7、scheduleHuman 非空

**Checkpoint**: 表格 7 列完整显示，火花图和数据正确渲染。

## Phase 5: US3 — 时效 Badge 点击联动筛选（P3）

**Goal**: 概览卡片和表格 Badge 可点击，点击后表格按对应档位筛选。

**Independent Test**: 点击概览区"陈旧"卡片 → 表格仅显示 STALE 任务；再点击"新鲜"Badge → 筛选项切换。

- [x] T028 [US3] 在 `frontend/components/workspace/views/freshness-view.tsx` 新增 `externalTier` state，传递到 DataTable fetcher 拼入 URL，实现外挂筛选
- [x] T029 [US3] 在 `frontend/components/workspace/views/freshness-summary-strip.tsx` 实现卡片点击：调用 `onTierClick(tier)` → 设置 `externalTier`；同一卡片再次点击取消筛选
- [x] T030 [US3] 在 `frontend/components/workspace/views/freshness-tier-badge.tsx` 实现 Badge 点击：`e.stopPropagation()` + 调用 `onClick(tier)`
- [x] T031 [US3] 在 `frontend/components/workspace/views/freshness-view.tsx` 实现联动高亮：当前 `externalTier` 对应的概览卡片高亮、工具栏筛选同步

**Checkpoint**: 点击联动完整闭环——卡片/Badge 点击 → 表格过滤 → 再次点击取消。

## Phase 6: US4 — 行操作快捷入口（P4）

**Goal**: 每行末尾提供 5 个图标操作按钮，hover tooltip，重跑确认弹窗，订阅 toggle。

**Independent Test**: 点击重跑 → 确认弹窗 → 提交请求；点击订阅 → 图标切换已订阅态。

- [x] T032 [US4] 在 `frontend/components/workspace/views/freshness-view.tsx` 的操作列中实现 5 个图标按钮（查看日志/查看实例/查看 DAG/重跑/订阅告警），使用 `Button size="icon" variant="ghost" className="size-7"` + Tooltip，与运营中心同款
- [x] T033 [US4] 在 `frontend/components/workspace/views/freshness-view.tsx` 实现重跑确认弹窗（复用 ConfirmDialog 模式）
- [x] T034 [US4] 在 `frontend/components/workspace/views/freshness-view.tsx` 实现订阅告警 toggle：调用 `AssetSubscriptionService` 订阅/取消 API，图标根据订阅态切换
- [x] T035 [P] [US4] 在 `frontend/messages/zh-CN.json` 新增行操作 tooltip 的 i18n key（btnLog/btnInstances/btnDag/btnRerun/btnSubscribe）
- [x] T036 [P] [US4] 在 `frontend/messages/en-US.json` 新增行操作 tooltip 的 i18n key（与 zh-CN 对齐）

**Checkpoint**: 全部 5 个操作按钮功能可用，订阅态正确切换。

## Phase 7: Polish & Cross-Cutting Concerns

> 跨 User Story 的收尾和打磨。

- [x] T037 在 `frontend/components/workspace/views/freshness-view.tsx` 实现概览区加载态（首次加载 skeleton）和空状态（0 任务时的友好提示）
- [x] T038 在 `frontend/components/workspace/views/freshness-view.tsx` 确保项目切换时概览区和表格同步刷新（复用现有 `projectId` 响应式机制）
- [x] T039 在 `frontend/components/workspace/views/freshness-view.tsx` 调整列宽百分比（新增 7 列后总和 ≈100）
- [x] T040 运行 `cd frontend && pnpm typecheck` 确认零类型错误
- [x] T041 运行 `cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api,dataweave-master test` 确认后端测试全绿

## Dependencies

```
Phase 1 (Setup: T001-T006)
  └── Phase 2 (US5 快照: T007-T011)
        ├── Phase 3 (US1 概览: T012-T018) ← MVP
        │     └── Phase 4 (US2 表格: T019-T027)
        │           ├── Phase 5 (US3 联动: T028-T031)
        │           └── Phase 6 (US4 操作: T032-T036)
        └── Phase 7 (Polish: T037-T041)
```

- US1 依赖 US5（dashboard API 需要快照表存在）
- US2 依赖 US1（表格在刷新后的页面框架内）
- US3 依赖 US2（联动依赖 Badge 组件和表格）
- US4 依赖 US2（操作列在表格内）
- US3 和 US4 可并行开发

## Parallel Opportunities

| 阶段 | 可并行任务 |
|------|------------|
| Phase 1 | T001 ∥ T002 ∥ T003 ∥ T005 ∥ T006 |
| Phase 4 | T023 ∥ T024 ∥ T025（3 个新组件独立） |
| Phase 6 | T035 ∥ T036（i18n 与组件逻辑独立） |
| Phase 7 | T040 ∥ T041（前后端检查独立） |

## Task Summary

| Phase | Story | Task Count |
|-------|-------|------------|
| Phase 1 | Setup | 6 |
| Phase 2 | US5 (P5) | 5 |
| Phase 3 | US1 (P1) | 7 |
| Phase 4 | US2 (P2) | 9 |
| Phase 5 | US3 (P3) | 4 |
| Phase 6 | US4 (P4) | 5 |
| Phase 7 | Polish | 5 |
| **Total** | | **41** |

## MVP Scope

Phase 1 + Phase 2 + Phase 3 = 18 tasks，完成后页面即可演示概览区 + 原有表格。
