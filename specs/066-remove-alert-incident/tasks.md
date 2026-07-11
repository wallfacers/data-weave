---

description: "Task list for 066-remove-alert-incident: 移除人工告警/事件/质量/工单体系"
---

# Tasks: 移除人工告警/事件/质量/工单体系

**Input**: Design documents from `/specs/066-remove-alert-incident/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: 删除特性——不写新功能测试；测试任务 = 删除引用已删类的测试 + 全量回归不退化验证。

**Organization**: Tasks grouped by user story. 依赖顺序 US3 → US1 → US2 → US4 → US5/Polish（US1 必须在 US2 前：alert 模块的 `AlertSignalListener` 是 AlertSignal 唯一消费者，删 alert 后 US2 删 AlertSignal 类才不破编译）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (基线确认)

**Purpose**: 确认 066 分支工作区状态，quality 并行删除在位

- [X] T001 确认 066 分支工作区状态：`git branch --show-current` = 066-remove-alert-incident；`git status` 含 quality 未提交删除 + spec/plan 工件；specs/066-remove-alert-incident/ 工件齐全

---

## Phase 2: Foundational (删除基线)

**Purpose**: 确认 quality 并行删除的编译自洽性——这是 066 所有后续工作的基线

**⚠️ CRITICAL**: quality 删除是工作区现状（并行工作所做），必须先确认不破编译再继续

- [X] T002 验证 quality 删除后全模块编译零错：`cd backend && ./dev-install.sh`；若断裂（残留引用），修复后再次编译至零错

**Checkpoint**: quality 删除自洽，基线绿——后续 US 可在此基础上推进

---

## Phase 3: User Story 3 - quality 收尾 (Priority: P1)

**Goal**: 收尾 quality 模块删除的残留（data.sql 策略种子）

**Independent Test**: `data.sql` 无 `QUALITY_*` 策略 + grep `com.dataweave.master.quality` 零命中 + 编译绿

### Implementation for User Story 3

- [X] T003 [US3] 删除 `backend/dataweave-api/src/main/resources/data.sql` 的 `QUALITY_RULE_WRITE`/`QUALITY_RUN` 两条 policy_rule 种子（行 489-490）
- [X] T004 [US3] grep 验证：现存 java 对 `com.dataweave.master.quality`/`com.dataweave.api.quality` 零引用；schema.sql 无 `quality_*` 表；data.sql 无 `QUALITY_*` 策略
- [ ] T005 [US3] 提交「quality 收尾」

**Checkpoint**: quality 模块彻底清零

---

## Phase 4: User Story 1 - alert 整模块删除 (Priority: P1) 🎯 MVP

**Goal**: 删除告警中心全部代码/表/前端/API/策略种子

**Independent Test**: `/api/alert/*` 返回 404 + `alert_*` 表消失 + 前端无告警入口 + 编译绿

### Implementation for User Story 1

- [ ] T006 [P] [US1] 删除 `backend/dataweave-alert/src/` 整目录（main + test 全部 .java + resources/messages.properties）
- [ ] T007 [US1] 删除 `backend/dataweave-alert/pom.xml` + `backend/pom.xml` 的 `<module>dataweave-alert</module>` 声明 + `backend/dataweave-api/pom.xml` 的 `dataweave-alert` 依赖
- [ ] T008 [P] [US1] 删除 `backend/dataweave-api/src/main/resources/schema.sql` 的 7 张 `alert_*` 表 CREATE（行 900-1050 区域：alert_rule/channel/route/event/notification/silence/poll_fire）+ DROP 段（行 82-88）+ `alert_*` project_id 回填段
- [ ] T009 [US1] 删除 `backend/dataweave-api/src/main/resources/data.sql` 的 `ALERT_RULE_WRITE`/`ALERT_TEST_SEND` 两条 policy_rule 种子（行 486-487）
- [ ] T010 [P] [US1] 删除前端 `frontend/components/workspace/views/alerts-view.tsx` + 清理 `frontend/lib/workspace/registry.tsx`/`views.ts`/`nav-groups.ts`/`nav-permissions.test.ts` 的 alerts 注册
- [ ] T011 [P] [US1] 删除前端 i18n `frontend/messages/zh-CN.json` + `en-US.json` 的 `alerts` 块（~40 key）+ `nav.alerts` + `leftNav.groups.alerting` + `eventVsPoll`/`eventRatio`/`btnSubscribe`（两 bundle 保 parity）
- [ ] T012 [P] [US1] 删除 api 测试 `backend/dataweave-api/src/test/java/com/dataweave/api/AlertSeamIT.java` + `AlertCrossProjectGuardTest.java`
- [ ] T013 [US1] 清理 `backend/dataweave-api/src/main/resources/ops-messages.properties` 的 `ops.alert.*` 模板；`application.yml` 的 `stuck-wait-alert-ms`（行 78）保留改注释、`default-response-timeout-ms`（行 103）保留改注释去 alert 举例；清理 `ProjectAuthz.java` 的 `AlertController.requireOwned` 注释
- [ ] T014 [US1] 验证：`./dev-install.sh` 编译零错 + `pnpm typecheck` + grep alert 零命中（`grep -rni "com.dataweave.alert" backend --include='*.java' | grep -v /target/`）
- [ ] T015 [US1] 提交「alert 整模块删除」

**Checkpoint**: 告警中心前后端全链下线，AlertSignal 失去唯一消费者（为 US2 铺路）

---

## Phase 5: User Story 2 - AlertSignal 信号桥删除 (Priority: P1)

**Goal**: 删除 AlertSignal 类 + 5 个发布点 + 相关测试，守调度死锁四不变量

**Independent Test**: `AlertSignal` 类消失 + 5 类无 `publishEvent(AlertSignal)` + master test 绿 + 调度 CAS/锁/状态转移不变

### Implementation for User Story 2

- [ ] T016 [US2] `backend/dataweave-master/src/main/java/com/dataweave/master/application/InstanceStateMachine.java`：删 3 处 `publishEvent(new AlertSignal(...))`（行 414/441/467）+ 2 个 helper（`publishAlertSignalForTask`/`publishAlertSignalForWorkflow`，行 417/474）+ `import AlertSignal`；**保留** `eventPublisher` 字段（`publishTaskState`/`publishWorkflowState` 喂 DAG SSE 运行态观测仍用）
- [ ] T017 [P] [US2] `backend/dataweave-master/.../application/LeaseReaper.java`：删 `NODE_OFFLINE` publish（行 214）+ `eventPublisher` 字段 + 构造参数 + import
- [ ] T018 [P] [US2] `backend/dataweave-master/.../application/SlaService.java`：删 `SLA_BREACH` publish（行 237）+ `eventPublisher` 字段 + 两处构造器参数 + import
- [ ] T019 [P] [US2] `backend/dataweave-master/.../application/StuckInstanceSweeper.java`：删 `NODE_STARVATION`/`TASK_SUSPENDED` publish（行 135/148）+ `eventPublisher` 字段 + 构造参数 + import；**保留** `stuckWaitAlertMs`（行 105 检测阈值用）；清理行 130 `ctx.put("stuckWaitAlertMs")` 若仅服务信号 payload
- [ ] T020 [P] [US2] `backend/dataweave-master/.../application/TimeoutSweeper.java`：删 `TASK_TIMEOUT` publish（行 131）+ `eventPublisher` 字段 + 构造参数 + import
- [ ] T021 [US2] 删除 `backend/dataweave-master/src/main/java/com/dataweave/master/domain/signal/AlertSignal.java`
- [ ] T022 [P] [US2] 删除/改测试：`StuckInstanceSweeperTest.java`（删 AlertSignal 断言，行 82-106）、`QualitySignalUnifiedTest.java`（删——quality 已删）、确认 `AlertSeamIT` 已由 T012 删
- [ ] T023 [US2] 验证：`./dev-install.sh` 编译零错 + `./mvnw -pl dataweave-master test` 绿 + grep `AlertSignal` 零命中
- [ ] T024 [US2] 提交「AlertSignal 信号桥删除」

**Checkpoint**: 故障信号桥彻底清零，调度核心逻辑不变

---

## Phase 6: User Story 4 - incident 残留清理 (Priority: P2)

**Goal**: 清理 incident/event/health 的 i18n 孤儿 key

**Independent Test**: `messages*.properties` 无 `incident.*` key

### Implementation for User Story 4

- [ ] T025 [P] [US4] 删除 `backend/dataweave-master/src/main/resources/messages.properties` + `messages_en_US.properties` 的 4 条 `incident.*` 孤儿 key（`incident.not_found`/`invalid_state`/`suppress_reason_required`/`action_target_mismatch`，行 421-425）
- [ ] T026 [US4] 确认 `specs/027`、`specs/043`、`specs/064` 保留作历史决策记录（不删规格目录）；提交「incident 残留清理」

**Checkpoint**: 065 监督席残留清零

---

## Phase 7: User Story 5 + Polish - 全量验证与收尾 (Priority: P2)

**Purpose**: 调度核心与闸门不退化验证 + schema 升版本 + 跨切收尾

- [ ] T027 升级 `backend/dataweave-api/src/main/resources/schema.sql` 版本到 `0.18.0`：改文件头 `Schema Version` 注释 + `schema_version` 表 INSERT 行，标注「066 移除告警/质量体系——删 alert_* 7 表」
- [ ] T028 全量编译（防 build-cache 假绿）：`cd backend && ./mvnw clean -Dmaven.build.cache.enabled=false compile`
- [ ] T029 全量后端测试：`cd backend && setsid bash -c './mvnw clean -Dmaven.build.cache.enabled=false -pl dataweave-master,dataweave-api,dataweave-worker -am test >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown`（认 `Tests run: N>0`，WSL2 setsid 脱离）
- [ ] T030 前端验证：`cd frontend && pnpm typecheck && pnpm test`
- [ ] T031 调度并发核验（因动 InstanceStateMachine，CLAUDE.md 硬规则）：跑 every-minute cron 端到端，确认 `started_at − created_at ≈ 0`、根节点 `attempt=1`、零「跳过下发/中止执行」stragglers；查 `task_instance` attempt-count 与延迟无异常相关
- [ ] T032 schema 启动验证：H2（`-Dspring-boot.run.profiles=h2`）+ PostgreSQL（`docker compose up -d`）双存储启动 + `GET /api/health`
- [ ] T033 grep 零命中全验证 + i18n parity：`AlertSignal`/`com.dataweave.alert`/`com.dataweave.master.quality`/`alert_*` 表/`ALERT_*`·`QUALITY_*` 策略全零；`diff <(jq keys zh-CN) <(jq keys en-US)` 空
- [ ] T034 审查 `CLAUDE.md` Knowledge Map：060 条目「StuckInstanceSweeper 无节点等待告警」等过时告警/事件/质量/工单引用按需更新（恢复唤醒保留，告警语义改检测）
- [ ] T035 最终提交 + 分支整合准备：确认 4 提交结构（quality 收尾 / alert 模块 / 信号桥 / incident 残留 + polish）落齐，工作区干净

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Setup——确认 quality 删除基线，**阻塞所有 US**
- **US3 (Phase 3)**: 依赖 Foundational——quality 收尾最轻，最先做
- **US1 (Phase 4)**: 依赖 Foundational——alert 模块删除
- **US2 (Phase 5)**: **依赖 US1**——alert 模块删后 AlertSignal 失去唯一消费者，再删 AlertSignal 类才不破编译
- **US4 (Phase 6)**: 依赖 Foundational——incident 残留独立，可在 US1/US2 后任意时点
- **US5+Polish (Phase 7)**: 依赖所有 US 完成

### User Story Dependencies

- **US3 (P1)**: 独立，最先（已大部分由并行工作完成）
- **US1 (P1)**: 独立于 US3，但建议在 US3 后（提交顺序清晰）
- **US2 (P1)**: **依赖 US1**（AlertSignalListener 在 alert 模块）
- **US4 (P2)**: 独立
- **US5 (P2)**: 横切验证，依赖所有删除完成

### Parallel Opportunities

- T006/T008/T010/T011/T012（US1 内不同文件）可并行
- T017/T018/T019/T020（US2 的 4 个发布点类，不同文件）可并行
- T025（US4）与 US1/US2 并行（不同文件）

---

## Parallel Example: User Story 2（4 个发布点类）

```bash
# 4 个发布点类互不相关，可并行删除 AlertSignal publish + eventPublisher 字段：
Task: "LeaseReaper.java 删 NODE_OFFLINE publish + eventPublisher 字段"
Task: "SlaService.java 删 SLA_BREACH publish + eventPublisher 字段"
Task: "StuckInstanceSweeper.java 删 NODE_STARVATION/TASK_SUSPENDED publish + eventPublisher 字段"
Task: "TimeoutSweeper.java 删 TASK_TIMEOUT publish + eventPublisher 字段"
# InstanceStateMachine (T016) 须单独做——保留 eventPublisher 字段，逻辑不同
```

---

## Implementation Strategy

### MVP First（US3 + US1）

1. Phase 1 Setup + Phase 2 Foundational（确认 quality 基线）
2. Phase 3 US3（quality 收尾——最轻，快速落袋）
3. Phase 4 US1（alert 整模块删除——主要人工运维载体下线）
4. **STOP and VALIDATE**: 编译绿 + grep alert/quality 零命中 + 前端无告警/质量入口
5. 此时可验证 Agent 运维接管的前提（人工告警/质量已清场）

### Incremental Delivery

1. Foundational → quality 基线绿
2. US3 → quality 清零 → 提交
3. US1 → alert 清零 → 提交 → 验证
4. US2 → 信号桥清零 → 提交 → 调度核验
5. US4 → incident 残留清零 → 提交
6. US5+Polish → 全量验证 + schema 升版本 → 最终提交

---

## Notes

- 删除特性无新功能测试；"测试"= 删引用已删类的测试 + 全量回归不退化
- 每 US 提交后编译必须绿（删除提交链可独立 review/回滚）
- T031 调度并发核验是硬门——动 InstanceStateMachine 必须真跑核实，非单测能覆盖
- `stuck-wait-alert-ms` 配置保留（检测用），`eventPublisher` 在 InstanceStateMachine 保留（观测用）——见 research.md R2/R3
- 不碰 worker HeartbeatReporter（容量检测已由 4e043c6 单独提交）
