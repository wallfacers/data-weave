# Tasks: 任务失败智能运维——Agent 自动诊断处置闭环 + 监督席指挥中心

**Input**: Design documents from `specs/067-agent-incident-ops/`

**Prerequisites**: plan.md, spec.md, research.md（R1–R13）, data-model.md, contracts/, quickstart.md

**Tests**: 宪法要求「新特性必须有测试；无测试=未完成」——测试任务为必选项。

**Organization**: 按用户故事分组，每故事独立可测可交付。后端包路径缩写 `…master…` = `backend/dataweave-master/src/main/java/com/dataweave/master`，测试同理 `src/test/java`；api/worker 模块类推（新类包名对齐同目录既有类，如 IncidentController 对齐 OpsController）。

## Phase 1: Setup（schema 与配置地基）

**Purpose**: 单一权威 DDL 与配置先行，所有故事共用。

- [ ] T001 schema.sql `0.18.0 → 0.19.0`：新增 `incident`/`incident_message`/`incident_proposal`/`incident_briefing` 四表及索引（`open_key` UNIQUE(tenant_id, open_key) NULL-可重复语义，不用 partial index）、`task_def`+`task_def_version` 加 `resources_json`、`lineage_agent_config` 加 `ops_enabled INT DEFAULT 0`、`policy_rules` 种子（incident_rerun/incident_adjust_resources/incident_reverify/incident_resume_checkpoint=L1，incident_publish_fix=L3）；版本号三处同步（文件头/schema_version INSERT/项目版本）；H2+PG 双方言各启动验证。File: `backend/dataweave-api/src/main/resources/schema.sql`
- [ ] T002 [P] 配置键 `ops.incident.*` 默认值（sweep-interval-ms=30000 / max-auto-actions=3 / memory-cap-mb=16384 / resource-step-factor-max=2.0 / briefing-debounce-ms=60000 / llm-pool-size=2 / llm-rate-per-min=30（独立令牌桶，与血缘富化分离） / evidence-log-tail-lines=400 / storm-max-inflight=5，research R12）。File: `backend/dataweave-api/src/main/resources/application.yml`
- [ ] T003 [P] 后端 i18n：`incident.*` 错误码（not_found/agent_disabled/closed/proposal_stale/proposal_not_pending 等）与骨架文案（policy.reason 风格）。Files: `backend/dataweave-master/src/main/resources/messages.properties` + `messages_en_US.properties`

---

## Phase 2: Foundational（阻塞全部故事的领域与通道地基）

**⚠️ CRITICAL**: 完成前任何用户故事不得开工。

- [ ] T004 [P] 事故领域对象：`Incident`/`IncidentMessage`/`IncidentProposal`/`IncidentBriefing` 实体 + `IncidentState`/`Classification`/`MessageKind`/`ProposalStatus` 枚举 + `IncidentEvent` 直播事件 record 族（thinking/chip/delta/message/incident/briefing，契约 sse-live-feed.md）。Dir: `…master…/domain/incident/`
- [ ] T005 事故仓储 4 个（JdbcTemplate；关键方法：`insertIfAbsentOpen`（开单单赢）/ `mergeInstance`（归并计数+latest 更新）/ `casState` / `casClose`（open_key→NULL）/ `incrementAutoAction`（只增）/ 消息 `nextSeq` 事故级发号 / briefing upsert(UNIQUE tenant+project)；两库方言 CONCAT/IF NOT EXISTS 守则）。Dir: `…master…/infrastructure/incident/`（depends T001, T004）
- [ ] T006 [P] `LlmChatClient` 通用多轮客户端：复用 `AnthropicProtocolAdapter`/`OpenAiProtocolAdapter` 新增 `buildChatRequest(cfg, messages)` 入口 + 流式分片回调 + 结构化 JSON 输出约定；沿袭 053 硬约束（降级永不抛/明文密钥不进日志/timeoutMs/令牌桶限频）。Files: `…master…/application/lineage/agent/LlmChatClient.java` + 两适配器扩展
- [ ] T007 [P] `LineageAgentConfig` record + `AgentConfigRepository` + `AgentLineageConfigService` 增 `opsEnabled` 字段读写（连接四元组不动）。Files: `…master…/domain/lineage/LineageAgentConfig.java` + 对应 repo/service
- [ ] T008 [P] `IncidentEventPublisher`：EventBus 封装，Jackson 3 序列化发布持久化/瞬态事件到 `dw:incident:evt:{projectId}`。File: `…master…/application/incident/IncidentEventPublisher.java`（depends T004）
- [ ] T009 地基测试：开单唯一约束单赢/归并、CAS 状态推进、seq 单调、briefing upsert 的 H2 IT（`@TestPropertySource` 独立库名防串台）。File: `…master…/src/test/java/…/incident/IncidentRepositoryIT.java`（depends T005）

