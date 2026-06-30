# Contract: POLL 规则条件（AlertRule.condition_json）

`eval_mode=POLL` 规则的 `condition_json` 形态——声明评估哪个指标、用什么阈值。

## condition_json schema

```json
{
  "metric_key": "task.fail_rate",
  "comparator": "GT",
  "threshold": 5.0
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `metric_key` | 是 | 指标口径 code，对应 master `MetricService.findLatestByCode` 的入参 |
| `comparator` | 是 | 比较运算符（与既有 `AlertEvaluator` 对齐）：`GT` `GTE` `LT` `LTE` `EQ` `NE`（缺省 `GT`） |
| `threshold` | 是 | 阈值（数值，缺省 0） |

## 评估语义

1. 取值：`MetricService.findLatestByCode(metric_key)` → `evaluate()` → `double currentValue`。
2. 比较：`AlertEvaluator.evaluateMetric(rule, currentValue)` 按 `comparator`/`threshold` 判定。
3. 边界：`comparator` 明确界定「恰等于阈值」是否触发（`GT` 不含等、`GTE` 含等），无歧义。
4. 取不到值（empty / 非数值）：跳过该规则 + WARN，**不触发**。

## 触发产物

满足条件时构造 `AlertEvent`：`tenant_id`=规则租户、`severity`=规则严重度、`value`=真实 currentValue、`fingerprint`=`AlertEvaluator.fingerprint(rule, metric_key)`（用于去重），交既有 `AlertDispatchService.dispatch` 分发。

## 兼容

未声明 `operator`/`threshold` 的历史规则：维持既有 `AlertEvaluator.evaluateMetric` 的默认判定，不因本期改动而行为突变（零回归）。
