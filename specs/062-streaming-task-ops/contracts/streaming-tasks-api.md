# API Contracts: 实时任务运维（062）

> Phase 1 输出。REST 契约。所有端点经项目隔离（X-Project-Id / projectId 查询参数）+ Bearer 鉴权；所有写操作 **L1 直执 + audit 留痕**（FR-011，复用现有 `OpsService.audit` 机制，不经 GatedActionService 审批）。datetime 统一 UTC ISO（带 Z）。

## 新增端点

### GET `/api/ops/streaming-tasks`

实时任务面板查询（StreamingTasksPanel 的 server DataTable fetcher）。返回当前项目下所有 `long_running=TRUE` 的实例。

- **Query**：`projectId`、`page`、`size`、`state`（可选过滤）、`keyword`（可选，任务名/实例 ID 模糊）。
- **Response 200**：`{ content: StreamingTaskRow[], total, page, size }`

```json
{
  "instanceId": "01964a3e-...",
  "taskDefId": "...",
  "taskName": "mysql-to-doris-realtime-sync",
  "state": "RUNNING",
  "longRunning": true,
  "startedAt": "2026-07-11T03:20:00Z",
  "durationSeconds": 86400,
  "businessAttempt": 1,
  "lastCheckpoint": { "id": "01964a4f-...", "status": "SUCCESS", "completedAt": "2026-07-11T04:00:00Z" },
  "externalJobHandlePresent": true,
  "workerOnline": true
}
```

- `workerOnline=false` + `state=RUNNING` 表达"断连但可能仍在引擎侧运行"（状态漂移呈现，Edge Case ⑥）。
- `lastCheckpoint=null` 表示无可用检查点（续跑按钮应禁用 / 提示全量重跑）。

### GET `/api/ops/streaming-tasks/{instanceId}/checkpoints`

某实时任务的检查点列表（滚动保留的 N 个），用于续跑时选择回滚点。

- **Response 200**：`CheckpointDTO[]`（按 ordinal DESC）

```json
{
  "id": "01964a4f-...",
  "ordinal": 3,
  "status": "SUCCESS",
  "checkpointPath": "hdfs:///.../savepoint-01964a4f",
  "completedAt": "2026-07-11T04:00:00Z",
  "sizeBytes": 10485760,
  "expired": false,
  "resumable": true
}
```

- `resumable = status==='SUCCESS' && !expired`（过期判定：completedAt 在保留窗内，默认 24h 可配置）。

### POST `/api/ops/streaming-tasks/{instanceId}/stop`

优雅停止（保留进度）。映射 `OpsService.stopWithSavepoint`。**异步**：发起 savepoint → 轮询 → 完成后 CAS STOPPED + 写 task_checkpoint。

- **Body**：`{ "targetDirectory"?: string }`（可选 savepoint 存储目录，默认引擎配置）。
- **Response 202**：`{ accepted: true, checkpointId: "01964a4f-...", state: "STOPPING" }`
- **Response 409**：实例非 RUNNING（无法停止）。
- **Response 503**：savepoint 触发不可用（**061 未就绪时**）—— 明确提示运维"无法保留进度，可改用强制终止"（US3 AC3）。
- **门控**：L1 直执 + `audit("STOP_WITH_SAVEPOINT")`。

### POST `/api/ops/streaming-tasks/{instanceId}/resume`

从检查点续跑。映射 `OpsService.resumeFromCheckpoint`。

- **Body**：`{ "checkpointId": "01964a4f-..." }`
- **Response 200**：更新后的 `TaskInstance`（state=WAITING）。
- **Response 404**：checkpointId 不存在或不属于该实例。
- **Response 409**：实例状态不在 `{STOPPED, SUSPENDED}`，或 checkpoint 无效（`!resumable`）——按 Clarification③，无有效 checkpoint 时此端点返回 409 并提示走全量重跑（FR-008）。
- **语义**：不清 external_job_handle（复用 060 reattach），CAS `STOPPED/SUSPENDED→WAITING`，记录所选 checkpointId → 由调度器重新 claim→dispatch→reattach。
- **门控**：L1 直执 + `audit("RESUME_FROM_CHECKPOINT")`。

## 复用既有端点（语义不变）

| 端点 | 用途 | 062 角色 |
|---|---|---|
| `POST /api/ops/instances/{id}/kill` | 强制终止（CANCEL，不写 ckpt） | 实时任务面板的"强制终止"按钮（FR-006 兜底） |
| `POST /api/ops/instances/{id}/rerun` | 全量重跑（清 handle） | 无有效 checkpoint 时的唯一恢复路径（FR-008） |
| `GET /api/ops/instances/{id}/logs/stream` | 日志 SSE（Last-Event-ID 续传） | 实时任务"最新日志"复用（US2），无需新端点 |
| `POST /api/ops/instances/batch` | 批量操作（100 上限） | 多实时任务批量停止/恢复（Edge Case ④） |

## 错误码（i18n，复用 BizException 体系）

- `streaming.not_long_running` — 非 long_running 实例调实时任务端点。
- `streaming.checkpoint.invalid` — 检查点无效/过期（resume 时）。
- `streaming.savepoint.unavailable` — savepoint 触发不可用（061 未就绪 / 引擎拒绝）。
- `streaming.instance.not_resumable` — 实例状态不允许续跑。

## 061 依赖边界

`stop` / `resume` 端点的引擎侧实际生效（savepoint 触发 + ckpt 写入 + reattach 恢复）依赖 061 Flink 真实 checkpoint 落地。061 未合前：端点骨架 + 表 + 契约可交付，实际 savepoint 返回 503（明确不冒充）。`streaming-tasks` 查询 / `checkpoints` 查询 / 日志 SSE / SUSPENDED 一等化不依赖 061，可先交付。
