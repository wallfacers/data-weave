# Tasks: 工作流实例运维操作

**Input**: Design documents from `/specs/006-workflow-instance-ops/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api-changes.md, quickstart.md

**Tests**: 本 feature 不要求独立测试任务。验收依赖 quickstart.md 中的 10 个验证场景 + 浏览器验证门。

**Organization**: 任务按用户故事分组，支持独立实现和测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无依赖）
- **[Story]**: 任务所属用户故事 (US1-US7)
- 每个任务包含精确文件路径

## Path Conventions

- **Backend**: `backend/dataweave-{api,master}/src/main/java/com/dataweave/{api,master}/`
- **Frontend**: `frontend/components/workspace/views/ops/`

---

## Phase 1: Setup

**Purpose**: 确认开发环境就绪，无需新建项目结构

- [ ] T001 确认后端编译通过: `cd backend && ./dev-install.sh` 无错误
- [ ] T002 [P] 确认前端类型检查通过: `cd frontend && pnpm typecheck` 无错误

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 后端 DTO 变更 + 审计日志改造，所有用户故事的前置依赖

**⚠️ CRITICAL**: 任何用户故事实现前必须完成此阶段

- [ ] T003 [P] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsContracts.java` 的 `WorkflowInstanceRow` 和 `InstanceRow` record 中添加 `env` 字段
- [ ] T004 [P] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 的 SQL 查询 SELECT 列表中添加 `wi.env`，确保 `queryWorkflowInstances()` 和 `queryInstances()` 返回 env 字段
- [ ] T005 [P] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 的 `workflowInstanceDetail()` 方法返回的 DTO 中添加 `env` 字段
- [ ] T006 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 的所有写操作方法中添加审计日志写入（直接调用 `AgentActionRepository.save()`），确保绕过闸门的操作仍有审计记录
- [ ] T007 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 的所有写操作端点中移除 `GatedActionService` 调用，改为直接调用 `OpsService`/`RecoveryService` 方法（实现 FR-011 直接执行）

**Checkpoint**: 后端编译通过，env 字段在 API 响应中可见，写操作绕过闸门但保留审计日志

---

## Phase 3: User Story 1 - 查看实例详情与状态 (Priority: P1) 🎯 MVP

**Goal**: 用户可在工作流实例列表和详情中看到完整字段，包括 env 环境标识

**Independent Test**: 打开任意工作流实例详情页，能看到 env 字段且值正确

### Implementation for User Story 1

- [ ] T008 [US1] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 的 DataTable 列定义中添加 `env` 列，使用 Badge 组件区分 PROD（蓝色）/ DEV（灰色）
- [ ] T009 [US1] 在 `frontend/components/workspace/views/ops/periodic-instances-panel.tsx` 的 DataTable 列定义中添加 `env` 列（透传自所属 workflow_instance）
- [ ] T010 [US1] 在 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` 的详情区域展示 `env` 字段

**Checkpoint**: 工作流实例列表、任务实例列表、DAG 弹窗中均可见 env 字段

---

## Phase 4: User Story 2 - 停止运行中的实例 (Priority: P1)

**Goal**: 用户可停止 RUNNING 状态的工作流实例，所有非终态子任务变为 STOPPED

**Independent Test**: 对 RUNNING 实例执行停止，验证实例和子任务变为 STOPPED

### Implementation for User Story 2

- [ ] T011 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 的 `killWorkflow()`、`pauseWorkflow()`、`resumeWorkflow()` 方法开头添加 `env=DEV` 前置校验：DEV 实例仅允许 killWorkflow，其他操作抛出 `BizException("workflow.dev_limited")`
- [ ] T012 [US2] 在 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` 中根据实例 `env` 字段条件渲染操作按钮：DEV 实例仅显示"停止"按钮
- [ ] T013 [US2] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 的行操作区域添加"停止"按钮（终态实例禁用），调用 `POST /api/ops/instances/{id}/kill`

**Checkpoint**: RUNNING 实例可停止，终态实例停止按钮禁用，DEV 实例仅有停止操作

---

## Phase 5: User Story 3 - 重跑失败/已停止的实例 (Priority: P1)

**Goal**: 用户可对终态实例执行"重跑全部"或"从失败点恢复"

**Independent Test**: 对 FAILED 实例执行重跑全部和恢复，验证状态转换正确

### Implementation for User Story 3

