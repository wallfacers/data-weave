# Quickstart 验证指南: 数据质量中心

证明数据质量中心端到端工作。详细字段/契约见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)。

## 前置

- 后端起 H2 profile(零外部依赖):`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2`。**改 master/worker 后先 `./dev-install.sh` 装进 m2 再跑 api**(否则 spring-boot:run 用旧 jar,见 [[track1-spark-and-runtime-parity]]);本地 m2 可能在 `/mnt/d` 非 `~/.m2`。长跑命令用 `setsid` 脱离(见 [[backend-dev-server-setsid-detach]]、CLAUDE.md Long-Running Commands)。
- 所有 curl 带 `Authorization: Bearer <token>`(`JwtTestSupport` 或 `DW_TOKEN`),否则 401(见 [[backend-fullstack-http-test-jwt]])。契约统一 200 + `$.code/$.data`。
- 验证后端改动:`cd backend && ./mvnw -q -pl dataweave-master,dataweave-worker compile`(零错)。
- 业务数据源:H2 下用一张已知数据的本地表(或集成测试内建表);SQL 断言连库经 `DatasourceResolver`,无可用数据源时 probe 返回 SKIPPED→ERROR(见场景 6)。

## 场景 1:8 类断言执行(SC-001,逐类覆盖)

对一张已知数据的表逐类建断言并 on-demand 触发,断言 measured_value/expected/status 正确:

| 类型 | 造数 | 断言 | 期望结果 |
|---|---|---|---|
| ROW_COUNT | 1500 行 | `min=1000` | PASS,measured=1500 |
| NULL_RATE | email 空值率 0.05 | `col=email max=0.01` | FAIL,measured=0.05 expected ≤0.01 |
| UNIQUENESS | order_id 有重复 | `columns=[order_id]` | FAIL,measured=dup>0 |
| FRESHNESS | 最新分区滞后 30h | `max_lag=24h` | FAIL,带滞后量 |
| RANGE | age 含越界值 | `col=age min=0 max=150` | FAIL,violations>0 |
| REFERENTIAL | user_id 有孤儿 | `ref users.id` | FAIL,orphans>0 |
| CUSTOM_SQL | 违规查询返回 3 行 | `expect_rows=0` | FAIL,失败样本可取证 |
| SCHEMA | 列缺失/类型不符 | `expected_columns` | FAIL,列差异 |

**断言**:每类 `POST /api/quality/rules/{id}/run` → 生成 `quality_check_run` + 一条 `quality_check_result`;`GET /runs/{id}/results` 下钻见 measured_value/expected/message。

## 场景 2:三入口执行(SC-002)

同一组断言分别经三入口触发,均产生统一结构 run/result:
1. **post-task**:建一条 `bound_task_id=T` 的断言;经 TEST/NORMAL 跑任务 T 成功 → **断言**质量自动执行,`quality_check_run.trigger=POST_TASK` 且 `task_instance_id` 关联 T 的实例。
2. **独立调度**:建 `schedule_cron` 断言;到点 → **断言**复用调度内核触发(`quality_fire` guard 防重),`trigger=SCHEDULED`。
3. **on-demand**:`POST /api/quality/rules/{id}/run` → **断言** `trigger=ON_DEMAND` 立即返回结果。
三者 run/result 结构一致(同 `QualityCheckRunner`)。

## 场景 3:BLOCK 阻断下游 + WARN 不阻断(SC-003 红线)

1. 工作流 W:T → D(T 有下游 D);建一条 `action=BLOCK` 断言绑 T。
2. 跑 W,造 T 的质量断言 FAIL → **断言**:D 被标 `SKIPPED`(不下发),`D.failure_reason` 含 `QUALITY_BLOCKED:rule=.. result=..`(可追溯到断言),且发 `QUALITY_FAILED`(场景 4)。
3. 换 `action=WARN` 同样 FAIL → **断言**:D **照常执行**(下游被 claim),仅产生 result + `QUALITY_FAILED`,**不**阻断。
4. **不变量**:全程无新增 DAG 状态机状态(只用既有 SKIPPED);`InstanceStateMachine.casTaskState` CAS `WHERE state='WAITING'`,下游已被 claim 时让步(集成测试模拟并发)。

## 场景 4:QUALITY_FAILED 喂 021 接缝(SC-004,跨特性)

> 前提:021 告警引擎已合并(定义 `AlertSignal.Type.QUALITY_FAILED` + 消费路径)。

1. 在 021 建 `signal_source=QUALITY_FAILED` 的告警规则 + 通道 + 路由(021 quickstart 场景 1 范式)。
2. 造一条**真实** 022 断言 FAIL(非桩信号)。
3. **断言**:021 出现一条 FIRING 告警 + 一条投递审计;`quality_check_result.signal_emitted=1`(幂等防重发)。
4. 这正是 re-run 021 quickstart 场景 7,把桩信号换成真实 022 产生方——证明 seam 闭合。

