# Tasks: 调度与运行态总览日期筛选

**Input**: Design documents from `specs/040-ops-summary-date-filter/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 无显式测试要求。后端复用现有测试框架，前端浏览器验证。

**Organization**: 任务按 user story 分组，支持独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件，无依赖）
- **[Story]**: 所属 user story（US1, US2, US3）
- 描述含确切文件路径

## Path Conventions

- **Web app**: `backend/`, `frontend/`
- 具体路径见 plan.md 接缝清单

---

## Phase 1: Setup

**Purpose**: 无需新项目初始化——改动在既有代码上增量进行。

- [x] T001 确认分支 `040-ops-summary-date-filter` 已创建，基于 `main` 最新提交（实际直接提交 main：aa73ca4）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 后端 Repository 方法是所有统计过滤的前提

**⚠️ CRITICAL**: US1 依赖此 Phase

- [x] T002 新增 `findByProjectIdAndRunModeAndBizDate` 方法到 `backend/dataweave-master/src/main/java/com/dataweave/master/domain/TaskInstanceRepository.java`

**Checkpoint**: Repository 方法就绪，可进入 US1

---

## Phase 3: User Story 1 - 按业务日期查看任务实例统计 (Priority: P1) 🎯 MVP

**Goal**: 运维可以在顶条选择日期，4 个统计项按 `biz_date` 过滤刷新

**Independent Test**: 顶条出现 DatePicker，默认今天；切换日期后统计数字变化；SLA 风险不变

### Implementation for User Story 1

- [x] T003 [US1] 修改 `summary()` 方法签名，新增 `bizDate` 参数；`instances()` 内部分流：`bizDate` 非空时调用新 Repository 方法，空时走原逻辑 in `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java`
- [x] T004 [US1] `/summary` 端点新增 `@RequestParam(required=false) String bizDate` 参数，传入 `opsService.summary()` in `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java`
- [x] T005 [US1] 后端编译验证：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile`（mvnd+JDK25 exit=0 2026-07-03）
- [x] T006 [US1] 新增 `bizDate` state（默认今天 `yyyy-MM-dd`）+ DatePicker 组件 + URL 拼接 `bizDate` 参数 in `frontend/components/workspace/views/ops/top-strip.tsx`
- [x] T007 [US1] 前端 typecheck：`cd frontend && pnpm typecheck`（exit=0 2026-07-03）
- [x] T008 [US1] 浏览器验证：打开运维中心，切换日期，确认 4 个统计数字变化、SLA 风险不变（Playwright 2026-07-03：DatePicker bizDate 联动 today→yesterday ✅、日历选 2026-06-09 total 0→6 ✅、SLA 风险始终全局无 bizDate ✅、console 0 error ✅）

**Checkpoint**: US1 功能完整可独立验证

---

## Phase 4: User Story 2 - SLA 风险不受日期筛选影响 (Priority: P2)

**Goal**: 确认 SLA 风险在日期切换时保持不变

**Independent Test**: 切换日期后 SLA 风险数字不变

### Implementation for User Story 2

- [x] T009 [US2] 验证 `GET /api/ops/eta-summary` 端点未改动（不接受 `bizDate` 参数），前端调用未传 `bizDate` in `frontend/components/workspace/views/ops/top-strip.tsx`（确认代码无需改动）

**Checkpoint**: US2 验证通过——SLA 风险始终全局视角

---

## Phase 5: User Story 3 - 标签语义与日期筛选一致 (Priority: P3)

**Goal**: "今日总数" → "总数"，消除与 DatePicker 的语义冲突

**Independent Test**: 标签显示"总数" / "Total"

### Implementation for User Story 3

- [x] T010 [P] [US3] 修改 `topTotal` 值：中文 "今日总数"→"总数" in `frontend/messages/zh-CN.json`
- [x] T011 [P] [US3] 修改 `topTotal` 值：英文 "Today Total"→"Total" in `frontend/messages/en-US.json`

**Checkpoint**: 两 bundle 同步，标签无"今日"

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 最终验证与收尾

- [x] T012 后端编译 + 测试：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误（mvnd exit=0 2026-07-03）
- [x] T013 前端 typecheck：`cd frontend && pnpm typecheck` 零错误（exit=0 2026-07-03）
- [x] T014 运行 quickstart.md 全部验证场景（API curl × 3 + 浏览器 × 2 + H2）—— 全部通过 2026-07-03：API curl×3 ✅ + H2(OpsProjectIsolationTest 12 绿) ✅ + 浏览器×2(DatePicker 联动+total 0→6+SLA 不变 / 标签"总数") ✅
- [x] T015 如有测试新增，运行 `cd backend && ./mvnw -pl dataweave-api test -Dtest="OpsProjectIsolationTest"` 确认存量测试通过（Tests run:12 Failures:0 2026-07-03）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 立即执行
- **Foundational (Phase 2)**: T002 阻塞 US1
- **US1 (Phase 3)**: T003→T004→T005（后端串行）；T006→T007→T008（前端串行）；前后端可并行
- **US2 (Phase 4)**: T009 纯验证，依赖 US1 完成
- **US3 (Phase 5)**: T010 ∥ T011，不依赖其他 phase
- **Polish (Phase 6)**: 依赖所有 phase 完成

### User Story Dependencies

- **US1 (P1)**: 依赖 Phase 2 Foundational
- **US2 (P2)**: 依赖 US1 完成（验证 SLA 不变需要日期筛选先能跑）
- **US3 (P3)**: 无依赖，可与 US1 并行

### Within User Story 1

- 后端 T003→T004（Service→Controller 签名一致性）
- T005 编译验证依赖 T003+T004
- 前端 T006 独立于后端（可并行）
- T008 浏览器验证依赖后端跑起来 + 前端改完

### Parallel Opportunities

- T003/T004 与 T006 可并行（后端 + 前端独立改）
- T010 ∥ T011（两个 i18n 文件独立）
- US3 整个 Phase 5 可与 US1 并行执行

---

## Parallel Example: US1 后端 + 前端 + US3 并行

```bash
# 并行启动这三个任务组:
# Task A: "T003 + T004 后端 Service/Controller in OpsService.java + OpsController.java"
# Task B: "T006 前端 DatePicker + bizDate state in top-strip.tsx"
# Task C: "T010 + T011 i18n labels in zh-CN.json + en-US.json"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 Setup 确认分支
2. T002 Foundational Repository 方法
3. T003→T004→T005 后端链路（Service + Controller + 编译）
4. T006→T007→T008 前端链路（DatePicker + typecheck + 浏览器）
5. **STOP and VALIDATE**: 日期筛选功能完整可用

### Incremental Delivery

1. US1 完成 → 核心功能可用 (MVP!)
2. US2 验证 → 边界确认 SLA 不受影响
3. US3 完成 → 标签语义一致
4. Polish → 全链路编译/typecheck/quickstart 验证

---

## Notes

- 本 feature **不改 schema、不升 schema_version**——所有改动是读路径扩展
- `eta-summary` 端点不改——US2 是纯验证任务
- 后端 API 向后兼容：`bizDate` 可选，不传=全量
- 前端 DatePicker 复用已有组件 `frontend/components/ui/date-picker.tsx`，零新建
- SLA 风险 stat 的 `useLiveData` 调用不传 `bizDate`
