# Tasks: Incident 域模型 + 监督席队列

**Input**: Design documents from `/specs/043-incident-queue/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/incident-api.md, contracts/ui-surface.md, quickstart.md

**执行模式**: 3 个外部 agent 并行 + 主 Claude 收口。全部工作在 worktree `/home/wushengzhou/workspace/github/dw-043-incident-queue`（分支 `043-incident-queue`），**禁止触碰主副本**。文件所有权硬切分（见每个任务的 🅰/🅱/🅲 标记与文末所有权表），交集为空。

**Tests**: 项目规约「新功能必须有测试；no test = not done」——各 story 含测试任务。

## Phase 1: Setup

- [ ] T001 🅰 schema 0.7.0：`backend/dataweave-api/src/main/resources/schema.sql` 新增 `incident`（含 active_key UNIQUE、三索引）与 `incident_event`（UNIQUE(incident_id,seq)）两表、`agent_action` 加 `incident_id` 列 + 索引，按 data-model.md 的 DDL 风格（H2/PG 兼容：IF NOT EXISTS、无部分索引）；文件头版本注释 + `schema_version` INSERT `0.7.0`；同步 `docs/architecture.md` schema 小节
- [ ] T002 [P] 🅱 后端错误码 i18n：`incident.not_found` / `incident.invalid_state` / `incident.suppress_reason_required` / `incident.action_target_mismatch` 四码进后端双语 messages bundle（contracts/incident-api.md §错误码；沿 `<domain>.<semantic>` 规约）
- [ ] T003 [P] 🅲 前端 i18n：`frontend/messages/{zh-CN,en-US}.json` 新增 `views.incidents` + `incidents.*` 命名空间（队列/卡片/动作/空态/占位态全部文案，ICU `{name}` 风格），双 bundle 键集一致（CI 硬门），禁止 `…` 表加载态

## Phase 2: Foundational（阻塞后续所有 story）

- [ ] T004 🅰 领域对象：`backend/dataweave-master/src/main/java/com/dataweave/master/domain/incident/Incident.java`（字段=data-model.md 表1）+ `IncidentEvent.java`（表2）+ `IncidentStates`（OPEN/MITIGATING/RESOLVED/SUPPRESSED/CLOSED 常量与 isTerminal/合法迁移表）
- [ ] T005 🅰 IncidentService 核心（**优先落地并立即 commit，解锁 🅱🅲**）：`master/.../application/incident/IncidentService.java`——公开契约方法：`openOrAttach(AlertSignal)`、`healByTask(long taskId, long tenantId)`、`healByWorkflowInstance(UUID wfi, long tenantId)`、`suppress(id, reason, actor)` / `unsuppress(id, actor)`、`appendNote(id, text, actor)`、`appendTimeline(incidentId, kind, payloadJson, actor)`、`queue(tenantId, projectId)`（三区结构）、`history(...)`、`detail(id)`、`markMitigating(id)`、`findByAgentActionIncident(actionId)`；内部实现附着优先写入（UPDATE→INSERT→撞唯一键重试，research D4）、状态全 CAS、STATE_CHANGE 时间线同事务写入
- [ ] T006 [P] 🅱 闸门接缝扩展：`master/.../application/ActionRequest.java` 加 `incidentId`（Builder）、`master/.../domain/AgentAction.java` 加字段映射、`master/.../application/GatedActionService.java` submit 落库透传 incident_id——既有调用方零影响（字段可空）

**Checkpoint**: T001+T004+T005 落地后 `./mvnw -q -pl dataweave-master,dataweave-api compile` 零错误——US1/US2/US3 三线并行开跑。

## Phase 3: User Story 1 - 故障即工单：失败自动开单、自动愈合、主页可见 (P1) 🎯 MVP

**Goal**: 四类信号 → 去重/归并工单 → 自动愈合/7 天自动关闭 → 主页队列可见。

**Independent Test**: quickstart 场景 1+2——注入失败 30s 内出卡、5 连败 1 卡计 5、恢复自动已解决、改时间戳验自动关闭。

- [ ] T007 [US1] 🅰 `IncidentSignalListener.java`：`@EventListener(AlertSignal)` 整方法 try-catch；仅处理 TASK_FAILED/TASK_TIMEOUT/SLA_BREACH/NODE_OFFLINE；签名生成（research D3）、同 workflowInstanceId 归并优先（D4）、severity 只升不降（D9）、SIGNAL 时间线
- [ ] T008 [US1] 🅰 自动愈合：`IncidentHealListener.java`（消费既有 `TaskSucceededEvent` + 新增 `WorkflowSucceededEvent`）；新建 `master/.../application/WorkflowSucceededEvent.java`（record，镜像 TaskSucceededEvent）并在 `WorkerReportService.java` workflow SUCCESS 分支（`slaService.recordCompletion` 处）吞异常发布；RESOLVED 窗口内复发 = CAS 重开 + 时间线记「复发」
- [ ] T009 [US1] 🅰 `IncidentSweeper.java`：`@Scheduled(fixedDelay=60_000, initialDelay=30_000)`——①RESOLVED 超 7 天集合 CAS 转 CLOSED（active_key=NULL）；②NODE 类工单心跳恢复检查（NodeTelemetryService/租约新鲜度）→ CAS RESOLVED；多 master 零协调（CAS 幂等，范式 A）
- [ ] T010 [US1] 🅰 master 单测/集成测（H2 **独立库名**防串台）：签名映射、同签名附着计数、同实例归并（SC-008 语义）、SLA 先到独立开单后归并、RESOLVED 复发重开、CLOSED 后开新单、自动愈合三路、7 天自动关闭、active_key 唯一约束竞态（INSERT 冲突重试）
- [ ] T011 [US1] 🅱 `dataweave-api/.../interfaces/IncidentController.java`：`GET /api/incidents`（三区队列）+ `GET /api/incidents/history`（筛选分页）+ `GET /api/incidents/{id}`（详情+timeline+actions）——DTO 按 contracts/incident-api.md §1-3；JWT + `ProjectScope.require`；跨项目访问 → `incident.not_found`
- [ ] T012 [US1] 🅱 契约测试（`@SpringBootTest` H2 独立库名 + WebTestClient + JwtTestSupport，统一 `200+$.code/$.data` 断言）：队列三区结构、注入信号后卡片字段完整性、计数聚合、详情 timeline 顺序、无 JWT=401、跨项目=not_found
- [ ] T013 [US1] 🅲 视图注册三件套：`frontend/lib/workspace/views.ts`（ViewType+`incidents` 且 VIEW_META **第一位** defaultPinned:true）、`frontend/lib/workspace/registry.tsx`（hugeicons 图标+IncidentsView）、`frontend/lib/workspace/nav-groups.ts`（首组首位）；同步更新 `store.test.ts`（base tab 2→3、首 tab=incidents）与 `nav-groups.test.ts` 相关断言
- [ ] T014 [US1] 🅲 `frontend/lib/incident-api.ts`（queue/history/detail 客户端，类型=契约 §1-3）+ `frontend/components/workspace/views/incidents-view.tsx` 队列骨架：活跃区/近 24h 已解决降权区/历史入口、正向空态、15s 轮询、卡片基础剖面（严重度/标题/计数/状态）

**Checkpoint**: quickstart 场景 1/2 可全程走通——MVP 可交付。

## Phase 4: User Story 2 - 分诊：爆炸半径 × 时间预算 × 紧迫度排序 (P2)

**Goal**: 卡片自带确定性分诊，队列按紧迫度排序。

**Independent Test**: quickstart 场景 3——有下游+SLA 的失败卡显示两项分诊；neo4j 关停显示「血缘不可用」；排序符合规则。

- [ ] T015 [US2] 🅰 `IncidentTriageService.java`：blastRadius=`LineageQueryService.downstreamTaskLevels().size()` 带探活包装（不可达→NULL，正常空→0，research D7）；timeBudgetAt=下游（含自身）workflow 的 `sla_baseline.baseline_ready_at` time-of-day 投影取最近将来；SLA_BREACH 单=破约时刻+breach_minutes；开单与重开时调用
- [ ] T016 [US2] 🅰 队列排序与富化：`IncidentService.queue` 内存排序四级（时间预算未过期升序 > 已超期 > 爆炸半径降序 > severity rank，并列 last_seen 降序，research D8）；填充 `pendingActionCount`（agent_action PENDING 计数）与 `priorIncidentCount`（同签名 CLOSED 计数）
- [ ] T017 [US2] 🅰 单测：排序四级规则表驱动、投影跨日边界（基线时刻已过今天→明天）、NULL/0 语义区分、SLA 单已超期展示值
- [ ] T018 [US2] 🅲 卡片分诊 UI（`components/workspace/views/incident/` 子组件）：下游徽标（null=血缘不可用/0=无下游）、SLA 倒计时客户端每秒刷新+过期转「已超期」红色态、「历史发生 N 次」提示、诊断/提案占位态（「等待运维编队接入」非错误样式）

**Checkpoint**: quickstart 场景 3 走通。

## Phase 5: User Story 3 - 处置闭环：卡内处置、审批内联、全程留痕 (P3)

**Goal**: 重跑走闸门、审批内联、静默/备注、时间线完整可溯。

**Independent Test**: quickstart 场景 4——重跑按 outcome 分流、OWNER 卡内批准、静默退出队列、时间线全事件可见。

- [ ] T019 [US3] 🅱 处置端点：`IncidentController` 加 `POST /{id}/rerun`（校验 taskInstanceId 归属→`ActionRequest(toolName=incident_rerun, actionType=TASK_RERUN, actorSource=UI, incidentId)`→`GatedActionService.submit`→ACTION 时间线→CAS MITIGATING→返回 `{outcome, actionId}`）、`POST /{id}/suppress`（reason 非空校验）/`unsuppress`、`POST /{id}/notes`（≤2000 字）——契约 §4-6
- [ ] T020 [US3] 🅱 审批回挂：`master/.../application/ApprovalService.java` approve/reject 后经 `agent_action.incident_id` 反查工单、调 `IncidentService.appendTimeline(APPROVAL,...)`（无关联时零行为）
- [ ] T021 [US3] 🅱 契约测试：rerun 三种 outcome 分流（policy_rules 配 L1/默认 L2）、agent_action.incident_id 落库断言、静默缺原因=`suppress_reason_required`、对 CLOSED 操作=`invalid_state`、notes 追加、审批后 APPROVAL 时间线出现
- [ ] T022 [US3] 🅲 卡片动作 UI：重跑按钮（**outcome 分流**：EXECUTED/PENDING_APPROVAL/REJECTED 各自 toast+状态变化，信任后端 message）、静默弹窗（原因必填）、待审批内联（调既有 `/api/approvals/{id}/approve|reject`）、时间线抽屉（kind 分类图标+actor+时间）、备注输入、深链（`refKindToView` 复用 + instance-log 直开）
- [ ] T023 [US3] 🅲 前端 vitest：三区渲染分流、卡片缺省态矩阵（blastRadius null/0 × timeBudget null/过期）、rerun outcome 三分支处理、静默必填校验

**Checkpoint**: quickstart 场景 4/5 走通——全部 story 完成。

## Phase 6: Polish & 收口（主 Claude）

- [ ] T024 收口评审：三线 diff 审读、契约一致性核对（API 响应↔incident-api.ts 类型↔UI 渲染）、FR-014 不变量确认（alerts/event-center 零 diff）
- [ ] T025 全量回归：后端 `dataweave-master,dataweave-api` 测试（setsid 脱离）双库（H2+PG）、`pnpm typecheck && pnpm test && pnpm i18n:lint`、既有 store/nav-groups 测试全绿
- [ ] T026 playwright 浏览器门（scratchpad 脚本）：首开=incidents 激活（SC-004）、注入失败→卡片（SC-001/002）、自动愈合移区（SC-003）、重跑 ≤3 击（SC-005）、分诊展示（SC-006）、无 JS 异常
- [ ] T027 quickstart 场景 1-5 逐条勾验 + tasks.md 全量复选 + 提交

## Dependencies & 执行序

```
T001(schema) ──▶ T004 ──▶ T005 ──┬─▶ 🅰 T007→T008→T009→T010 → T015→T016→T017
T002/T003/T006 可即刻并行         ├─▶ 🅱 T011→T012 → T019→T020→T021（T011 依赖 T005 签名骨架）
                                 └─▶ 🅲 T013→T014 → T018 → T022→T023（仅依赖契约文档，可最早开跑）
