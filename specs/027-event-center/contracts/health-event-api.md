# Contract: 事件中心查询 API

## GET /api/events
按租户列出健康事件（分页、倒序）。

Query: `type?` `severity?` `refKind?` `refId?` `from?` `to?` `page?`(1-based) `size?`
Response: `ApiResponse<{ items: HealthEvent[], total }>`；`HealthEvent` 含 type/severity/refKind/refId/summary/count/firstOccurredAt/lastOccurredAt。

租户隔离：仅返回当前租户事件。错误经 BizException 本地化。

## 深链
前端按 refKind/refId 跳对应视图（TASK/METRIC/TABLE/WORKFLOW）；对象不存在优雅降级。
