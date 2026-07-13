# Contract: Incident REST API（`/api/incidents`）

统一响应壳 `{code, message, data}`（复用既有约定，200 + `$.code`）；鉴权 JWT Bearer + `X-Project-Id`（`ProjectScope.require` 项目成员校验）；错误经 `BizException(code,args)` 按 UI locale 渲染。

## 查询

### GET /api/incidents
列表（指挥中心 feed 快照/过滤）。Query：`state`（可多值：OPEN/ANALYZING/ACTING/AWAITING_APPROVAL/NEEDS_HUMAN/RESOLVED/DIAG_UNAVAILABLE）、`taskDefId`、`page`/`size`（1-based，对齐 DataTable 惯例）、`openOnly`(bool)。
→ `data: PageResult<IncidentSummary>`；`IncidentSummary = {id, taskDefId, taskDefName, triggerSource, classification, confidence, state, summary, instanceCount, autoActionCount, openedAt, closedAt, closeKind, latestInstanceId}`。
排序：待处理优先（AWAITING_APPROVAL, NEEDS_HUMAN 置顶）→ openedAt DESC。

### GET /api/incidents/{id}
详情。→ `data: {incident: IncidentSummary + suggestion, proposals: IncidentProposal[], messageCount}`。

### GET /api/incidents/{id}/messages
线程消息分页。Query：`afterSeq`(默认 0)、`limit`(默认 200)。→ `data: IncidentMessage[]`；
`IncidentMessage = {id, seq, kind: AGENT_STEP|AGENT_SAY|HUMAN_SAY|ACTION|PROPOSAL|SYSTEM, content, payload, actor, createdAt}`；
`payload`（按 kind 可选）：`{chips: [{label, status: RUNNING|DONE|FAILED}], evidence: {instanceId, logLines}, agentActionId, proposalId, classification, confidence}`。

### GET /api/incidents/briefing
战况播报。→ `data: {summaryLine, reportMd, stats: {acting, awaitingApproval, needsHuman, resolvedToday, diagUnavailable}, generatedAt}`。
**stats 由 SQL 实时计算**（非 briefing 行快照）——SC-010 数字一致性契约。

## 人机交互（写，全部过闸门留审计）

### POST /api/incidents/{id}/chat
`{content: string}`。落 HUMAN_SAY 消息，异步触发 Agent 流式回复（经 SSE 直播，完成后落 AGENT_SAY）。→ `data: {messageId, seq}`（202 语义：回复不同步返回）。
错误：`incident.agent_disabled`（ops_enabled=0）、`incident.closed`。

### POST /api/incidents/{id}/proposals/{proposalId}/approve | /reject
`{confirmation?: string}`。approve：底层走 `ApprovalService`（agent_action 审批单）→ 发布 + 重跑验证异步推进，线程追加 ACTION 消息。
错误：`incident.proposal_stale`（基线漂移，提案已作废）、`incident.proposal_not_pending`。

### POST /api/incidents/{id}/mark-handled
`{note?: string}`。人声明已处理（NEEDS_HUMAN 态限定），落 HUMAN_SAY + SYSTEM，事故转 ACTING 触发复验。

### POST /api/incidents/{id}/reverify
显式触发复验（NEEDS_HUMAN / VERIFY_FAILED 后可用）：Agent 经闸门 rerun 最近实例并跟踪结果。

### POST /api/incidents/{id}/close
人工直接收口（任意非终态 → RESOLVED, close_kind=MANUAL），`{reason: string}` 必填，落 SYSTEM 消息。

## 配置

### GET /api/incidents/agent-config · PUT /api/incidents/agent-config
读/写 `ops_enabled` 开关（连接四元组仍走 053 既有血缘 Agent 配置端点，不重复暴露）。PUT body `{opsEnabled: boolean}`。

## 闸门种子（policy_rules 新增，随 schema seed）

| pattern (TOOL) | base_level | 效果 |
|---|---|---|
| `incident_rerun` | L1 | 自动执行，事后可查 |
| `incident_adjust_resources` | L1 | 自动执行（服务端另有护栏硬校验） |
| `incident_reverify` | L1 | 自动执行 |
| `incident_resume_checkpoint` | L1 | 自动执行（实时任务检查点续跑，复用 062 `resumeFromCheckpoint` 语义） |
| `incident_publish_fix` | L3 | 需确认审批（PENDING_APPROVAL → ApprovalController） |

`DefaultPlatformActionExecutor` 新增以上 actionType 执行分支。既有 prod 环境抬升、非平台资源抬升维度照常叠加。

## MCP（可选自动化面，遵循「查询 + 运维动作」边界）

`McpToolRegistry` 追加：`query_incidents`（只读列表/详情）、`incident_reverify`（写，过闸门）。不追加 authoring 能力。