- [ ] T014 [US3] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/RecoveryService.java` 的 `rerunAll()` 方法开头添加状态守卫：仅允许终态（SUCCESS/FAILED/STOPPED）实例重跑，非终态返回 `BizException("workflow.not_terminal")`
- [ ] T015 [US3] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/RecoveryService.java` 的 `resetNodes()` SQL WHERE 子句中添加 `AND ti.state <> 'SKIPPED'`，确保冻结节点在重跑时保持 SKIPPED 状态
- [ ] T016 [US3] 在 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` 中添加"重跑全部"和"从失败点恢复"两个按钮：FAILED 实例显示两个按钮，STOPPED/SUCCESS 实例仅显示"重跑全部"，RUNNING 实例不显示
- [ ] T017 [US3] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 的行操作区域添加"重跑全部"按钮（仅终态实例可用），调用 `POST /api/ops/instances/{id}/rerun`
- [ ] T018 [US3] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 的行操作区域添加"从失败点恢复"按钮（仅 FAILED 实例可用），调用 `POST /api/ops/instances/{id}/recover`

**Checkpoint**: 终态实例可重跑/恢复，非终态被拒绝，冻结节点保持 SKIPPED

---

## Phase 6: User Story 4 - 强制置成功单个任务 (Priority: P2)

**Goal**: 用户可对 FAILED/STOPPED/RUNNING/PREEMPTED 任务节点执行"置成功"

**Independent Test**: 对 FAILED 节点执行置成功，验证节点变为 SUCCESS 且下游被唤醒

### Implementation for User Story 4

- [ ] T019 [US4] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 中新增 `POST /api/ops/task-instances/{id}/set-success` 端点，直接调用 `OpsService.setSuccess(id)`，添加 DEV 环境限制（`BizException("task.dev_limited")`）
- [ ] T020 [US4] 在 `frontend/components/workspace/views/ops/instance-detail-side-panel.tsx` 中添加"置成功"按钮：根据任务节点状态条件渲染（仅 FAILED/STOPPED/RUNNING/PREEMPTED 可用），DEV 实例不显示

**Checkpoint**: 单任务置成功端点可用，状态校验和 DEV 限制生效

---

## Phase 7: User Story 5 - 暂停/恢复工作流实例 (Priority: P2)

**Goal**: 用户可暂停 RUNNING 实例，恢复 PAUSED 实例

**Independent Test**: 暂停 RUNNING 实例验证状态变化，恢复 PAUSED 实例验证继续调度

### Implementation for User Story 5

- [ ] T021 [US5] 在 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx` 中添加"暂停"/"恢复"按钮：RUNNING 实例显示暂停，PAUSED 实例显示恢复，DEV 实例不显示
- [ ] T022 [US5] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 的行操作区域添加"暂停"/"恢复"按钮，调用 `POST /api/ops/instances/{id}/pause` 和 `POST /api/ops/instances/{id}/resume`

**Checkpoint**: 暂停/恢复功能可用，操作后 DAG 节点状态实时更新（SSE 推送）

---

## Phase 8: User Story 6 - 单任务节点操作 (Priority: P3)

**Goal**: 用户可在 DAG 弹窗中对单个任务节点执行停止/重跑/暂停/恢复

**Independent Test**: 对 DAG 中 DISPATCHED 节点执行停止，验证仅该节点变为 STOPPED

### Implementation for User Story 6

- [ ] T023 [US6] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 中新增 `POST /api/ops/task-instances/{id}/rerun` 端点，直接调用 `OpsService.rerunInstance(id)`，添加 DEV 限制
- [ ] T024 [US6] 在 `frontend/components/workspace/views/ops/instance-detail-side-panel.tsx` 中为任务节点详情面板添加完整操作按钮组（停止/重跑/暂停/恢复/置成功），根据节点当前状态动态决定哪些按钮可用
- [ ] T025 [US6] 操作按钮状态矩阵：创建共享工具函数 `getAvailableTaskActions(taskState, env)` 返回可用操作列表，所有操作入口复用此函数

**Checkpoint**: DAG 节点面板中操作按钮完整可用，状态矩阵正确

---

## Phase 9: User Story 7 - 批量操作 (Priority: P3)

**Goal**: 用户可在实例列表中多选并批量执行停止/重跑/置成功，上限 100 个

**Independent Test**: 选中 3 个 FAILED 实例执行批量重跑，验证全部进入 RUNNING

### Implementation for User Story 7

