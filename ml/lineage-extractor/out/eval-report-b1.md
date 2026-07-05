# 041 模型评估报告（SC-006 门槛闸）

- 样本：600（heldout，形态隔离）
- 表级 precision：**0.9916**（闸 ≥ 0.85）
- 表级 recall（参考）：0.9916
- 方向准确率：**0.9916**（闸 ≥ 0.95）
- 规则未覆盖子集召回：**0.9900**（闸 ≥ 0.6）
- 幻觉率：**0.0019**（闸 ≤ 0.02）
- 非法输出：0
- **结论：✅ 过闸**

## 按形态（form_family）

| 形态 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| chain | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| cli | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| config | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| decorator | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| h | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| orm | 0.9327 | 0.9327 | 0.9327 | 0.9327 | 0.0000 | 0 |
| py | 0.9943 | 0.9943 | 0.9943 | 0.9943 | 0.0057 | 0 |
| sh | 0.9922 | 0.9922 | 0.9922 | 0.9922 | 0.0078 | 0 |
| wrapper | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |

## 按数据源（synth / real）

| 来源 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| synth | 0.9916 | 0.9916 | 0.9916 | 0.9916 | 0.0019 | 0 |

## 规则未覆盖子集（meta.rule_covered=False）

| 子集 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| uncovered | 0.9900 | 0.9900 | 0.9900 | 0.9900 | 0.0000 | 0 |

## 失败样例（≤30）
- [h-py-orm-chain-dsl#81] R gold=['dw_clicks_hourly'] got=['repo']
- [h-py-orm-chain-dsl#102] R gold=['resourcemanagement'] got=['repo']
- [h-py-orm-chain-dsl#107] R gold=['unique_donors'] got=['repo']
- [h-py-orm-chain-dsl#126] R gold=['taxi_occupancy'] got=['repo']
- [h-py-orm-chain-dsl#220] R gold=['dws.dws_sessions_df'] got=['repo']
- [py-cursor-execute#255] W gold=['furniture_manufacte'] got=['furniture_manufacture']
- [h-py-orm-chain-dsl#379] R gold=['dw.payments_full'] got=['repo']
- [sh-heredoc#381] R gold=['ods.ods_member_point', 'tourism_impact'] got=['odds.member_point', 'tourism_impact']
- [h-py-orm-chain-dsl#555] R gold=['dws.dws_exposure_df'] got=['repo']