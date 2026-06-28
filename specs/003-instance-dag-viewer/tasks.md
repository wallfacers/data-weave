# Tasks: 运营中心实例列表切换与 DAG 查看

**Input**: Design documents from `specs/003-instance-dag-viewer/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: 后端集成测试 + 前端浏览器验证门（CLAUDE.md 要求新功能必须有测试）
**Organization**: 按 User Story 分组，每个 Story 可独立实现和测试

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无依赖）
- **[Story]**: 所属 User Story（US1/US2/US3）
- 描述中包含具体文件路径

---

## Phase 1: Setup

**Purpose**: 确认项目就绪状态，无需创建新项目结构

- [x] T001 确认 dev 环境就绪：`docker compose up -d`（PostgreSQL + Redis）、`cd backend && ./dev-install.sh` 编译通过、`cd frontend && pnpm typecheck` 零错误

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 User Story 共享的类型定义和接口声明，**必须先完成才能开始任何 Story**

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T002 [P] 新增后端 DTO records 到 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsContracts.java`：WorkflowInstanceRow、WorkflowInstanceQuery、InstanceDagNode、InstanceDagEdge、InstanceDagView、ResolvedCodeView、ResolvedConfigView（参考 data-model.md 字段定义）
- [x] T003 [P] 新增前端类型定义到 `frontend/lib/types.ts`：WorkflowInstanceRow、WorkflowInstanceQuery、InstanceDagNode、InstanceDagEdge、InstanceDagView、ResolvedCodeView、ResolvedConfigView（与后端 DTO 字段对齐）
- [ ] T004 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridge.java` 接口中新增方法声明：queryWorkflowInstances、getInstanceDag、getResolvedCode、getResolvedConfig
- [x] T005 编译验证：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误

**Checkpoint**: 共享类型层就绪 — User Story 实现可以开始

---

## Phase 3: User Story 1 - 实例列表视图切换 (Priority: P1) 🎯 MVP

**Goal**: 运营中心"实例"页签增加"任务实例"/"任务流实例"双视图切换，任务流实例列表支持筛选和分页

**Independent Test**: 打开 `http://localhost:4000/?open=ops`，点击"任务流实例"切换按钮，列表展示任务流实例数据，各列信息完整；切换回"任务实例"恢复原列表

### Implementation for User Story 1

#### Backend

