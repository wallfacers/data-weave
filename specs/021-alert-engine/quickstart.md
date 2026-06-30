# Quickstart 验证指南: 告警引擎

证明告警引擎端到端工作。前置见各步。详细字段/契约见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)。

## 前置

- 后端起 H2 profile(零外部依赖):`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2`(注意:改 alert/master 后**先 `./dev-install.sh` 装进 m2** 再跑 api,见 [[track1-spark-and-runtime-parity]] 踩坑;长跑用 `setsid` 脱离)
- 所有 curl 带 `Authorization: Bearer <token>`(`JwtTestSupport` 或 `DW_TOKEN`)。
- 验证后端改动后:`cd backend && ./mvnw -q -pl dataweave-alert,dataweave-master compile`(零错)。

## 场景 1:事件驱动告警(任务失败 → 触发 → 分发 → 审计)

1. 建 WEBHOOK 通道:`POST /api/alert/channels`(type=WEBHOOK,config 指向本地 mock 接收器)。
2. 建规则:`POST /api/alert/rules`(signal_source=TASK_INSTANCE,eval_mode=EVENT,condition `{"event_type":"TASK_FAILED"}`,severity=CRITICAL,dedup_key_template=`task:{taskId}`)。
3. 建路由:CRITICAL→该通道。
4. 造一个任务实例 FAILED(经 TEST 跑一个必失败任务,或集成测试直发 `AlertSignal(TASK_FAILED)`)。
5. **断言**:`GET /api/alert/events?state=FIRING` 出现一条;`GET /api/alert/events/{id}/notifications` 有一条 `SENT`;mock 接收器收到 webhook。

## 场景 2:去重 + for_duration 去抖(metric 轮询)

1. 建 metric 规则(eval_mode=POLL,eval_interval_sec=30,condition `{"metric_key":"...","comparator":"GT","threshold":X}`,for_duration=3,suppress_window_sec=600)。
2. 让指标连续 2 周期越界 → **断言**不触发(未达 for_duration);第 3 周期越界 → 触发一条 FIRING。
3. 抑制窗口内再越界 → **断言**同 fingerprint `count` 递增、**无新通知**(`notifications` 条数不增)。

## 场景 3:自动恢复

1. 承场景 2 的 FIRING 告警,让指标回落到阈值内。
2. **断言**:事件转 `RESOLVED`、`resolved_at` 置位,产生恰一条恢复通知。

## 场景 4:静默窗口

1. `POST /api/alert/silences`(match 命中场景 1 规则的 label,窗口覆盖当前)。
2. 再造同类失败 → **断言**事件标 `SUPPRESSED`、**无投递**,但事件记录在。

## 场景 5:HA 单点(distributed)

1. distributed 模式起两 master。
2. 同一 metric 规则到评估周期 → **断言**只产生一条告警 + 一组通知(`alert_poll_fire` UNIQUE 冲突让一个 master 跳过)。
   - 集成测试可模拟两线程并发 INSERT 同 `(rule_id, poll_slot)`,断言只一个成功评估。

## 场景 6:写闸门(test-send L2)

1. 以 agent 身份 `POST /api/alert/channels/{id}/test`。
2. **断言**:返回 `outcome=PENDING_APPROVAL`(未真发),`agent_action` 留审计一条;审批后才 EXECUTED。
3. 反证:规则写以 agent 身份提交 → 经 `GatedActionService`,`policy_rules` 命中 `ALERT_RULE_WRITE`=L1 → EXECUTED + 审计。

## 场景 7:跨特性接缝(QUALITY_FAILED 预留)

1. 建一条 signal_source=QUALITY_FAILED 的规则。
2. 集成测试直发 `AlertSignal(QUALITY_FAILED)` → **断言**触发告警 + 分发。证明 022 落地后消费路径已通。

## 场景 8:租户隔离

1. 租户 A 的规则 + 租户 B 的失败信号 → **断言** A 规则不触发、B 不可见 A 的事件。

## 前端验证

- `cd frontend && pnpm typecheck`(零错)+ 双语 key 等集(CI 校验)。
- 浏览器开 alerts 视图:规则/活跃告警/历史/通道/静默分区可见;ACK 一条 FIRING → 状态即时转 ACKED;设静默 → 后续匹配告警 SUPPRESSED。
- SSE/实时若用:走直连后端避免 Next 代理缓冲(见 [[next-rewrite-proxy-buffers-sse]])。

## 测试门禁(完成标准)

- 后端:`dataweave-alert` + `dataweave-master` compile 0 错;单元(评估/去重/状态机/分发重试)+ 集成(WebTestClient 带 JWT,八场景)全绿;H2 与 PG 双库 DDL 通过;`schema_version`=0.1.0 三处恒等。
- 前端:typecheck 0 错;双语等集;浏览器验证。
- 闸门:agent 写 + test-send 经 PolicyEngine,零旁路(反证测试)。