**Checkpoint**: 领域+存储+LLM 通道就绪，US1–US4 可并行开工。

---

## Phase 3: User Story 1 - 失败自动感知与分型诊断 (Priority: P1) 🎯 MVP

**Goal**: 周期/手动/实时失败 → 自动开单 → 采证 → LLM 分型诊断（含证据引用与建议）；未配置模型时降级 DIAG_UNAVAILABLE、主链路零影响。

**Independent Test**: quickstart 场景 1——制造 SQL 语法错误失败，≤1 sweep 周期开单、≤5min 出分型+证据+建议；关 `ops_enabled` 后事故仍创建且调度/日志无感。

- [ ] T010 [P] [US1] `IncidentEvidenceCollector`：LogBus 尾部 N 行（`evidence-log-tail-lines`，超长头尾采样截断预算 ~32KB）+ `task_instance` 快照字段（failure_reason/exit_code/error_message）+ task_def 定义与类型 + 近 10 次运行历史 + 实时任务附 external_job_handle 与 checkpoint 可用性。File: `…master…/application/incident/IncidentEvidenceCollector.java`
- [ ] T011 [P] [US1] `CredentialFingerprint` 确定性前置指纹：authentication failed / access denied / password / login 等多语言模式 → 直接 CONFIG_CREDENTIAL（免 LLM 误判，research R4）。File: `…master…/application/incident/CredentialFingerprint.java`
- [ ] T012 [P] [US1] `DiagnosisPrompt`：系统提示+证据包组装 → 结构化 JSON（classification/confidence/evidenceLines/suggestion，输出语言=agent locale 兜底 zh-CN）；解析失败降级 UNKNOWN。File: `…master…/application/incident/DiagnosisPrompt.java`
- [ ] T013 [US1] `IncidentSweeper`：`@Scheduled(fixedDelay=${ops.incident.sweep-interval-ms})` 扫 `state IN ('FAILED','SUSPENDED')` 且无未收口事故的实例（排除 `run_mode IN ('TEST','BACKFILL')`）→ `insertIfAbsentOpen` 单赢开单 / 冲突归并（SYSTEM 消息）→ 赢者提交本机 llm 线程池；`storm-max-inflight` 限流、排队事故如实标记；trigger_source 推导（long_running→STREAMING，否则按 cron_expression 有无分 CRON/MANUAL）。File: `…master…/application/incident/IncidentSweeper.java`（depends T005, T008, T010）
- [ ] T014 [US1] `IncidentAgentService` 诊断段：OPEN→ANALYZING CAS → 采证（chip 事件逐项点亮）→ 指纹前置 → LLM 分型（独立令牌桶 `llm-rate-per-min`）→ 诊断结论落 `incident_message`(AGENT_STEP, payload 含分型/置信度/证据引用) + 广播；`ops_enabled=0`/端点失败 → DIAG_UNAVAILABLE（上下文保留，配置恢复后下轮 sweep 补诊断）。File: `…master…/application/incident/IncidentAgentService.java`（depends T006, T011, T012, T013）
- [ ] T015 [US1] `IncidentQueryService` + `IncidentController` 查询面 + 配置开关：`GET /api/incidents`（分页/state/taskDefId 过滤/待处理置顶排序）、`GET /{id}`、`GET /{id}/messages?afterSeq`、`GET|PUT /api/incidents/agent-config`；JWT + `X-Project-Id` + `ProjectScope.require`；错误走 BizException（contracts/incident-api.md）。Files: `…master…/application/incident/IncidentQueryService.java` + `backend/dataweave-api/src/main/java/…/interfaces/IncidentController.java`（depends T005, T007）
- [ ] T016 [US1] US1 测试：Sweeper 开单/归并/TEST 排除/风暴限流 IT；桩 LLM（假端点或桩适配器）诊断编排与 DIAG_UNAVAILABLE 降级；Controller WebTestClient（JwtTestSupport，200+$.code 契约）。Dir: `…master…/src/test/java/…/incident/` + `backend/dataweave-api/src/test/java/…/IncidentControllerIT.java`

