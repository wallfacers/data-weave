# Data Model: 待删实体清单

**Feature**: 066-remove-alert-incident | **Date**: 2026-07-12

本特性为删除清场，**无新增实体**。下列实体/表/种子待删。

## 待删数据表（schema.sql）

告警中心 7 表（本特性删）：

| 表名 | CREATE 行 | 说明 |
|---|---|---|
| `alert_rule` | 900 | 告警规则定义 |
| `alert_channel` | 929 | 通知通道（邮件/Webhook） |
| `alert_route` | 949 | 规则→通道路由 |
| `alert_event` | 967 | 告警事件 |
| `alert_notification` | 996 | 通知发送记录 |
| `alert_silence` | 1017 | 告警静默 |
| `alert_poll_fire` | 1035 | 轮询触发 |

- 对应 `DROP TABLE IF EXISTS`：行 82-88
- 对应 `alert_*` project_id 回填段（036 隔离）：一并删
- schema 版本升 `0.18.0`

quality_* 表：由并行工作已删（本特性仅收尾 data.sql 策略种子）。

incident/event/health 4 表：已由 065 删除（0.17.0）。

## 待删 Java 实体/类

**alert 整模块** `dataweave-alert/`：
- domain: `AlertRule` / `AlertChannel` / `AlertRoute` / `AlertEvent` / `AlertNotification` / `AlertSilence` / `AlertState`
- domain/repository: 6 个 Repository 接口
- application: `AlertDispatchService` / `AlertEvaluator` / `AlertRuleService` / `AlertLifecycleService` / `AlertMetrics` / `AlertSignalListener` / `MetricPollEvaluator`
- infrastructure: 6 个 JdbcRepository + `JdbcInsertSupport` + `AlertActionHandler` + `channel/`(`ChannelDispatcher`/`DispatchResult`/`EmailDispatcher`/`WebhookDispatcher`)
- interfaces: `AlertController`
- resources: `messages.properties`

**AlertSignal 信号桥** `master/`：
- `domain/signal/AlertSignal.java`（含 `Type` 枚举）
- 5 个发布类的 AlertSignal publish 调用 + helper（见 plan.md）

**quality 整包** `master/quality/` + `api/quality/` + `worker/QualityProbeExecutor`：并行工作已删。

## 待删 policy_rule 种子（data.sql）

| id | code | 等级 | 说明 |
|---|---|---|---|
| 48 | `QUALITY_RULE_WRITE` | L1 | 质量断言写 |
| 49 | `QUALITY_RUN` | L2 | 质量触发 |
| — | `ALERT_RULE_WRITE` | L1 | 告警规则写 |
| — | `ALERT_TEST_SEND` | L2 | 告警测试发送 |

（`policy_rules` 表保留，仅删这 4 条种子。）

## 待删 i18n（前端 + 后端）

- 前端 `alerts` 块（~40 key）+ `nav.alerts` + `leftNav.groups.alerting` + `eventVsPoll`/`eventRatio`/`btnSubscribe`（zh-CN/en-US 保 parity）
- 前端 `quality` 块（并行已删，29 key）
- 后端 `master/messages*.properties`：4 条 `incident.*` 孤儿 key
- 后端 `ops-messages.properties`：`ops.alert.*` 模板

## 保留实体（不动）

- **调度核心**：`task_instance` / `workflow_instance` / `task_def` / `workflow_def` / `cron_fire` 等
- **闸门**：`agent_action` / `policy_rules`（表保留，仅删 4 条种子）
- **运行态观测**：ops overview / Micrometer metrics / logs SSE / DAG instance events SSE
- **就绪态**：`readiness_signal` outbox（InstanceStateMachine 的 eventPublisher 仍服务于它）
