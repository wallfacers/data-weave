# Research: 工作流实例运维操作

**Date**: 2026-06-26

## 现有系统状况

### 后端已实现（绿色通道）

通过全面代码探索，确认以下端点与方法已完整实现：

| 类别 | 已实现 | 文件 |
|------|--------|------|
| 工作流实例查询 | `GET /api/ops/workflow-instances` (多条件+分页) | `OpsController.java:179` |
| 工作流实例详情 | `GET /api/ops/workflow-instances/{id}` (含任务节点) | `OpsController.java:278` |
| 实例 DAG | `GET /api/ops/workflow-instances/{id}/dag` | `OpsController.java:290` |
| SSE 事件流 | `GET /api/ops/workflow-instances/{id}/events/stream` | `OpsController.java:305` |
| 任务实例查询 | `GET /api/ops/instances` (多条件+分页) | `OpsController.java:99` |
| 实例级停止 | `POST /api/ops/instances/{id}/kill` → `OpsService.killWorkflow()` | `OpsController.java:327` |
| 实例级暂停 | `POST /api/ops/instances/{id}/pause` → `OpsService.pauseWorkflow()` | `OpsController.java:308` |
| 实例级恢复 | `POST /api/ops/instances/{id}/resume` → `OpsService.resumeWorkflow()` | `OpsController.java:317` |
| 实例级重跑 | `POST /api/ops/instances/{id}/rerun` → `RecoveryService.rerunAll()` | `OpsController.java:336` |
| 实例级恢复 | `POST /api/ops/instances/{id}/recover` → `RecoveryService.resume()` | `OpsController.java:345` |
| 单任务停止 | `POST /api/ops/task-instances/{id}/kill` → `OpsService.killTask()` | `OpsController.java:356` |
| 单任务暂停 | `POST /api/ops/task-instances/{id}/pause` → `OpsService.pauseTask()` | `OpsController.java:338` |
| 单任务恢复 | `POST /api/ops/task-instances/{id}/resume` → `OpsService.resumeTask()` | `OpsController.java:347` |
| 批量操作 | `POST /api/ops/instances/batch` (RERUN/KILL/SET_SUCCESS) | `OpsController.java:703` |
| 审计日志 | `GatedActionService` → `AgentAction` 表 | `GatedActionService.java` |

### 前端已实现

| 组件 | 文件 | 能力 |
|------|------|------|
| Ops 运维中心 | `ops-view.tsx` | 4 标签页 (周期工作流/手动工作流/实例/回填) |
| 工作流实例面板 | `workflow-instances-panel.tsx` | DataTable 列表, DAG 弹窗 |
| 任务实例面板 | `periodic-instances-panel.tsx` | DataTable 列表, 多选+批量操作 |
| 实例 DAG 弹窗 | `instance-dag-dialog.tsx` | 运行时 DAG 可视化 |
| 实例详情侧面板 | `instance-detail-side-panel.tsx` | 代码/配置查看 |
| 批量操作 UI | `periodic-instances-panel.tsx:352` | 批量重跑/置成功/停止 |
| Ops 告警卡片 | `ops-alert-card.tsx` | Agent 建议操作按钮 |

---

## 差距分析

### 后端缺口（5 项）

#### G1: `RecoveryService.rerunAll()` 缺少状态守卫
- **现状**: `rerunAll()` 对任何 `deleted=0` 的实例都可执行，包括 RUNNING 状态
- **规格要求**: FR-003 — 仅允许对终态（FAILED/STOPPED/SUCCESS）实例重跑
- **Decision**: 在 `rerunAll()` 开头添加状态检查，拒绝非终态实例
- **Rationale**: 防止误操作终止正在运行的实例；与 `rerunInstance()` (任务级) 的状态检查逻辑一致
- **实现**: `RecoveryService.java` 中添加 `if (!InstanceStates.isTerminal(currentState)) throw BizException`

