# Implementation Plan: 实例列表排序 + 操作按钮状态化

**Branch**: `056-instance-list-sort-actions` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/056-instance-list-sort-actions/spec.md`

## Summary

两个改进：① 任务流实例和任务实例列表新增按时间字段排序（默认 scheduledFireTime DESC），复用现有 DataTable 排序基础设施 + 后端扩增 sort 参数；② 操作按钮基于实例状态条件禁用（RUNNING 不重跑等），纯前端状态推导 + 现有 `disabled` prop。

## Technical Context

**Language/Version**: Java 25 + TypeScript/React 19
**Primary Dependencies**: Spring Boot 4.0 (WebFlux), Next.js 16 (App Router), shadcn/ui, DataTable (自研)
**Storage**: PostgreSQL（sort 通过 SQL ORDER BY 动态拼接，沿用已有 whitelist 模式）
**Testing**: JUnit 5 + AssertJ (backend), vitest + browser (frontend)
**Target Platform**: Web 应用（Docker 多节点 distributed 集群）
**Project Type**: Web 应用（frontend + backend）
**Performance Goals**: 排序查询 < 200ms（与现有实例列表查询同等量级，5000 条内）
**Constraints**: 排序字段白名单（防 SQL 注入），null 值排序约定（NULLS LAST for DESC）
**Scale/Scope**: 两个面板各 5 个排序列 + 操作按钮状态表（7 种实例状态 × 3 种操作）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Files-First | ✅ N/A | 不涉及文件定义变更 |
| II. DDD Layering | ✅ Pass | 排序扩展在现有接口层 + 领域层契约内，不改分层边界 |
| III. Phased Runtime | ✅ Pass | 不涉及运行时阶段变更 |
| IV. AI Lives in Local Agent | ✅ Pass | 不涉及 AI 能力 |
| V. Proper Layering | ✅ Pass | 变更仅涉及已存在的 controller/service/contracts，不跨层违规 |

**Gate Result**: PASS — 无违规，无需 justification。

## Project Structure

### Documentation (this feature)

```text
specs/056-instance-list-sort-actions/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
# Frontend (排序 UI + 按钮状态)
frontend/
├── lib/
│   └── data-table.ts                    # SortState, FetchQuery.sort 已有，不变
├── components/
│   ├── ui/
│   │   └── data-table.tsx               # 排序列头 UI 已有，不变
│   └── workspace/
│       └── views/
│           └── ops/
│               ├── workflow-instances-panel.tsx  # +sortable cols + 按钮状态逻辑
│               └── periodic-instances-panel.tsx  # +sortable cols + 按钮状态逻辑

# Backend (sort API 扩展)
backend/
├── dataweave-api/src/main/java/com/dataweave/api/
│   ├── interfaces/
│   │   └── OpsController.java           # 新增 sort 参数解析
│   ├── interfaces/dto/
│   │   └── InstanceQuery.java           # 新增 sortField/sortDir
│   └── application/
│       └── DataOpsBridgeRealImpl.java   # 透传 sort 字段
├── dataweave-master/src/main/java/com/dataweave/master/
│   └── application/
│       ├── OpsContracts.java            # InstanceQuery/WorkflowInstanceQuery +sortField/sortDir
│       └── OpsService.java              # queryInstances/queryWorkflowInstances +orderByClause
└── dataweave-master/src/main/resources/
    └── messages*.properties              # 按钮状态 Tooltip i18n（如需要）