全部完成 ──▶ 主 Claude T024-T027
```

- Story 间：US2/US3 依赖 US1 的工单存在，但 🅰🅱🅲 各自线内顺序执行即自然满足。
- MVP = Phase 1-3（US1）。

## 文件所有权表（硬边界，交集为空）

| Agent | 独占文件/目录 |
|---|---|
| 🅰 后端域核心 | `schema.sql`、`docs/architecture.md`、`master/.../domain/incident/**`、`master/.../application/incident/**`（Controller 依赖的 Service 契约见 T005）、`master/.../application/WorkflowSucceededEvent.java`、`master/.../application/WorkerReportService.java`、master 侧 incident 测试 |
| 🅱 后端 HTTP+闸门 | `dataweave-api/.../interfaces/IncidentController.java` + DTO、后端 i18n messages、`master/.../application/ActionRequest.java`、`master/.../application/GatedActionService.java`、`master/.../application/ApprovalService.java`、`master/.../domain/AgentAction.java`、api 侧契约测试 |
| 🅲 前端 | `frontend/lib/workspace/{views.ts,registry.tsx,nav-groups.ts}`、`frontend/lib/workspace/store.test.ts`、`frontend/lib/workspace/nav-groups.test.ts`、`frontend/lib/incident-api.ts`、`frontend/components/workspace/views/incidents-view.tsx`、`frontend/components/workspace/views/incident/**`、`frontend/messages/*.json` |
| 主 Claude | 收口 T024-T027、任何跨界仲裁 |

**跨界规则**: 需要改不属于自己的文件时 STOP，在完成报告中申报，由主 Claude 仲裁——不得自行修改。
