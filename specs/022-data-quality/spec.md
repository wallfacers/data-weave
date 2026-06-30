# Feature Specification: 数据质量中心 —— 断言定义、执行集成与阻断/告警

**Feature Branch**: `022-data-quality`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 轨道3「新模块」第 2 份(共 3 份)。来源:2026-06-30 轨道3 拆解(4 模块→3 份)。架构演进路线 [docs/architecture.md](../../docs/architecture.md) §8「数据质量中心」。

> **范围边界**:本特性为**数据质量闭环**——对数据集(表)定义**质量断言**,在任务执行/独立调度/按需触发时运行,产出可追溯结果,失败按动作**阻断下游 DAG** 或**仅告警**,并把失败经 `QUALITY_FAILED` 事件**喂入份1 告警引擎**。**不**做:通知分发(份1 负责)、资产/指标编目(份3)。执行复用 worker 执行器(宪法原则 III),不另起引擎。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 质量断言定义与执行 (Priority: P1)

数据负责人对某表定义一组质量断言(行数、空值率、唯一性、新鲜度、值域、引用、自定义 SQL、schema),触发后系统执行并产出每条断言的 PASS/FAIL/WARN 结果与实测值。

**Why this priority**: 质量中心的根——无断言定义与执行,后续阻断/告警/评分都无从谈起。

**Independent Test**: 对一张已知数据的表定义若干断言并触发执行,断言生成 `quality_check_run` + 每断言一条 `quality_check_result`,实测值/期望/状态正确。

**Acceptance Scenarios**:

1. **Given** 一条 `ROW_COUNT min=1000` 断言, **When** 表实际 1500 行执行, **Then** 结果 `PASS`,实测值 1500。
2. **Given** 一条 `NULL_RATE col=email max=0.01` 断言, **When** 空值率 0.05, **Then** 结果 `FAIL`,实测 0.05、期望 ≤0.01。
3. **Given** 一条 `FRESHNESS max_lag=24h` 断言, **When** 最新分区滞后 30h, **Then** `FAIL` 并带滞后量。
4. **Given** 一条 `CUSTOM_SQL`(期望返回 0 行违规), **When** 返回 3 行违规, **Then** `FAIL` 且失败样本可取证引用。

---

### User Story 2 - 执行时机:随任务/独立调度/按需 (Priority: P1)

质量断言可在**任务执行后作为门禁**运行、可**独立调度**周期运行、也可**按需**触发,三种入口共享同一执行与结果模型。

**Why this priority**: 企业级质量必须嵌入数据生产链路(post-task 门禁)而非孤立巡检;三入口覆盖治理全场景。

**Independent Test**: 同一组断言分别经 ①post-task ②调度 ③按需 三入口触发,均产生统一结构的 run/result;post-task 入口与任务实例关联。

**Acceptance Scenarios**:

1. **Given** 任务 T 绑定了 post-task 质量门禁, **When** T 成功产出后, **Then** 质量断言自动执行并与 T 的 taskInstance 关联。
2. **Given** 一个独立质量调度, **When** 到点, **Then** 按调度执行(复用调度内核,不重造定时)。
3. **Given** 用户在前端点「立即检查」, **When** 触发, **Then** on-demand 执行并返回结果。

---

### User Story 3 - 阻断 vs 告警 与下游联动 (Priority: P1)

断言可配动作:`BLOCK`(失败则**阻断该任务下游 DAG 节点**)或 `WARN`(仅记录 + 告警);失败统一发 `QUALITY_FAILED` 事件给份1。

**Why this priority**: 「失败了能拦住坏数据流向下游」是质量中心与 demo 的本质区别;喂告警闭合人工响应。

**Independent Test**: `BLOCK` 断言失败时,断言其任务下游节点被标 FAILED/SKIPPED 且不下发;`WARN` 失败时下游照常但产生 `QUALITY_FAILED` 事件。

**Acceptance Scenarios**:

1. **Given** 一条 `BLOCK` 断言绑定任务 T(T 有下游 D), **When** 断言 FAIL, **Then** D 被阻断(标 FAILED/SKIPPED,不下发),且发 `QUALITY_FAILED`。
2. **Given** 一条 `WARN` 断言, **When** FAIL, **Then** 下游照常执行,但产生 `QUALITY_FAILED` 事件(份1 据规则通知)。
3. **Given** 阻断发生, **When** 查看下游实例, **Then** 失败原因可追溯到具体断言结果。

---

