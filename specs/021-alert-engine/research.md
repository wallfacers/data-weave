# Phase 0 Research: 告警引擎

技术决策与依据。所有 NEEDS CLARIFICATION 已解。

## D1. 信号注入方式:应用内 ApplicationEvent(非 Redis per-instance 频道)

- **Decision**:在 master 三个现有发射点 publish `AlertSignal`(Spring `ApplicationEvent`):
  - `InstanceStateMachine.casTaskTerminal(...)`(file:64-73)推进 FAILED 后 → `TASK_FAILED`/`TASK_TIMEOUT`(按 failureReason 区分)
  - `SlaService.recordCompletion(...)`(file:58-116)算出 `breached=1` 时 → `SLA_BREACH`
  - `LeaseReaper` 心跳过期标 FAILED 处 → `NODE_OFFLINE`(failureReason 含"心跳超期")
  - `InstanceStateMachine.casWorkflowState(...)`(file:107-113)→ `WORKFLOW` 状态信号
  alert 侧 `AlertSignalListener` 用 `@EventListener` 消费。
- **Rationale**:现有 `EventBus.publish("dw:evt:{wiId}", json)` 是 **per-workflow-instance** 频道(给前端 SSE 节点变色用),消费者须知 wiId,不适合"全局订阅所有失败"。ApplicationEvent 在同 JVM 内可靠、解耦、无序列化开销;distributed 下每个 master 处理自己 CAS 的转换并本地 publish,天然按归属分片。
- **依赖方向(已拍板)**:`AlertSignal`(POJO,Spring `ApplicationEvent`)**放在 `dataweave-master`**(`com.dataweave.master.application.signal` 或 `domain/event`),master 用框架 `ApplicationEventPublisher.publishEvent(signal)` 发射(编译期不知消费者)。**`dataweave-alert` 新建为模块并依赖 `dataweave-master`**(模块图:api→master+worker+alert、worker→master、alert→master;master 不依赖任何业务模块),alert 侧 `@EventListener` 消费。**守住 domain←application←infra←interfaces 且无模块反向依赖**。
- **模块创建**:`dataweave-alert` 当前**无 pom,仅陈旧 jar = 非真模块**。本特性须新建 `backend/dataweave-alert/pom.xml`(artifactId `dataweave-alert`,依赖 `dataweave-master`)+ 在 `dataweave-api/pom.xml` 加 `dataweave-alert` 依赖(api 装配 alert 的 controller/handler/listener bean)。父 `dataweave-backend` 的 `<modules>` 加 alert。
- **Alternatives**:① 复用 Redis dw:evt 频道——否决(per-instance,无法全局订阅,且耦合前端 SSE 语义);② alert 轮询 task_instance 表找 FAILED——否决(延迟高、扫表浪费,事件驱动更准)。

## D2. metric 阈值评估 + HA 单点:复用 cron_fire UNIQUE 冲突范式

- **Decision**:`MetricPollEvaluator` 定时(可配 `eval_interval`)对 `eval_mode=POLL` 规则评估。distributed 下防多 master 重复:新增 `alert_poll_fire` guard 表,复合唯一键 `(rule_id, poll_slot)`(`poll_slot` = 评估周期对齐时间戳);评估前 `INSERT` guard 行,捕获唯一约束冲突(`DataIntegrityViolationException`)= 别的 master 已认领本轮 → 本 master 跳过。镜像 `DefaultTriggerEngine.fire()`(file:218-225)+ `cron_fire`(schema.sql)做法。
- **Rationale**:调度内核已用此范式做 cron 触发防重,经生产验证;不引入新分布式锁(Redis 锁/ZK),复用既有不变量。
- **方言**:`INSERT` 冲突捕获在 PG/H2 均工作(均支持 UNIQUE 约束 + 抛 `DataIntegrityViolationException`);**不用** MySQL 的 `INSERT IGNORE/ON DUPLICATE KEY`(PG/H2 无该语法)。
- **for_duration 去抖**:连续 N 个评估周期越界才 FIRING;状态存 `alert_event` 的 `count`/临时 breach streak(评估器内存 + guard 表回溯),跨周期判定。
- **Alternatives**:`SELECT ... FOR UPDATE SKIP LOCKED` claim 规则——可用但 metric 评估非"抢任务"语义,guard 表更轻;否决 leader 选举(过重)。

## D3. 告警事件状态机与去重/抑制/自动恢复

- **Decision**:`AlertEvent.state ∈ {FIRING, RESOLVED, ACKED, SUPPRESSED}`。转换:
  - 信号满足 + 无活跃同 fingerprint → 新建 `FIRING` + 分发
  - 抑制窗口内同 fingerprint 再满足 → `count++`、`last_fired_at` 更新,**不分发**
  - 条件清除(规则 `auto_resolve=true`)→ `FIRING→RESOLVED` + 一次恢复分发
  - 人工 ACK → `FIRING→ACKED`(抑制窗口内不再分发)
  - 命中生效 `alert_silence`(label 匹配)→ 标 `SUPPRESSED` 不分发(优先级最高)
  - **fingerprint** = hash(rule_id + dedup_key_template 渲染值[如 taskId/metric_key]),决定"同一问题"。
