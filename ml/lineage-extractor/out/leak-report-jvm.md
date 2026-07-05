# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

| 抽取器 | 幻觉数 | 逐字训练名 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- |
| sft-1.5b | 98 | 40 | 0.408 | 49 | 0.500 |
| m2-anthropic | 6 | 0 | 0.000 | 0 | 0.000 |

## 示例（逐字/形态命中）

### sft-1.5b
- `dwd.dwd_coupon_use_daily` [合成形态] @ SQLClient.java
- `bi.bi_inventory_di` [逐字训练名] @ SQLClient.java
- `dwd.dwd_campaigns` [逐字训练名] @ adapter.scala
- `mart.mart_users_di` [逐字训练名] @ adapter.scala
- `flatv3_events` [合成形态] @ DatasetUtils.java
- `dwd.dwd_campaigns` [逐字训练名] @ DataSet16.java
- `dwd.dwd_orders_daily` [逐字训练名] @ SqlDao.java
- `mart.mart_users_di` [逐字训练名] @ SqlDao.java
- `dws.dws_member_point_df` [逐字训练名] @ DataFrameWriter.scala
- `dws.dws_coupon_use_di` [逐字训练名] @ InsertOperation.java
- `dws.dws_coupon_use_di` [逐字训练名] @ SQLStatement.java
- `bi.bi_inventory_daily` [合成形态] @ SQLStatement.java
- `dwd.dwd_risk_score_df` [合成形态] @ SqlExecuteJob.java
- `ods.clicks_delta` [逐字训练名] @ utils.scala
- `ods.ods_users_daily` [逐字训练名] @ utils.scala

### m2-anthropic
