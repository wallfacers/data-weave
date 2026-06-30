# Phase 0 Research: 数据质量中心

技术决策与依据。所有 NEEDS CLARIFICATION 已解。给出 file:line 依据,证明每条决策都落在既有内核上(宪法原则 V「内核复用而非重写」),不另起引擎、不新增状态机状态。

## D1. 断言执行复用 worker 执行器(不另起查询引擎)

- **Decision**:一条质量断言最终落为「**在受控数据源只读会话上运行的查询**」,经 worker 的 **`SqlTaskExecutor`** 执行,**不**写新查询引擎、不引新 JDBC 路径。质量执行服务 `QualityCheckRunner`(master 侧 application)把每条 `quality_rule` **编译**为一段质量度量 SQL(见 D6 类型→SQL 编译表),装配 `ExecutionContext`(taskType=`SQL`,datasource 经 `DatasourceResolver.resolve` 解析),经统一的执行接缝下发执行,从结果集读出**实测值**(measured_value)。
- **调用路径(关键决断)**:复用既有任务下发接缝 `TaskExecutionGateway`(`backend/dataweave-master/.../application/TaskExecutionGateway.java:12`),而**非**新接缝:
  - **all-in-one(默认)**:`InProcessTaskExecutionGateway`(`backend/dataweave-api/.../infrastructure/InProcessTaskExecutionGateway.java:43`)在进程内按 `taskType` 从 `byType` 映射(file:54-82)取 `SqlTaskExecutor`,`executor.execute(ctx, lineConsumer)` 同步拿 `ExecutionResult`(file:142)。
  - **distributed**:`WorkerExecService.executeSync(...)`(`backend/dataweave-worker/.../application/WorkerExecService.java:103-119`)同款幂等执行,经 HTTP `POST /internal/worker/exec` 下发。
  - 质量执行**需要读回结果集的具体值**(行数/空值率/违规样本),而现行 `SqlTaskExecutor.doExecute(...)`(`SqlTaskExecutor.java:47-100`)**本期只汇报「有返回结果集」不打印行数据**(file:74-77)。因此 022 在 worker `infrastructure` 新增**只读度量执行器** `QualityProbeExecutor`(type=`QUALITY_PROBE`,**继承 `SqlTaskExecutor` 的建连/驱动隔离/SKIPPED 语义**,仅在执行层把 `ResultSet` 首行首列读为标量度量值回传)——**复用建连与方言/驱动隔离不变量**(`SqlTaskExecutor.openConnection` file:103-114、`isConnectionFailure` file:117-127),不复制连库逻辑。
- **Rationale**:宪法原则 III/V——「复用平台 SQL 执行语义,MUST NOT fork a divergent second execution engine」。断言本质是「对数据源跑一条度量查询」,与任务 SQL 同一执行语义;新建 `QualityProbeExecutor` 是**对既有 `SqlTaskExecutor` 的最小扩展**(读回标量),不是第二引擎。建连/超时/驱动隔离/SKIPPED 全部沿用。
- **SKIPPED → 基础设施失败语义(FR-007/SC-005 红线)**:`SqlTaskExecutor` 已把「未绑定数据源 / 连接失败 / 无驱动」判为 `ExecutionResult.skipped()`(file:55-99)。022 **复用此判定**:probe 返回 SKIPPED → run/result 标 `status=ERROR`(基础设施失败),**不**计入数据质量 FAIL、**不**发 `QUALITY_FAILED`、**不**阻断下游。只有 probe 真正返回度量值且违反期望才是 `FAIL`(数据问题)。这条把「检查失败」与「断言失败」从执行器一层就分清。
- **Alternatives**:① master 侧直接 `JdbcTemplate` 连业务库跑度量——**否决**(绕开 worker 执行器=第二引擎,违原则 III;且丢失驱动隔离/SKIPPED 不变量);② 复用 `SqlTaskExecutor` 原样不打印结果集,靠日志正则抠数——**否决**(脆弱、无法取证 measured_value)。

## D2. 三入口执行:post-task 门禁 / 独立调度 / on-demand(共享统一 run/result 模型)

三入口都进同一个 `QualityCheckRunner.run(ruleSet, trigger, taskInstanceId?, locale)`,只是触发源不同,产出统一 `quality_check_run` + N×`quality_check_result`。

