---
description: "Task list for 026 告警引擎收口"
---

# Tasks: 告警引擎收口（指标轮询 + 邮件真投递 + 全租户）

**Input**: Design documents from `specs/026-alert-engine-closure/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 包含（CLAUDE.md 硬规则「新功能必须有测试」）。测试先写、先失败再实现。

**Organization**: 按 user story 分组，每个 story 可独立实现与验证。改动集中在 `backend/dataweave-alert`，只读复用 `dataweave-master` 的 `MetricService`。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 / US2 / US3
- 路径为仓库相对路径（在 026 worktree 内执行）

## Path Conventions

后端单模块为主：`backend/dataweave-alert/src/main/java/com/dataweave/alert/...`，测试 `backend/dataweave-alert/src/test/java/com/dataweave/alert/...`。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 引入邮件与测试依赖

- [X] T001 在 `backend/dataweave-alert/pom.xml` 增加 `spring-boot-starter-mail` 依赖（compile scope）
- [X] T002 [P] 在 `backend/dataweave-alert/pom.xml` 增加 GreenMail 测试依赖（`com.icegreen:greenmail-junit5`，test scope）
- [X] T003 [P] 编译基线确认：`cd backend && ./mvnw -q -pl dataweave-alert -am compile` 零错误（依赖解析通过）

**Checkpoint**: 依赖就绪，可开始 story 实现

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 共享的分发结果类型扩展——「未配置」三态语义，US2 依赖，且属跨通道共享契约

**⚠️ CRITICAL**: T004 阻塞 US2

- [X] T004 在 `backend/dataweave-alert/src/main/java/com/dataweave/alert/infrastructure/channel/DispatchResult.java` 增加 `notConfigured(String reason)` 工厂（`success=false`，语义区别于 `failed`）；保持 `sent`/`failed` 不变（零回归）

**Checkpoint**: 共享结果类型就绪

---

## Phase 3: User Story 1 - 指标阈值告警在真实指标上触发 (Priority: P1) 🎯 MVP

**Goal**: `MetricPollEvaluator` 读真实指标值（替代恒 `0.0` 桩），越界才触发、缺值跳过 + WARN

**Independent Test**: 造一个真实越阈指标 → 一周期内产生 `AlertEvent`（value=真实值）；改为未越界 → 不触发；metric_key 不存在 → 跳过 + WARN

### Tests for User Story 1 ⚠️（先写先失败）

- [X] T005 [P] [US1] 集成测试 `backend/dataweave-alert/src/test/java/com/dataweave/alert/application/MetricPollEvaluatorRealValueTest.java`：mock/stub `MetricService.findLatestByCode` 返回越阈值 → 断言 `evaluateRule` 产生 AlertEvent 且 value=真实值；返回未越阈 → 断言不触发；返回 `Optional.empty()` → 断言跳过且不触发（验证 WARN 路径不抛错）

### Implementation for User Story 1

- [X] T006 [US1] 在 `backend/dataweave-alert/src/main/java/com/dataweave/alert/application/MetricPollEvaluator.java` 构造器注入 master `MetricService`（alert 已依赖 master）
- [X] T007 [US1] 在同文件实现 `fetchMetricValue(rule)`：`metricService.findLatestByCode(metricKey)` → `evaluate()` → 转 `double`；`metricKey` 复用既有 `extractMetricKey`
- [X] T008 [US1] 在同文件处理降级：`Optional.empty()` / 非数值 / 异常 → 跳过该规则评估 + WARN（不误报、不阻断其它规则，对齐 contracts/poll-rule-condition）
- [X] T009 [US1] 删除 `fetchMetricValue` 的桩注释与 `return 0.0`，确保 `evaluateRule` 走真实值链路

**Checkpoint**: 单条 POLL 规则在真实指标上正确触发/静默/跳过

---

## Phase 4: User Story 2 - 邮件通道真正送达 (Priority: P1)

**Goal**: `EmailDispatcher` 从桩 → `JavaMailSender` 真发；未配置/失败显式区分，不阻断主链路

**Independent Test**: GreenMail 捕获到邮件（收件人/正文含规则名·severity·值·时间）；SMTP 不可达 → failed + Webhook 照发；未配置 → notConfigured + WARN

### Tests for User Story 2 ⚠️（先写先失败）

- [X] T010 [P] [US2] 集成测试 `backend/dataweave-alert/src/test/java/com/dataweave/alert/infrastructure/channel/EmailDispatcherTest.java`：GreenMail 起本地 SMTP，`spring.mail.*` 指向它 → 触发 → 断言收到 1 封、收件人正确、正文含规则名/severity/value/time
- [X] T011 [P] [US2] 同测试类补：SMTP 不可达 → 断言返回 `failed` 且带原因；`spring.mail.host` 空（未配置）→ 断言返回 `notConfigured`，不抛错、不假成功

### Implementation for User Story 2

- [X] T012 [US2] 在 `backend/dataweave-alert/src/main/java/com/dataweave/alert/infrastructure/channel/EmailDispatcher.java` 注入 `ObjectProvider<JavaMailSender>`（可选，支持未配置判定）
- [X] T013 [US2] 解析 `channel.getConfigJson()` 取 `recipients`/`cc`/`subjectPrefix`（对齐 contracts/email-channel-config）；`recipients` 空或 mail sender 缺失 → `DispatchResult.notConfigured`
- [X] T014 [US2] 构造并发送邮件：主题含 prefix + 规则名，正文含规则名/severity/指标值/发生时间/metric_key；成功 → `sent(digest)`，异常 → `failed(reason)`
- [X] T015 [US2] 确认失败/未配置如实落 `AlertNotification`（复用 `AlertDispatchService.sendWithRetry`），不阻断其它通道（与 Webhook 并行分发）

**Checkpoint**: 邮件真送达，三态（成功/失败/未配置）可分辨且不阻断主链路

---

## Phase 5: User Story 3 - 全租户 POLL 覆盖 (Priority: P2)

**Goal**: 轮询遍历所有租户的启用 POLL 规则，逐规则按其租户评估，guard 去重不变

**Independent Test**: 租户 A、B 各一条越阈规则 → 都触发，事件 tenantId 各自正确；多 master 同槽仅一认领

### Tests for User Story 3 ⚠️（先写先失败）

- [X] T016 [P] [US3] 集成测试 `backend/dataweave-alert/src/test/java/com/dataweave/alert/application/MetricPollMultiTenantTest.java`：租户 A、B 各建越阈 POLL 规则 → `evaluate()` → 断言两条都触发、AlertEvent.tenantId 分别为 A/B；同规则同 `poll_slot` 第二次评估被 `alert_poll_fire` guard 跳过（不重复）

### Implementation for User Story 3

- [X] T017 [US3] 在 `backend/dataweave-alert/src/main/java/com/dataweave/alert/domain/repository/AlertRuleRepository.java` 声明 `findByEvalModeAndEnabled(String evalMode, int enabled)`（跨租户）
- [X] T018 [US3] 在 `backend/dataweave-alert/src/main/java/com/dataweave/alert/infrastructure/jdbc/AlertRuleJdbcRepository.java` 实现该方法（不带 tenant 过滤，按 eval_mode/enabled 查全部，H2/PG 双方言安全）
- [X] T019 [US3] 改 `MetricPollEvaluator.evaluate()`：遍历 `findByEvalModeAndEnabled("POLL", 1)`，逐规则 `evaluateRule(rule)`，移除 `TODO` 与 `tenantId=1` 硬编码；事件/指纹/路由均以 `rule.getTenantId()` 为准

**Checkpoint**: 多租户 POLL 全覆盖，HA 去重不变

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 回归保证与端到端验证

- [X] T020 [P] 回归测试：既有 Webhook/钉钉/企微/飞书 分发与 SIGNAL 类规则（SLA/质量）触发路径不变（新增或确认既有用例覆盖）
- [X] T021 [P] i18n 核查：邮件正文/日志中面向用户文案按归属（数据术语 severity/metric 保留英文；如有 toast/UI 文案走前端 i18n）
- [X] T022 前端核实（按 quickstart §0）：**发现 `alerts-view.tsx`（219 行）为纯展示**——只列规则（signalSource/evalMode/severity），无任何 POLL 规则（comparator/threshold/metric_key）或 EMAIL 收件人的创建/编辑 UI（021 即如此）。补完整规则/通道编辑表单属「大前端」，**026 spec 明确划在范围外**（只做后端收口，前端仅「字段缺失时小补」）→ 现状无字段可补，**零改动**；规则/通道经 API/seed 配置。**记为后续债**：alert 规则/通道前端 CRUD（可并入 027 事件中心或独立特性）。
- [X] T023 quickstart 场景已实现为自动化测试并全绿（12 通过）：US1=`MetricPollEvaluatorRealValueTest`(5)、US2=`EmailDispatcherTest`(4, GreenMail 真捕获)、US3=`MetricPollMultiTenantTest`(1)+`AlertRuleCrossTenantQueryTest`(2)；`./mvnw -pl dataweave-alert -am test` → BUILD SUCCESS。**未做**：起整服务器手动端到端跑（契约/降级已由测试覆盖）。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，先行
- **Foundational (Phase 2, T004)**: 依赖 Setup；阻塞 US2
- **US1 (Phase 3)**: 依赖 Setup（不依赖 T004）
- **US2 (Phase 4)**: 依赖 Setup + T004
- **US3 (Phase 5)**: 依赖 US1（`evaluate()` 遍历调用 `evaluateRule`→`fetchMetricValue`，需 US1 的真实值实现先就位）
- **Polish (Phase 6)**: 依赖所有目标 story 完成

### User Story Dependencies

- **US1 (P1)**: 基础，最先；可独立测（单租户单规则真实值）
- **US2 (P1)**: 与 US1 独立（邮件通道），仅依赖 T004；可并行于 US1
- **US3 (P2)**: 依赖 US1（复用真实值评估）；US3 把单租户扩为全租户

### Within Each User Story

- 测试先写并失败 → 实现 → 集成
- US1：注入 → fetch 实现 → 降级 → 去桩
- US2：注入 → 解析配置/未配置 → 发送 → 审计落库
- US3：repo 声明 → JDBC 实现 → evaluate 遍历

### Parallel Opportunities

- T002/T003 并行；各 story 的测试任务（T005/T010/T011/T016）标 [P] 可并行编写
- US1 与 US2 可由不同人并行（US2 仅需 T004）
- T020/T021 并行

---

## Parallel Example: US1 + US2 并行

```bash
# US1（开发者 A）：MetricPollEvaluator 真实值
# US2（开发者 B，T004 完成后）：EmailDispatcher 真发
# 两者文件不重叠（MetricPollEvaluator vs EmailDispatcher），可并行
```

---

## Implementation Strategy

### MVP First（US1 + US2，两条 P1）

1. Phase 1 Setup → Phase 2 T004
2. US1（真实值）+ US2（邮件）→ 这是「告警名副其实」的 MVP：指标越界能触发、且能真发邮件
3. **STOP & VALIDATE**：quickstart §1+§2 独立验证
4. 再做 US3（全租户）扩展覆盖面

### Incremental Delivery

1. Setup + T004 → 地基
2. US1 → 单租户真实触发（可演示）
3. US2 → 邮件真送达（可演示）
4. US3 → 全租户覆盖
5. Polish → 回归 + quickstart 全场景

---

## Notes

- [P] = 不同文件、无依赖；[Story] 标签用于追溯
- 全程复用 021 内核（分发/重试/限流/审计）与 master `MetricService`（只读），无新表、schema_version 不升
- 每完成一个 task 或逻辑组提交
- H2/PG 双方言：T018 的 SQL 注意 CONCAT/IF NOT EXISTS 等（见 CLAUDE.md 记忆 h2-pg-sql-dialect-traps）
- H2 测试隔离：用独立库名避免串台（见记忆 h2-shared-mem-db-test-pollution）