**Checkpoint**: US1 API 层独立可验（quickstart 场景 1 除首屏 UI 外全部通过）——MVP。

---

## Phase 4: User Story 2 - 可自愈故障的自动处置与验证收口 (Priority: P2)

**Goal**: 分型→梯度处置：瞬态自动重跑、资源不足调 CPU/内存（护栏内）、实时优先 checkpoint 续跑、代码缺陷生成人审提案；处置后验证收口/升级；防循环；修复回流 repo。

**Independent Test**: quickstart 场景 2/3/4——瞬态与 OOM 两类零人工自愈收口；代码缺陷出提案、批准后发布重跑成功；`dw pull` 取回含新 `resources` 节。

- [ ] T017 [P] [US2] 资源声明创作链：`TaskDef` 实体 + `TaskService` 版本快照复制 + `TaskDoc`/`TaskMapper`/`ProjectSyncService` 的 `*.task.yaml` 可选 `resources` 节（pull/push 往返完整性，062 Option C 教训：创作→下发一次接通）。Files: `…master…/domain/TaskDef.java`、`…master…/application/TaskService.java`、`ProjectSyncService.java` 及 TaskDoc/TaskMapper
- [ ] T018 [P] [US2] 资源下发链：`DispatchCommand` + `SchedulerKernel` claim + `WorkerExecController` 构造 + 两 gateway 传播 `resources_json`；worker 引擎映射——Spark(driver/executor memory/cores)、Flink(taskmanager memory)、SeaTunnel/DataX(JVM -Xm*)，Shell/Python/SQL 不映射但日志说明。Dirs: `…master…/application/`（kernel/gateway）+ `backend/dataweave-worker/src/main/java/…/`（各执行器）
- [ ] T019 [P] [US2] CLI 契约：Go `TaskDoc` 增可选 `resources` 节（pull/diff/push 透传，diff 语义正确）。Dir: `cli/`
- [ ] T020 [P] [US2] `RemediationPlanner` 确定性梯度映射：TRANSIENT→rerun；RESOURCE→护栏校验（≤memory-cap-mb、调幅≤resource-step-factor-max，越界转 NEEDS_HUMAN）后调资源+rerun；CODE→提案；UPSTREAM_DATA/CONFIG_CREDENTIAL→升级；UNKNOWN→至多 1 次试探；streaming 且有可用 checkpoint→续跑优先。File: `…master…/application/incident/RemediationPlanner.java`
- [ ] T021 [US2] 闸门接线：Agent 动作以 `ActionRequest`(actorSource=AGENT, actor=ops-agent) 经 `GatedActionService.submit`；`DefaultPlatformActionExecutor` 新增 5 分支（incident_rerun→`OpsService.rerunInstance`；incident_adjust_resources→改 resources_json+`writeTaskVersionSnapshot`；incident_resume_checkpoint→`OpsService.resumeFromCheckpoint`；incident_reverify→rerun+跟踪；incident_publish_fix→T023 发布链）；执行前实例状态 CAS 前置校验——人工已介入（kill/rerun）即让位并落 SYSTEM 消息。Files: `…master…/application/DefaultPlatformActionExecutor.java` + `IncidentAgentService` 行动段（depends T014, T020）
- [ ] T022 [US2] 验证收口循环：sweep 周期跟踪处置后 latest_instance 终态——SUCCESS→RESOLVED(close_kind=AUTO, open_key 置 NULL)；FAILED→下一梯度或 `auto_action_count≥max-auto-actions`→NEEDS_HUMAN（附全部尝试记录）；每步 ACTION/VERDICT 消息+广播。File: `IncidentAgentService`/`IncidentSweeper` 验证段（depends T021）
- [ ] T023 [US2] 修复提案全链：LLM 生成全量修复内容+change_summary+证据包 → `incident_proposal`(PENDING)+事故 AWAITING_APPROVAL+PROPOSAL 消息；`POST /{id}/proposals/{pid}/approve|reject`（底层 ApprovalService/agent_action 审批单）；批准→基线陈旧校验（不符→STALE+SYSTEM 消息）→`writeTaskVersionSnapshot`+`publish`（remark 溯源 incident）→重跑验证→VERIFIED 收口 / VERIFY_FAILED→base 版本内容回滚（新快照）+NEEDS_HUMAN。Files: `…master…/application/incident/`（提案逻辑）+ `IncidentController`（审批端点）（depends T017, T021）
- [ ] T024 [US2] US2 测试：Planner 映射表全分支单测、护栏越界转人工、防循环上限断言（无第 N+1 次自动动作）、提案 stale/回滚 IT、资源往返（push→pull 含 resources、worker 映射单测）、人工介入让位、checkpoint 续跑优先。Dirs: master/worker 对应 test 目录

