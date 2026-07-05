# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

| 抽取器 | 幻觉数 | 逐字训练名 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- |
| sft-1.5b | 99 | 40 | 0.404 | 49 | 0.495 |
| m2-anthropic | 7 | 0 | 0.000 | 0 | 0.000 |

## 示例（逐字/形态命中）

### sft-1.5b
- `dwd.dwd_coupon_use_di` [合成形态] @ SparkMongoSink.scala
- `dwd.dwd_campaigns_delta` [合成形态] @ VastWriteFactory.java
- `dws.dws_coupon_use_di` [逐字训练名] @ InsertOperation.java
- `ads.ads_payments_hourly` [逐字训练名] @ SaveAdaptor.scala
- `ods.ods_users_daily` [逐字训练名] @ FileMessageSet.scala
- `flatv3_events` [合成形态] @ DatasetUtils.java
- `ods.ods_risk_score_delta` [逐字训练名] @ RssColumnType.scala
- `dws.dws_member_point_df` [逐字训练名] @ RssColumnType.scala
- `ods.ods_risk_score_delta` [逐字训练名] @ CelebornColumnType.scala
- `dws.dws_member_point_df` [逐字训练名] @ CelebornColumnType.scala
- `mart.mart_users_di` [逐字训练名] @ adapter.scala
- `dwd.dwd_campaigns` [逐字训练名] @ adapter.scala
- `ods.ods_risk_score_delta` [逐字训练名] @ PipelineUtil.scala
- `ads.ads_payments_hourly` [逐字训练名] @ PipelineUtil.scala
- `ods.clicks_delta` [逐字训练名] @ FeatureGroupEngine.java

### m2-anthropic