### D2.1 入口①:post-task 门禁——挂在任务终态推进点之后

- **Decision**:在任务实例推进到终态的点 `InstanceStateMachine.casTaskTerminal(...)`(`InstanceStateMachine.java:64-73`)**CAS 成功(`to=SUCCESS`)之后**注入质量门禁钩子。沿用 021 同款**应用内 `ApplicationEvent`** 接缝:master 在 `casTaskTerminal` 成功 `publishTaskState(id, to)`(file:69-71)旁,额外 `publish` 一个 `TaskSucceededEvent`(Spring `ApplicationEventPublisher`);质量侧 `@EventListener` 消费,查该 task 是否绑定 `POST_TASK` 质量规则,有则触发 `QualityCheckRunner.run(..., trigger=POST_TASK, taskInstanceId=该实例)`。
- **为何用事件而非内联**:① 守依赖方向——`InstanceStateMachine`(master 调度内核)**不得反向依赖**质量 application 服务;框架级 `ApplicationEventPublisher` 让 master 只 `publish` 不感知消费者(与 021 D1 同构,见 `specs/021-alert-engine/research.md` D1)。② 事务纪律——`casTaskTerminal` 是「事务内只落状态」,质量执行是**事务外副作用**(原则:状态推进 CAS 成功后再做副作用,见 `InstanceStateMachine.java:13-19` 死锁防御不变量③);事件监听器在事务提交后异步跑,不污染状态机事务。
- **BLOCK 的时序**:见 D3——BLOCK 断言要在**下游被认领前**裁决。post-task 钩子在上游 task `SUCCESS` 之后、下游 `crossCycleReady`/就绪门(`SchedulerKernel` claim,见 D3)之前完成质量裁决并对 FAIL 的 BLOCK 规则把下游标 SKIPPED。
- **Alternatives**:① 在 `WorkerReportService.reportFinished` 内联跑质量——否决(把质量逻辑塞进 worker 回报路径,耦合且违分层);② 轮询找新 SUCCESS 任务跑质量——否决(延迟高、扫表,事件驱动更准,同 021 D1)。

### D2.2 入口②:独立调度——复用调度内核,不重造定时

- **Decision**:周期性质量巡检复用调度内核范式,**不**新建 `@Scheduled`/Quartz/线程定时。`quality_schedule`(随 `quality_rule.schedule_cron` 字段表达,见 data-model)由一个 `QualityScheduleTrigger` 复用 `TriggerEngine`/`cron_fire` 同款 **SKIP LOCKED + guard 表 UNIQUE 冲突**防重(`cron_fire` 范式 `schema.sql:586-594`):distributed 下多 master 防重复评估靠新增 `quality_fire` guard 表(复合唯一键 `(rule_id, scheduled_fire_time)`),INSERT 冲突=别 master 已认领本轮 → 跳过。镜像 021 D2 的 `alert_poll_fire`。
- **Rationale**:CLAUDE.md「Peer masters + SKIP LOCKED claim + cron guard table for dedup」;调度去重范式经生产验证,不引新分布式锁。
- **方言**:INSERT 冲突捕获 `DataIntegrityViolationException` 在 PG/H2 均工作;**不**用 MySQL `INSERT IGNORE`(同 021 D2)。
- **Alternatives**:独立 `ScheduledExecutorService` 定时——否决(distributed 下重复触发,且重造已有内核)。

### D2.3 入口③:on-demand——前端「立即检查」/agent 触发

- **Decision**:UI「立即检查」走普通鉴权 REST `POST /api/quality/rules/{id}/run`(或对 dataset 批量),同步/异步触发 `QualityCheckRunner.run(..., trigger=ON_DEMAND)`。**agent** 发起的 on-demand 触发是**执行副作用**,MUST 过写闸门(见 D5,`QUALITY_RUN` = L2)。
- **Rationale**:三入口共享同一 runner,仅 trigger 标记不同,产出结构一致(FR-002/SC-002)。

## D3. BLOCK 阻断下游 DAG(复用既有状态机,不新增状态——红线)

