# Data Model: 告警引擎收口

**本期无新表、无 schema 变更、schema_version 不升。** 以下为复用的既有实体与本期关注的字段形态。

## 复用实体（不改结构）

### AlertRule（`alert_rule`）
POLL 规则关注字段：
- `tenant_id` —— 全租户遍历时事件/分发的归属维度。
- `eval_mode` = `POLL`（区别于 SIGNAL）。
- `enabled` = 1 才评估。
- `eval_interval_sec` —— 评估周期 + `alert_poll_fire` 槽计算。
- `condition_json` —— 含 `metric_key` 与阈值条件（见 contracts/poll-rule-condition）。
- `severity` —— 触发事件严重度。

### AlertChannel（`alert_channel`）
EMAIL 通道关注字段：
- `type` = `EMAIL`。
- `config_json` —— 收件人等通道配置（见 contracts/email-channel-config）。SMTP 连接**不在此**，走 `spring.mail.*`。
- `enabled` / `rate_limit_per_min` —— 既有分发框架使用。

### AlertEvent（`alert_event`）
POLL 触发时构造（既有逻辑）：`tenant_id`（=规则租户）、`rule_id`、`severity`、`fingerprint`、`value`（真实指标值）、`context_json`。

### AlertNotification（`alert_notification`）—— 分发审计，复用
每次通道分发一条：通道、状态、重试次数、时间、响应/错误摘要。本期把 EMAIL 的「成功/失败/未配置」如实写入。

### alert_poll_fire（HA guard）
`(rule_id, poll_slot)` UNIQUE —— 跨租户遍历下仍按 rule_id 唯一认领，语义不变。

## 本期代码层「形态」变更（非 DB）

### DispatchResult（record，新增工厂）
```
record DispatchResult(boolean success, String error, String responseDigest)
  + DispatchResult.sent(digest)           // 既有
  + DispatchResult.failed(error)          // 既有
  + DispatchResult.notConfigured(reason)  // 新增：success=false，语义=通道未配置（区别于发失败）
```
落 `AlertNotification` 时，`notConfigured` 映射为可区分的状态/原因，便于运维分辨「没配」与「配了发不出」。

## 取值链路（只读，跨模块）

`MetricPollEvaluator` → `MetricService.findLatestByCode(metricKey)` → `Optional<AtomicMetric>` → `MetricService.evaluate(metric)` → 数值 → `AlertEvaluator.evaluateMetric(rule, value)`。`AtomicMetric` 为 master 既有实体，本期只读消费，不改。

## 状态/降级矩阵

| 情形 | 行为 |
|------|------|
| 指标无值 / 非数值 | 跳过规则评估 + WARN，不触发 |
| 指标越界 | 构造 AlertEvent → 既有分发 |
| EMAIL 发送成功 | `sent` → AlertNotification 成功 |
| EMAIL 发送异常 | `failed` → AlertNotification 失败（不阻断其它通道） |
| 邮件未配置 | `notConfigured` → AlertNotification 标未配置 + WARN |
| 多 master 同轮 | `alert_poll_fire` 冲突 → 跳过（仅一个认领） |