### User Story 4 - 质量评分卡与趋势 (Priority: P2)

每个数据集按历史结果聚合出质量分与趋势,供治理概览与份3 资产徽章复用。

**Why this priority**: 治理可视化价值面,但核心断言/阻断链路即便无评分也闭环,故 P2。

**Independent Test**: 多次执行后,`quality_scorecard` 反映该数据集的通过率/趋势;接口返回随时间序列。

**Acceptance Scenarios**:

1. **Given** 某表多次质量执行, **When** 查其评分卡, **Then** 返回当前质量分与历史趋势。
2. **Given** 质量分下降跨阈值, **When** 配置了相应规则, **Then** 经 `QUALITY_FAILED`/评分事件触发份1 告警。

---

### User Story 5 - 质量治理前端视图 (Priority: P2)

用户在 Workspace 管理断言、查看执行结果与失败样本、查评分卡。

**Why this priority**: 价值呈现面;核心链路经 API 即可闭环,故 P2。

**Independent Test**: 打开质量视图,能 CRUD 断言、查 run/result/失败样本、查评分卡;`pnpm typecheck` 零错,双语 key 等集,浏览器验证。

**Acceptance Scenarios**:

1. **Given** 已有断言与执行结果, **When** 打开质量视图, **Then** 分区展示断言/执行历史/失败明细/评分卡。
2. **Given** 一条 FAIL 结果, **When** 下钻, **Then** 展示实测值、期望、失败样本引用。

### Edge Cases

- 数据源不可达 / 表不存在 → 执行 MUST 区分「检查失败(基础设施)」与「断言失败(数据问题)」,前者不误判数据质量。
- 大表全扫代价高 → 断言执行 MUST 支持采样/限定分区,避免拖垮数据源;采样策略可配。
- `CUSTOM_SQL` 注入/越权风险 → SQL 仅在受控数据源、只读会话执行,不弱化安全解析。
- post-task 门禁与任务本身的关系 → 质量执行失败不应被误记为任务执行失败(语义分离:任务成功但质量门禁拦下游)。
- 断言在执行中被修改/删除 → 进行中的 run 用快照定义收尾,不受后续编辑影响。
- 多租户:断言/run/result/评分卡 MUST 按 `tenant_id` 隔离。
- 失败样本含敏感数据 → 样本引用 MUST 受权限控制,不无差别明文落库/回显。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `quality_rule` 断言定义,v1 支持类型:`ROW_COUNT`(min/max/delta)、`NULL_RATE`、`UNIQUENESS`、`FRESHNESS`、`RANGE`、`REFERENTIAL`、`CUSTOM_SQL`、`SCHEMA`;每断言绑定数据集、期望参数、severity、动作(`BLOCK|WARN`)。
- **FR-002**: 系统 MUST 支持三种执行入口——post-task 门禁、独立调度(复用调度内核)、on-demand,三者共享统一 `quality_check_run`/`quality_check_result` 模型。
- **FR-003**: 断言执行 MUST 复用 worker 执行器(宪法原则 III「AI Lives in the Local Agent」对应的服务端执行复用),在受控数据源只读会话运行,不另起独立查询引擎。
- **FR-004**: `quality_check_result` MUST 记录每断言的 status(`PASS|FAIL|WARN`)、实测值、期望、失败样本引用(可取证);`quality_check_run` MUST 记录触发方式、关联 taskInstance(若 post-task)、耗时、整体状态。
- **FR-005**: `BLOCK` 动作断言失败时 MUST 阻断其绑定任务的**下游 DAG 节点**(标 FAILED/SKIPPED 不下发),并在下游失败原因中可追溯到具体断言;`WARN` 仅记录不阻断。
- **FR-006**: 任何断言 FAIL MUST 发 `QUALITY_FAILED` 事件喂入份1 告警引擎(携带 rule/dataset/实测值/severity 上下文);本特性只产生事件,通知分发由份1 负责。
- **FR-007**: 质量执行失败 MUST 与任务执行失败语义分离——基础设施失败(数据源不可达)区别于断言失败(数据问题),不互相误判。
- **FR-008**: 断言执行 MUST 支持采样/分区限定以控代价;采样策略可配,且结果 MUST 标注是否采样(避免误读为全量)。
- **FR-009**: 系统 MUST 聚合 `quality_scorecard`(数据集质量分 + 趋势),供治理概览与份3 资产质量徽章复用。
- **FR-010**: 断言/run/result/评分卡 MUST 按 `tenant_id` 隔离(沿用 `TenantContext`),缺身份拒绝。
- **FR-011**: 断言的写(agent 发起)与 on-demand 触发(执行副作用)MUST 经 `ActionRequest → GatedActionService.submit → PolicyEngine` + `agent_action` 审计,无旁路;UI admin CRUD 走普通鉴权 API + 审计;`CUSTOM_SQL` MUST 经安全解析,不弱化。
- **FR-012**: 系统 MUST 暴露 `QualityMetrics`(Micrometer):执行延迟、通过/失败数、阻断次数;经 `/actuator/prometheus` + `/api/ops/metrics`。
- **FR-013**: 错误 MUST 走 `BizException(code, args)` + `GlobalExceptionHandler`,错误码 `quality.<semantic>`(如 `quality.rule_not_found`/`quality.datasource_unreachable`/`quality.tenant_required`),稳定不复用;数据术语(NULL/SQL/freshness)保留英文。
- **FR-014**: 前端 MUST 提供质量治理视图(断言/执行历史/失败明细/评分卡),注册进 Workspace view registry,遵循 DESIGN.md 与前端栈约定;静态文案走 next-intl(zh-CN/en-US key 等集)。
- **FR-015**: 新增 `quality_*` 表 MUST 写入权威 `schema.sql` 并**升 `schema_version`**(MINOR;三处恒等,SemVer),H2/PG 双方言兼容。
- **FR-016**: 失败样本 MUST 受权限/租户控制,敏感数据不无差别明文落库或回显。

