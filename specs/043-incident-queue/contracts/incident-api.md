# Contract: Incident REST API (043)

统一约定：全部端点 JWT 必带 + `ProjectScope.require`（036 惯例，`X-Project-Id`/`?projectId=`）；响应统一 `200 + {code, message, data}` 包裹；错误走 `BizException(code, args)` + GlobalExceptionHandler（错误码见文末）。Controller：`dataweave-api/.../interfaces/IncidentController.java`，服务：`master/.../application/incident/IncidentService`。

## 1. GET /api/incidents — 监督席队列

Query: `projectId`（必填）

返回（clarify Q2 的默认视图，一次给齐三区，量级小不分页）:

```json
{
  "active": [ <IncidentCard>... ],          // OPEN+MITIGATING，服务端按紧迫度排序(research D8)
  "recentResolved": [ <IncidentCard>... ],  // 24h 内 RESOLVED，按 resolved_at 降序
  "activeCount": 3, "recentResolvedCount": 5
}
```

`IncidentCard`:

```json
{
  "id": 1, "title": "ods_订单同步 失败(EXIT_NONZERO)",
  "severity": "HIGH", "state": "OPEN",
  "signature": "T:1024:EXIT_NONZERO",
  "sourceKind": "TASK", "sourceRefId": "1024", "sourceRefName": "ods_订单同步",
  "workflowInstanceId": "0197...", 
  "occurrenceCount": 3, "firstSeenAt": "...", "lastSeenAt": "...",
  "blastRadius": 7,            // null = 血缘不可用（前端显示缺省态，区别于 0）
  "timeBudgetAt": "...",       // null = 无 SLA 基线；早于 now = 已超期
  "suppressReason": null, "resolutionKind": null, "resolvedAt": null,
  "pendingActionCount": 1,     // 关联 agent_action 中 PENDING 数（卡片审批角标）
  "priorIncidentCount": 2,     // 历史同签名已关闭工单数（经验库预热提示）
  "diagnosis": null, "proposal": null   // FR-013 槽位，本期恒 null，前端渲染占位态
}
```

## 2. GET /api/incidents/history — 历史筛选

Query: `projectId` 必填；可选 `state`（RESOLVED/CLOSED/SUPPRESSED）、`signature`、`from`、`to`、`page`、`size`（默认 20）。返回 `{items: [IncidentCard], total}`，按 last_seen_at 降序。

## 3. GET /api/incidents/{id} — 详情 + 时间线 + 关联动作

```json
{
  "incident": <IncidentCard>,
  "timeline": [ {"seq":1, "kind":"SIGNAL", "payload":{...}, "actor":"system", "createdAt":"..."}, ... ],
  "actions":  [ {"id":9, "actionType":"TASK_RERUN", "approvalStatus":"PENDING", "summary":"...",
                 "policyLevel":"L2", "executedAt":null, "resultJson":null}, ... ]
}
```

timeline 按 seq 升序全量（append-only，不分页）；actions = `agent_action WHERE incident_id=?`。

## 4. POST /api/incidents/{id}/rerun — 闸门化重跑（FR-009）

Body: `{ "taskInstanceId": "0197..." }`（须属于该工单来源，否则 `incident.action_target_mismatch`）

行为：构造 `ActionRequest(toolName=incident_rerun, actionType=TASK_RERUN, targetType=TASK_INSTANCE, actorSource=UI, params.incidentId)` → `GatedActionService.submit`；agent_action 落库带 incident_id；timeline 追加 ACTION 条目；工单 CAS OPEN→MITIGATING（已在 MITIGATING 则不变）。

返回闸门 outcome 透传（**前端必须按 outcome 分流，不能只看 code===0**——既有教训）：

```json
{ "outcome": "EXECUTED" | "PENDING_APPROVAL" | "REJECTED", "actionId": 9, "message": "..." }
```

## 5. POST /api/incidents/{id}/suppress · /unsuppress

suppress Body: `{ "reason": "上游供应商已通报故障" }`（非空必填 → `incident.suppress_reason_required`）。CAS OPEN|MITIGATING→SUPPRESSED / SUPPRESSED→OPEN；timeline 记 STATE_CHANGE（actor=当前用户）。

## 6. POST /api/incidents/{id}/notes

Body: `{ "text": "..." }`（≤2000 字）。timeline 追加 NOTE 条目。工单任意非 CLOSED 状态可加。

## 7. 审批（复用，不新建）

卡片内联批准/驳回直接调既有 `POST /api/approvals/{actionId}/approve|reject`（OWNER 权限、L3 二次确认语义均不变）。审批结果由后端在 ApprovalService 回调处追加 APPROVAL 时间线条目（经 agent_action.incident_id 反查工单）。

## 错误码（i18n 三规则之③，双语 bundle 同步）

| code | 场景 |
|---|---|
| `incident.not_found` | id 不存在或跨项目访问 |
| `incident.invalid_state` | CAS 前置状态不符（如对 CLOSED 静默） |
| `incident.suppress_reason_required` | 静默缺原因 |
| `incident.action_target_mismatch` | 重跑目标不属于该工单来源 |

## 内部接缝（非 HTTP 契约，供 tasks 引用）

- `IncidentSignalListener.on(AlertSignal)`：四类 type 分发，其余 type 直接 return；整方法 try-catch。
- `IncidentHealListener.on(TaskSucceededEvent)` / `.on(WorkflowSucceededEvent)`。
- **新增事件** `WorkflowSucceededEvent(UUID workflowInstanceId, Long workflowId, Long tenantId)`：`WorkerReportService` workflow SUCCESS 分支发布（紧邻 `slaService.recordCompletion`），发布失败吞异常。
- `IncidentSweeper`：`@Scheduled(fixedDelay=60_000, initialDelay=30_000)`；7 天自动关闭（集合 CAS）+ NODE 心跳恢复愈合。