**Checkpoint**: US1+US2 联合 = 自愈闭环成立（quickstart 场景 2/3/4 + 场景 7 防循环）。

---

## Phase 5: User Story 3 - 无法自愈故障的升级与建议 (Priority: P2)

**Goal**: 凭据错误/上游脏数据零徒劳重试直达「需人工介入」并 100% 附根因与建议；人工处理后复验收口。

**Independent Test**: quickstart 场景 5——错密码数据源失败：零 rerun 记录、NEEDS_HUMAN+建议；修复后 mark-handled→复验→RESOLVED(HUMAN_ASSISTED)。

- [ ] T025 [US3] 升级路径实现：CONFIG_CREDENTIAL/UPSTREAM_DATA 分型直达 NEEDS_HUMAN（`agent_action` 零 rerun）+ suggestion 生成（数据源定位/受影响范围/操作指引，落 incident.suggestion）；UPSTREAM_DATA 绝不修改上游数据（白名单外动作丢弃并记录）；UNKNOWN 单次试探后升级。File: `IncidentAgentService`/`RemediationPlanner` 升级段（depends T020）
- [ ] T026 [US3] 人工协同端点：`POST /{id}/mark-handled`（NEEDS_HUMAN 限定→HUMAN_SAY+SYSTEM→ACTING 复验）、`POST /{id}/reverify`（经闸门 incident_reverify）、`POST /{id}/close`（任意非终态→RESOLVED(MANUAL)，reason 必填）；复验成功→RESOLVED(HUMAN_ASSISTED)。File: `IncidentController` + `IncidentAgentService`（depends T022）
- [ ] T027 [US3] US3 测试：凭据指纹→零自动重跑断言、mark-handled→复验→收口 IT、UNKNOWN 单试探、close 审计。Dir: `…master…/src/test/java/…/incident/`

**Checkpoint**: 三条出口（自愈/人审/人工协同）全部闭合。

---

## Phase 6: User Story 4 - 监督席·指挥中心直播流（产品主页）(Priority: P3)

**Goal**: 首屏=指挥中心：战况播报横幅+全量直播 feed（thinking/chip/delta 智能感层）+下钻线程自由对话+结构化裁决；SC-009 ≤2s、SC-010 首屏与数字一致、SC-011 对话 ≤5s P90。

**Independent Test**: quickstart 场景 6 + Playwright 门——打开即监督席、feed 实时流式、线程对话有效回应、审批按钮可用。

### 后端

- [ ] T028 [US4] SSE `GET /api/incidents/stream`：连接先发 snapshot（未收口事故+SQL 实时数字）再 Sinks 桥接 `dw:incident:evt:{projectId}`；`Last-Event-ID`=`incidentId:seq` 重连补齐持久化消息（瞬态不重放）；token/projectId query 鉴权、end/心跳对齐既有流（contracts/sse-live-feed.md）。File: `IncidentController`（depends T008, T015）
- [ ] T029 [US4] 瞬态直播事件织入：`IncidentAgentService` 各阶段 emit thinking(START/STOP)/chip(RUNNING/DONE/FAILED)/delta（诊断叙述流式）；持久化消息落库后广播 `message` 事件（同 streamId 收尾约定）。File: `IncidentAgentService`+`IncidentEventPublisher`（depends T014, T028）
- [ ] T030 [US4] `IncidentConversationService` + `POST /{id}/chat`：HUMAN_SAY 落库→prompt（系统提示/证据包/线程历史截断预算）→`LlmChatClient` 流式→delta 直播+完成落 AGENT_SAY；结构化动作提议块解析（白名单 rerun/adjust_resources/reverify/publish_fix/escalate，其余丢弃记录）→闸门执行+ACTION 消息；`incident.agent_disabled`/`incident.closed` 错误。File: `…master…/application/incident/IncidentConversationService.java`（depends T006, T021）
- [ ] T031 [US4] `IncidentBriefingService`：开立/收口/升级触发防抖（briefing-debounce-ms）生成 summary_line+report_md（LLM，agent locale）落表+广播 briefing 事件；`GET /api/incidents/briefing` 数字永远 SQL 实时算（SC-010 结构保证）。File: `…master…/application/incident/IncidentBriefingService.java`（depends T005, T008）
- [ ] T032 [US4] 后端 US4 测试：SSE 快照+直播 IT（WebTestClient Flux）、对话桩 LLM 流程、提议白名单拒收、briefing 数字与事实一致性、Last-Event-ID 续传。Dirs: 对应 test 目录