- [x] T006 [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 中新增 `queryWorkflowInstances(WorkflowInstanceQuery q)` 方法，使用 JDBC 动态 SQL + correlated subquery 获取 workflow_def.name，支持 state/triggerType/bizDate 范围等筛选 + 分页，CASE-based ORDER BY（FAILED/STOPPED > RUNNING > others），须 H2 + PG 双兼容
- [x] T007 [P] [US1] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeRealImpl.java` 中实现 `queryWorkflowInstances`，委托给 OpsService
- [x] T008 [P] [US1] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeStub.java` 中新增 `queryWorkflowInstances` 空实现（返回空 Page）
- [x] T009 [US1] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 中新增 `GET /api/ops/workflow-instances` 端点，调用 bridge.queryWorkflowInstances，返回统一格式 `{success, data: {items, total, page, size}}`
- [x] T010 [US1] 后端编译验证：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误

#### Frontend

- [x] T011 [P] [US1] 新建 `frontend/components/workspace/views/ops/workflow-instances-panel.tsx`：使用现有 DataTable 组件展示任务流实例列表，列包含 workflowName/triggerType/bizDate/state/totalTasks/completedTasks/failedTasks/startedAt/durationMs，支持状态/触发类型/业务日期筛选，点击行触发 onRowClick 回调
- [x] T012 [US1] 修改 `frontend/components/workspace/views/ops-view.tsx`：在"实例"页签内增加"任务实例 | 任务流实例"切换按钮组，默认选中"任务实例"（保持向后兼容），切换到"任务流实例"时渲染 WorkflowInstancesPanel
- [x] T013 [US1] 前端类型检查：`cd frontend && pnpm typecheck` 零错误

**Checkpoint**: 此时 US1 应独立可用 — 任务流实例列表可查看、筛选、翻页

---

## Phase 4: User Story 2 - 实例级别 DAG 图与节点状态 (Priority: P2)

**Goal**: 从任一列表点击行以全屏弹窗展示实例 DAG 图，节点叠加 10 种运行时状态视觉区分，支持缩放拖拽，支持 SSE 实时状态更新

**Independent Test**: 从任务流实例列表点击一行 → 全屏 Dialog 展示 DAG 图，节点显示运行时状态颜色，悬停显示详情浮层，缩放拖拽正常；从任务实例列表点击一行 → DAG 图高亮该节点

### Implementation for User Story 2

#### Backend

- [x] T014 [US2] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 中新增 `getInstanceDag(UUID workflowInstanceId)` 方法：从 WorkflowInstance 获取 workflowVersionNo → 查 workflow_def_version.dag_snapshot_json 获取历史拓扑 → 查 task_instance 按 workflowInstanceId 获取运行时状态 → 合并为 InstanceDagView（含节点状态、位置、边）
- [x] T015 [P] [US2] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeRealImpl.java` 中实现 `getInstanceDag`，委托给 OpsService
- [x] T016 [P] [US2] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeStub.java` 中新增 `getInstanceDag` 空实现（返回 null / 404）
- [x] T017 [US2] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 中新增 `GET /api/ops/workflow-instances/{id}/dag` 端点（id 不存在或 dag_snapshot_json 为空返回 404）
- [x] T018 [US2] 后端编译验证：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误

#### Frontend

- [x] T019 [P] [US2] 新建 `frontend/lib/hooks/use-instance-dag.ts`：封装 `GET /api/ops/workflow-instances/{id}/dag` fetch + SSE 订阅 `GET /api/ops/workflow-instances/{id}/events/stream`（使用 SSE_BASE 直连），返回 {dag, loading, error}，SSE 推送时自动合并节点状态更新
- [x] T020 [P] [US2] 修改 `frontend/components/workspace/dag-renderer.tsx`：支持 instanceState 属性覆盖节点颜色（10 种状态映射：FAILED/STOPPED=红, RUNNING=蓝+动画, SUCCESS=绿, WAITING/DISPATCHED=黄, SKIPPED=灰, PREEMPTED=橙, PAUSED=紫, NOT_RUN=浅灰），支持 highlightNodeKey 高亮指定节点
- [x] T021 [US2] 新建 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx`：全屏 Dialog 封装（参考 dag-viewer-dialog.tsx 交互模式），内嵌 DagRenderer + useInstanceDag hook，从列表点击行传入 workflowInstanceId（或 taskInstanceId→所属 workflowInstanceId+高亮），Dialog 关闭时清理 SSE 连接
- [x] T022 [US2] 修改 `frontend/components/workspace/views/ops/ops-view.tsx`：任务流实例列表行点击 → 打开 InstanceDagDialog(workflowInstanceId)；任务实例列表行点击 → 若有关联 workflowInstanceId 则打开 InstanceDagDialog(workflowInstanceId, highlightTaskInstanceId)，否则进入 US3 独立详情流程
- [x] T023 [US2] 前端类型检查：`cd frontend && pnpm typecheck` 零错误

**Checkpoint**: 此时 US1 + US2 应同时可用 — 列表切换 + DAG 弹窗 + 节点状态可视化

---

## Phase 5: User Story 3 - 实例实际代码与配置查看 (Priority: P3)

**Goal**: DAG 图中点击节点，弹窗内右侧展开详情面板，包含"实际代码"和"实际配置"两个页签，展示参数替换后的真实执行内容和配置

**Independent Test**: DAG 图中点击任意 SHELL/SQL 节点 → 右侧面板出现"实际代码"+"实际配置"标签页，代码中 `${...}` 被替换为实际值，未解析占位符标注"未解析"；切换节点面板内容更新

### Implementation for User Story 3

#### Backend

- [x] T024 [US3] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 中新增 `resolveActualCode(UUID taskInstanceId)` 方法：查 task_instance 获取 bizDate/contentOverride/paramsOverride/taskVersionNo → 按优先级（contentOverride > taskDefVersion.content > taskDef.content）获取模板 → 调用 `ScheduleParamResolver.resolve(template, bizDate, paramsJson, builtInContext)` → 返回 ResolvedCodeView（含 rawContent/resolvedContent/unresolvedPlaceholders/runMode/isOverride/taskType）
- [x] T025 [US3] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/OpsService.java` 中新增 `resolveActualConfig(UUID taskInstanceId)` 方法：查 task_instance 获取配置 → paramsJson 走 ScheduleParamResolver 参数替换 → 返回 ResolvedConfigView（含 taskType/timeoutSeconds/retryStrategy/resourceLimit/rawParamsJson/resolvedParamsJson/runMode/isOverride），TEST 模式追加 originalParamsJson/originalTimeoutSeconds
- [x] T026 [P] [US3] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeRealImpl.java` 中实现 `getResolvedCode`、`getResolvedConfig`，委托给 OpsService
- [x] T027 [P] [US3] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/application/DataOpsBridgeStub.java` 中新增 `getResolvedCode`、`getResolvedConfig` 空实现
- [x] T028 [US3] 在 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/OpsController.java` 中新增 `GET /api/ops/task-instances/{id}/resolved-code` 和 `GET /api/ops/task-instances/{id}/resolved-config` 端点（id 不存在返回 404，无文本内容的纯配置驱动任务返回 400）
- [x] T029 [US3] 后端编译验证：`cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误

#### Frontend

- [x] T030 [US3] 新建 `frontend/components/workspace/views/ops/instance-detail-side-panel.tsx`：DAG 弹窗内的侧边面板，包含"实际代码"和"实际配置"两个子页签（Tab），点击 DAG 节点时 fetch resolved-code 和 resolved-config 端点加载内容；代码页签使用代码高亮展示脚本/SQL，"未解析"占位符带视觉提示；测试运行实例标注"测试运行"标识；切换节点自动切换内容
- [x] T031 [US3] 修改 `frontend/components/workspace/views/ops/instance-dag-dialog.tsx`：集成 InstanceDetailSidePanel（点击节点 → DAG 图左移 → 右侧展开面板），点击其他节点切换面板内容，面板关闭按钮收回
- [x] T032 [US3] 前端类型检查：`cd frontend && pnpm typecheck` 零错误

**Checkpoint**: 此时 US1 + US2 + US3 全部可用 — 完整流程：列表切换 → DAG 弹窗 → 节点点击 → 代码/配置查看

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 边缘情况处理、测试验证、整体质量保障

### Backend Tests

- [x] T033 [P] 新建后端集成测试 `backend/dataweave-master/src/test/java/com/dataweave/master/application/OpsServiceWorkflowInstanceTest.java`：覆盖 queryWorkflowInstances（空结果/多筛选组合/分页/H2+PG）、getInstanceDag（正常/404/空 DAG snapshot）、resolveActualCode（NORMAL/TEST/嵌套参数未解析）、resolveActualConfig（正常/TEST覆盖）
- [ ] T034 [P] 新建后端 API 测试 `backend/dataweave-api/src/test/java/com/dataweave/api/interfaces/OpsControllerWorkflowTest.java`：使用 WebTestClient + JwtTestSupport 测试全部 4 个新端点（200 正常返回 + 404 边界 + 400 边界 + 分页）

### Edge Case Handling

- [ ] T035 处理边缘情况：DAG 全部冻结/skip → "该实例无活跃任务节点"空状态；独立 MANUAL/TEST 任务实例（无 workflowInstanceId）→ 不显示 DAG 直接展示详情；已删除任务流 → 名称显示"已删除的任务流"；>100 节点 → 搜索/过滤节点功能
- [x] T036 处理切换竞态：快速连续切换任务实例/任务流实例视图时，取消前一个未完成的 fetch 请求（AbortController）

### Verification

- [x] T037 浏览器验证门：启动前后端 → 打开 `http://localhost:4000/?open=ops` → 验证：① 实例页签切换按钮存在且可点击 ② 任务流实例列表加载、筛选、翻页正常 ③ 点击行 DAG 弹窗打开且节点状态颜色正确 ④ 点击 DAG 节点侧面板展开、代码/配置加载、参数替换正确 ⑤ 无 console 错误
- [x] T038 运行 quickstart.md 全部验证步骤，确保 H2 和 PG 两种 profile 均通过

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖 — 立即开始
- **Foundational (Phase 2)**: 依赖 Setup — **阻塞所有 User Story**
- **User Story 1 (Phase 3)**: 依赖 Foundational 完成
- **User Story 2 (Phase 4)**: 依赖 Foundational 完成 + US1 前端列表入口（T011/T012）供 DAG Dialog 挂载点击回调
- **User Story 3 (Phase 5)**: 依赖 Foundational 完成 + US2 的 InstanceDagDialog（T021）供侧面板集成
- **Polish (Phase 6)**: 依赖所有 User Story 完成

### User Story Dependencies

- **US1 (P1)**: 可独立开始 — 仅依赖 Foundational
- **US2 (P2)**: 前端依赖 US1 的 WorkflowInstancesPanel（提供行点击入口），后端完全独立
- **US3 (P3)**: 前端依赖 US2 的 InstanceDagDialog（提供侧面板容器），后端完全独立

### Within Each User Story

- 后端：DTOs/Bridge 接口在 Foundational 已完成 → Service 方法 → Bridge 实现（[P] 可并行） → Controller 端点
- 前端：Types 在 Foundational 已完成 → Hook/组件（[P] 可并行） → 集成到父组件
- 每个 Story 完成后执行 typecheck / compile 验证

### Parallel Opportunities

- T002、T003 可在 Foundational 阶段并行（不同文件：后端 OpsContracts.java vs 前端 lib/types.ts）
- T007、T008 可在 US1 后端阶段并行（Bridge Real + Bridge Stub 不同文件）
- T011 可与 US1 后端任务并行（前后端不同仓库）
- T019、T020 可在 US2 前端阶段并行（hook vs dag-renderer 不同文件）
- T026、T027 可在 US3 后端阶段并行（Bridge Real + Bridge Stub）
- T033、T034 可在 Polish 阶段并行（不同测试文件）
- **跨 Story 并行**：US1/US2/US3 的后端任务可全并行；前端任务在 US1 列表入口完成后 US2/US3 可部分并行

---

## Parallel Example: User Story 1

```bash
# 后端 — 并行 Bridge 实现 + Stub（不同文件，无依赖）
Task: "在 DataOpsBridgeRealImpl.java 中实现 queryWorkflowInstances"
Task: "在 DataOpsBridgeStub.java 中新增 queryWorkflowInstances 空实现"

# 前后端 — 并行（前端 WorkflowInstancesPanel 不依赖后端完成，可先用 mock 数据开发）
Task: "后端 OpsService.queryWorkflowInstances() + OpsController 端点"
Task: "前端 WorkflowInstancesPanel 组件"
```

## Parallel Example: User Story 2

```bash
# 前端 — 并行 hook + dag-renderer 修改（不同文件）
Task: "新建 use-instance-dag.ts hook"
Task: "修改 dag-renderer.tsx 支持 instanceState 属性"
# 然后串行集成到 InstanceDagDialog
Task: "新建 InstanceDagDialog + 集成到 ops-view"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup — 确认环境就绪
2. 完成 Phase 2: Foundational — 共享 DTOs/Types（**关键阻塞点**）
3. 完成 Phase 3: User Story 1 — 前后端全部完成 + typecheck/compile
4. **STOP and VALIDATE**: 浏览器验证列表切换可用
5. 可交付/演示 MVP

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. Add US1 → 独立测试 → 交付 MVP（任务流实例列表）
3. Add US2 → 独立测试 → 交付 DAG 可视化增量
4. Add US3 → 独立测试 → 交付代码/配置查看增量
5. 每个 Story 增加价值，不破坏已有功能

### Parallel Team Strategy

多开发者场景：
1. 团队一起完成 Setup + Foundational
2. Foundational 完成后：
   - 开发者 A: US1（后端 + 前端列表）
   - 开发者 B: 先做 US2/US3 后端（独立于前端）
   - 开发者 C: US1 完成后接 US2 前端（DAG）
3. US2 前端完成后：
   - 开发者 C: US3 前端（侧面板）

---

## Notes

- [P] 标记 = 不同文件、无依赖，可并行执行
- [Story] 标签将任务映射到具体 User Story 以便追溯
- 每个 User Story 应可独立完成和测试
- 每次任务组完成后执行对应的 compile/typecheck 验证
- 后端所有 SQL 须 H2 + PostgreSQL 双兼容（不可用 `||` 拼接字符串）
- 前端 SSE 须使用 `SSE_BASE` 直连后端，不可走相对路径
- 所有新端点遵循现有 REST 模式：统一 `{code, data, message}` 响应格式
- 浏览器验证门为硬性要求 — `pnpm build` 通过不等于页面渲染正确
