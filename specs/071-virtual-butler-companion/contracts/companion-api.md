# API Contract: 虚拟管家

统一约定:REST 走 `authFetch` + `X-Project-Id` 头,响应 `200 + {code, message, data}`(`code===0` 成功);错误 = `BizException(code, args)` 经 `GlobalExceptionHandler` 本地化。SSE 直连 `SSE_BASE`,鉴权走 query(`token`/`projectId`),支持 `Last-Event-ID` 续传 + 心跳。

## SSE

### `GET /api/companion/stream?projectId&token`

| event | data(JSON) | 说明 |
|---|---|---|
| `snapshot` | `{state, briefing:{todayRuns, openAnomalies, nextPatrolAt}, reports:[ReportView...]}` | 连接即全量(未 CLOSED 汇报,时间倒序) |
| `state` | `{state: "idle"\|"patrol"\|"alert"\|"think"\|"speak", reason}` | 服务端归一的管家形态 |
| `report` | `{type: "created"\|"closed", report: ReportView}` | 新汇报 / 项目级关闭同步 |
| `briefing` | 同 snapshot.briefing | 概况变更 |
| `message` | `MessageView` | 完整消息落库(用户消息回显/管家整条) |
| `delta` | `{messageId, chunk}` | 管家流式增量 |
| `end` | `{messageId, interrupted: boolean}` | 单条流式结束 |

`ReportView = {id, domain, severity, title, summary, detail, aggregateCount, status, closedBy, createdAt}`
`MessageView = {id, reportId?, role, actorName, content, createdAt}`

## REST

### 汇报

- `GET /api/companion/reports?status=&limit=` — 汇报列表(补看/分页)
- `POST /api/companion/reports/{id}/close` — 关闭(项目级;幂等,已关闭返回成功);触发 SSE `report:closed`
- `POST /api/companion/reports/{id}/read` — 标记已读(未读计数)

### 对话

- `POST /api/companion/chat` — body `{content, reportId?}`;服务端认定 actor;`reportId` 存在时后端注入该汇报的巡检上下文到 brain 会话;回流走 SSE `message/delta/end`
- `POST /api/companion/chat/cancel` — 打断当前用户会话的流式输出(L0 免审批,对齐 070 打断先例);1 秒内 `end{interrupted:true}`
- `GET /api/companion/messages?reportId=&before=&limit=` — 历史消息(全局或锚定会话)

### 巡检治理(US4)

- `GET /api/companion/routines` — 四领域例程与状态
- `PATCH /api/companion/routines/{id}` — body `{enabled?, cronExpression?, scopeJson?}`(缺失=不改,显式 null=清空 scope;PATCH 需在 CORS allowedMethods 中)
- `POST /api/companion/routines/{id}/trigger` — 手动触发一轮(返回 runId)
- `GET /api/companion/routines/{id}/runs?limit=` — 执行历史(触发时间/耗时/结论/关联汇报 id)

### 降级语义

- brain 不可用:`chat` 返回 `code=companion.brain_unavailable`(本地化);stream 照常服务(形象/汇报不受影响,FR-016)。
- 错误码域:`companion.*`(如 `companion.report_not_found`、`companion.routine_domain_unknown`),稳定不复用。

## 后端内部端口(领域接口,非 HTTP)

```
CompanionBrain
  ChatHandle  openChat(projectId, contextPrompt)      // 会话级;流式回调 onDelta/onEnd,支持 cancel()
  PatrolResult runPatrol(routine, scope, timeout)     // headless 单轮;返回结构化 {severity,title,summary,detail}
  boolean     healthy()
实现:WorkhorseBrainClient(HTTP 8300,session 映射,SSE 消费) / MockBrain(降级)
```

## workhorse 侧约定(部署面,非代码契约)

- agent 角色 `companion.yaml`(`~/.workhorse-agent/agents/`):管家人设 system prompt + 工具白名单(只读 `dataweave__*` + 平台写工具经 MCP 细闸)。
- 巡检提示词按 domain 模板化,要求结构化 JSON 产出;解析失败按 `FAILED` run 处理(产出"未完成"汇报)。
