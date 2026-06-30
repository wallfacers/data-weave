# Quickstart: 验证统一数据健康事件中心

## 1. 统一信号契约 + 修孤儿质量信号（US2）

- 触发一次质量断言 FAIL（`QualitySignalEmitter`）。
- 预期：信号以 `domain.signal.AlertSignal`（QUALITY_FAILED）发出 → 既被 `AlertSignalListener`（告警）消费、也被 `HealthEventRecorder`（事件中心）消费。
- 回归：`quality.domain.AlertSignal` 已删除，编译全绿；SLA/任务信号路径不变。

## 2. 事件持久化 + 查询（US1）

- 触发一次 SLA_BREACH + 一次 QUALITY_FAILED。
- 断言 `health_event` 出现两行，`GET /api/events` 倒序返回；按 `type=QUALITY_FAILED` 筛选只返回质量事件。
- 同根因短时间重复 → `count` 递增、不刷屏（去重）。
- 点事件关联对象 → 深链跳对应视图。

## 3. 订阅触达（US3）

- 建订阅 `{ typeFilter: QUALITY_FAILED, minSeverity: HIGH, channelId: <EMAIL 通道> }`。
- 触发匹配事件 → 经 026 通道分发，`AlertNotification` 记一条；触发不匹配事件 → 不分发。
- 通道分发失败 → 事件仍持久化，失败有审计。

## 4. 前端

- Workspace 打开「事件中心」tab：时间线 + 类型/severity/资产筛选 + 深链。
- i18n：zh-CN/en-US 两 bundle 同键；浏览器验证渲染。

## 运行

```bash
cd backend && ./mvnw -q -pl dataweave-alert -am test    # 含事件持久化/订阅/统一信号 用例
cd frontend && pnpm typecheck && pnpm test
# WSL2 长跑用 setsid 脱离（见 CLAUDE.md）；JDK25 须 export JAVA_HOME
```
