# API Contract: OpsService.tasks 迁移（v2 — 真树重核）

**Feature**: 011-weft-cleanup | **Date**: 2026-06-28 | **Spec**: FR-007 | **基准树**: `e568c38`

## 背景

spec FR-007：迁移前端 `log-panel.tsx` 使用的 deprecated `GET /api/ops/tasks`（列表端点），再删除。

## ⚠️ 端点区分（避免误删）

`/api/ops/tasks` 下有**两个端点**，只删列表端点：

| 端点 | OpsController | 状态 | 处置 |
|------|-----------|------|------|
| `GET /api/ops/tasks`（**列表**） | `:98` `tasks()` `@Deprecated` | 废弃 | **删**（log-panel:74 在用） |
| `GET /api/ops/tasks/{taskDefId}/latest-instance` | `:269` | **现行** | **不删**（task-editor-pane.tsx:234 在用） |

## 删除的端点

| 端点 | Controller | Service | 返回 |
|------|-----------|---------|------|
| `GET /api/ops/tasks`（`@Deprecated`） | `OpsController.tasks()` (:98) | `OpsService.tasks()` (:72-85, `@Deprecated`) | `List<TaskDef>` |

## 迁移目标端点

| 端点 | Controller | 返回 |
|------|-----------|------|
| `GET /api/ops/instances`（现行） | `OpsController` (:169) | **`Page<InstanceRow>`**（信封，非 List） |

**`InstanceRow` 字段**（`OpsContracts.java:21-24`，**OpsContracts 版**——注意 `dto/InstanceRow.java` 是孤儿，OpsService 用的是 OpsContracts 版）：
`id, taskDefId, taskDefName, workflowInstanceId, runMode, state, bizDate, startedAt(String), finishedAt(String), durationMs, cronExpression, env, workflowName`

`taskDefId`(Long) / `taskDefName`(String) **存在**，迁移可行。

## 前端迁移契约（`components/workspace/log-panel.tsx`）

```diff
- const { data: taskDefs } = useApi<TaskDef[]>("/api/ops/tasks");
- taskDefs.forEach(t => m.set(t.id, t.name));
+ const { data } = useApi<Page<InstanceRow>>("/api/ops/instances");  // 解 Page 信封
+ data.items.forEach(r => m.set(r.taskDefId, r.taskDefName));
```

**字段映射**：`TaskDef.id` → `InstanceRow.taskDefId`；`TaskDef.name` → `InstanceRow.taskDefName`（类型一致）。

## ⚠️ 迁移前置（agent 重核发现）

1. **`Page` 信封**：`/api/ops/instances` 返回 `Page<InstanceRow>`（`OpsController:192` `new Page<>(...)`），不是裸 List——迁移代码必须解 `.items`，否则 `forEach` 报错。
2. **`frontend/lib/types.ts` 缺 `InstanceRow` 接口**（agent 确认）：迁移前须先在 `types.ts` 新增 `InstanceRow`（对齐后端 `OpsContracts` 版字段），否则无类型。

## 约束

- **log-panel 无 TEST tab**（双核实确认），`/api/ops/instances` 默认 `runMode=NORMAL` 不影响——无需带 `runMode` 参数。
- 工作量：小（log-panel 单文件 + types.ts 加接口）。
- 后置删除：前端迁移 + typecheck 通过后，删 `OpsController.tasks()` + `OpsService.tasks()`；核查 `types.ts` 的 `TaskDef` 是否仅此处引用。
- **连带（收尾批）**：`dto/InstanceRow.java` 是孤儿（OpsService 用 OpsContracts 版），可顺清。

## 验证

1. 前端 `pnpm typecheck` 零错误。
2. `log-panel` tab 标题 `taskId → name` 正确（浏览器）。
3. 后端编译 + `grep -rn '"/api/ops/tasks"' frontend/`（精确列表，带引号）零命中；`/api/ops/tasks/{id}/latest-instance` 保留。
