# Research: Incident 域模型 + 监督席队列 (043)

Phase 0 产物。全部基于对现有代码的实证摸底（两轮只读调研：信号体系/闸门/前端 workspace 机制 + 接缝精查），无遗留 NEEDS CLARIFICATION。

## D1 模块归属：incident 域落 dataweave-master

- **Decision**: 领域对象 `domain/incident/`、应用服务 `application/incident/` 均在 dataweave-master；HTTP 面 `IncidentController` 在 dataweave-api interfaces。
- **Rationale**: incident 深度依赖 master 应用服务——`LineageQueryService`（爆炸半径）、`SlaService`（时间预算）、`GatedActionService`（处置闸门）、`InstanceStates`（信号语义）；AlertSignal 本身就定义在 `master.domain.signal`。alert 模块按 FR-014 零改动。
- **Alternatives considered**: ① 放 dataweave-alert（AlertSignalListener/HealthEventRecorder 所在地）——被拒：需要 alert 反向依赖 master 的应用服务，破坏依赖方向；② 新建 dataweave-incident 模块——被拒：多一个部署/依赖单元无收益，方向文档"第一版要小"。

## D2 信号消费：master 内第三个同步 @EventListener

- **Decision**: `IncidentSignalListener implements @EventListener(AlertSignal)`，与 `AlertSignalListener`/`HealthEventRecorder` 并列消费同一进程内事件；**整方法 try-catch 吞异常**；单信号处理只做轻量 DB 写（开单/附着），分诊计算（neo4j/SLA 查询）容忍在同步链内（毫秒级，队列量级低），若实测拖慢再异步化。
- **Rationale**: 实证——Spring 事件默认同步派发，listener 异常会冒泡回 `InstanceStateMachine`/`SlaService`/`LeaseReaper` 并中断后续 listener；两个现存消费者均整体吞异常（HealthEventRecorder 注释 FR-007），第三个必须同样隔离。零侵入：不改任何发布点。
- **Alternatives considered**: ① 消费 `health_event` 表（轮询）——被拒：引入延迟且把去重语义耦合到 health_event 的 fingerprint 上；② Redis EventBus 订阅——被拒：EventBus 是跨 master 唤醒/UI SSE 通道，消息面窄且非领域事件。

## D3 故障签名（未来经验库的 join key）

- **Decision**: `signature` 列格式：
  - TASK_FAILED/TASK_TIMEOUT → `T:{taskId}:{failureClass}`，failureClass = task_instance.failure_reason 归一（`TIMEOUT` / `EXIT_NONZERO` / `WORKER_RESTART` / `WORKER_LOST` / `UNKNOWN`）
  - SLA_BREACH → `WSLA:{workflowId}`
  - NODE_OFFLINE → `N:{nodeCode}:OFFLINE`
- **Rationale**: 与信号 context 实际携带字段对齐（TASK 信号带 taskId+failureReason；SLA 信号 fingerprintHint=workflowId；NODE 信号 fingerprintHint=nodeCode）；粒度 = "同对象同类故障"，即 FR-002 去重单位；日志指纹维度留给编队阶段追加（签名格式允许尾部扩段，不破坏既有键）。
- **Alternatives considered**: 复用 AlertSignal.fingerprintHint 原值——被拒：TASK 信号的 hint 只有 taskId 不含故障类别，会把 OOM 和代码 bug 合并成一张卡，污染未来经验库键。

## D4 去重与归并的并发安全：active_key 唯一约束

- **Decision**: incident 表加 `active_key VARCHAR` 列 + `UNIQUE(tenant_id, project_id, active_key)`：未关闭时 `active_key = signature`，转 CLOSED 时置 NULL（H2/PG 对 UNIQUE 中多 NULL 均不冲突）。写入顺序 = **附着优先**：先 `UPDATE ... count=count+1, last_seen_at=now WHERE active_key=? AND ...`（含 RESOLVED→OPEN 重开 CAS），影响行数 0 才 INSERT；INSERT 撞唯一键（多 master 竞态）→ 重试改走附着。
- **Rationale**: 多 master 对等部署下"同签名未关闭工单全局唯一"必须由 DB 约束兜底，不能靠 SELECT-then-INSERT；镜像项目现有防重范式（cron_fire 唯一键"谁先 INSERT 谁认领"）但不需要独立 guard 表。
- **归并规则（FR-003）**: 信号带 `workflowInstanceId` 时优先查同 `workflow_instance_id` 的未关闭工单并附着（TASK_FAILED 次生失败、SLA_BREACH 均归并入同实例首单）；查不到再走签名附着/开单。SLA_BREACH 先到、失败后到的时序：SLA 独立开单（ref=WORKFLOW），后续同实例 TASK_FAILED 附着入该单。
- **Alternatives considered**: 部分唯一索引（`WHERE state != 'CLOSED'`）——被拒：H2 不支持；独立 guard 表——被拒：多一张表 + 开/关单双写清理，active_key 一列达成同等强度。

