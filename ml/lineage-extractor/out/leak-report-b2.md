# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

**verbatim_own** = 逐字命中被测模型自有训练池（047：防换名字假性归零）。

| 抽取器 | 幻觉数 | 逐字自有池 | 占比 | 逐字合成池 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| sft-1.5b | 72 | 10 | 0.139 | 10 | 0.139 | 11 | 0.153 |

## 示例（逐字/形态命中）

### sft-1.5b
- `dws.dws_coupon_use_di` [逐字训练名] @ sqoop-import.sh
- `ads.ads_exposure_di` [逐字训练名] @ hiveTest.sh
- `ads.ads_payments_delta` [逐字训练名] @ impala_foreach_table.sh
- `bi.bi_inventory_di` [逐字训练名] @ conf.py
- `ads.ads_payments_delta` [逐字训练名] @ conf.py
- `mart.mart_users_di` [逐字训练名] @ gdal_merge.py
- `ads.ads_payments_hourly` [逐字训练名] @ bq_load.sh
- `ods.ods_risk_score_df` [逐字训练名] @ initialize_table.sh
- `ads.ads_payments_delta` [逐字训练名] @ initialize_table.sh
- `dwd.dwd_campaigns` [逐字训练名] @ sh_63.sql
- `failure_events` [合成形态] @ run.sh