- [ ] T026 [US7] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 的 `buildBatchActionRequest()` 方法中添加 `ids.length > 100` 校验，超过上限返回 `BizException("batch.too_many")`
- [ ] T027 [US7] 在 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx` 中添加 `selectable` + `bulkActions` props，复用 `periodic-instances-panel.tsx` 的批量操作模式（批量停止/重跑/置成功按钮 + 三路结果处理）
- [ ] T028 [US7] 在 `frontend/components/workspace/views/ops/periodic-instances-panel.tsx` 的批量操作栏中添加选中上限提示：`ids.length > 100` 时禁用操作按钮并显示"最多选中 100 个实例"提示文字

**Checkpoint**: 两个实例面板均支持批量操作，100 上限前后端双重校验

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: 验证、i18n、错误处理完善

- [ ] T029 [P] 为新增后端错误码（`workflow.not_terminal`、`workflow.dev_limited`、`task.dev_limited`、`batch.too_many`、`task.not_found`、`task.set_success_invalid_state`）添加中英文双语消息到 i18n 资源文件
- [ ] T030 [P] 在前端 `messages/zh-CN.json` 和 `messages/en-US.json` 中添加运维操作相关 UI 文案（按钮标签、确认对话框、错误提示）
- [ ] T031 运行 `cd backend && ./mvnw -q -pl dataweave-api compile` 确认后端编译零错误
- [ ] T032 运行 `cd frontend && pnpm typecheck` 确认前端类型检查零错误
- [ ] T033 执行 quickstart.md 中的 VS-1 到 VS-10 验证场景（浏览器验证门），确认所有操作可用且无 console error
- [ ] T034 [P] 运行 `cd frontend && pnpm build` 确认生产构建通过

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖 — 立即开始
- **Foundational (Phase 2)**: 依赖 Setup 完成 — 阻塞所有用户故事
- **User Stories (Phase 3-9)**: 全部依赖 Foundational 完成
  - US1-US3 (P1) 按顺序执行（后续故事复用前面的 UI 组件）
  - US4-US5 (P2) 可在 US3 完成后并行
  - US6-US7 (P3) 可在 US5 完成后并行
- **Polish (Phase 10)**: 依赖所有用户故事完成

### User Story Dependencies

- **US1 (P1)**: 仅依赖 Foundational — 无其他故事依赖
- **US2 (P1)**: 依赖 US1（复用 env 字段展示）— DEV 限制影响后续所有操作
- **US3 (P1)**: 依赖 US2（复用操作按钮框架）
- **US4 (P2)**: 依赖 US2（复用 DEV 限制逻辑）
- **US5 (P2)**: 依赖 US2（复用操作按钮框架）
- **US6 (P3)**: 依赖 US4（依赖单任务端点 T019）
- **US7 (P3)**: 依赖 US3+US4（依赖批量端点修改 + 状态矩阵）

### Within Each User Story

- 后端任务先于前端任务
- 同一故事内标记 [P] 的任务可并行
- 故事完成后 checkpoint 验证

### Parallel Opportunities

- Phase 2: T003, T004, T005 可并行（不同 DTO/查询）
- US4 和 US5 可在 US3 完成后并行
- US6 和 US7 可在各自依赖满足后并行
- Phase 10: T029, T030, T034 可并行

---

## Parallel Example: Phase 2 Foundational

```bash
# 并行执行 DTO 和查询变更:
Task: "T003 在 OpsContracts.java 中添加 env 字段"
Task: "T004 在 OpsService.java SQL 查询中添加 wi.env"
Task: "T005 在 workflowInstanceDetail() 中添加 env 字段"

# 然后串行（有依赖）:
Task: "T006 添加审计日志写入"
Task: "T007 移除 GatedActionService 调用"
```

---

## Implementation Strategy

### MVP First (US1 + US2 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL)
3. Complete Phase 3: US1 — 实例详情含 env 字段
4. Complete Phase 4: US2 — 停止操作 + DEV 限制
5. **STOP and VALIDATE**: 浏览器验证 US1+US2
6. 已可交付基础运维能力

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. +US1 → 实例详情可见 → 浏览器验证
3. +US2 → 停止操作可用 → 浏览器验证
4. +US3 → 重跑/恢复可用 → 浏览器验证 (核心运维闭环)
5. +US4 → 置成功 → 浏览器验证
6. +US5 → 暂停/恢复 → 浏览器验证
7. +US6 → 单节点操作 → 浏览器验证
8. +US7 → 批量操作 → 浏览器验证
9. +Polish → i18n + 生产构建 → 发布就绪

### Suggested MVP Scope

**P1 故事 (US1+US2+US3)** 构成最小可交付运维闭环：查看实例状态 → 停止异常实例 → 重跑失败实例。这是运维人员日常最高频的三个操作。

---

## Notes

- [P] 标记 = 不同文件、无依赖，可并行
- [Story] 标签映射任务到用户故事，便于追踪
- 每个 checkpoint 后可独立验证该故事
- 后端每次修改后运行 `./mvnw -q -pl <module> compile`
- 前端每次修改后运行 `pnpm typecheck`
- 浏览器验证门是硬性要求：涉及聊天/AG-UI/操作交互的修改必须浏览器实跑
- 操作权限依赖现有 JWT 认证，无需新增权限模型