## D5 生命周期与自动愈合

- **Decision**: 状态 `OPEN / MITIGATING / RESOLVED / SUPPRESSED / CLOSED`（VARCHAR(16)），一切推进乐观 CAS（`WHERE state=?`），复用调度内核不变量②。愈合信号源按工单来源分三路：
  1. **TASK 类** ← 既有 `TaskSucceededEvent(taskInstanceId, taskId, tenantId)`（InstanceStateMachine SUCCESS CAS 后发布，现成）：按 taskId 找未关闭 TASK 工单 → CAS 到 RESOLVED。
  2. **WORKFLOW/SLA 类** ← **新增** `WorkflowSucceededEvent(workflowInstanceId, workflowId, tenantId)`：在 `WorkerReportService` workflow SUCCESS 分支（现调 `slaService.recordCompletion` 处）发布，镜像 TaskSucceededEvent 的形态与吞异常策略。工作流成功当前没有任何进程内事件，这是本期唯一对既有类的行为性改动。
  3. **NODE 类** ← 无恢复事件，由 `IncidentSweeper` 周期检查该 nodeCode 心跳是否恢复（NodeTelemetryService/租约新鲜度）→ CAS RESOLVED。
- **重开语义（clarify 对齐）**: RESOLVED（7 天窗口内）收到同签名/同实例信号 → CAS RESOLVED→OPEN、计数累加、timeline 记"复发"。SUPPRESSED 收到信号 → 仅累加计数与 last_seen，不改状态不进默认队列。
- **Alternatives considered**: 全部愈合走 sweeper 轮询——被拒：SC-003 要求 30s 内自动已解决，事件驱动免延迟；TASK/WORKFLOW 两路有现成/低成本事件，只有 NODE 别无选择才用 sweeper。

## D6 自动关闭与清扫：IncidentSweeper

- **Decision**: `@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)`，两件事：① `UPDATE incident SET state='CLOSED', active_key=NULL, closed_at=now WHERE state='RESOLVED' AND resolved_at < now - 7d`（集合式 CAS，天然幂等）；② NODE 工单心跳恢复检查（D5-3）。多 master 防重走**范式 A（CAS 幂等）**——两 master 同扫只有一个 UPDATE 生效，零协调，不建 guard 表、不用 advisory lock。
- **Rationale**: 实证项目三种防重范式（CAS / advisory lock / guard 表）中，纯状态推进类清扫用 CAS 是既有惯例（LeaseReaper 同款）。
- **Alternatives considered**: advisory lock（FreshnessSnapshotJob 范式 B）——被拒：无跨行一致性需求，CAS 已足。

## D7 分诊计算：爆炸半径 + 时间预算

- **Decision**:
  - **爆炸半径** = `LineageQueryService.downstreamTaskLevels(tenantId, projectId, taskDefId).size()`（neo4j BFS，唯一活路——PG 血缘四表含 task_table_io 已于 0.0.2 退役删除）。neo4j 不可达时该方法降级返回空 map，**无法区分"无下游"与"血缘不可用"**：包一层探活（查询异常/不可达标记）→ 写 `blast_radius = NULL`（卡片显示"血缘不可用"缺省态）；正常空结果写 0。
  - **时间预算** = 下游任务所属 workflow（含自身 workflow）的 `sla_baseline.baseline_ready_at` 取 time-of-day 投影到当前 biz date，取最近将来时刻写 `time_budget_at`；无任何基线（历史 <3 次不建基线）→ NULL 缺省态。SLA_BREACH 工单本身已破约：`time_budget_at` = 破约时刻（已过去），卡片显示"已超期 {breach_minutes} 分钟"。
  - **计算时机**: 开单时 + RESOLVED→OPEN 重开时各算一次；普通附着不重算（倒计时由前端基于 time_budget_at 本地渲染，无需服务端刷新）。
- **Rationale**: 实证——不存在 task 级 SLA deadline（SLA 是 workflow×biz_date 粒度，隐式期限 = baseline_ready_at 中位数基线），投影是当前数据能给出的最诚实近似；spec FR-007 已为不可得情形规定缺省态。
- **Alternatives considered**: 新建 task 级 SLA 配置——被拒：引入人肉录入元数据，违反北极星"代码是唯一真相"；用 `predictLatestEta`——被拒：那是"预计完成"不是"必须完成"，语义不符。

