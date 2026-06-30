# Contract: /api/alert/* REST 端点

统一约定:WebFlux;响应 `200 + {code, data, message}`(契约统一,错误也 200 + 非零 code 走 `GlobalExceptionHandler`,前端按 code 分流);全部带 `Authorization: Bearer`(测试用 `JwtTestSupport`);`TenantContext` 从身份解析,缺身份 → `alert.tenant_required`;所有读写按 `tenant_id` 隔离。

## 规则 alert_rule

| 方法 | 路径 | 说明 | 写闸门 |
|---|---|---|---|
| GET | `/api/alert/rules` | 列规则(分页 + signal_source/enabled 过滤) | — |
| GET | `/api/alert/rules/{id}` | 规则详情 | — |
| POST | `/api/alert/rules` | 建规则 | UI:普通鉴权+审计;agent:`ALERT_RULE_WRITE`(L1) |
| PATCH | `/api/alert/rules/{id}` | 改规则(PATCH null=清空 / 缺字段=不改) | 同上 |
| DELETE | `/api/alert/rules/{id}` | 删规则(进行中告警优雅收尾) | 同上 |

请求体(POST)关键字段:`name, signal_source, eval_mode, eval_interval_sec?, condition_json, severity, for_duration, dedup_key_template, suppress_window_sec, auto_resolve, labels_json, enabled`。校验:METRIC 必带 `eval_interval_sec`;condition_json 结构按 signal_source 校验。

## 通道 alert_channel

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/alert/channels` | 列通道(config 密钥脱敏回显) |
| POST | `/api/alert/channels` | 建通道(`ALERT_CHANNEL_WRITE` L1) |
| PATCH | `/api/alert/channels/{id}` | 改通道 |
| DELETE | `/api/alert/channels/{id}` | 删通道 |
| POST | `/api/alert/channels/{id}/test` | **test-send**(真发,`ALERT_TEST_SEND` L2 → 可能 PENDING_APPROVAL) |

`test` 响应须区分 `outcome`:`EXECUTED`(已发)/`PENDING_APPROVAL`(挂起待批,**未发**)/`REJECTED`。前端不能只看 code===0。

## 路由 alert_route

| 方法 | 路径 |
|---|---|
| GET/POST/PATCH/DELETE | `/api/alert/routes[/{id}]` |

## 告警事件 alert_event

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/alert/events?state=FIRING\|...` | 活跃/历史告警(分页 + 状态/severity 过滤) |
| GET | `/api/alert/events/{id}` | 事件详情(含 context + 关联通知) |
| POST | `/api/alert/events/{id}/ack` | 人工 ACK(`FIRING→ACKED`) |
| GET | `/api/alert/events/{id}/notifications` | 该事件投递审计 |

## 静默 alert_silence

| 方法 | 路径 |
|---|---|
| GET/POST/DELETE | `/api/alert/silences[/{id}]` |

POST 体:`match_json, starts_at, ends_at, reason`。

## 错误码(alert.<semantic>,稳定不复用)

`alert.tenant_required`、`alert.rule_not_found`、`alert.channel_not_found`、`alert.channel_invalid_config`、`alert.silence_invalid_window`(ends≤starts)、`alert.condition_invalid`(condition_json 不匹配 signal_source)、`alert.event_not_found`、`alert.event_not_ackable`(非 FIRING)。