## 场景 5:基础设施失败 vs 断言失败语义分离(SC-005 反证)

1. 建断言指向**不可达数据源 / 不存在的表**。
2. 触发 → **断言**:`quality_check_result.status=ERROR`(基础设施失败),`message` 含数据源不可达;**反证**:① **不**发 `QUALITY_FAILED`;② **不**阻断下游;③ **不**计入 `quality_scorecard`(分母分子均不含 ERROR);④ run 整体 `status=ERROR` 而非 FAIL。数据源不可达**不被误判为数据质量 FAIL**。
3. probe 一层即分清:`QualityProbeExecutor` 继承 `SqlTaskExecutor.skipped()` → ERROR;真读回度量才进 PASS/FAIL。

## 场景 6:采样标注(SC-001/FR-008)

1. 大表断言配 `sampling_json={mode:SAMPLE, sample_pct:10}` 或 `mode:PARTITION`。
2. 触发 → **断言**:`quality_check_run.sampled=1` 且对应 `quality_check_result.sampled=1`;结果展示标注「采样」,不被误读为全量。
3. `mode=FULL` 时 `sampled=0`。

## 场景 7:写闸门(QUALITY_RULE_WRITE L1 / QUALITY_RUN L2,SC-006)

1. 以 **agent 身份**提交断言写 → 经 `GatedActionService`,`policy_rules` 命中 `QUALITY_RULE_WRITE=L1` → `outcome=EXECUTED` + `agent_action` 审计一条。
2. 以 **agent 身份** `POST /api/quality/rules/{id}/run` → 命中 `QUALITY_RUN=L2` → **断言** `outcome=PENDING_APPROVAL`(**未跑**),审批后才 EXECUTED。**前端/调用方按 outcome 分流,不只看 code===0**(见 [[rollback-policy-default-l2]])。
3. `CUSTOM_SQL` 安全解析:提交含重定向/写关键字/分隔符的 `sql` → **断言**经 `PolicyEngine` 抬升 L2 或拒(`quality.custom_sql_unsafe`),**不**因来自 agent 放行。
4. UI admin CRUD 走普通鉴权(非 agent),审计仍记。

## 场景 8:租户隔离 + 失败样本权限(SC-007)

1. 租户 A 的断言 + 租户 B 触发 → **断言**:A 断言不被 B 触发;B 不可见 A 的 run/result/评分卡。
2. 缺身份调 `/api/quality/*` → `quality.tenant_required`。
3. 失败样本:`GET /results/{id}/sample` 跨租户/越权 → `quality.sample_forbidden`;敏感样本不无差别明文回显(FR-016)。

## 场景 9:评分卡与趋势(SC-001/FR-009)

1. 某表多次执行后 → `GET /api/quality/scorecards/{datasetRef}` → **断言**返回当前 `score`/`pass_rate` + `trend_json` 时间序列;ERROR 不污染质量分。

## 前端验证(SC-008)

- `cd frontend && pnpm typecheck`(零错)+ 双语 key 等集(CI 校验,`quality` 命名空间 zh-CN/en-US 同集)。
- 浏览器开 quality 视图(注册进 registry,参考 lineage-view):断言列表 / 执行历史 / 失败明细下钻(measured/expected/失败样本)/ 评分卡 + 趋势分区可见;点「立即检查」→ on-demand 触发出结果。
- base-style/hugeicons/语义 token 约定(CLAUDE.md Frontend Stack Gate);改视觉前读 DESIGN.md。
- SSE/实时若用:走直连后端避免 Next 代理缓冲(见 [[next-rewrite-proxy-buffers-sse]])。

## 测试门禁(完成标准)

- **后端**:`dataweave-master` + `dataweave-worker` compile 0 错;单元(8 类编译/比较、BLOCK 阻断 CAS、语义分离 ERROR vs FAIL、采样标注、评分算法、guard 防重)+ 集成(WebTestClient 带 JWT,九场景)全绿;**H2 与 PG 双库 DDL 均通过**;净库测试用 `@TestPropertySource` 独立库名防串台(见 [[h2-shared-mem-db-test-pollution]]);`schema_version` 三处恒等(占位 0.2.0,合并期对齐)。
- **前端**:typecheck 0 错;双语等集;浏览器验证渲染与下钻。
- **闸门**:agent 断言写 + on-demand 触发经 PolicyEngine,零旁路;`CUSTOM_SQL` 安全解析不弱化(反证测试)。
- **跨特性**:021 合并后 re-run `QUALITY_FAILED` 接缝集成测试(真实断言 FAIL → 021 告警),seam 闭合。
- **新功能必有测试,无测试=未完成**(CLAUDE.md)。