- **Rationale**:Alertmanager 成熟语义(去重/抑制/恢复/静默)是区分企业级与 demo 的关键;状态机幂等(FR-003)避免重复转换。
- **不新增调度状态机状态**:alert 自有状态机,与 `InstanceStateMachine`(task/workflow 状态)解耦,不侵入调度内核(守 V)。

## D4. 通道抽象与分发可靠性

- **Decision**:`ChannelDispatcher` 策略接口 `dispatch(AlertEvent, AlertChannel) → DispatchResult`。实现:`EmailDispatcher`、`WebhookDispatcher`;钉钉/企微/飞书为 **WebhookDispatcher 子类型**(各自机器人 URL + 签名规则),`alert_channel.type` 区分但共享 webhook 投递骨架。分发用现有自建 `WebClient` @Bean(`WebClientConfig`)。失败指数退避重试至上限,每次投递写 `alert_notification`(SENT/FAILED/RETRYING + attempts + error + response_digest)。每通道 `rate_limit` 令牌桶限流。
- **Rationale**:IM 统一为 webhook 子类降实现量(spec Assumptions);投递审计是合规要求;退避避免雪崩。
- **密钥**:`alert_channel.config_json` 存密钥引用/脱敏;读出 API 脱敏(FR-006)。真实密钥管理以现有 `datasources` 凭据范式为基线,不引新 KMS。
- **Alternatives**:每 IM 独立 dispatcher——冗余,否决;同步阻塞分发——否决(阻塞评估,用异步 + 重试队列)。

## D5. 写闸门接入(rule/channel/silence 写 + test-send)

- **Decision**:agent 发起的告警写与 test-send 经 `ActionRequest → GatedActionService.submit(req, locale) → PolicyEngine.decide`。**写执行用 SPI 委派(已拍板,避免 master→alert 反向依赖)**:在 master 的 `DefaultPlatformActionExecutor` 引入 `interface PlatformActionHandler { boolean supports(String actionType); ExecOutcome handle(AgentAction, Locale); }` + 注入 `List<PlatformActionHandler>`,内置 switch 未命中时**兜底遍历 handler 委派**;`dataweave-alert` 提供 `AlertActionHandler implements PlatformActionHandler`(处理 `ALERT_RULE_WRITE`/`ALERT_CHANNEL_WRITE`/`ALERT_TEST_SEND`),master 编译期只见接口,运行期由 api 装配 alert bean。这是对 executor 的**最小加性改造**(加一个兜底委派循环,内置 PROJECT_PUSH 等 case 不动)。22/023 在 master 内可直接加 case 或同样供 handler。`policy_rules` seed:`ALERT_RULE_WRITE`/`ALERT_CHANNEL_WRITE`=L1、`ALERT_TEST_SEND`=L2(真发通知副作用需审批)。`GateResult.Outcome ∈ {EXECUTED, PENDING_APPROVAL, REJECTED}`,调用方按 outcome 分流(不能只看 code===0,见 [[rollback-policy-default-l2]] 教训)。UI admin CRUD 走普通鉴权 API(非 agent)+ `agent_action` 审计。
- **Rationale**:原则 V「任何 agent 写必过闸门留审计,不因来自 AI 放行」;test-send 真发通知是副作用须 L2。
- **Alternatives**:告警写绕过闸门(纯 admin API)——否决(agent 也能写,须统一闸门)。

## D6. i18n:通知模板(后端文案,规则②)+ UI 文案(规则①)+ 错误(规则③)

- **Decision**:① 通知模板(发给收件人)→ 后端 `Messages.get(code, locale, args)`(file:Messages.java:28),按收件人 locale,MessageFormat `{0}`;code 如 `alert.notify.task_failed`/`alert.notify.sla_breached`。② 前端 alerts 视图静态文案 → next-intl `alerts` 命名空间,zh-CN/en-US key 等集。③ 错误 → `BizException(code, args)` + `GlobalExceptionHandler`,code `alert.<semantic>`。数据术语(SLA/webhook/metric/fingerprint)保留英文。
- **Rationale**:遵循 CLAUDE.md i18n 三规则归属。

## D7. schema_version 升版

- **Decision**:新增 6 业务表 + 1 guard 表 → `schema_version` `0.0.1 → 0.1.0`(MINOR:新增功能、向后兼容)。三处恒等:schema.sql 内 `INSERT INTO schema_version`(file:65-66)、schema.sql 文件头注释(file:2)、项目发布版本。`INSERT INTO schema_version VALUES ('0.1.0', ..., 'Alert engine tables (021)')`。
- **Rationale**:CLAUDE.md「改表必升版本,库内/文件头/项目版本三处恒等,SemVer」(017 治理)。
- **注意**:022/023 若并行,各自再 MINOR 升(0.2.0/0.3.0),合并时按落地顺序定版本,避免版本冲突——在 worktree 合并阶段对齐。

## D8. 可观测 AlertMetrics

- **Decision**:`AlertMetrics`(Micrometer,镜像 `SchedulerMetrics` 范式):`alert.eval.latency`(Timer)、`alert.fired.count`(Counter by severity)、`alert.notify.count`(by channel/status)、`alert.notify.retry.count`。经 `/actuator/prometheus` + `/api/ops/metrics`。
- **Rationale**:FR-013;指标定义不可变(改加 version,不 UPDATE)。
