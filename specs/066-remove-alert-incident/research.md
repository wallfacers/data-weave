# Research: 移除人工告警/事件/质量/工单体系

**Date**: 2026-07-12 | **Feature**: 066-remove-alert-incident

本特性为删除清场，无新增技术选型。research 聚焦删除过程中的 7 个决策点。

## R1: schema 版本策略

- **Decision**: 升 `0.18.0`，标注「066 移除告警/质量体系——删 alert_* 7 表」。
- **Rationale**: `0.17.0` 已被 065（移除监督席，删 incident/event/health 4 表）占用并提交（13557f8）。本特性再删 alert_* 7 表是新结构变更，按项目规则「any table change bumps the version」升 0.18.0。quality_* 表由并行工作删除，并入同一版本标注。
- **Alternatives**: 并入 0.17.0 改标注——拒绝，0.17.0 已提交落袋，改已发布版本标注不如新升干净。

## R2: AlertSignal 发布点 eventPublisher 字段去留

- **Decision**:
  - `InstanceStateMachine`：**保留** eventPublisher 字段。它还用于 `publishTaskState`/`publishWorkflowState`（行 360/380）发调度状态事件，喂 DAG SSE 运行态观测（`/api/ops/workflow-instances/{id}/events/stream`）。删字段会损伤运行态观测，违反 Constitution IV 内核第 3 条。只删 3 处 `publishEvent(new AlertSignal(...))`（行 414/441/467）+ 2 个 helper（`publishAlertSignalForTask`/`ForWorkflow`，行 417/474）+ import。
  - `LeaseReaper` / `SlaService` / `StuckInstanceSweeper` / `TimeoutSweeper`：**删** eventPublisher 字段 + 构造参数 + import。grep 确认这 4 个类的 eventPublisher 仅出现在 AlertSignal publish 处。
- **Rationale**: 删字段避免 Spring 装配多余 bean；但 InstanceStateMachine 的 eventPublisher 是调度状态发布核心，不可删。
- **Verification**: `grep -n eventPublisher` 4 个类均仅命中 AlertSignal publish 行。

## R3: stuck-wait-alert-ms 配置去留

- **Decision**: 配置项**保留**，改注释（「无健康节点等待告警阈值」→「卡住检测阈值」）。
- **Rationale**: `StuckInstanceSweeper` 行 105 `threshold = now.minusNanos(stuckWaitAlertMs * 1_000_000L)` 是**卡住检测逻辑**，非仅发信号。StuckInstanceSweeper 核心功能=检测卡住实例 + 恢复唤醒（060 FR-015），删信号保留检测恢复。`stuckWaitAlertMs` 是检测阈值，保留。行 130 `ctx.put("stuckWaitAlertMs")` 若仅服务于信号 payload 则一并删。
- **Alternatives**: 删配置——拒绝，破坏卡住检测恢复功能。

## R4: alert webhook 配置去留

- **Decision**: `application.yml` 的 `default-response-timeout-ms`（行 103）**保留**（通用 WebClient 超时，alert webhook 仅举例），改注释去掉 alert 举例。`ops-messages.properties` 的 `ops.alert.*` 模板**删**。
- **Rationale**: 通用配置不为告警独占；ops.alert.* 模板是告警专属，删。

## R5: quality 收尾点

- **Decision**: 并行工作已删 quality Java/schema/前端，现存 java 对 quality 包引用=零。收尾= `data.sql` 删 2 条 `QUALITY_RULE_WRITE`/`QUALITY_RUN` policy_rule + 全量编译验证。
- **Verification**: `grep -rn "com.dataweave.master.quality" backend --include='*.java'` 零命中。

## R6: 提交结构

- **Decision**: 4 个聚焦提交：
  1. quality 收尾（data.sql 2 policy_rule + 编译验证）
  2. alert 整模块删除（pom 声明 + 模块 src + schema 7 表 + data.sql 2 policy_rule + 前端 alerts-view + api 测试/配置/ops-messages）
  3. AlertSignal 信号桥删除（master 5 类 publish + AlertSignal 类 + StuckInstanceSweeperTest/QualitySignalUnifiedTest 改删 + AlertSeamIT 删）
  4. incident 残留清理（master messages*.properties 4 条 incident.* key）
- **Rationale**: 每提交聚焦可独立 review/回滚；按依赖顺序（quality→alert→信号桥→残留）保证每步编译可过。

## R7: 验证策略

- **Decision**:
  - 全模块编译：`./dev-install.sh`（content-hash 缓存，跳测跳 fat jar）
  - 各模块 test：`./mvnw -pl dataweave-master,dataweave-api,dataweave-worker test`（注意 maven-build-cache 假绿陷阱——加 `clean -Dmaven.build.cache.enabled=false` 真验证）
  - 前端：`pnpm typecheck` + `pnpm test`
  - 调度并发核验：因动 `InstanceStateMachine`，按 CLAUDE.md 硬规则跑 every-minute cron 端到端，确认 `started_at − created_at ≈ 0`、根节点 `attempt=1`、零「跳过下发/中止执行」stragglers
  - schema 启动：H2（profile=h2）+ PG（docker compose）
- **Rationale**: 守调度死锁四不变量 + WSL2 长命令 setsid 脱离 + build-cache 假绿防线。
