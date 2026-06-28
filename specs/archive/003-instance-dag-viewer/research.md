# Research: 运营中心实例列表切换与 DAG 查看

**Feature**: 003-instance-dag-viewer
**Date**: 2026-06-26

## 1. Parameter Resolution Code Path

### Decision

复用现有的 `ScheduleParamResolver`（master 模块 `@Component`）进行参数替换。该组件已被 `POST /api/tasks/preview-params` 端点公开调用。

### Rationale

- `ScheduleParamResolver.resolve(content, bizDate, paramsJson, builtInContext)` 是执行时参数替换的唯一权威实现。
- 已有集成测试 (`SchedulingParameterIntegrationTest.previewEndpoint_resolvesPlaceholders()`) 确认其行为正确。
- SchedulerKernel 在 dispatcher 阶段已调用此方法，返回的 resolved content 放入 `DispatchCommand` 再发给 worker。实际执行的就是这个解析结果。
- 通过复用同一解析器，保证"实际代码"展示与真实执行完全一致。

### Alternatives Considered

1. **在 dispatch 时持久化 resolved content 到 task_instance** — 需要 schema 变更 + 修改 SchedulerKernel。过度工程化，v1 不需要。
2. **在前端做参数替换** — 需要重复实现解析逻辑，且无法获取运行时上下文（$jobid、$taskid 等内置变量）。不可行。

### Content/Prams Source Priority (matching SchedulerKernel.contentOf/paramsJsonOf)

1. `task_instance.content_override` / `params_override` (TEST 模式)
2. `task_def_version.content` / `params_json` (已发布版本快照)
3. `task_def.content` / `params_json` (草稿/当前版本)

### Built-in Context for Resolution

`ScheduleParamResolver.BuiltInContext(jobId, nodeId, taskInstanceId, today)`:
- `$jobid` → workflow instance UUID
- `$nodeid` → workflow node id
- `$taskid` → task instance UUID
- `$bizdate` → yyyyMMdd formatted bizDate
- `$bizmonth` → yyyyMM (cross-month rule)
- `$gmtdate` → today (for the view, use current date)

### Implementation Approach

在后端新增 `GET /api/ops/task-instances/{id}/resolved-code` 端点：
1. 查询 task_instance 获取 bizDate、workflowInstanceId、taskVersionNo、contentOverride、paramsOverride
2. 根据 contentOf/paramsJsonOf 优先级获取原始模板和参数
3. 调用 `ScheduleParamResolver.resolve(template, bizDate, paramsJson, builtInContext)`
4. 返回 `{resolvedContent, unresolvedPlaceholders}`

---

## 2. Workflow Instance List Query Pattern

### Decision

新增 `GET /api/ops/workflow-instances` 端点，完全复刻现有的 `GET /api/ops/instances` (task instance list) 的 JDBC 动态 SQL + 分页模式。

### Rationale

- 现有 `queryInstances()` 模式经过验证，H2 和 PostgreSQL 双兼容。
- 使用 correlated subquery 而非 JOIN 获取 workflow_def.name，与 task instance list 的 task_name 获取方式一致。
- CASE-based ORDER BY 提供直观的优先级排序（失败 > 运行中 > 其他）。

### SQL Structure

```sql
SELECT wi.id, wi.workflow_id, wi.trigger_type, wi.state, wi.biz_date,
       wi.total_tasks, wi.completed_tasks, wi.failed_tasks,
       wi.started_at, wi.finished_at, wi.priority,
       (SELECT wd.name FROM workflow_def wd WHERE wd.id=wi.workflow_id) AS workflow_name
FROM workflow_instance wi {dynamic WHERE wi.deleted=0 ...}
ORDER BY CASE
  WHEN wi.state IN ('FAILED','STOPPED') THEN 0
  WHEN wi.state IN ('RUNNING') THEN 1
  ELSE 2 END, wi.id DESC
LIMIT ? OFFSET ?
```

### Filters

| Filter | SQL | Type |
|--------|-----|------|
| state | `AND wi.state=?` | String |
| stateIn | `AND wi.state IN (?,?,?)` | String[] (CSV) |
| triggerType | `AND wi.trigger_type=?` | String |
| workflowId | `AND wi.workflow_id=?` | Long |
| bizDate | `AND wi.biz_date=?` | String |
| bizDateFrom | `AND wi.biz_date >= ?` | String |
| bizDateTo | `AND wi.biz_date <= ?` | String |
| startedAtFrom | `AND wi.started_at >= ?` | Timestamp |
| startedAtTo | `AND wi.started_at <= ?` | Timestamp |

