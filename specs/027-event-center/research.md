# Research: 统一数据健康事件中心

## D1. 统一信号契约 + 修复孤儿质量信号（US2）

- **现状（实测）**：存在两个 `AlertSignal`——
  - `domain.signal.AlertSignal`（class）：**活跃总线**。emit=`SlaService`/`LeaseReaper`/`InstanceStateMachine`/`AssetSubscriptionService`；consume=`AlertSignalListener`/`AlertEvaluator`。
  - `quality.domain.AlertSignal`（record）：**仅** `QualitySignalEmitter` emit，**无人 consume**（`AlertSignalListener` 只 `@EventListener(domain.signal.AlertSignal)`）。→ **质量断言失败信号是孤儿，从未到告警引擎**（022 自建、合并期「平凡去重」从未发生）。
- **Decision**：统一为单一 `domain.signal.AlertSignal`。`QualitySignalEmitter` 改 publish `domain.signal.AlertSignal`（`Type.QUALITY_FAILED` 已存在），删除 `quality.domain.AlertSignal`。
- **Rationale**：消重 + **顺带修真 bug**（质量信号从此真正到达告警引擎 + 事件中心）。最小改动、零新概念。
- **Alternatives**：保留两类各自适配（拒绝：永久双契约、孤儿不修）；新建第三个统一类（拒绝：又多一个，活跃总线已是天然归一点）。

## D2. 事件持久化：旁路第二监听器（US1）

- **Decision**：新增 `HealthEventRecorder`（`@EventListener` on `domain.signal.AlertSignal`），与 `AlertSignalListener`（告警分发）**并行独立**，把每条信号落 `health_event` 表。即使无任何告警规则匹配，事件仍被记录。
- **Rationale**：旁路 = 对告警分发零干扰（FR-007）；Spring 多 `@EventListener` 监听同一事件天然支持。`alert_event`（rule_id NOT NULL、规则绑定）不适合承载「规则无关的全量健康事件」，故新表。
- **去重（FR-006）**：按 `(tenant_id, type, fingerprint)` + 时间窗合并，`count++`/`last_occurred_at` 刷新（镜像 `alert_event` 的 count/last_fired 范式）。
- **Alternatives**：让 `AlertSignalListener` 顺带落库（拒绝：耦合告警与观测两职责，违 FR-007 隔离）；复用 `alert_event`（拒绝：rule_id 必填、语义是「已触发告警」非「健康事件」）。

## D3. Schema 演进

- **Decision**：新增 `health_event`、`event_subscription` 两表，`schema_version` 0.3.0 → **0.4.0**（库内 INSERT + 文件头 + 项目版本三处恒等，SemVer MINOR=加表）。
- **Rationale**：加表是向后兼容的 MINOR 演进；遵 CLAUDE.md「改表必升版本」。

## D4. 订阅与分发（US3）

- **Decision**：`event_subscription`（类型 + severity 阈值 + 资产/租户维度 + 目标通道）。`HealthEventRecorder` 持久化后匹配订阅，命中经 026 `AlertDispatchService` 分发到通道（复用真实邮件/Webhook + `AlertNotification` 审计）。
- **Rationale**：复用 026 分发即得真实触达 + 审计 + 重试/限流，零重建（宪法 V）。分发失败不阻断持久化（FR-009，try-catch 吞）。
- **依赖**：必须有 026 的真实通道分发（本 worktree 已合入 026）。

## D5. 查询 API 与前端视图（US1）

- **Decision**：`EventCenterController` 提供按 type/severity/asset/时间范围分页查询（租户隔离）；前端新增 `event-center` Workspace 视图（时间线 + 筛选 + 关联对象深链）。
- **Rationale**：复用既有视图框架（ViewType 注册 + DataTable/筛选范式）与 i18n 三类归属。深链复用既有任务/指标/血缘视图路由。

## D6. 024 血缘 CONFLICT —— 预留接缝

- **Decision**：`AlertSignal.Type` 预留/新增 `LINEAGE_CONFLICT`（或复用既有扩展点），024 落地后 emit 即入事件流；本期不强依赖 024，不 emit。
- **Rationale**：FR-001 要求预留接缝而非实现 024。

## 待澄清残留

无阻断性未知。关联对象深链的目标视图路由沿用既有 `redirect("/?open=")` 范式；severity 维度复用既有 severity 词表。