```

**Structure Decision**: 沿用现有 Web 应用结构（frontend/ + backend/ 双项目），改动集中在已有的 Ops 面板和 API 层。

## Complexity Tracking

> 无宪法违规，此节留空。

## Implementation Phases

### Phase 1: Backend Sort API（后端排序支持）

**参考模式**: `OpsService.orderByClause()` (lines 218-224) 为 `queryWorkflows` 实现的 whitelist 排序。

改动清单：

1. **OpsContracts.InstanceQuery** — 新增 `sortField` (String, nullable) 和 `sortDir` (String, nullable)
2. **OpsContracts.WorkflowInstanceQuery** — 新增 `sortField`/`sortDir`
3. **api.dto.InstanceQuery** — 新增 `sortField`/`sortDir`
4. **OpsService.queryInstances()** — 替换硬编码 ORDER BY 为 `instanceOrderByClause(ti, q)`：
   - Whitelist: `scheduledFireTime` → `ti.scheduled_fire_time`, `bizDate` → `ti.biz_date`, `startedAt` → `ti.started_at`, `finishedAt` → `ti.finished_at`, `durationMs` → `ti.duration_ms`
   - 默认（无 sort 参数时）保持现有优先级排序
   - `dir = "asc"` → ASC NULLS LAST, `dir = "desc"` → DESC NULLS LAST
   - 次级键：`ti.id` 保证稳定排序
5. **OpsService.queryWorkflowInstances()** — 同上，引用 `wi.*` 列
6. **OpsController.instances()** — 接受 `@RequestParam(required = false) String sort`，解析 `field:dir` 格式
7. **OpsController.workflowInstances()** — 同上
8. **DataOpsBridgeRealImpl** — 透传 sortField/sortDir

### Phase 2: Frontend Sort UI（前端列头排序）

1. **workflow-instances-panel.tsx** — 为以下列添加 `sortable: true` + `sortKey`：
   - `scheduledFireTime`（sortKey: `scheduledFireTime`）
   - `bizDate`（sortKey: `bizDate`）
   - `startedAt`（sortKey: `startedAt`）
   - `finishedAt`（sortKey: `finishedAt`）
   - `durationMs`（sortKey: `durationMs`）
2. **periodic-instances-panel.tsx** — 同上
3. **默认排序** — 在 fetcher 中添加默认 sort 参数：`{ field: "scheduledFireTime", dir: "desc" }`
4. **URL 持久化** — 读写 `useSearchParams` 或 zustand store 保持排序状态（跨 Tab 切换保留）

### Phase 3: 操作按钮状态化（前端按钮禁用逻辑）

状态 → 按钮可用性映射表：

| 实例状态 | 重跑全部 | 从失败恢复 | 停止 |
|---|---|---|---|
| RUNNING / DISPATCHED | ❌ | ❌ | ✅ |
| SUCCESS | ✅ | ❌ | ❌ |
| FAILED / PREEMPTED | ✅ | ✅ | ✅ |
| STOPPED | ✅ | ❌ | ❌ |
| NOT_RUN / WAITING / PAUSED | ❌ | ❌ | ✅ |

实现方式：
1. 提取 `isActionEnabled(state, action)` 纯函数 → `lib/instance-actions.ts`
2. **workflow-instances-panel.tsx**: 每个操作按钮的 `disabled` prop 调用上述函数
3. **periodic-instances-panel.tsx**: 同上
4. **批量操作**: `bulkActions` 中检查所有选中行，任一不满足 → 按钮整体 disabled + Tooltip
5. 禁用态视觉：`disabled:opacity-50 cursor-not-allowed`（shadcn Button 原生支持）

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| 排序字段注入 | 白名单映射（Java `switch`），非白名单字段回退默认排序 |
| 默认排序打破现有行为 | 无 sort 参数时保持现有优先级排序（FAILED 优先），仅显式传 sort 时覆盖 |
| null scheduledFireTime 排序 | SQL `NULLS LAST` 确保降序时 null 排末，与 FR-007 一致 |
| 按钮状态与后端不一致 | 前端状态仅做 UI 防护，后端校验保持不变（双重保障） |

## Verification

1. `cd backend && ./mvnw -pl dataweave-master test` → 新增 orderByClause 白名单单测
2. `curl /api/ops/workflow-instances?sort=scheduledFireTime:desc` → 验证排序生效
3. 浏览器：Ops Center → Workflow Instances → 点击列头切换排序 → 验证指示箭头 + 数据重排
4. 浏览器：查看各状态实例 → 验证按钮禁用态正确
5. `cd frontend && pnpm typecheck` → 零错误
