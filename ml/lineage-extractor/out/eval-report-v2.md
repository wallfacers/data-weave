# 041 模型评估报告（SC-006 门槛闸）

- 样本：600（heldout，形态隔离）
- 表级 precision：**0.9954**（闸 ≥ 0.85）
- 表级 recall（参考）：0.9954
- 方向准确率：**0.9954**（闸 ≥ 0.95）
- 规则未覆盖子集召回：**0.9943**（闸 ≥ 0.6）
- 幻觉率：**0.0009**（闸 ≤ 0.02）
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
| orm | 0.9615 | 0.9615 | 0.9615 | 0.9615 | 0.0000 | 0 |
| py | 0.9943 | 0.9943 | 0.9943 | 0.9943 | 0.0057 | 0 |
| sh | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| wrapper | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |

## 按数据源（synth / real）

| 来源 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| synth | 0.9954 | 0.9954 | 0.9954 | 0.9954 | 0.0009 | 0 |

## 规则未覆盖子集（meta.rule_covered=False）

| 子集 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| uncovered | 0.9943 | 0.9943 | 0.9943 | 0.9943 | 0.0000 | 0 |

## 失败样例（≤30）
- [h-py-orm-chain-dsl#107] R gold=['unique_donors'] got=['repo']
- [h-py-orm-chain-dsl#167] R gold=['mart.mart_risk_score_di'] got=['repo']
- [py-cursor-execute#255] W gold=['furniture_manufacte'] got=['furniture_manufacture']
- [h-py-orm-chain-dsl#379] R gold=['dw.payments_full'] got=['repo']
- [h-py-orm-chain-dsl#415] R gold=['regionwildlifehabitats'] got=['repo']