### New DTOs Required

- `WorkflowInstanceRow(UUID id, Long workflowId, String workflowName, String state, String bizDate, Integer priority, String triggerType, Integer totalTasks, Integer completedTasks, Integer failedTasks, String startedAt, String finishedAt, Long durationMs)`
- `WorkflowInstanceQuery(...)` — 对应上述 filters + page/size

### Files to Modify

1. `OpsContracts.java` — 新增 DTO records
2. `OpsService.java` — 新增 `queryWorkflowInstances()` 方法
3. `DataOpsBridge.java` + `DataOpsBridgeRealImpl.java` + `DataOpsBridgeStub.java` — 新增 bridge 方法
4. `OpsController.java` — 新增 `GET /api/ops/workflow-instances` 端点
5. API 层 DTO (`dto/` 目录) — 新增对应的 API DTO

---

## 3. Instance DAG Endpoint

### Decision

新增 `GET /api/ops/workflow-instances/{id}/dag` 端点，返回 DAG 拓扑 + 每个节点的运行时状态。

### Rationale

- DAG 拓扑从 `WorkflowInstance.workflowVersionNo` 对应的 `workflow_def_version.dag_snapshot_json` 获取（实例真实执行时的拓扑）
- 运行时状态从 `task_instance` 表按 `workflowInstanceId` 查询，map 到 DAG 节点上
- 已有 `DagView` DTO（`frontend/lib/types.ts`）可作为 API 返回格式参考

### Response Structure

```json
{
  "workflowInstanceId": "uuid",
  "workflowName": "string",
  "workflowVersionNo": 3,
  "triggerType": "CRON",
  "state": "RUNNING",
  "bizDate": "20260626",
  "nodes": [
    {
      "nodeKey": "node_1",
      "taskName": "import_data",
      "taskId": 42,
      "taskInstanceId": "uuid",
      "state": "SUCCESS",
      "attempt": 0,
      "startedAt": "2026-06-26T09:00:00",
      "finishedAt": "2026-06-26T09:01:00",
      "durationMs": 60000,
      "posX": 100,
      "posY": 200,
      "nodeType": "TASK"
    }
  ],
  "edges": [
    {"fromNodeKey": "node_1", "toNodeKey": "node_2", "strength": "NORMAL"}
  ]
}
```

### Files to Modify

1. `OpsService.java` — 新增 `getInstanceDag(UUID workflowInstanceId)` 方法
2. `OpsController.java` — 新增 `GET /api/ops/workflow-instances/{id}/dag` 端点
3. `OpsContracts.java` — 新增 `InstanceDagView`, `InstanceDagNode` DTOs
4. API 层 DTO — 对应

---

## 4. Frontend Architecture

### Decision

新增 `InstanceDagDialog` 组件，基于现有 `DagViewerDialog` 模式扩展，增加运行时状态叠加和侧边面板。

### Component Reuse

| Existing Component | Reuse For |
|-------------------|-----------|
| `DagRenderer` | DAG 画布渲染（已有 readOnly 模式） |
| `TaskNode` | DAG 节点渲染（已有 runState overlay） |
| `dagViewToFlow()` | DAG 数据到 ReactFlow 格式转换 |
| `DagViewerDialog` | 弹窗交互模式参考 |

### New Components

| Component | Purpose |
|-----------|---------|
| `WorkflowInstancesPanel` | 任务流实例列表面板 |
| `InstanceDagDialog` | 实例 DAG 弹窗（封装 DagRenderer + SSE 订阅 + 侧面板） |
| `InstanceDetailSidePanel` | 侧边面板（实际代码/实际配置页签） |

### Data Flow

```
OpsView (toggle state: "task" | "workflow")
  ├─ PeriodicInstancesPanel (existing, task instance list)
  │   └─ click row → InstanceDagDialog(highlightNode=taskInstanceId)
  └─ WorkflowInstancesPanel (new, workflow instance list)
      └─ click row → InstanceDagDialog(workflowInstanceId)
          ├─ fetch GET /api/ops/workflow-instances/{id}/dag
          ├─ subscribe SSE /api/ops/workflow-instances/{id}/events/stream
          ├─ DagRenderer (readOnly, runState overlay)
          └─ InstanceDetailSidePanel
              ├─ Tab: 实际代码 → fetch GET /api/ops/task-instances/{id}/resolved-code
              └─ Tab: 实际配置 → fetch from same endpoint (extended)
```
