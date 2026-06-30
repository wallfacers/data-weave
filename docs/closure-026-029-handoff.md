# 收口期 026–029 交接（跨机续接用）

> 「现有功能完善」收口期：把 021/022/023 留下的「宣称落地但半成品」补成真闭环。
> 四个独立特性，各自一个分支。本文件随 git 走，供另一台机器拉取后续接。

## 状态总览

| # | 分支 | 状态 | 说明 |
|---|------|------|------|
| **026** | `026-alert-engine-closure` | ✅ **完成 + 测试全绿 + 已 push** | 告警引擎收口 |
| **027** | `027-event-center` | ✅ **后端+前端完成 + 测试全绿 + 已 push**（含 026 合并） | 统一数据健康事件中心 |
| **028** | `028-distributed-validation` | ⏳ 仅 spec/plan/tasks（未实现）| distributed 端到端真验证——**需 docker 多进程，本机 daemon 未运行被卡** |
| **029** | `029-asset-frontend-closure` | ⏳ 仅 spec（未 plan/tasks/实现）| 资产/指标市场前端闭环 |

提交点：026=`655799d`；027=`1826f0f`(后端)+`1857315`(前端)。`027-event-center` 分支内已 merge 026。

## 026 告警引擎收口（done）

收口 021 三处「假落地」桩：① `MetricPollEvaluator.fetchMetricValue` 恒 0.0 → 接 master `MetricService.findLatestByCode+evaluate` 真值（缺值/非数值跳过+WARN）；② `EmailDispatcher` stub → `JavaMailSender` 真发（`DispatchResult.notConfigured` 三态，`AlertDispatchService.sendWithRetry` 短路标 SKIPPED）；③ 只查 tenant 1 → `AlertRuleRepository.findByEvalModeAndEnabled` 跨租户。12 测试全绿（GreenMail 真捕获邮件 + Mockito + H2 独立库）。无新表。
- 范围外（已记）：alert 规则/通道前端 CRUD —— `alerts-view` 纯展示，补编辑表单属大前端，026 spec 划在范围外。

## 027 统一事件中心（done）

- **US2 统一信号契约 + 修真 bug**：`quality.domain.AlertSignal` 是**孤儿**（只 `QualitySignalEmitter` 发、无人 `@EventListener`，`AlertSignalListener` 只听 `domain.signal.AlertSignal`）→ 质量断言失败信号**从未到达告警引擎**。已迁 `QualitySignalEmitter` 用 `domain.signal.AlertSignal(QUALITY_FAILED)` + 删孤儿类。
- **US1 持久化+查询**：新增 `health_event` 表（规则无关，去重 upsert）+ `HealthEventRecorder`（第二 `@EventListener`，旁路、整体 try-catch 不影响告警分发）+ `GET /api/events`（租户隔离+过滤+分页）。
- **US3 订阅+分发**：新增 `event_subscription` 表 + `EventCenterService.matchAndDispatch` 经 026 `AlertDispatchService.dispatchToChannel` 分发（失败不阻断）+ 订阅端点。
- **前端**：`event-center-view`（时间线+筛选+深链+订阅 UI）、`lib/event-center-api`、注册 `event-center` ViewType、i18n `eventCenter` 命名空间。
- **schema_version 0.3.0 → 0.4.0**（schema.sql 头/库内 INSERT/5 个 pom 三处恒等）。
- 测试：后端 9 新增全绿（QualitySignalUnifiedTest / HealthEventRecorderTest / EventCenterServiceQueryTest / EventSubscriptionDispatchTest）；前端 typecheck + i18n:lint 通过。
- **待验证**：浏览器验证视图渲染 + 全栈启动（schema 0.4.0 boot）；HTTP WebTestClient 测试留 api 模块。

## 回家后续接清单

1. **C 合并 main**：main 已含 024/025，但**它们是 neo4j-only、schema_version 仍 0.3.0**，与 027 的 0.4.0 bump **不冲突**，合并较干净。026/027 都测过，可并入。先合 026 再合 027（027 含 026）。
2. **028**：等 docker daemon 起来，按 `specs/028-distributed-validation/` 的 spec 做 plan→tasks→implement，核心是多进程 PG+Redis+MinIO 端到端真跑 + 修 `FleetService` 注释暴露的「SlotManager 0 槽致 distributed worker 永收不到下发」真坑。
3. **029**：按 `specs/029-asset-frontend-closure/spec.md`，第一步 discovery 核实 023 前端已覆盖/缺失，只补真缺的。
4. **027 浏览器验证**：起后端（h2 profile）+ 前端，开「事件中心」tab 验证渲染。

## 踩过的坑（务必复用）

- **JDK**：非交互 shell（setsid 脱离）默认 JDK21，编译报「release version 25 not supported」→ 须 `export JAVA_HOME=<jdk-25 路径>`（本机在 `~/.local/opt/jdk-25.0.3+9`，家里机器路径自查）。
- **版本 bump 后 m2 解析 403**：027 把 5 个 pom bump 到 0.4.0-SNAPSHOT 后，`-pl dataweave-alert -am` 因 build-cache 没真装 master:0.4.0 而远程 403 → 先 `./mvnw -pl dataweave-master -am install -DskipTests -Dmaven.build.cache.enabled=false` 装 master，再 `-pl dataweave-alert test -Dmaven.build.cache.enabled=false`。
- **`AlertEvaluator` 阈值字段**是 `comparator`（GT/GTE/LT/LTE/EQ/NE）+`threshold`，非 operator/`>`。
- **GreenMail「smtp 不可达」测试**须设 `mail.smtp.connectiontimeout`，否则默认无超时卡 ~135s。
- **WSL2 长跑**：build/test 用 `setsid bash -c '... >log 2>&1; echo $? >exit' </dev/null >/dev/null 2>&1 & disown` 脱离，否则 surefire 子进程持有 stdout 管道致工具挂到超时。
- **worktree 隔离**：每个特性独立 worktree，`.specify/feature.json` 是 per-worktree SDD 指针，**不提交**（避免合并污染 main 指针）。
