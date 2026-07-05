# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

**verbatim_own** = 逐字命中被测模型自有训练池（047：防换名字假性归零）。

| 抽取器 | 幻觉数 | 逐字自有池 | 占比 | 逐字合成池 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| sft-1.5b | 42 | 8 | 0.190 | 6 | 0.143 | 9 | 0.214 |

## 示例（逐字/形态命中）

### sft-1.5b
- `dwd.dwd_users_hourly` [逐字训练名] @ sqoop-import.sh
- `mart.mart_campaigns_di` [合成形态] @ sqoop-import.sh
- `user_clicks` [合成形态] @ mysql2hive_spark.py
- `bi.bi_events` [合成形态] @ conf.py
- `bi.bi_events_daily` [逐字训练名] @ snowflake-bq.sh
- `stg.stg_users_di` [逐字训练名] @ snowflake-bq.sh
- `ads.ads_payments_hourly` [逐字训练名] @ bq_load.sh
- `dws.dws_coupon_use_di` [逐字训练名] @ install.sql
- `bi.bi_events_daily` [逐字训练名] @ initialize_table.sh
- `public.police_calls` [合成形态] @ initialize_table.sh
- `assets` [合成形态] @ run.sh
