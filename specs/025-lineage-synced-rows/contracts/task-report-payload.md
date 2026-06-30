# Contract: worker→master 上报 payload（statementMetrics 扩展）

**Feature**: 025-lineage-synced-rows

## TaskReportRequest 扩展（HTTP 路径）

`POST /api/cluster/report`（Bearer），body JSON 加可选字段 `statementMetrics`：

```json
{
  "event": "finished",
  "taskInstanceId": "...",
  "exitCode": 0,
  "tailLog": "...",
  "failureReason": null,
  "statementMetrics": [
    { "sqlText": "INSERT INTO orders_clean(total) SELECT amount FROM orders", "updateCount": 1000 },
    { "sqlText": "SELECT 1", "updateCount": -1 }
  ]
}
```

- `statementMetrics` 可缺失（旧 worker）→ master 跳过 recordSynced。
- `updateCount<0`（SELECT/DDL/无返回）→ master 跳过该 statement。

## 序列化

`WorkerExecController.ReportCallback.reportToMaster`（现状 `:204` 手拼 `StringBuilder`）**改 Jackson `ObjectMapper`** 序列化整 payload，正确处理 SQL 文本中的 `"` / 换行 / 反斜杠转义（手拼 JSON 数组易错）。或保留手拼但定义 `record StatementMetric(sqlText, updateCount)` + `escapeJson` 逐元素转义。

## 向后兼容

| 组合 | 行为 |
|---|---|
| 新 worker + 旧 master | 旧 master `TaskReportRequest` 的 `@JsonIgnoreProperties(ignoreUnknown=true)` 忽略 `statementMetrics`，不崩 |
| 旧 worker + 新 master | `statementMetrics` 缺失 → null → reportFinished 跳过 recordSynced |
| 新 worker + 新 master | 正常透传，逐 statement 解析写表 → recordSynced |

## all-in-one 路径（不经 HTTP / 序列化）

`InProcessTaskExecutionGateway.run`（`scheduler.mode=all-in-one`，默认）进程内从 `ExecutionResult.statementMetrics()` 直传 reportFinished（对象引用，无 JSON 序列化）。**此路径必须一并改**，否则默认模式下漏采。