### Key Entities *(include if feature involves data)*

- **quality_rule**: 质量断言定义。`id, tenant_id, dataset_ref(数据源+表), assertion_type, expectation_json(期望参数), severity, action(BLOCK|WARN), sampling_json, enabled, version, created/updated`。
- **quality_check_run**: 一次执行。`id, tenant_id, rule_set_ref, trigger(POST_TASK|SCHEDULED|ON_DEMAND), task_instance_id(可空), status, started/finished_at, sampled`。
- **quality_check_result**: 单断言结果。`id, tenant_id, run_id, rule_id, status(PASS|FAIL|WARN), measured_value, expected, failed_sample_ref, message`。
- **quality_scorecard**: 数据集评分卡。`id, tenant_id, dataset_ref, score, pass_rate, trend_window, computed_at`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 8 类断言各能定义、执行并产出正确 PASS/FAIL/WARN 与实测值(集成测试逐类覆盖)。
- **SC-002**: 三种执行入口(post-task / 调度 / on-demand)均产生统一结构 run/result;post-task 与 taskInstance 正确关联。
- **SC-003**: `BLOCK` 断言失败 100% 阻断下游 DAG 节点(标 FAILED/SKIPPED 不下发),失败原因可追溯到断言;`WARN` 不阻断。
- **SC-004**: 每条 FAIL 100% 产生 `QUALITY_FAILED` 事件,接缝可被份1 告警引擎消费(集成测试验证接缝)。
- **SC-005**: 基础设施失败与断言失败语义分离——数据源不可达时不误判为数据质量 FAIL(反证测试)。
- **SC-006**: 断言写 + on-demand 触发的 agent 路径全部经 PolicyEngine 闸门 + `agent_action` 审计,`CUSTOM_SQL` 安全解析不弱化,零旁路。
- **SC-007**: 跨租户隔离 100%;失败样本受权限控制不泄露。
- **SC-008**: 前端质量视图 `pnpm typecheck` 零错、双语 key 等集(CI 校验)、浏览器验证渲染与下钻。
- **SC-009**: `schema_version` 三处恒等且升版;H2 与 PG 双库 DDL 均通过。

## Assumptions

- 调度复用现有调度内核(claim/CAS/触发),不重造定时;post-task 门禁挂在任务完成事件后。
- 阻断下游复用现有 DAG 状态机(`InstanceStateMachine` CAS),不新增状态机状态——用既有 FAILED/SKIPPED 表达。
- `QUALITY_FAILED` 是份1 预留的 `signal_source`,两份在该接缝对齐;本特性产生事件,份1 消费分发。
- 执行复用 worker 执行器(SQL/Spark 等已有 runtime),数据源复用 `datasources` 表;不引入新数据源类型。
- 失败样本以引用/有限快照存储,真实大样本留存策略以现有日志归档(MinIO)范式为基线。
- 评分卡聚合算法以「通过率 + 加权 severity」为基线,具体公式留 plan 细化。