#### G2: 缺少 DEV 环境操作限制
- **现状**: 所有实例不论 `env` 字段值均暴露全部操作
- **规格要求**: FR-013 — DEV 实例仅展示停止操作
- **Decision**: 在 `OpsService` 操作入口添加 `env=DEV` 的前置校验；前端对应过滤操作按钮
- **Rationale**: DEV 是画布试运行环境，生命周期短；失败直接重新触发更高效
- **实现**: 后端各写操作方法开头添加 `if ("DEV".equals(instance.env())) throw BizException("workflow.dev_limited")`；前端根据 `env` 字段条件渲染按钮

#### G3: 缺少 `env` 字段输出
- **现状**: `WorkflowInstanceRow` 和 `WorkflowInstanceDetail` DTO 未包含 `env` 字段
- **规格要求**: FR-001 — 展示环境(env)信息
- **Decision**: 在所有实例查询 DTO 和 API 响应中添加 `env` 字段
- **Rationale**: 用户需要区分 PROD/DEV 实例决定操作范围
- **实现**: 修改 `WorkflowInstanceRow`/`WorkflowInstanceDetail` record + SQL 查询 SELECT 列表

#### G4: 缺少单任务重跑/置成功 REST 端点
- **现状**: `OpsService.rerunInstance()` 和 `setSuccess()` 方法存在，但仅通过批量端点 (`/instances/batch`) 或闸门 (`GatedActionService`) 访问
- **规格要求**: FR-007 — 支持对单个任务节点执行停止、重跑、暂停、恢复（其中停止/暂停/恢复已有端点，重跑和置成功缺端点）
- **Decision**: 新增 `POST /api/ops/task-instances/{id}/rerun` 和 `POST /api/ops/task-instances/{id}/set-success` 端点
- **Rationale**: 单节点操作是 P3 用户故事的独立需求；不应强制用户通过批量端点执行单节点操作
- **实现**: 在 `OpsController` 中添加两个新端点，直接调用 `OpsService.rerunInstance(id)` / `OpsService.setSuccess(id)`

#### G5: 批量操作缺少 100 上限
- **现状**: `/api/ops/instances/batch` 接受任意大小的 ID 数组
- **规格要求**: FR-008 — 单次最多 100 个实例
- **Decision**: 在批量端点入口添加 ID 数量校验，超过 100 返回错误
- **Rationale**: 防止客户端或 API 调用方误传海量 ID 造成后端压力
- **实现**: `OpsController.buildBatchActionRequest()` 中 `if (ids.length > 100) throw BizException`

### 前端缺口（6 项）

#### G6: 工作流实例面板缺少批量操作
- **现状**: `workflow-instances-panel.tsx` 没有 `selectable` 和 `bulkActions`
- **规格要求**: US7 — 批量停止/重跑/置成功也适用于工作流实例
- **Decision**: 复用 `periodic-instances-panel.tsx` 的批量操作模式，添加到工作流实例面板
- **Rationale**: 保持 UI 一致性；后端 `/instances/batch` 支持工作流实例 ID
- **实现**: 为 `workflow-instances-panel.tsx` 添加 `selectable` + `bulkActions` props

#### G7: 工作流实例面板缺少 env 列展示
- **现状**: 工作流实例列表未显示环境列
- **规格要求**: FR-001 — 展示环境字段
- **Decision**: 添加 `env` 列，使用 Badge 区分 PROD/DEV
- **Rationale**: 用户需要快速判断实例环境
- **实现**: 在列定义中添加 env 列

#### G8: DAG 弹窗中缺少单节点操作按钮
- **现状**: `instance-dag-dialog.tsx` 展示 DAG 节点状态但无可点击的操作按钮
- **规格要求**: US6 — 单任务节点操作（停止/重跑/暂停/恢复）
- **Decision**: 在 DAG 节点详情侧面板上添加操作按钮，根据节点当前状态显示可用操作
- **Rationale**: DAG 视图是运维人员定位问题节点的自然入口
- **实现**: 在 `instance-detail-side-panel.tsx` 或 DAG 节点弹出层中添加条件操作按钮

