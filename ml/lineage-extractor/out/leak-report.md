# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

| 抽取器 | 幻觉数 | 逐字训练名 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- |
| sft-1.5b | 76 | 17 | 0.224 | 19 | 0.250 |
| m2-anthropic | 34 | 0 | 0.000 | 0 | 0.000 |

## 示例（逐字/形态命中）

### sft-1.5b
- `dws.dws_member_point_di` [逐字训练名] @ etl.sh
- `dws.dws_member_point_df` [逐字训练名] @ sqoop-import.sh
- `ads.ads_payments_delta` [逐字训练名] @ sqoop-import.sh
- `dwd.dwd_risk_score_delta` [逐字训练名] @ hiveTest.sh
- `user_clicks` [合成形态] @ mysql2hive_spark.py
- `dws.dws_inventory_hourly` [逐字训练名] @ impala_foreach_table.sh
- `dws.dws_member_point_di` [逐字训练名] @ sqoop-import.distro
- `bi.bi_events_delta` [合成形态] @ conf.py
- `ads.ads_payments` [逐字训练名] @ conf.py
- `dwd.dwd_risk_score_delta` [逐字训练名] @ db2ls.py
- `ads.ads_payments_delta` [逐字训练名] @ bq_load.sh
- `ads.ads_payments_delta` [逐字训练名] @ s3tohive.py
- `dws.dws_coupon_use_di` [逐字训练名] @ s3tohive.py
- `ads.ads_payments_delta` [逐字训练名] @ s3tohive.py
- `dw.dw_member_point_di` [逐字训练名] @ create_pipe.sql

### m2-anthropic
