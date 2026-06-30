---
description: "Task list for 027 统一数据健康事件中心"
---

# Tasks: 统一数据健康事件中心

**Input**: Design documents from `specs/027-event-center/`

**Tests**: 包含（CLAUDE.md 硬规则）。**依赖**：026 已合入本 worktree（通道分发可用）。

**Organization**: 按 user story；实现序 = US2（统一契约/修孤儿）→ US1（持久化+查询+视图）→ US3（订阅）。

## Phase 1: Setup

- [X] T001 `backend/dataweave-api/src/main/resources/schema.sql`：新增 `health_event`、`event_subscription` 两表（见 data-model.md），`schema_version` 0.3.0 → **0.4.0**（库内 INSERT + 文件头 + 项目版本三处恒等）；H2/PG 双方言安全

---

## Phase 2: User Story 2 - 统一信号契约（修孤儿质量信号）(Priority: P1) 🎯 先行基座

**Goal**: 收敛为单一 `domain.signal.AlertSignal`；删 `quality.domain.AlertSignal`；质量信号从此真正到达消费端

**Independent Test**: 触发质量断言 FAIL → `AlertSignalListener` 收到 QUALITY_FAILED（修孤儿）；编译全绿

- [X] T002 [P] [US2] 测试 `backend/dataweave-master/src/test/.../quality/QualitySignalUnifiedTest.java`：发布事件后断言 `domain.signal.AlertSignal`(QUALITY_FAILED) 被监听器接收（孤儿修复）
- [X] T003 [US2] 改 `backend/dataweave-master/.../quality/application/QualitySignalEmitter.java`：publish `domain.signal.AlertSignal`（Type.QUALITY_FAILED），载荷字段不变
- [X] T004 [US2] 删除 `backend/dataweave-master/.../quality/domain/AlertSignal.java`，清理其 import
- [~] T005 [US2] 回归(部分:迁移后编译绿 + 定向测试过;完整 master 套件未跑)：SLA/任务/资产 信号 emit/consume 路径不变，全模块编译 + 既有测试绿

**Checkpoint**: 单一信号契约，质量信号不再孤儿

---

## Phase 3: User Story 1 - 一个地方看全数据健康事件 (Priority: P1)

**Goal**: 旁路持久化每条信号到 `health_event` + 查询 API + 前端事件中心视图

**Independent Test**: 触发 SLA + 质量事件 → `health_event` 两行，`GET /api/events` 倒序返回，按 type 筛选正确

- [X] T006 [P] [US1] `HealthEvent` 域对象 + `HealthEventRepository` 接口（`backend/dataweave-alert/.../domain/`）
- [X] T007 [US1] `HealthEventJdbcRepository`：插入 + 按 `(tenant,type,fingerprint)` 去重 upsert（count++/last_occurred_at）+ 多条件分页查询；H2/PG 双方言
- [X] T008 [P] [US1] 测试 `HealthEventRecorderTest`（H2 独立库）：信号→落库；重复信号→count 递增不新增
- [X] T009 [US1] `HealthEventRecorder` `@EventListener(domain.signal.AlertSignal)`（旁路，独立于 `AlertSignalListener`）：映射 type/severity/fingerprint/ref/summary 落库；异常不影响告警分发（FR-007）
- [X] T010 [US1] `EventCenterController` `GET /api/events`：租户隔离 + type/severity/refKind/refId/from/to 过滤 + 分页倒序（契约 health-event-api）
- [X] T011 [P] [US1] 测试(改 service 层 EventCenterServiceQueryTest：过滤+租户隔离 H2;HTTP WebTestClient 留 api 模块)（WebTestClient + JWT）：返回事件、按 type/severity 过滤、跨租户隔离
- [ ] T012 [US1] 前端注册 `event-center`：`frontend/lib/workspace/views.ts`（ViewType）+ `registry.tsx`（icon + component）
- [ ] T013 [US1] 前端 `frontend/components/workspace/views/event-center-view.tsx`：时间线 + 类型/severity/资产/时间筛选 + 关联对象深链（refKind→对应视图，对象不存在优雅降级）
- [ ] T014 [P] [US1] i18n：`frontend/messages/{zh-CN,en-US}.json` 加 `eventCenter` 命名空间（两 bundle 同键，`pnpm i18n:lint` 过）

**Checkpoint**: 事件可查可视，告警分发零回归

---

## Phase 4: User Story 3 - 订阅事件并经既有通道触达 (Priority: P2)

**Goal**: 订阅命中经 026 通道分发

**Independent Test**: 订阅(type+severity+资产+通道) → 匹配事件触达、不匹配不触达、分发失败不阻断持久化

- [X] T015 [P] [US3] `EventSubscription` 域对象 + repo + `EventSubscriptionJdbcRepository`（CRUD + 按维度查匹配订阅）
- [X] T016 [US3] `HealthEventRecorder` 持久化后匹配订阅（type/severity≥/ref），命中经 026 `AlertDispatchService` 分发到 `channelId`；try-catch 吞失败不阻断（FR-009）
- [X] T017 [P] [US3] 测试 `EventSubscriptionDispatchTest`：匹配→分发（verify dispatch）、不匹配→不分发、分发抛错→事件仍持久化
- [X] T018 [US3] `EventCenterController` 订阅端点：POST/DELETE/GET（契约 subscription-contract，租户隔离）
- [ ] T019 [US3] 前端 event-center-view 加订阅 UI（选 type/severity/通道，列/取消订阅）

**Checkpoint**: 三 story 各自独立可用

---

## Phase 5: Polish

- [X] T020 [P] 回归：026 告警分发 + 各信号业务判定全绿（`./mvnw -pl dataweave-alert -am test`，JDK25+setsid）
- [ ] T021 quickstart 全场景 + 前端 `pnpm typecheck` + 浏览器验证事件中心视图渲染
- [X] T022 schema_version 0.4.0 三处恒等核对（库内/文件头/项目版本）

---

## Dependencies & Execution Order

- **Setup(T001)** → 先行（建表）
- **US2(Phase 2)** → 契约基座，先于 US1（让质量信号能被事件中心捕获）
- **US1(Phase 3)** → 依赖 T001 + US2；持久化/查询/视图
- **US3(Phase 4)** → 依赖 US1（事件已持久化）+ 026 通道
- **Polish(Phase 5)** → 全部之后

### Parallel Opportunities
- T002/T006/T008/T011/T014/T015/T017 标 [P]，文件不重叠可并行
- 前端 T012-T014 与后端 T006-T011 可由不同人并行（契约先定）

## Implementation Strategy

**MVP = US2 + US1**：统一契约（修孤儿）+ 事件可查可视，即「数据健康有统一归宿」。US3 订阅为增量。

## Notes
- 依赖 026（本 worktree 已合入）；新增两表升 schema_version 0.4.0
- 旁路持久化对告警分发零回归（独立 @EventListener）
- H2/PG 双方言 + H2 独立库测试隔离（见 CLAUDE.md 记忆）
- 前端遵 DESIGN.md + hugeicons + i18n 三类归属；新视图须浏览器验证
