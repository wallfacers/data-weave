# 041 模型评估报告（SC-006 门槛闸）

- 样本：600（heldout，形态隔离）
- 表级 precision：**0.9954**（闸 ≥ 0.85）
- 表级 recall（参考）：0.9963
- 方向准确率：**0.9954**（闸 ≥ 0.95）
- 规则未覆盖子集召回：**0.9943**（闸 ≥ 0.6）
- 幻觉率：**0.0000**（闸 ≤ 0.02）
- 非法输出：0
- **结论：✅ 过闸**

## 按形态（form_family）

| 形态 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| chain | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| cli | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| config | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| decorator | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| h | 0.9877 | 0.9901 | 0.9889 | 0.9877 | 0.0000 | 0 |
| orm | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| py | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| sh | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| wrapper | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |

## 按数据源（synth / real）

| 来源 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| synth | 0.9954 | 0.9963 | 0.9958 | 0.9954 | 0.0000 | 0 |

## 规则未覆盖子集（meta.rule_covered=False）

| 子集 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| uncovered | 0.9929 | 0.9943 | 0.9936 | 0.9929 | 0.0000 | 0 |

## 失败样例（≤30）
- [h-py-spark-table-alias#17] R gold=['visitor_stats'] got=['students_in_detention', 'visitor_stats']
- [h-py-spark-table-alias#30] R gold=['safety_test_results'] got=['investigative_journalism']
- [h-py-spark-table-alias#30] W gold=['investigative_journalism'] got=['safety_test_results']
- [h-py-spark-table-alias#249] R gold=['social_impact_scores'] got=['restorative_justice']
- [h-py-spark-table-alias#249] W gold=['restorative_justice'] got=['social_impact_scores']