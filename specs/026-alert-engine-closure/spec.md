# Feature Specification: 告警引擎收口（指标轮询 + 邮件真投递 + 全租户）

**Feature Branch**: `026-alert-engine-closure`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 现有功能完善（告警 L2 收口）：021 告警引擎已合并 main，但两处关键能力是「假落地」桩——① `MetricPollEvaluator.fetchMetricValue()` 恒返回 `0.0`，POLL 指标阈值告警在真实指标上**从不触发**；② `EmailDispatcher` 只打日志返回 `"email sent (stub)"`，邮件**根本没发**；③ POLL 轮询有 `TODO: 遍历所有租户的 POLL 规则`，**只评估 tenant 1**。Webhook（钉钉/企微/飞书）已是真实分发，不在本期。

> **范围边界**：本特性把告警引擎从「规则能建、信号能收、Webhook 能发，但指标轮询是空跑、邮件是桩」补成**名副其实的告警**——POLL 规则读真实指标值并按阈值真正触发、EMAIL 通道真正投递、轮询覆盖全部租户、分发结果可查。**不做**：新通道类型（短信/电话）、告警风暴抑制/聚合归因（归属独立的「统一事件中心」特性）、Webhook 通道改动、指标体系本身的扩展。前端复用现有告警视图，仅在字段缺失时小补，不新建大前端。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 指标阈值告警在真实指标上真正触发（破空跑桩）(Priority: P1)

运维为某指标配一条 POLL 规则（如「失败率 > 5%」）。轮询周期到达时，系统读取该指标的**真实当前值**与阈值比较：越界才产生告警事件并按通道分发，未越界则静默。今天这条规则因 `fetchMetricValue` 恒返回 `0.0` 永远不会触发——本故事让它在真实数据上工作。

**Why this priority**: 这是「指标告警」这一核心承诺能否兑现的前提；没有它，所有 POLL 规则都是空跑，告警引擎对指标异常完全失明。

**Independent Test**: 造一个真实指标越过阈值，断言一个轮询周期内产生对应 `AlertEvent`；再造一个未越界指标，断言零告警。

**Acceptance Scenarios**:

1. **Given** 指标 `task.fail_rate` 当前真实值 = 8%、规则阈值「> 5%」, **When** 轮询评估, **Then** 产生 severity 对应的告警事件并触发通道分发，事件 value 记录真实值 8%。
2. **Given** 同规则但指标真实值 = 2%, **When** 轮询评估, **Then** 不产生告警（零误报）。
3. **Given** 规则引用的指标不存在 / 当前无值, **When** 轮询评估, **Then** 跳过该规则 + 记 WARN，不误报、不阻断其它规则评估。

---

### User Story 2 - 邮件通道真正把告警送达收件人（破桩）(Priority: P1)

告警触发且命中 EMAIL 通道时，系统真正向通道配置的收件人发出邮件，并如实返回投递结果（成功 / 失败 + 原因）。今天 `EmailDispatcher` 只打日志、返回假成功——本故事让邮件真正送达。

**Why this priority**: 邮件是数据平台告警的刚需触达方式；桩实现等于「系统以为发了，人从没收到」，是最危险的假绿。

**Independent Test**: 配一个指向可观测收件箱（或 SMTP 捕获器）的 EMAIL 通道，触发一次告警，断言邮件实际抵达且 `DispatchResult` 为成功；再令 SMTP 不可用，断言返回 FAILED + 原因，且告警主链路与其它通道分发不受影响。

**Acceptance Scenarios**:

1. **Given** 配置正确的 EMAIL 通道 + 一次告警触发, **When** 分发, **Then** 收件人实际收到含告警摘要（规则名/严重度/指标值/时间）的邮件，`DispatchResult` = 成功。
2. **Given** SMTP 服务不可达 / 认证失败, **When** 分发, **Then** `DispatchResult` = FAILED 且带可读原因，告警事件仍存续、其它通道（如 Webhook）照常分发。
3. **Given** 邮件服务**未配置**, **When** 分发 EMAIL 通道, **Then** 显式标记「未配置」降级（不抛错、不静默假成功）。

---

### User Story 3 - 全租户 POLL 覆盖（破单租户硬编码）(Priority: P2)

轮询评估遍历**所有租户**的启用 POLL 规则，各租户规则在其租户上下文下评估、互不串扰。今天 `evaluate()` 写死只查 `tenantId=1`——本故事让多租户部署下每个租户的指标告警都生效。

**Why this priority**: 多租户是平台基线；只评估 tenant 1 意味着其它租户的告警规则全部沉默，但不影响 tenant 1 自身工作，故 P2。