### 前端

- [ ] T033 [P] [US4] 视图注册与首屏切换：`lib/workspace/views.ts`（ViewType `supervision` + VIEW_META + `DEFAULT_VIEWS[0]="supervision"`）+ `lib/workspace/registry.tsx`（hugeicons 图标+组件）+ `lib/workspace/nav-groups.ts`（ops 组首位）+ nav-groups 测试不变量同步 + i18n `views.supervision`/`leftNav`（zh-CN/en-US 双 bundle 键集一致）。
- [ ] T034 [P] [US4] supervision 数据层：REST hooks（列表/详情/消息分页/chat/proposal 审批/mark-handled/reverify/close/briefing）+ SSE 消费 store（`useEventSource` 直连 SSE_BASE + token/projectId query；snapshot/incident/message/briefing/thinking/chip/delta 归约：置顶区、过滤、streamId 拼接与替换）。Dir: `frontend/lib/supervision/`
- [ ] T035 [US4] `briefing-banner.tsx`：一句话综述横幅+内嵌数字（点击过滤 feed）+展开完整接班报告（markdown 渲染，复用既有渲染原语）。Dir: `frontend/components/workspace/views/supervision/`（depends T034）
- [ ] T036 [US4] `live-feed.tsx`：直播流——DwScroll 滚动、进行中条目 `motion-safe:` 呼吸态、chips 逐项点亮、delta 打字流、state/taskDefId 过滤、待处理（AWAITING_APPROVAL/NEEDS_HUMAN）固定置顶区不被刷走。Dir: 同上（depends T034）
- [ ] T037 [US4] `incident-thread.tsx` + `chat-composer.tsx`：下钻线程六类消息形态（AGENT_STEP 带 chips/AGENT_SAY 打字流/HUMAN_SAY/ACTION 含闸门结果/PROPOSAL 卡片批准驳回/SYSTEM）、证据下钻链到 `instance-log` 视图、mark-handled/reverify/close 按钮、对话输入+流式回显+发送态。Dir: 同上（depends T034）
- [ ] T038 [US4] `supervision-view.tsx` 总装 + i18n `supervision` 命名空间双 bundle + `prefers-reduced-motion` 全面降级（呼吸/打字→静态状态文本）+ reuse-first checklist 履行（`specs/037-shared-ui-kit/contracts/reuse-first-checklist.md`，新建消息气泡/工具 chip 原语回填 DESIGN.md 公共组件目录）。Files: `frontend/components/workspace/views/supervision-view.tsx` + `frontend/DESIGN.md` + `frontend/messages/*`（depends T035, T036, T037）
- [ ] T039 [US4] 前端测试：vitest（SSE 归约 store：置顶/过滤/streamId 替换；banner 数字渲染）+ `pnpm typecheck` 零错。Dir: `frontend/lib/supervision/` + 组件旁 `*.test.tsx`
- [ ] T040 [US4] Playwright 浏览器门：登录注入（dw.auth.token+dw.auth.user）→ 首屏即监督席（SC-010）→ 触发失败后 feed 实时出现步骤（SC-009）→ 下钻线程发问得流式回应 → 提案批准按钮可用 → reduced-motion 降级检查；SSE 必直连后端（Next rewrite 缓冲坑）。Dir: `frontend/`（既有 Playwright 门模式）
- [ ] T046 [US4] 智能运维启停开关 UI：settings 视图的 053 Agent 配置卡追加「智能运维」开关（对接 `GET|PUT /api/incidents/agent-config`，i18n 双 bundle，FR-012）。Dir: `frontend/components/workspace/views/`（053 血缘 Agent 配置卡既有位置，depends T034）

**Checkpoint**: 全部故事完成——quickstart 场景 1–7 可整体走查。

