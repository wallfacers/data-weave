# Contract: /api/quality/* REST 端点

统一约定:WebFlux;响应 `200 + {code, data, message}`(契约统一,错误也 200 + 非零 code 走 `GlobalExceptionHandler`,前端按 code 分流);全部带 `Authorization: Bearer`(测试用 `JwtTestSupport`);`TenantContext` 从身份解析,缺身份 → `quality.tenant_required`;所有读写按 `tenant_id` 隔离。

## 断言 quality_rule

| 方法 | 路径 | 说明 | 写闸门 |
|---|---|---|---|
| GET | `/api/quality/rules` | 列断言(分页 + dataset_ref/assertion_type/action/enabled 过滤) | — |
| GET | `/api/quality/rules/{id}` | 断言详情 | — |
| POST | `/api/quality/rules` | 建断言 | UI:普通鉴权+审计;agent:`QUALITY_RULE_WRITE`(L1) |
| PATCH | `/api/quality/rules/{id}` | 改断言(PATCH null=清空 / 缺字段=不改,见 [[patch-null-vs-missing-semantics]]) | 同上 |
| DELETE | `/api/quality/rules/{id}` | 删断言(进行中 run 用快照收尾,不受影响,D11) | 同上 |

请求体(POST)关键字段:`name, dataset_ref, assertion_type, expectation_json, severity, action(BLOCK\|WARN), sampling_json?, bound_task_id?, schedule_cron?, enabled`。
校验:`expectation_json` 结构按 `assertion_type` 校验(不匹配 → `quality.assertion_invalid`);`assertion_type=CUSTOM_SQL` 的 `sql` 经 `PolicyEngine` 安全解析(只读、无重定向/写关键字),不弱化(`quality.custom_sql_unsafe`);`action ∈ {BLOCK,WARN}`;`bound_task_id` 指向存在的任务定义。

## 执行 quality_check_run / on-demand 触发

| 方法 | 路径 | 说明 | 写闸门 |
|---|---|---|---|
| POST | `/api/quality/rules/{id}/run` | **立即检查**(on-demand,单断言) | UI:普通鉴权+审计;agent:`QUALITY_RUN`(L2 → 可能 PENDING_APPROVAL) |
| POST | `/api/quality/datasets/{datasetRef}/run` | 对数据集批量 on-demand 检查 | 同上 |
| GET | `/api/quality/runs` | 列执行历史(分页 + dataset_ref/trigger/status 过滤) | — |
| GET | `/api/quality/runs/{id}` | run 详情(含整体 status/sampled/blocked + 关联 task_instance) | — |
| GET | `/api/quality/runs/{id}/results` | 该 run 各断言结果(下钻 measured_value/expected/failed_sample_ref) | — |

`run` 响应须区分 `outcome`(agent 路径,L2):`EXECUTED`(已跑)/`PENDING_APPROVAL`(挂起待批,**未跑**)/`REJECTED`。**前端不能只看 code===0**(见 [[rollback-policy-default-l2]])。UI on-demand 走普通鉴权直接执行。

## 结果下钻 quality_check_result

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/quality/results/{id}` | 单断言结果详情(measured_value/expected/message/sampled) |
| GET | `/api/quality/results/{id}/sample` | 失败样本取证(`failed_sample_ref` 解引用;受租户+权限控制,敏感数据不无差别明文回显,FR-016) |

## 评分卡 quality_scorecard

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/quality/scorecards` | 列各数据集评分卡(score/pass_rate) |
| GET | `/api/quality/scorecards/{datasetRef}` | 单数据集评分卡 + 趋势(trend_json 时间序列,供前端趋势图,FR-009) |

## (可选)MCP 只读查询面

与 021 一致,创作能力不在 MCP 扩展;可暴露只读 `query_quality`(列断言/run/result/评分卡),`requireTenant(ctx)`,按 `tenant_id` 隔离。**断言写一律走上面 REST + 闸门**,不在 MCP 加写工具。

## 错误码(quality.<semantic>,稳定不复用)

`quality.tenant_required`、`quality.rule_not_found`、`quality.run_not_found`、`quality.result_not_found`、`quality.assertion_invalid`(expectation_json 不匹配 assertion_type)、`quality.custom_sql_unsafe`(CUSTOM_SQL 未过安全解析)、`quality.datasource_unreachable`(基础设施失败,区别于断言失败)、`quality.dataset_invalid`(dataset_ref 解析失败)、`quality.sample_forbidden`(失败样本越权访问)。数据术语(NULL/SQL/freshness/uniqueness/schema/row_count)保留英文。