#### G9: DEV 实例操作按钮过滤
- **现状**: 所有操作按钮对所有实例可见
- **规格要求**: FR-013 — DEV 实例仅展示停止
- **Decision**: 前端根据实例 `env` 字段条件渲染操作按钮
- **Rationale**: 双重保障（后端校验 + 前端过滤）
- **实现**: 在所有操作按钮组件中检查 `env === "DEV"` 条件

#### G10: 批量操作选中上限提示
- **现状**: 无选中数量限制提示
- **规格要求**: Edge Case — 超过 100 个时提示用户
- **Decision**: 批量操作栏在选中 >100 个时禁用操作按钮并显示提示文字
- **Rationale**: 前端即时反馈优于等待后端报错
- **实现**: 在 `bulkActions` render props 中检查 `ids.length > 100`

#### G11: 重跑工作流需提供"重跑全部"/"从失败点恢复"两种选项
- **现状**: 仅 `POST /api/ops/instances/{id}/rerun` 一个按钮，语义是重跑全部
- **规格要求**: US3 — 区分"重跑全部"和"从失败点恢复"
- **Decision**: 为 FAILED 状态的工作流实例提供两个操作按钮（或下拉选择）
- **Rationale**: "从失败点恢复"保留已完成节点的结果，节省计算资源
- **实现**: 在操作入口同时展示 `rerun` 和 `recover` 按钮，根据实例状态控制可见性

---

## 技术决策

### D1: 审批策略 — 操作绕过 PolicyEngine
- **Decision**: 运维操作（停止/重跑/置成功/暂停/恢复）绕过 `GatedActionService` 闸门，直接调用 `OpsService`/`RecoveryService` 方法
- **Rationale**: 规格 FR-011 明确要求"无需审批直接执行"；运维人员已通过 JWT 认证授权
- **Alternatives considered**:
  - 通过 GatedActionService 但配置 L0/L1 策略 → 增加不必要的间接层
  - 保留闸门但默认所有操作 L0 → 规格明确要求无审批，闸门多余

### D2: 审计日志保留
- **Decision**: 绕过闸门的直接操作仍需写入 `AgentAction` 审计表
- **Rationale**: FR-012 要求 100% 操作可追溯；不能因跳过闸门而丢失审计
- **实现**: 在 `OpsController` 中直接调用 `AgentActionRepository.save()` 或在 `OpsService` 方法内部记录

### D3: 重跑时冻结节点保护
- **Decision**: `RecoveryService.resetNodes()` 重跑时跳过 `state=SKIPPED` 的节点
- **Rationale**: 规格边界条件明确要求冻结节点保持 SKIPPED 状态；SKIPPED 是终态不应被重置
- **实现**: 在重置 SQL 的 WHERE 子句中添加 `AND state <> 'SKIPPED'`

### D4: env 字段来源确认
- **Decision**: `WorkflowInstance` 领域实体已包含 `env` 字段（`schema.sql:464` `env VARCHAR(8) DEFAULT 'PROD'`），只需在查询投影中透出
- **Rationale**: 无需新增数据库列；仅需修改 SQL SELECT 和 DTO record
- **已验证**: `WorkflowInstance.java` 包含 `env` 属性，`InstanceRow`/`WorkflowInstanceRow` 未包含

### D5: 单任务重跑/置成功端点设计
- **Decision**: 新增端点 `POST /api/ops/task-instances/{id}/rerun` 和 `POST /api/ops/task-instances/{id}/set-success`，直接调用 `OpsService.rerunInstance(id)` / `OpsService.setSuccess(id)`
- **Rationale**: 与现有单任务端点（`pause`/`resume`/`kill`）风格一致；复用已验证的领域服务方法
- **实现**: 在 `OpsController` 中添加两个 `@PostMapping` 方法，无需通过闸门
