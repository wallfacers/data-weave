# Feature Specification: 告警引擎 —— 规则评估、多通道通知与告警生命周期

**Feature Branch**: `021-alert-engine`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 轨道3「新模块」第 1 份(共 3 份)。来源:2026-06-29 三轨路线头脑风暴 + 2026-06-30 轨道3 拆解(4 模块→3 份:①告警 ②数据质量 ③资产目录+指标市场)。架构演进路线 [docs/architecture.md](../../docs/architecture.md) §8「alert 规则引擎与多通道」。

> **范围边界**:本特性为**告警闭环底座**——把平台**已在产出但无人消费**的信号(任务失败/超时、SLA 违约、节点离线、指标阈值越界)转成**有生命周期的告警事件**并经**多通道**通知到人,含去重抑制、自动恢复、投递审计、HA 单点评估。**不**做:质量断言(份2,通过 `QUALITY_FAILED` 事件喂入本引擎)、资产/指标目录(份3)。复用现有 `dataweave-alert` 骨架模块位填充。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多信号告警规则与触发 (Priority: P1)

平台管理员定义告警规则,绑定一个信号源与结构化条件;当条件满足并持续到阈值,系统生成一条告警事件。

**Why this priority**: 告警的根——没有规则与触发,后续通知/生命周期都无从谈起。闭合「SLA/失败信号产出却无人消费」的现有缺口。

**Independent Test**: 造一条 `TASK_FAILED` 信号(或 metric 越界),在已定义匹配规则前提下,断言生成了 `alert_event(state=FIRING)` 且携带正确 severity / 上下文 / fingerprint。

**Acceptance Scenarios**:

1. **Given** 一条「某任务连续失败」事件规则, **When** 该任务实例 FAILED, **Then** 生成 `alert_event(FIRING)`,severity/上下文(taskInstanceId 等)正确。
2. **Given** 一条 metric 阈值规则(`comparator=GT, threshold=X, for_duration=3 次`), **When** 指标连续 3 个评估周期 > X, **Then** 触发;**仅** 2 个周期越界则不触发(`for_duration` 去抖)。
3. **Given** 一条 `SLA_BREACH` 规则, **When** 实例预测 ETA 超过 SLA, **Then** 触发并带预测违约信息。
4. **Given** 规则 `enabled=false`, **When** 条件满足, **Then** 不触发(且不产生通知)。

---

### User Story 2 - 多通道通知分发与投递审计 (Priority: P1)

告警事件按路由规则分发到一个或多个通道(EMAIL / WEBHOOK / 钉钉 / 企业微信 / 飞书),失败退避重试,每次投递留审计。

**Why this priority**: 「通知到人」是告警的终极价值;无分发的告警等于没有。投递审计是企业级合规要求。

**Independent Test**: 对一条 FIRING 告警,断言按路由命中的每个通道各产生一条 `alert_notification` 记录,内容/状态/重试次数正确;模拟通道失败时按退避重试并最终落 `FAILED`。

**Acceptance Scenarios**:

1. **Given** 一条 `severity=CRITICAL` 告警 + 路由「CRITICAL→邮件+钉钉」, **When** 告警触发, **Then** 邮件与钉钉各发一次,各留一条 `alert_notification(SENT)`。
2. **Given** 通道首次投递失败, **When** 进入重试, **Then** 按退避重试至上限,最终状态 `SENT` 或 `FAILED`,`attempts` 与 `error` 如实记录。
3. **Given** 同一通道短时间多次触发, **When** 命中通道速率限制, **Then** 按限流聚合/丢弃并 `log`,不刷屏。
4. **Given** 通道 `config_json` 含密钥(webhook token), **When** 读取/展示规则, **Then** 密钥不明文回显(脱敏)。

---

### User Story 3 - 告警生命周期:去重、抑制、自动恢复、静默 (Priority: P1)

