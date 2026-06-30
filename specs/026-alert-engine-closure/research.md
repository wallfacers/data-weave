# Research: 告警引擎收口

Phase 0 决策记录。所有 spec/Technical Context 的未知项在此解析。

## D1. POLL 规则的真实指标值来源（FR-001/002/003）

- **Decision**: `MetricPollEvaluator.fetchMetricValue(rule)` 改为调用 master 的 `MetricService.findLatestByCode(metricKey)` → `Optional<AtomicMetric>`，命中则 `metricService.evaluate(metric)` 得当前值，转 `double` 后交给既有 `AlertEvaluator.evaluateMetric(rule, value)` 做阈值比较。`metricKey` 取自 `rule.conditionJson` 的 `metric_key`（既有 `extractMetricKey` 已解析）。
- **Rationale**: `dataweave-alert` 的 pom 已声明依赖 `dataweave-master`，可进程内直接注入 `MetricService`，零 HTTP、零新基建，最符合宪法 V「复用内核」。`findLatestByCode` 正是「按指标口径取最新值」的现成只读入口。
- **Alternatives considered**:
  - 调 `/api/ops/metrics` 或指标 REST API（拒绝：同进程绕 HTTP，徒增延迟与失败面）。
  - 用 Micrometer/Actuator 指标（拒绝：那是系统运行指标，非业务「指标口径」AtomicMetric，语义不符）。
- **降级**: `findLatestByCode` 返回 empty 或 `evaluate` 抛错/非数值 → 跳过该规则 + WARN（FR-003），不误报、不阻断其它规则。

## D2. 邮件真投递（FR-004/005/006）

- **Decision**: `EmailDispatcher` 注入 Spring Boot Mail 的 `JavaMailSender`（新增 `spring-boot-starter-mail`）。收件人从 `channel.configJson` 解析（见 contracts/email-channel-config）；SMTP 连接（host/port/username/password/tls）由应用配置 `spring.mail.*` 提供，不内置邮件服务器。发送成功返回 `DispatchResult.sent(digest)`，异常返回 `DispatchResult.failed(reason)`。
- **未配置降级（FR-006）**: 用 `ObjectProvider<JavaMailSender>` 或对 `spring.mail.host` 空判定「未配置」，返回新增的 `DispatchResult.notConfigured(...)`（success=false 但语义区别于「发失败」），WARN 日志，不抛错、不假成功。
- **Rationale**: Spring Boot Mail 是 SB 生态标准、`JavaMailSender` 抽象成熟，配置即用；GreenMail 可在测试内捕获真实 SMTP 投递做断言。
- **Alternatives considered**: 直接用 `jakarta.mail`（拒绝：重造 Spring 已封装的连接/模板）；第三方邮件 API（拒绝：引外部依赖，超范围）。
- **不阻断（FR-005）**: 发送在 `AlertDispatchService.sendWithRetry` 既有重试/审计框架内，失败落 `AlertNotification` 且不影响其它通道（Webhook 照发）。

## D3. 全租户 POLL 覆盖（FR-007/008）

- **Decision**: `AlertRuleRepository` 新增 `findByEvalModeAndEnabled(String evalMode, int enabled)`（不带 tenant 过滤，跨租户查启用的 POLL 规则）；`MetricPollEvaluator.evaluate()` 改为遍历该结果，逐规则 `evaluateRule(rule)`，评估时一切以 `rule.getTenantId()` 为准（事件 tenantId、指纹、分发路由均按规则租户）。
- **HA 去重（FR-008）**: 沿用既有 `alert_poll_fire (rule_id, poll_slot)` UNIQUE guard——它本就按 `rule_id` 唯一、与租户无关，跨租户遍历下每条规则各自认领，语义不变。
- **Rationale**: 规则行自带 `tenant_id`，无需先枚举租户再查；一条「去 tenant 过滤」的查询即覆盖全部，最小改动。
- **Alternatives considered**: 先查租户清单再逐租户查（拒绝：多一轮查询、且租户清单来源不在 alert 模块）；为 `findByTenantId...(null,...)` 打补丁支持 null（拒绝：语义含混，新增明确方法更清晰）。
- **指标值的租户维度**: 若 `MetricService.findLatestByCode` 不区分租户，记为已知限制并在 research 标注——MVP 阶段指标口径按 code 全局；如需租户隔离取值，留作后续债（不在本期扩 MetricService）。

## D4. 分发结果持久化（FR-009）—— 已存在，复用

- **Decision**: 不新建。`AlertDispatchService.sendWithRetry` 已把每次分发落 `AlertNotification`（通道、状态、重试、时间）。本期仅确保 EMAIL 的成功/失败/未配置三态都如实落入既有 `AlertNotification`，并把「未配置」映射为可区分状态。
- **Rationale**: 投递审计基建（含指数退避重试、令牌桶限流）021 已交付，重建违反宪法 V。

## D5. 零回归保证（FR-010）

- **Decision**: Webhook/钉钉/企微/飞书 `WebhookDispatcher` 不动；SIGNAL 类规则路径（`AlertSignalListener`/`AlertLifecycleService.onSignal`）不动；POLL 改动只在 `MetricPollEvaluator` + `EmailDispatcher` + 新 repo 方法。回归测试覆盖既有 Webhook 分发与 SIGNAL 触发。
- **Rationale**: 收口是「填实桩」，不是改契约；改动面最小化即天然控回归。

## 待澄清残留

无阻断性未知项。唯一标注为「已知限制」的是 D3 末尾：指标取值是否需租户维度——采用 MVP 全局 code 取值，租户化取值留后续债，已在 spec Assumptions 与本文件记录，不阻塞本期。
