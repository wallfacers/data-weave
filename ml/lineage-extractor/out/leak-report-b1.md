# 041-R 记忆泄漏分析（负结果论文立论基石）

幻觉表名 = 预测里既不在金标、也不字面出现在脚本文本中的名字。
**verbatim_train** = 逐字命中训练表池；**synthetic_shaped** = 形态像合成池（schema+base）。

**verbatim_own** = 逐字命中被测模型自有训练池（047：防换名字假性归零）。

| 抽取器 | 幻觉数 | 逐字自有池 | 占比 | 逐字合成池 | 占比 | 合成形态 | 占比 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| sft-1.5b | 70 | 7 | 0.100 | 0 | 0.000 | 1 | 0.014 |

## 示例（逐字/形态命中）

### sft-1.5b
- `agricultural_innovation` [合成形态] @ etl.sh
- `adoption` [合成形态] @ conf.py
- `sustainable_properties` [合成形态] @ yt.py
- `inspections` [合成形态] @ gdal_merge.py
- `safety_incidents` [合成形态] @ s3tohive.py
- `safety_incidents` [合成形态] @ create_pipe.sql
- `failure_events` [合成形态] @ run.sh
- `sensor_readings` [合成形态] @ run.sh