同一问题不重复刷屏(按 fingerprint 去重 + 抑制窗口),条件清除时自动恢复并发恢复通知,运维可对匹配告警设置静默窗口。

**Why this priority**: 区分「企业级告警」与「demo」的关键——没有去重/恢复/静默的告警系统在生产会变成噪音灾难。

**Independent Test**: 同一 fingerprint 在抑制窗口内重复触发只累加 `count` 不重复通知;条件清除后事件转 `RESOLVED` 并发一次恢复通知;命中静默窗口的告警不通知但留记录。

**Acceptance Scenarios**:

1. **Given** 一条 FIRING 告警(fingerprint F), **When** 抑制窗口内 F 再次满足, **Then** `count++`、`last_fired_at` 更新,**不**再发通知。
2. **Given** 一条 FIRING 告警, **When** 触发条件不再满足(且规则开启自动恢复), **Then** 事件转 `RESOLVED`、`resolved_at` 置位,发一次恢复通知。
3. **Given** 一个匹配 label 的静默窗口生效中, **When** 匹配告警触发, **Then** 事件转/标记 `SUPPRESSED`,留记录但不投递。
4. **Given** 一条 FIRING 告警, **When** 运维 ACK, **Then** 事件转 `ACKED`,后续抑制窗口内不再通知。

---

### User Story 4 - HA 单点评估与 test-send 闸门 (Priority: P2)

peer-master 部署下指标轮询评估**单点执行**,杜绝多 master 重复通知;手动 test-send(真发通知的副作用)与 agent 发起的写经 `PolicyEngine` 闸门。

**Why this priority**: 分布式正确性 + 安全合规;优先级低于核心三链路但企业部署必须。

**Independent Test**: 两 master 并发评估同一指标规则,断言只产生一条告警/一组通知(claim+guard 去重);调用 test-send 经 `GatedActionService` 提交并留 `agent_action` 审计。

**Acceptance Scenarios**:

1. **Given** distributed 模式两 master, **When** 同一 metric 规则到评估周期, **Then** 仅一个 master 评估并通知(SKIP LOCKED claim + guard 去重),不重复。
2. **Given** agent 调用 test-send / 规则写, **When** 提交, **Then** 走 `ActionRequest → GatedActionService.submit → PolicyEngine`,留 `agent_action` 审计,无旁路。

---

### User Story 5 - 告警治理前端视图 (Priority: P2)

运维在 Workspace 中管理规则、通道、静默窗口,浏览活跃告警与历史,执行 ACK/静默。

**Why this priority**: 价值呈现面;但核心告警链路即便无 UI 也可经 API 闭环,故 P2。

**Independent Test**: 打开 alerts 视图,能 CRUD 规则/通道、查看活跃/历史告警、ACK、设静默;`pnpm typecheck` 零错,双语 key 等集,浏览器验证渲染。

**Acceptance Scenarios**:

1. **Given** 已有规则与活跃告警, **When** 打开 alerts 视图, **Then** 分区展示规则/活跃告警/历史/通道/静默,状态与 severity 正确着色。
2. **Given** 一条 FIRING 告警, **When** 在视图 ACK 或设静默, **Then** 状态即时更新,后端审计留痕。

### Edge Cases

