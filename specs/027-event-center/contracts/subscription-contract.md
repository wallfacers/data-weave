# Contract: 事件订阅

## POST /api/events/subscriptions
创建订阅：`{ typeFilter?, minSeverity?, refKind?, refId?, channelId }`（channelId 复用 026 alert_channel）。

## DELETE /api/events/subscriptions/{id}
取消订阅（软删）。

## GET /api/events/subscriptions
列当前租户/用户订阅。

## 触达语义
HealthEvent 持久化后匹配订阅（type/severity≥/ref 维度），命中经 026 AlertDispatchService 分发到 channelId。分发失败不阻断持久化与其它订阅（复用 AlertNotification 审计）。