**Independent Test**: 为两个租户各配一条会越界的 POLL 规则，断言两条都在轮询周期内触发，且事件归属各自租户。

**Acceptance Scenarios**:

1. **Given** 租户 A、B 各有一条越界的启用 POLL 规则, **When** 轮询评估, **Then** 两条规则均触发，告警事件 tenant_id 分别为 A、B。
2. **Given** HA 多 master 同时轮询, **When** 同一规则同一评估窗口, **Then** 仅一个 master 认领评估（沿用 `alert_poll_fire` guard），不重复告警。

---

### Edge Cases

- **指标无值 / metric_key 不存在**：跳过该规则评估 + WARN，不触发误报。
- **SMTP 超时 / 认证失败 / 收件人为空**：标 FAILED + 原因，不阻断主链路与其它通道。
- **邮件服务未配置**：显式「未配置」降级，区别于「配置了但发失败」。
- **多 master 抢同一轮**：`alert_poll_fire` UNIQUE guard 去重，仅一个认领。
- **规则 disabled / 删除**：不参与评估。
- **阈值边界（恰等于阈值）**：由规则 operator 语义（`>` / `>=` 等）明确界定，无歧义。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: POLL 规则评估 MUST 读取规则所指指标的**当前真实值**（替代恒 `0.0` 桩），来源为平台既有指标体系。
- **FR-002**: 系统 MUST 仅在真实值满足规则阈值条件时产生告警事件；不满足时不产生（零误报）。
- **FR-003**: 当规则指标值缺失或查询失败时，系统 MUST 跳过该规则评估并记 WARN，不误报、不阻断其它规则。
- **FR-004**: EMAIL 通道 MUST 真实向通道配置收件人投递邮件，邮件内容含规则名、严重度、触发值、时间。
- **FR-005**: 邮件投递结果（成功 / 失败 + 原因）MUST 如实返回，且失败 MUST NOT 阻断告警主链路或其它通道分发。
- **FR-006**: 邮件服务未配置时，系统 MUST 显式降级标记「未配置」，不抛错、不静默返回假成功。
- **FR-007**: POLL 轮询 MUST 覆盖所有租户的启用 POLL 规则，各租户规则在其租户上下文下评估、互不串扰。
- **FR-008**: HA 多 master 下，同一 POLL 规则的同一评估窗口 MUST 仅被一个 master 认领评估（沿用 `alert_poll_fire` guard），不重复告警。
- **FR-009**: 每次通道分发 MUST 持久化结果（通道、状态、时间、失败原因），供运维查询近期分发与失败。
- **FR-010**: 以上能力 MUST 对 Webhook 等既有真实通道与 SIGNAL 类规则零回归。

### Key Entities

- **AlertRule（POLL）**：归属租户；指向一个指标（metric_key）、阈值条件（operator + 阈值）、评估周期、严重度、启用态。
- **AlertChannel（EMAIL）**：归属租户；收件人列表 + 邮件服务连接配置引用。
- **AlertEvent**：一次触发的告警，含严重度、指纹（去重）、触发真实值、上下文、租户。
- **DispatchRecord**：一次通道分发的结果——通道、状态（成功/失败/未配置）、时间、失败原因。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 一个越过阈值的真实指标，在一个轮询周期内产生告警（触发延迟 ≤ 一个评估周期）。
- **SC-002**: 指标未越界时，POLL 规则零误报。
- **SC-003**: 邮件服务可用时，触发告警的 EMAIL 通道收件人实际收到邮件，成功率 ≥ 99%。
- **SC-004**: 在 N 个租户各配 POLL 规则的部署下，N 个租户的越界规则全部按期触发（覆盖率 100%）。
- **SC-005**: 邮件服务不可用时，告警主链路与其它通道分发零阻断，且每次失败都有可查的分发记录与原因。

## Assumptions

- 真实指标值来源 = 平台既有指标体系（按规则 metric_key 查询当前值）；指标体系本身不在本期扩展。
- 邮件投递经应用配置注入的 SMTP 连接参数完成；不内置/部署邮件服务器，仅作为客户端发送。
- POLL 规则与 EMAIL 通道的创建/配置复用现有告警视图；本期仅在配置字段缺失时小补，不新建大前端。
- Webhook 通道（钉钉/企微/飞书）已是真实分发，本期不改动。
- 短信、电话等新通道类型，以及告警风暴抑制/聚合归因，均不在本期（后者归属独立的「统一事件中心」特性）。
- 多租户遍历沿用平台既有的租户枚举与 `TenantContext` 隔离范式。