- 通道持续不可达(如 webhook 域名挂) → 重试至上限后落 `FAILED` + 进入死信/告警自身,不无限重试堵塞队列。
- 指标数据源短暂缺数据 → 评估 MUST 区分「无数据」与「越界」,无数据不误触发(可配 `no_data` 策略)。
- 条件抖动(反复跨阈值) → `for_duration` 去抖 + 抑制窗口防 flapping 刷屏。
- 规则在告警 FIRING 期间被禁用/删除 → 已有事件优雅收尾(不再通知),不留悬挂。
- 静默窗口与去重窗口重叠 → 静默优先级最高(SUPPRESSED 不投递)。
- 同一事件命中多条规则 → 各自独立成 `alert_event`(独立 fingerprint),不互相吞没。
- 多租户:规则/通道/事件/静默 MUST 按 `tenant_id` 严格隔离,跨租户不可见、不可触发。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `alert_rule` 定义,支持信号源 `TASK_INSTANCE`(失败/超时)、`SLA_BREACH`、`WORKFLOW_INSTANCE`、`METRIC`(阈值)、`NODE_OFFLINE`,及结构化条件(metric: `metric_key/aggregation/comparator/threshold/for_duration`;event: `event_type + 过滤标签`)。
- **FR-002**: 系统 MUST 支持两种评估模式:**事件驱动**(调度/SLA/节点信号到达即匹配)与**定时轮询**(metric 按 `eval_interval` 评估),并以 `for_duration` 持续判定去抖。
- **FR-003**: 系统 MUST 维护 `alert_event` 状态机 `FIRING → {RESOLVED, ACKED, SUPPRESSED}`,转换基于条件清除(自动恢复)、人工 ACK、静默命中;状态转换 MUST 幂等且可审计。
- **FR-004**: 系统 MUST 按 fingerprint 去重:抑制窗口内同 fingerprint 重复满足只累加 `count`/更新 `last_fired_at`,不重复通知。
- **FR-005**: 系统 MUST 支持自动恢复:规则开启时,条件不再满足则事件转 `RESOLVED` 并发一次恢复通知。
- **FR-006**: 系统 MUST 提供 `alert_channel`,支持 `EMAIL`、`WEBHOOK`、`DINGTALK`、`WECOM`、`FEISHU`(IM 类统一为 webhook 机器人子类型),含 `config_json`(密钥引用,读出脱敏)、enabled、每通道速率限制。
- **FR-007**: 系统 MUST 提供 `alert_route` 路由:按 `severity` 与 label 把告警映射到一组通道(避免规则硬绑通道),命中可多通道。
- **FR-008**: 系统 MUST 对每次投递留 `alert_notification` 审计:channel、status(`SENT/FAILED/RETRYING`)、attempts、sent_at、error、response 摘要;失败按指数退避重试至上限。
- **FR-009**: 系统 MUST 支持 `alert_silence` 静默/维护窗口:按 label 匹配,在窗口内匹配告警标记 `SUPPRESSED` 不投递但留记录;静默优先级高于去重。
- **FR-010**: distributed 模式下 metric 轮询评估 MUST 单点执行——复用调度 SKIP LOCKED claim + guard 去重表(cron-guard 范式),保证不重复评估/不重复通知。
- **FR-011**: 规则/通道/静默的写(agent 发起)与 test-send(真发通知副作用)MUST 经 `ActionRequest → GatedActionService.submit → PolicyEngine`(数据驱动 `policy_rules`)+ `agent_action` 审计,无旁路;UI admin CRUD 走普通鉴权 API + 审计。
- **FR-012**: 所有 `alert_*` 读写 MUST 按 `tenant_id` 隔离(沿用 `TenantContext`),缺身份拒绝。
- **FR-013**: 系统 MUST 暴露 `AlertMetrics`(Micrometer):评估延迟、触发数、投递成败、重试数;经 `/actuator/prometheus` + `/api/ops/metrics`。
- **FR-014**: 错误 MUST 走 `BizException(code, args)` + `GlobalExceptionHandler`,错误码 `alert.<semantic>`(如 `alert.rule_not_found`/`alert.channel_invalid_config`/`alert.tenant_required`),稳定不复用;数据术语(SLA/webhook/metric)保留英文。
- **FR-015**: 通知模板 MUST 按收件人 locale 本地化(后端 `Messages.get`,规则②);静态 UI 文案走 next-intl(zh-CN/en-US key 等集)。
- **FR-016**: 前端 MUST 提供 alerts 治理视图(规则/活跃告警/历史/通道/静默),注册进 Workspace view registry,遵循 DESIGN.md 与前端栈约定(shadcn base / hugeicons / 语义 token)。
- **FR-017**: 新增 `alert_*` 表 MUST 写入权威 `schema.sql` 并**升 `schema_version`**(MINOR;库内/文件头/项目版本三处恒等,SemVer),H2/PG 双方言兼容。