## D8 队列排序与默认视图

- **Decision**: 队列接口一次性取活跃工单（OPEN+MITIGATING）+ 近 24h RESOLVED（量级十/百，全取），**服务端 Java 内存排序**：① 有 time_budget_at 且未过期者按剩余时间升序优先；② 已超期者次之（按超期时长降序）；③ 无时间预算者按爆炸半径降序；④ 再按 severity rank（CRITICAL>HIGH>MEDIUM>…，复用 EventCenterService.SEVERITY_RANK 语义）；⑤ 并列按 last_seen_at 降序。已解决区独立分区按 resolved_at 降序。历史（>24h RESOLVED / CLOSED / SUPPRESSED）走带筛选的分页查询。
- **Rationale**: 排序键含"当前时刻"动态项，SQL 表达跨 H2/PG 方言成本高且无必要（量级小）；clarify Q2 已定默认视图状态集。
- **Alternatives considered**: 物化 triage_rank 列——被拒：倒计时随时间连续变化，物化必然过期。

## D9 severity 映射

- **Decision**: 工单 severity = 全部已附着信号 severityHint 的最大值。实证各信号硬编码值：TASK_FAILED/TASK_TIMEOUT=HIGH、SLA_BREACH=CRITICAL、NODE_OFFLINE=CRITICAL——即纯任务失败单 HIGH，归并进 SLA 破约后升 CRITICAL。
- **Alternatives considered**: 按签名类型静态映射——被拒：归并单的严重度应随最重信号抬升。

## D10 处置闸门接缝：独立端点，不复用旁路

- **Decision**: `POST /api/incidents/{id}/rerun` → 构造 `ActionRequest(toolName="incident_rerun", actionType="TASK_RERUN", targetType="TASK_INSTANCE", actorSource="UI", params={incidentId})` → `GatedActionService.submit`。`agent_action` 加可空 `incident_id` 列，submit 落库时透传；闸门裁决结果（直执行/待审批/拒绝）作为 ACTION 条目写 timeline，工单 CAS 到 MITIGATING。审批复用既有 `/api/approvals/{id}/approve|reject`（OWNER 权限不变），卡片内联展示该工单关联的 PENDING 动作。
- **Rationale**: 实证——OpsController 既有 rerun 端点**直调领域服务不过闸门**（仅 MCP 侧过闸门），FR-009 要求 incident 动作必留痕，故新开端点镜像 McpToolRegistry.task_rerun 的 ActionRequest 构造；修复既有 UI 旁路属越界（FR-014 精神），只在 research 记录该债务。
- **Alternatives considered**: 直调 OpsService.rerunInstance——被拒：违反 FR-009 与宪法原则 V"每个写操作过闸门留痕"。

## D11 前端接入与主页切换

- **Decision**: `ViewType` 增 `"incidents"`；`VIEW_META` 中把 incidents 声明在**第一位**且 `defaultPinned: true`——实证 `PINNED_VIEWS = keys(VIEW_META).filter(defaultPinned)` 且 `store` 的初始 `activeTabId = baseTabs()[0]`，声明顺序即主页顺序，零 store 改动完成主页切换（pinned 变为 incidents/freshness/metrics 三个）。nav-groups 将 incidents 加入首组首位（满足 nav-groups.test.ts"入口∪详情 == 全集"不变量）。数据刷新用 15s 轮询（满足 SC-001/SC-003 的 30s 预算），SSE 推送留待编队期按需引入。深链复用 event-center 的 `refKindToView` 映射。i18n 新增 `incidents.*` 命名空间，双 bundle 键集一致（CI 硬门）。
- **Alternatives considered**: SSE 实时推送——被拒 v1：多一条长连接与发布链路，轮询在量级与 SC 预算内足够；改 store 默认激活逻辑——被拒：VIEW_META 顺序已是既有机制。

## D12 保留与体量

- **Decision**: CLOSED 工单与 timeline 永久保留（v1 不清理）——它们是未来经验库的原始素材；量级（每日十级工单 × timeline 数十条）三年内无压力。清理策略留给经验库立项时统一设计。

## 已知债务登记（不在本期修）

- OpsController 既有 rerun/batch 端点不过闸门（UI 旁路），`buildBatchActionRequest` 为死代码——待后续统一收口。
- neo4j 不可达时 `downstreamTaskLevels` 静默降级为空 map 的语义混淆（本期以探活包装规避，根修在血缘域）。