- **Decision(机制)**:`BLOCK` 断言 FAIL 时,把该 task 实例所属工作流实例内的**直接+传递下游 TASK 节点实例**经 `InstanceStateMachine.casTaskTerminal(id, "WAITING", "SKIPPED", reason)` / 标 `FAILED` **CAS 置终态、使其永不被认领**——**复用既有 `SKIPPED`/`FAILED` 状态**,不新增「QUALITY_BLOCKED」之类的状态机状态。
- **依据(为何能复用、不下发)**:
  - 调度认领门 `SchedulerKernel.claim`(`SchedulerKernel.java:284-292`)的上游就绪子句:下游只在「上游 `pred.state='SUCCESS'`(强依赖)」时才被 claim;一旦把上游或下游本身标为非 `SUCCESS` 终态(`SKIPPED`/`FAILED`),`NOT EXISTS(...)` 就绪门**天然不放行**——下游 `WAITING` 永不被认领、永不下发。BLOCK 复用的正是这条既有就绪门。
  - `SKIPPED` 已是平台既有「非失败完成、不阻塞 vs 阻塞」语义载体:worker `SqlTaskExecutor`/`InProcessTaskExecutionGateway` 已用 `result.skipped()` 表达「环境缺失跳过」(`InProcessTaskExecutionGateway.java:154-158`、`WorkerExecService.java:156-159`),`NodeFreezeService` 冻结节点也物化为 SKIPPED(`schema.sql:392-394` 注释)。BLOCK 阻断与「冻结节点传递下游 SKIPPED」**同构**——把待阻断下游标 SKIPPED 即可,失败原因写入 `failure_reason`(可追溯到具体 `quality_check_result`,FR-005/SC-003)。
  - **下游集合计算**:沿用 `workflow_edge`(`schema.sql:376-390`)做图遍历,从绑定 task 的 `workflow_node` 出发取传递闭包(含弱依赖边,与冻结传递下游一致)。
- **时序与 CAS 纪律**:post-task 钩子(D2.1)在上游 `SUCCESS` 后、下游被 claim 前完成:对每个待阻断下游实例 `casTaskState(id, "WAITING", "SKIPPED")`(`InstanceStateMachine.java:41-47`,乐观 CAS,`WHERE state='WAITING'`);若某下游已被并发 claim(已非 WAITING),CAS 返回 0 让步——遵守死锁防御不变量②(乐观 CAS,影响 0 行即让步)。**绝不**先读后写、不持锁等待。
- **WARN**:`WARN` 动作 FAIL **不**触碰下游状态机,下游照常被 claim;仅记 result + 发 `QUALITY_FAILED`(D4)。
- **失败可追溯(FR-005/SC-003)**:被阻断下游 `failure_reason` 写 `QUALITY_BLOCKED:rule={id} result={resultId}`(英文术语保留);前端查下游实例下钻到具体断言结果。
- **Rationale**:宪法 V + spec Assumptions「阻断下游复用现有 DAG 状态机,不新增状态机状态——用既有 FAILED/SKIPPED 表达」;这是硬红线。
- **Alternatives**:① 新增 `BLOCKED` 状态——**否决**(侵入调度内核状态机,违红线);② 删/暂停下游实例——否决(丢失可观测与可追溯,SKIPPED 保留实例记录更优)。

## D4. QUALITY_FAILED 接缝(022 产生,021 消费)

