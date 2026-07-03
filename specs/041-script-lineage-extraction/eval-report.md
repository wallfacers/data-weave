# 041 模型评估报告（SC-006 门槛闸）

- 样本：240（heldout，形态隔离）
- 表级 precision：**0.9531**（闸 ≥ 0.85）
- 表级 recall（参考）：0.9279
- 规则未覆盖子集召回：**0.8902**（闸 ≥ 0.6）
- 幻觉率：**0.0025**（闸 ≤ 0.02）
- 字段级正确率（参考）：1.0000（116/116）
- 非法输出：0
- **结论：✅ 过闸**

## 失败样例（≤20）
- [h-py-custom-wrapper#7] R gold=['problems'] got=[]
- [h-py-custom-wrapper#7] W gold=['document_drafts'] got=['document_drafts', 'problems']
- [h-py-custom-wrapper#15] R gold=['trend_popularity'] got=[]
- [h-py-custom-wrapper#15] W gold=['dws.dws_cart_item_di'] got=['dws.dws_cart_item_di', 'trend_popularity']
- [h-py-spark-table-alias#16] W gold=['algorithm_fairness'] got=[]
- [h-py-custom-wrapper#18] R gold=['cargoships'] got=[]
- [h-py-custom-wrapper#18] W gold=['dwd.dwd_campaigns_daily'] got=['cargoships', 'dwd.dwd_campaigns_daily']
- [h-py-custom-wrapper#31] R gold=['buildingtypes'] got=[]
- [h-py-custom-wrapper#31] W gold=['bi_vendors_daily'] got=['bi_vendors_daily', 'buildingtypes']
- [h-py-custom-wrapper#33] R gold=['dws_shipments'] got=[]
- [sh-psql-c#34] R gold=['furniture_manufacte', 'museum_artists'] got=['furniture_manufacture', 'museum_artists']
- [h-py-custom-wrapper#51] R gold=['network_investments'] got=[]
- [h-py-custom-wrapper#51] W gold=['public_buildings'] got=['network_investments', 'public_buildings']
- [h-py-spark-table-alias#64] W gold=['graduatestudents'] got=[]
- [h-py-custom-wrapper#68] R gold=['digitalexperiences'] got=[]
- [h-py-custom-wrapper#68] W gold=['user_check_ins'] got=['digitalexperiences', 'user_check_ins']
- [h-py-custom-wrapper#70] R gold=['union_finance'] got=[]
- [h-py-custom-wrapper#70] W gold=['marine_protected_areas'] got=['marine_protected_areas', 'union_finance']
- [h-py-custom-wrapper#88] R gold=['ferry_routes'] got=[]
- [h-py-custom-wrapper#88] W gold=['dwd.dwd_risk_score'] got=['dwd.dwd_risk_score', 'ferry_routes']