### Key Entities *(include if feature involves data)*

- **alert_rule**: 告警规则。`id, tenant_id, name, description, enabled, signal_source, condition_json(结构化条件), severity, eval_mode(EVENT|POLL), eval_interval, for_duration, dedup_key_template, suppress_window, auto_resolve, labels, version, created/updated`。
- **alert_channel**: 通知通道。`id, tenant_id, name, type(EMAIL|WEBHOOK|DINGTALK|WECOM|FEISHU), config_json(密钥引用), rate_limit, enabled`。
- **alert_route**: 路由。`id, tenant_id, match(severity/label 匹配), channel_ids, ordering`。
- **alert_event**: 告警事件(生命周期实例)。`id, tenant_id, rule_id, state(FIRING|RESOLVED|ACKED|SUPPRESSED), severity, fingerprint, value, context_json, count, first_fired_at, last_fired_at, resolved_at, acked_by/at`。
- **alert_notification**: 投递审计。`id, tenant_id, event_id, channel_id, status(SENT|FAILED|RETRYING), attempts, sent_at, error, response_digest`。
- **alert_silence**: 静默窗口。`id, tenant_id, match(label), starts_at, ends_at, creator, reason`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 五类信号(任务失败/超时、SLA 违约、工作流、metric 阈值、节点离线)各能定义规则并在条件满足时正确生成告警事件(集成测试覆盖五类)。
- **SC-002**: `for_duration` 去抖、fingerprint 去重、抑制窗口生效——重复满足的同一问题在窗口内**仅一次**通知(可量化断言通知条数)。
- **SC-003**: 自动恢复:条件清除后事件 100% 转 `RESOLVED` 并恰发一次恢复通知。
- **SC-004**: 五类通道(含 IM 子类)投递路径均产生 `alert_notification` 审计;失败按退避重试,最终态如实落库。
- **SC-005**: distributed 双 master 并发评估同一规则,通知**恰一组**(无重复),由 claim+guard 保证。
- **SC-006**: 规则/通道/静默 + test-send 的 agent 写全部经 PolicyEngine 闸门 + `agent_action` 审计,零旁路(测试反证)。
- **SC-007**: 跨租户隔离 100%——A 租户规则不被 B 租户信号触发、不可见。
- **SC-008**: 前端 alerts 视图 `pnpm typecheck` 零错、双语 key 等集(CI 校验)、浏览器验证渲染与 ACK/静默动作生效。
- **SC-009**: `schema_version` 三处恒等且升版;H2(`profiles=h2`)与 PG 双库 DDL 均通过。

## Assumptions

- 信号来源复用现有产出:调度内核(任务/工作流状态、节点心跳 `NodeTelemetryService`)、`SlaService`(ETA/违约)、metrics 四层体系;本特性消费这些信号,不重造采集。
- 份2「数据质量中心」将通过 `QUALITY_FAILED` 事件喂入本引擎(本特性预留该 `signal_source` 但其产生方在份2),两份在该接缝处对齐。
- `dataweave-alert` 现为空骨架(仅 target jar,无 src),本特性填充其 DDD 四层;模块依赖方向遵守 domain←application←infrastructure←interfaces。
- IM 通道(钉钉/企微/飞书)经各自机器人 webhook 实现,统一为 WEBHOOK 子类型以降复杂度;Slack/SMS 后置不在 v1。
- HA 单点评估复用调度的 SKIP LOCKED claim 与 guard 表范式,不新造分布式锁机制。
- 通道密钥以引用/脱敏存储,真实密钥管理(KMS/vault)以现有 `datasources` 凭据范式为基线,不在本特性引入新密钥后端。
