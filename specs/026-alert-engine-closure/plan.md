# Implementation Plan: 告警引擎收口（指标轮询 + 邮件真投递 + 全租户）

**Branch**: `026-alert-engine-closure` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/026-alert-engine-closure/spec.md`

## Summary

把 021 告警引擎从「规则能建、信号能收、Webhook 能发，但指标轮询空跑、邮件是桩、只评估 tenant 1」收口为名副其实的告警。技术路径全程**复用既有内核**（宪法 V）：① POLL 真值 = `MetricPollEvaluator.fetchMetricValue` 改调 master `MetricService.findLatestByCode + evaluate`（alert 模块已依赖 master）；② 邮件真投递 = `EmailDispatcher` 接 Spring Boot Mail（`JavaMailSender`），收件人取 `AlertChannel.configJson`，SMTP 取 `spring.mail.*`，未配置显式降级；③ 全租户 = `AlertRuleRepository` 增「跨租户查 POLL 规则」方法，逐规则按其 `tenantId` 评估，沿用 `alert_poll_fire` guard 去重。分发审计/重试/限流（`AlertDispatchService` + `AlertNotification`）已存在，本期复用并补「未配置」状态语义。

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7（Jackson 3）；Spring Data JDBC + JdbcTemplate；**新增** `spring-boot-starter-mail`（`JavaMailSender`）；复用 021 告警引擎（`AlertDispatchService`/`ChannelDispatcher`/`MetricPollEvaluator`/`AlertEvaluator`）与 master `MetricService`

**Storage**: PostgreSQL（默认）/ H2（`profiles=h2`）；既有表 `alert_rule`/`alert_channel`/`alert_notification`/`alert_poll_fire`（**本期无新表、无 schema 变更，schema_version 不升**）

**Testing**: JUnit 5 + AssertJ；WebTestClient（端点）；GreenMail（SMTP 捕获，验证邮件真发）；H2 独立库名隔离（见 CLAUDE.md 记忆）

**Target Platform**: Linux server（backend `dataweave-alert` + `dataweave-master`）

**Project Type**: web-service（后端为主；前端仅在 POLL 规则/EMAIL 通道配置字段缺失时小补）

**Performance Goals**: POLL 默认 30s 一轮（`alert.poll.interval-ms`），越界规则一个周期内触发；邮件投递不阻塞主链路

**Constraints**: 任一通道/取值/无关链路失败 MUST 不阻断告警主链路；多租户隔离；HA 多 master 同轮去重

**Scale/Scope**: 多租户；POLL 规则数量级与既有规则相当；改动集中在 3 个类 + 1 个 repo 方法 + 1 依赖

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**: N/A —— 不引入新定义文件格式。
- **II. Server is Source of Truth**: N/A —— 不涉及 pull/push/版本快照。
- **III. Two-Legged Debugging**: N/A —— 不涉及 CLI 本地 runtime。
- **IV. AI Lives in Local Agent**: ✅ 合规 —— 不在服务端嵌入 AI 大脑；告警是平台自有运行态行为，不属 agent 写操作；不损伤观测/调度内核。
- **V. Reuse the Kernel**: ✅ **核心** —— 全程复用 021 告警内核（分发/重试/限流/审计、`ChannelDispatcher` 策略、POLL 评估器、`AlertEvaluator`）与 master `MetricService`，零重写。**写闸门豁免说明**：告警分发是平台内部触发的运行态行为，非 CLI/MCP 发起的 agent 写操作，故不经 PolicyEngine L0–L4（与现有 SLA/质量信号分发一致）；不构成 V 的「写操作绕闸门」违例。

**结论**：无违例，Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/026-alert-engine-closure/
├── plan.md              # 本文件
├── spec.md              # 需求
├── research.md          # Phase 0：取值源/邮件/全租户/未配置降级 决策
├── data-model.md        # Phase 1：复用实体 + configJson/conditionJson 形态
├── quickstart.md        # Phase 1：端到端验证（含 GreenMail / 真实指标越界）
├── contracts/           # Phase 1：EMAIL 通道配置 + POLL 规则条件 契约
│   ├── email-channel-config.md
│   └── poll-rule-condition.md
└── checklists/requirements.md   # 已通过
```

### Source Code (repository root)

```text
backend/
├── dataweave-alert/                      # 主改动模块（已依赖 dataweave-master）
│   ├── pom.xml                           # + spring-boot-starter-mail
│   └── src/main/java/com/dataweave/alert/
│       ├── application/
│       │   └── MetricPollEvaluator.java  # fetchMetricValue 接 MetricService；evaluate() 全租户遍历
│       ├── infrastructure/channel/
│       │   ├── EmailDispatcher.java      # 桩 → JavaMailSender 真发 + 未配置降级
│       │   └── DispatchResult.java       # + 「未配置」语义（notConfigured 工厂）
│       └── domain/repository/
│           └── AlertRuleRepository.java  # + findByEvalModeAndEnabled（跨租户 POLL）
└── dataweave-master/
    └── .../application/MetricService.java # 复用 findLatestByCode + evaluate（只读，不改）

frontend/                                  # 仅当 POLL/EMAIL 配置字段前端缺失时小补
└── components/workspace/views/alerts-view.tsx
```

**Structure Decision**: 改动集中在 `dataweave-alert`，复用 `dataweave-master` 的 `MetricService`（只读）。无新模块、无新表。前端为可选小补，先在 quickstart 核实现有 `alerts-view` 是否已能配 POLL 规则与 EMAIL 通道收件人。

## Complexity Tracking

> 无 Constitution 违例，本节留空。