---

## Phase 7: Polish & Cross-Cutting

- [ ] T041 [P] MCP 面：`McpToolRegistry.registerTools()` 追加 `query_incidents`（只读，复用 IncidentQueryService）+ `incident_reverify`（写，过闸门）；每工具 `requireTenant(ctx)`。File: `backend/dataweave-api/src/main/java/…/application/mcp/McpToolRegistry.java`
- [ ] T042 [P] 宪法修订案：起草原则 IV 重定义（authoring AI 归位本地不变；Trust 层运维编队允许服务端有界存在，三条不可让渡内核等价物），MAJOR bump 提案连同 Sync Impact Report——**提交用户批准，未批准前保留 plan.md Complexity Tracking 记录**。File: `.specify/memory/constitution.md`
- [ ] T043 [P] 文档收口：CLAUDE.md Knowledge Map 增 067 行（一句话+关键类+spec 链接）；README/docs 涉及首屏说明处同步。Files: `CLAUDE.md` 等
- [ ] T044 全量回归：H2 与 PG 双 profile 后端测试全绿（setsid 脱离 + mvnd 禁缓存防假绿）；**调度红线真并发 dispatch 回归**（resources 传播动了 DispatchCommand 链——每分钟 cron 真跑确认 `started_at−created_at≈0`、根节点 `attempt=1`、零「跳过下发/中止执行」stragglers）；i18n 双 bundle 键集 CI 检查过。
- [ ] T045 quickstart.md 场景 1–7 端到端真验（真 LLM 端点）+ SC-001…SC-011 全量核对留痕（结果记录进 specs/067-agent-incident-ops/）。

---

## Dependencies & Execution Order

### Phase 依赖

- **Phase 1 → Phase 2 → 各 US**：T001 阻塞 T005；T004–T008 阻塞全部故事。
- **US1 (P3 阶段)**：仅依赖 Foundational——MVP。
- **US2**：依赖 US1 的 T013/T014（Sweeper/AgentService 骨架）；T017/T018/T019（资源链）与 T020 互相独立可并行。
- **US3**：依赖 US2 的 T020/T022（Planner/验证循环）。
- **US4 后端**：T028 依赖 T015；T029 依赖 T014；T030 依赖 T021（闸门）。**US4 前端 T033/T034 只依赖 Foundational+contracts，可与后端故事并行开发（对契约编码）**。
- **Polish**：T041–T043 随时可并行；T044/T045 必须最后。

### 关键并行机会

- Phase 1：T002/T003 并行；Phase 2：T004/T006/T007/T008 并行（T005 等 T001+T004）。
- US1 内：T010/T011/T012 三个纯新文件并行。
- US2 内：T017/T018/T019/T020 四链并行（创作链/下发链/CLI/决策器互不相扰）。
- 跨故事：Foundational 后，后端走 US1→US2→US3 主线的同时，前端 T033/T034 即可对契约并行开工。

### Parallel Example: US2 资源四链

```bash
Task: "T017 资源声明创作链 TaskDef/TaskService/TaskDoc/ProjectSyncService"
Task: "T018 资源下发链 DispatchCommand/SchedulerKernel/worker 执行器映射"
Task: "T019 CLI TaskDoc resources 节透传 cli/"
Task: "T020 RemediationPlanner 梯度映射 …master…/application/incident/"
```

## Implementation Strategy

**MVP = Phase 1+2+US1**（失败自动开单+诊断，quickstart 场景 1）：先交付「每个失败自动附诊断」的独立价值，验证 LLM 通道与巡检骨架，再叠处置（US2）→ 出口闭合（US3）→ 指挥中心（US4）。每 checkpoint 停下独立验证；US4 前端可从 Foundational 完成后即对契约并行推进。多 agent 并行开发时遵循 CLAUDE.md 并行特性隔离规则（独立 worktree）。

## Notes

- 全部写动作过闸门（含 Agent 来源），调度内核零侵入是 T044 的硬验收线。
- 涉及 `SchedulerKernel`/`DispatchCommand`/gateway 的 T018 属「调度下发链路变更」——CLAUDE.md 硬规则要求真并发 dispatch 验证，不得只跑单测。
- LLM 相关测试一律桩端点/桩适配器，真端点只在 T045。
- 后端每任务后 `./mvnw -q -pl <module> compile` 零错；前端每任务后 `pnpm typecheck` 零错。