- **Decision**:断言 FAIL(BLOCK 或 WARN 均)时,022 **复用 021 已定义的 `AlertSignalPublisher`**(Spring `ApplicationEvent`)`publish` 一个 `AlertSignal(Type.QUALITY_FAILED, ...)`,载荷 `tenantId / fingerprintHint=datasetRef / severityHint=rule.severity / context={ruleId, runId, resultId, datasetRef, measuredValue, expected, action}`。021 侧 `AlertSignalListener` `@EventListener` 消费,匹配 `signal_source=QUALITY_FAILED` 的告警规则 → 评估 → 分发(见 `specs/021-alert-engine/contracts/signal-seam.md:18,40`)。
- **依据**:021 `AlertSignal.Type` 已枚举 `QUALITY_FAILED`(`signal-seam.md:18`),并明确「**产生方在 022**」「022 实现时复用本 `AlertSignal`/`AlertSignalPublisher`,在各自发射点 publish 对应 Type」(`signal-seam.md:40-43`)。022 是这条预留接缝的兑现方。
- **依赖方向**:与 021 D1 同构——`AlertSignal`/`AlertSignalPublisher` 是 021 提供、master 可见的契约;022 质量服务(落 master,见 D7)只 `publishEvent`,编译期不依赖 alert 模块消费者。若 021 把 `AlertSignal` 放在 alert 模块,022 作为 master 模块代码 `publish` 需 alert 在 master 运行期依赖(同 021 D1 的处置);**合并期对齐**(见 seam.md)。
- **本特性责任边界**:022 **只产生 `QUALITY_FAILED` 事件 + 携带上下文**;通知规则/分发/去重/静默全在 021,022 不碰(spec 范围边界、FR-006)。
- **闭环测试**:022 合并后 re-run 021 场景 7(`specs/021-alert-engine/quickstart.md:47-50`)的接缝测试——造真实断言 FAIL → 断言 021 对应规则触发告警 + 分发(SC-004)。详见 [contracts/seam.md](./contracts/seam.md)。
- **Alternatives**:① 022 直接调 021 的 service 分发通知——否决(跨模块直依赖 + 越界做通知,违范围与分层);② 写 DB 表让 021 轮询——否决(延迟、扫表,事件驱动更准,同 021 D1)。

## D5. 写闸门接入(断言写 + on-demand agent 触发)

- **Decision**:
  - **agent 发起的断言写**(建/改/删 `quality_rule`)→ `ActionRequest(actionType="QUALITY_RULE_WRITE", ...) → GatedActionService.submit(req, locale)`(`GatedActionService.java:49`)→ `PolicyEngine.decide` → `DefaultPlatformActionExecutor.execute` 加 **case `QUALITY_RULE_WRITE`**(镜像 `PROJECT_PUSH` case,`DefaultPlatformActionExecutor.java:94`,解码 JSON payload → `QualityRuleService.upsert/delete`)。
  - **agent 发起的 on-demand 触发**(真跑 SQL 读业务库,执行副作用)→ `actionType="QUALITY_RUN"` → 同闸门 → executor case `QUALITY_RUN` → `QualityCheckRunner.run(..., ON_DEMAND)`。
  - **UI admin CRUD**:走普通鉴权 REST(非 agent),`agent_action` 审计仍记录(平台约定:每个写动作留痕)。
- **policy_rules seed(data.sql 增量)**:`QUALITY_RULE_WRITE`=**L1**(断言定义的增改属租户内例行写,直通+审计;镜像 `ALERT_RULE_WRITE`=L1)、`QUALITY_RUN`=**L2**(真连业务库读、可能扫大表、属执行副作用,需审批;镜像 `ALERT_TEST_SEND`=L2)。前端/调用方按 `GateResult.Outcome ∈ {EXECUTED, PENDING_APPROVAL, REJECTED}` 分流,**不能只看 code===0**(见 [[rollback-policy-default-l2]] 教训)。
- **`CUSTOM_SQL` 安全解析不弱化(FR-011/SC-006)**:`CUSTOM_SQL` 断言的 SQL **MUST**①仅在受控数据源、**只读会话**执行(probe 执行器以只读意图运行,违规样本查询 = `SELECT`);②经 `PolicyEngine` 既有命令串安全解析路径升级裁决(重定向/分隔符/子命令/写 DDL-DML 关键字 → 抬升 L2,镜像 `node_exec` 的 `PolicyEngine` 解析,CLAUDE.md「`node_exec` command-string safe parsing lives in `PolicyEngine`」);**不**因来自 agent 放行(原则 V)。
- **Rationale**:原则 V「Every write operation issued via CLI or MCP MUST pass the write gate and leave an audit trail」;断言写是定义写、on-demand 真跑是副作用,均须闸门。
- **Alternatives**:断言写绕闸门走纯 admin API——否决(agent 也能写,须统一闸门,同 021 D5)。

## D6. 断言类型 → 度量 SQL 编译(8 类)

- **Decision**:`QualityRuleCompiler` 把 8 类 `assertion_type` + `expectation_json` 编译为度量查询 + 期望比较,probe 读回标量 `measured_value`:

| 类型 | expectation_json 关键字段 | 度量 SQL(示意) | PASS 判定 |
|---|---|---|---|
| `ROW_COUNT` | `min`/`max`/`delta` | `SELECT COUNT(*) FROM <t> [WHERE <partition>]` | min≤cnt≤max(或 delta 同环比) |
| `NULL_RATE` | `column`,`max` | `SELECT SUM(CASE WHEN <col> IS NULL THEN 1 ELSE 0 END)*1.0/COUNT(*) FROM <t>` | rate≤max |
| `UNIQUENESS` | `columns[]` | `SELECT COUNT(*)-COUNT(DISTINCT <cols>) FROM <t>` | dup=0 |
| `FRESHNESS` | `ts_column`,`max_lag` | `SELECT MAX(<ts_col>) FROM <t>`(滞后 = now - max) | lag≤max_lag |
| `RANGE` | `column`,`min`/`max` | `SELECT COUNT(*) FROM <t> WHERE <col> < min OR <col> > max` | violations=0 |
| `REFERENTIAL` | `column`,`ref_table`,`ref_column` | `SELECT COUNT(*) FROM <t> a LEFT JOIN <ref> b ON a.<col>=b.<refcol> WHERE a.<col> IS NOT NULL AND b.<refcol> IS NULL` | orphans=0 |
| `CUSTOM_SQL` | `sql`(期望返回 0 行违规) | 用户 SQL(只读,安全解析,见 D5) | 违规行数=0 |
| `SCHEMA` | `expected_columns[]`/`types` | 经 JDBC `DatabaseMetaData` 列出实际列对比(非数据 SQL) | 列集/类型匹配 |

- **采样/分区(FR-008/SC-009)**:`sampling_json`={`mode`:`FULL`/`SAMPLE`/`PARTITION`, `partition_expr`, `sample_pct`/`limit`}。编译时把分区谓词拼入 `WHERE`、采样用 `LIMIT`/数据库采样子句(方言差异由 compiler 按 datasource type 处理);`quality_check_run.sampled`/`quality_check_result` 标注是否采样(避免误读为全量)。**采样命中 SAMPLE/PARTITION 时,result 显式 `sampled=1`**。
- **失败样本取证(FR-004/FR-016)**:违规类断言(RANGE/REFERENTIAL/CUSTOM_SQL/UNIQUENESS)在 FAIL 时,再跑一条 `... LIMIT N` 取有限违规样本,**以引用方式**存(`failed_sample_ref` 指向 MinIO 日志归档对象键或有限内联摘要),受租户/权限控制,**不无差别明文落库/回显**(FR-016/SC-007;沿用现有日志归档 MinIO 范式,spec Assumptions)。
- **Rationale**:度量统一为「读回一个标量 + 与期望比较」,使 8 类共享同一 probe 执行路径与结果模型。

## D7. 模块归属与依赖方向

- **Decision**:**治理面落 `dataweave-master`**——`quality_rule`/`quality_check_run`/`quality_check_result`/`quality_scorecard` 的定义、调度、结果、评分卡服务与 `quality_*` 表归 master(与 metrics/task/lineage 同模块,CLAUDE.md 模块描述 "metrics/task/lineage");**执行面复用 worker** 的 `QualityProbeExecutor`(D1)。
- **DDD 四层**(domain←application←infrastructure←interfaces,outer→inner only):
  - `domain`:`QualityRule`/`QualityCheckRun`/`QualityCheckResult`/`QualityScorecard` + repository 接口 + `AssertionType`/`RuleAction`/`CheckStatus` 枚举 + 编译/比较纯逻辑。
  - `application`:`QualityCheckRunner`(三入口统一执行)、`QualityRuleCompiler`、`QualityGateService`(BLOCK 阻断下游,调 `InstanceStateMachine`)、`QualityScheduleTrigger`、`QualitySignalEmitter`(发 `QUALITY_FAILED`)、`QualityScorecardService`、`QualityMetrics`、`QualityRuleService`/`QualityCheckService`(CRUD)。
  - `infrastructure`:`Quality*RepositoryImpl`(JdbcTemplate)、probe 装配。
  - `interfaces`:`QualityController`(`/api/quality/*`,WebFlux + TenantContext)。
