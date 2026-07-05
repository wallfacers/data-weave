# 041 模型评估报告（SC-006 门槛闸）

- 样本：600（heldout，形态隔离）
- 表级 precision：**0.9670**（闸 ≥ 0.85）
- 表级 recall（参考）：0.9274
- 方向准确率：**0.9264**（闸 ≥ 0.95）
- 规则未覆盖子集召回：**0.8929**（闸 ≥ 0.6）
- 幻觉率：**0.0078**（闸 ≤ 0.02）
- 非法输出：0
- **结论：❌ 不过闸**

## 按形态（form_family）

| 形态 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| chain | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| cli | 0.9688 | 0.9688 | 0.9688 | 0.9688 | 0.0312 | 0 |
| config | 0.4000 | 0.2000 | 0.2667 | 0.2000 | 0.0444 | 0 |
| decorator | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| h | 0.9951 | 0.9951 | 0.9951 | 0.9951 | 0.0049 | 0 |
| orm | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |
| py | 0.9886 | 0.9886 | 0.9886 | 0.9886 | 0.0114 | 0 |
| sh | 0.9844 | 0.9921 | 0.9882 | 0.9843 | 0.0078 | 0 |
| wrapper | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 0.0000 | 0 |

## 按数据源（synth / real）

| 来源 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| synth | 0.9670 | 0.9274 | 0.9468 | 0.9264 | 0.0078 | 0 |

## 规则未覆盖子集（meta.rule_covered=False）

| 子集 | precision | recall | f1 | 方向准确率 | 幻觉率 | 非法输出 |
| --- | --- | --- | --- | --- | --- | --- |
| uncovered | 0.9551 | 0.8929 | 0.9230 | 0.8929 | 0.0062 | 0 |

## 失败样例（≤30）
- [h-sh-dbt-run#30] W gold=['room'] got=[]
- [h-sh-dbt-run#36] W gold=['financial_wellbeing'] got=[]
- [h-sh-dbt-run#41] W gold=['worker_salaries'] got=[]
- [h-sh-dbt-run#46] W gold=['violation'] got=[]
- [h-sh-dbt-run#53] W gold=['social_impact_scores'] got=[]
- [h-sh-dbt-run#79] R gold=['intangible_heritage'] got=['esports_participants']
- [h-sh-dbt-run#79] W gold=['esports_participants'] got=[]
- [h-sh-dbt-run#91] R gold=['union_members_demographics'] got=['skills_required_to_fix']
- [h-sh-dbt-run#91] W gold=['skills_required_to_fix'] got=[]
- [h-sh-dbt-run#96] R gold=['lending_initiatives'] got=['personfriend']
- [h-sh-dbt-run#96] W gold=['personfriend'] got=[]
- [h-sh-dbt-run#98] R gold=['age_groups'] got=['bi.products_hourly']
- [h-sh-dbt-run#98] W gold=['bi.products_hourly'] got=[]
- [h-sh-dbt-run#109] W gold=['elements'] got=[]
- [h-py-sqlalchemy-text#111] W gold=['animaleducation'] got=['animatededuction']
- [h-sh-dbt-run#117] R gold=['production_quebec'] got=['london.lines']
- [h-sh-dbt-run#117] W gold=['london.lines'] got=[]
- [h-sh-dbt-run#131] W gold=['marketing'] got=[]
- [py-insert-into#136] W gold=['furniture_manufacte'] got=['furniture_manufacture']
- [h-sh-dbt-run#164] W gold=['peacekeeping_operations'] got=[]
- [h-sh-impala#166] W gold=['animaleducation'] got=['animatedeserts']
- [h-sh-dbt-run#190] R gold=['threat_intelligence_data'] got=['volunteer_programs']
- [h-sh-dbt-run#190] W gold=['volunteer_programs'] got=[]
- [h-sh-dbt-run#194] R gold=['donors'] got=['model_transactions']
- [h-sh-dbt-run#194] W gold=['transactions'] got=[]
- [h-sh-dbt-run#201] W gold=['treatment_type'] got=[]
- [h-sh-dbt-run#213] R gold=['project_staff'] got=['education_aid']
- [h-sh-dbt-run#213] W gold=['education_aid'] got=[]
- [h-sh-dbt-run#220] R gold=['mental_health_providers'] got=['dw.dw_refunds_delta']
- [h-sh-dbt-run#220] W gold=['dw.dw_refunds_delta'] got=[]