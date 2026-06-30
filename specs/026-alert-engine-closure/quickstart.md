# Quickstart: 验证告警引擎收口

端到端验证「POLL 真触发 / 邮件真投递 / 全租户覆盖」。前置：后端可跑（H2 零依赖即可），测试用 GreenMail 捕获 SMTP。

## 0. 前置核实（前端是否需小补）

打开 Workspace 的告警视图（`alerts-view`），确认能否：
- 创建 `eval_mode=POLL` 规则并填 `metric_key`/`operator`/`threshold`；
- 创建 `EMAIL` 通道并填收件人。
> 若任一字段前端缺失 → 落一个「前端小补」任务；若已具备 → 前端零改动。

## 1. POLL 指标阈值告警在真实指标上触发（US1）

**场景**：指标 `task.fail_rate` 真实值越过阈值，POLL 周期内产生告警。

步骤：
1. 准备一个 master 已有的 `AtomicMetric`（code=`task.fail_rate`），令其最新值 = 8。
2. 建 POLL 规则：`condition_json = {"metric_key":"task.fail_rate","operator":">","threshold":5}`，enabled=1。
3. 等待一个 `alert.poll.interval-ms` 周期。

预期：
- 产生一条 `AlertEvent`，`value=8`、severity=规则 severity；
- 命中路由的通道收到分发；
- 把指标值改为 2 后，下一周期**不**产生告警（零误报）。
- 把 `metric_key` 改成不存在的 code → 跳过 + WARN，无误报。

## 2. 邮件真投递（US2，GreenMail 断言）

步骤：
1. 测试内起 GreenMail（SMTP 监听本地端口），`spring.mail.host/port` 指向它。
2. 建 EMAIL 通道：`config_json = {"recipients":["ops@example.com"]}`，绑定到会触发的规则路由。
3. 触发一次告警（复用步骤 1）。

预期：
- GreenMail 收到 1 封邮件，收件人 `ops@example.com`，正文含规则名/severity/指标值/时间；
- `AlertNotification` 记一条成功；
- 关掉 GreenMail（SMTP 不可达）再触发 → `AlertNotification` 记 failed + 原因，**其它通道（Webhook）照常**；
- 清空 `spring.mail.host`（未配置）再触发 EMAIL → `notConfigured`，WARN，不抛错、不假成功。

## 3. 全租户 POLL 覆盖（US3）

步骤：
1. 为租户 A、B 各建一条会越界的 POLL 规则。
2. 等一个轮询周期。

预期：
- 两条规则都触发，`AlertEvent.tenant_id` 分别为 A、B；
- 多 master 部署下同一规则同一槽仅一个 master 认领（`alert_poll_fire` 冲突跳过），不重复告警。

## 4. 回归（FR-010）

- 既有 Webhook/钉钉/企微/飞书分发不变；
- SIGNAL 类规则（SLA/质量信号触发）路径不变；
- 既有 `AlertDispatchService` 重试/限流/审计行为不变。

## 运行

```bash
cd backend && ./mvnw -q -pl dataweave-alert -am test    # 含 GreenMail / POLL / 全租户 用例
# WSL2 长跑请按 CLAUDE.md 用 setsid 脱离：
# setsid bash -c 'cd backend && ./mvnw -pl dataweave-alert -am test >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
```