- **执行接缝**:`QualityCheckRunner`(master application)→ `TaskExecutionGateway`(master application 接口,D1)→ probe 执行(worker)。master 引用 worker `TaskExecutor`/`ExecutionContext` 是既有事实(`InProcessTaskExecutionGateway` 在 api 组合根装配,同款),不破坏依赖方向。
- **Rationale**:质量是治理语义(同 metrics/lineage 归 master);执行是 worker 职责;接缝沿用既有 gateway。
- **Alternatives**:质量独立新模块——否决(spec 范围明确「不做编目/通知」,体量不足以独立模块,且 metrics/lineage 已在 master,内聚更优)。

## D8. i18n 三规则

- **Decision**:① **前端质量视图静态文案**(按钮/标签/空态/toast)→ next-intl `quality` 命名空间,ICU `{name}`,**zh-CN/en-US key 等集**(CI 校验);② **后端生成文案**(MCP 工具描述、闸门审批理由如有)→ `Messages.get`,MessageFormat `{0}`,**按 agent locale**;③ **错误**→ `BizException(code, args)` + `GlobalExceptionHandler`,code `quality.<semantic>`(`quality.rule_not_found`/`quality.datasource_unreachable`/`quality.tenant_required`/`quality.assertion_invalid`/`quality.custom_sql_unsafe`),**按 UI locale**,稳定不复用。数据术语(NULL/SQL/freshness/uniqueness/schema)保留英文。`data.sql` seed i18n 豁免(中文)。
- **Rationale**:CLAUDE.md i18n 三规则归属,同 021 D6。

## D9. 租户隔离

- **Decision**:`quality_*` 四表全带 `tenant_id`,所有读写按 `TenantContext.tenantId()`(`backend/dataweave-api/.../infrastructure/TenantContext.java`)隔离;缺身份 → `quality.tenant_required`。MCP 工具(若暴露只读 `query_quality`)`requireTenant(ctx)`;repo 增量方法 `findByTenantId*`。失败样本引用受租户+权限控制(FR-016)。
- **Rationale**:CLAUDE.md「all reads/writes 按 TenantContext.tenantId() 隔离」;FR-010/SC-007。

## D10. 可观测 QualityMetrics(镜像 SchedulerMetrics)

- **Decision**:`QualityMetrics`(Micrometer,镜像 `SchedulerMetrics` 范式 `backend/dataweave-master/.../application/SchedulerMetrics.java`):`quality.check.latency`(Timer)、`quality.result.count`(Counter by status PASS/FAIL/WARN/ERROR)、`quality.block.count`(Counter,BLOCK 阻断次数)、`quality.signal.count`(Counter,发出的 QUALITY_FAILED)。经 `/actuator/prometheus` + `/api/ops/metrics`。指标定义不可变(改加 version,不 UPDATE)。
- **Rationale**:FR-012;CLAUDE.md「Metric definitions are immutable」「SchedulerMetrics owns all instrumentation」类比。

## D11. 进行中 run 用快照定义收尾(spec Edge Case)

- **Decision**:`QualityCheckRunner` 启动一次 run 时,把参与的 `quality_rule` 定义(`expectation_json`/`action`/`severity`/`sampling_json`)**快照**进 run 上下文(可落 `quality_check_run.rule_snapshot_json` 或运行内存);执行期对规则的并发编辑/删除**不影响**本 run(用快照收尾)。
- **Rationale**:spec Edge Case「断言在执行中被修改/删除 → 进行中的 run 用快照定义收尾」;与平台「版本快照」治理一致(原则 II)。

## D12. schema_version 升版

- **Decision**:新增 4 业务表(+1 `quality_fire` guard 表)→ `schema_version` **MINOR 升**(新增功能、向后兼容)。因 022 与 021/023 **并行**,具体版本号在**合并入 main 时按落地顺序定**:data-model 写**占位 `0.2.0`** 并注明「合并期与 021(`0.1.0`)/023 对齐版本号,避免冲突」。三处恒等:`schema.sql` 内 `INSERT INTO schema_version`(`schema.sql:65-66`)、`schema.sql` 文件头注释(`schema.sql:2`)、项目发布版本。
- **Rationale**:CLAUDE.md「改表必升版本,三处恒等,SemVer」(017 治理);021 D7 已注明并行升版对齐策略。
