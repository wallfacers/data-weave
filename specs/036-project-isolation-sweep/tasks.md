# Tasks: 项目级数据隔离全盘收口

**Input**: Design documents from `specs/036-project-isolation-sweep/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/foundation-contract.md ✅

**Tests**: 包含（spec 明确要求"双项目不串数据"测试，无测试=未完成）。

**Organization**: 按用户故事分组 → 映射到并行 worktree（Foundation=收尾方；US1=A；US2=B；US3=C；US4=D）。

## Format: `[ID] [P?] [Story] Description`
- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: FND(地基) / US1(A) / US2(B) / US3(C) / US4(D)

---

## Phase 1: Setup

- [ ] T001 各路建 worktree：`git worktree add ../dw-036-<a|b|c|d> -b 036-iso-<x>`（收尾方在 main 落地地基）
- [ ] T002 [P] 各路阅读 spec.md + CLAUDE.md + contracts/foundation-contract.md，确认所属域冲突面边界

---

## Phase 2: Foundational（地基 — 阻塞所有故事，收尾方先行）

**⚠️ CRITICAL**: 契约冻结前，US1~US4 不得开工。

- [x] T003 [FND] `TenantContext` 增加 `projectId` 字段 + `projectId()` 访问器 + 4 参 `set` 重载（`dataweave-api/.../TenantContext.java`）✅ 已落地
- [x] T004 [FND] `JwtAuthFilter` 解析 projectId（`X-Project-Id` 头优先 / `?projectId=` 兜底），置入 `TenantContext` + `exchange` 属性（供 alert 模块读），`doFinally` clear ✅ 已落地
- [~] T005 [FND] `McpAuthFilter`：MCP 无运行时项目选择，projectId 保持 null、租户隔离不变——**文档化豁免**，无需改动
- [x] T006 [FND] 实现 `ProjectScope`（**落 master 作 `@Service`**，非 api 静态）：`require(tenantId,userId,projectId)` **真成员校验**（`project_member` 查库，非成员→`project.forbidden(403)`）+ `isMember` 软校验。roleOf 归 D 路。✅ 已落地
- [x] T007 [FND] 错误码 `project.required`/`project.forbidden` 双语 messages（zh+en 均已存在）+ 经 `GlobalExceptionHandler` 本地化 ✅（`project.role.forbidden` 由 D 路加）
- [x] T008 [P] [FND] 前端约定：受隔离请求附 `X-Project-Id` 头或 `?projectId=`（沿用 catalog/datasource 既有约定，无需新封装）— ✅ 已冻结于 contracts/foundation-contract.md，`lib/project-api.ts` 已有 X-Project-Id 示范
- [x] T009 [FND] 成员校验测试 `ProjectScopeTest`（成员放行/非成员 forbidden/缺失 required/403 映射）✅ 已写，隔离 worktree 验证
- [x] T010 [FND] **契约已冻结并修订** `contracts/foundation-contract.md`（落地修正：master @Service + 显式参数 + 真校验）✅

> **⚠️ 并行隔离告警**：发现外部 agent 未用隔离 worktree、直接在主副本并发改 A 路(Ops)/schema，致主副本编译崩坏。地基验证已改用**隔离 worktree**（`../dw-036-fnd`）完成。四路**必须**各自 worktree，否则持续互相打断（见 launch-prompts 硬约束①②）。

**Checkpoint**: 地基就绪 → 四路可并行开工。

---

## Phase 3: User Story 1 — 运维/运行态 + 日期联动（P1，worktree A）🎯 MVP

**Goal**: ops 调度/运行态总览/实例表按 (projectId, bizDate) 收敛，消除裸查，下钻保项目上下文。

### Tests（先写并 FAIL）
- [x] T011 [P] [US1] WebTestClient 双项目实例隔离测试：`/api/ops/instances`、`/api/ops/workflow-instances`、`summary`、`eta-summary` 跨项目 0 串（`dataweave-master/src/test/.../OpsProjectIsolationTest.java`）
- [ ] T012 [P] [US1] bizDate 收敛测试：(projectId, bizDate) 联合过滤正确 + 切项目 bizDate 重置为 T-1 + 返回原项目恢复上次日期

### Implementation
- [x] T013 [US1] `OpsService.instances()` 去 `findAll()`，改按 `TenantContext.projectId()` 过滤（新增/调用 `findByTenantIdAndProjectId...`）
- [x] T014 [US1] `OpsService.periodicWorkflows()` 及其余无隔离查询接入项目作用域
- [x] T015 [US1] `OpsController` instances/workflow-instances/periodic/backfill/summary/eta-summary 端点接入 `ProjectScope.require`，(projectId,bizDate) 传递
- [ ] T016 [US1] SSE 日志/DAG 事件流补项目订阅校验（若可跨项目订阅）
- [x] T017 [P] [US1] 前端 `ops/periodic-instances-panel.tsx` fetcher 附 projectId
- [x] T018 [P] [US1] 前端 `ops/workflow-instances-panel.tsx` fetcher 附 projectId
- [ ] T019 [P] [US1] 前端 `ops/backfill-panel.tsx` fetcher 附 projectId
- [ ] T020 [US1] 下钻详情 tab（DAG/instance-log/workflow-instance-detail）params 透传 projectId
- [ ] T021 [US1] 浏览器验证：切项目/切日期实例表刷新、下钻不串

**Checkpoint**: US1 独立可用 = 项目切换首次成为真隔离（MVP）。

---

## Phase 4: User Story 2 — 指标 + 血缘 + 时效 + 日期观察（P1，worktree B）

**Goal**: metrics/lineage/freshness 按项目隔离；指标看板支持 bizDate；移除血缘硬编码。

### Tests
- [x] T022 [P] [US2] 双项目指标隔离测试 `/api/metrics`（`dataweave-master/src/test/.../MetricProjectIsolationTest.java`）
- [ ] T023 [P] [US2] `LineageService` 用上下文而非常量的单测；neo4j 双项目血缘不串
- [x] T024 [P] [US2] 指标按 bizDate 返回对应快照测试

### Implementation
- [x] T025 [US2] `MetricService.listLatest()/findLatestByCode()` 改按 `TenantContext.projectId()` 过滤（atomic/derived_metrics 已有列）
- [ ] T026 [US2] `MetricsController` 接入 `ProjectScope.require`；增加 bizDate 观察查询路径
- [x] T027 [US2] `LineageService.lineageOf()` 移除硬编码 `1L,1L`，从上下文取 tenantId/projectId（neo4j 查询按项目作用域）
- [x] T028 [US2] freshness/时效及其余含隔离列未收口读路径改按 projectId 过滤
- [ ] T029 [P] [US2] 前端 `metrics-view.tsx`：附 projectId + bizDate 选择（复用 ops 模型），缺数据空态
- [x] T030 [P] [US2] 前端 `freshness-view.tsx`：附 projectId
- [x] T031 [P] [US2] 前端血缘图视图（若有）：附 projectId
- [ ] T032 [US2] 浏览器 + neo4j（etl-neo4j）真验

**Checkpoint**: 指标/血缘/时效独立隔离可用。

---

## Phase 5: User Story 3 — 告警 + 质量 + Schema 迁移（P2，worktree C）

**Goal**: alert_*/quality_*/cron_fire/sla_baseline 升级为项目隔离，含补列+回填+升版。

### Schema（仅改 `backend/dataweave-api/src/main/resources/schema.sql`）— ✅ C agent 已完成
- [x] T033 [US3] `alert_rule/event/channel/route` 增 `project_id` + 索引 ✅
- [x] T034 [US3] `quality_rule/quality_check_run` 增 `project_id` + 索引 ✅
- [x] T034b [US3] `cron_fire`/`sla_baseline` 增 `project_id` + 索引（统一补列，仅追加 WHERE 过滤，不改 join/lock 语义）✅ schema.sql + CronFire.java + SlaBaseline.java + pom.xml 0.6.1
- [x] T035 [US3] ~~cron_fire/sla_baseline 文档化豁免~~ → **已反转并实现**：统一补列，无豁免（T034b）✅
- [x] T036 [US3] 存量幂等回填：按租户取**最早创建的项目**作为默认项目，将 `alert_*`/`quality_*`/`cron_fire`/`sla_baseline` 存量行 `project_id` 回填到该默认项目 ✅（须扩展至 cron_fire/sla_baseline）
- [x] T037 [US3] `schema_version` 0.4.0 → 0.5.0，pom 同步 0.5.0-SNAPSHOT（三处恒等）✅ / ⚠️ T044 PG+H2 双库加载待验

### Tests
- [x] T038 [P] [US3] 双项目告警隔离测试 `AlertRuleProjectIsolationTest`（repo/service/signalSource 分支/ProjectScope 接缝，4 用例）✅ **exit=0 通过**
- [ ] T039 [P] [US3] 双项目质量隔离测试 — 待 quality 接线后补

### Implementation
- [x] T040 [US3] Alert Rule/Event/Channel/Route domain+repo 补 project_id（`findByTenantIdAndProjectId`+count+INSERT，`insertReturningId`/GeneratedKeyHolder）✅ C agent
- [x] T041 [US3] `AlertController` list（rules/channels/routes/events）项目级 + create 打 projectId + **`ProjectScope.require` 成员校验**；`AlertRuleService.list` 加 projectId ✅ 收尾方本轮
- [ ] T042 [US3] Quality repo ✅（已就位）; **QualityController/Service 接线待做**（repo 方法齐，同 alert 套路）
- [ ] T043 [P] [US3] 前端 `alerts-view.tsx` + 质量视图附 projectId — 待做
- [ ] T044 [US3] 浏览器验证 + PG/H2 双库回归 — 待做
- [x] T045b [US3] alert get/update/delete-by-id 的 project 归属守卫（防跨项目按 id 改删）✅ 收口方本轮：`AlertController.requireOwned` 覆盖 rules/channels/routes/events by-id 读改删，跨项目→`project.forbidden`；`AlertCrossProjectGuardTest`(6/6)

**Checkpoint**: 告警/质量项目隔离可用。

---

## Phase 6: User Story 4 — 项目角色 + 菜单隔离 + i18n（P2，worktree D）

**Goal**: 按当前项目角色做前端菜单/视图隔离 + 后端写授权，切项目重算。

### Tests
- [x] T045 [P] [US4] 后端角色授权测试 ✅ `ProjectRoleAuthzTest`(8, api 模块) + D1 `TaskRoleAuthzTest`(5)/`WorkflowRoleAuthzTest`(4) + D2 `GovernanceRoleAuthzTest`(6)
- [x] T046 [P] [US4] 前端 vitest：三角色菜单可见差异 ✅ `nav-permissions.test.ts`(9)

### Implementation
- [x] T047 [US4] 角色/权限集解析 ✅ `ProjectRoleService`（master @Service，显式三元组参数）+ `ProjectRoleServiceTest`(10)。角色以 seed 为准：ADMIN≈OWNER、DEVELOPER≈EDITOR、VIEWER
- [x] T048 [US4] 受保护写端点项目角色授权 ✅ 收尾方冻结 `ProjectAuthz` 门面（89eeca0）→ D1：TaskController(7 写端点, task:manage)+WorkflowController(11 写端点, workflow:manage)+Task/WorkflowService.create 去 1L/1L 硬编码；D2：MetricMarketplaceController(4 写, metric:manage+去 defaultValue=1)+ApprovalController(approve/reject, project:manage)+ProjectSyncController.push(task:manage)；ProjectController(设置/成员, project:manage) 收尾方先落。by-id 一律按实体归属 projectId 授权
- [x] T049 [US4] 前端权限查询 ✅ `GET /api/projects/{id}/me` + `lib/project-permissions.ts`（zustand store）
- [x] T050 [P] [US4] `left-nav.tsx` 按权限过滤 ✅ canView + 整组隐藏
- [x] T051 [P] [US4] 视图级权限守卫 ✅ `views.ts` requirePermission 声明 + `workspace.tsx` denied 遮罩 + `tab-bar.tsx` 过滤
- [x] T052 [US4] 切项目重算 ✅ `syncProjectPermissions()` 订阅 currentProjectId
- [x] T053 [P] [US4] i18n ✅ `permission` 命名空间双 bundle 键集一致(7/7)
- [x] T054 [US4] 浏览器验证 ✅ 收尾方 Playwright 实证 5/5（2026-07-02）：ADMIN 16 项菜单含 5 写入口；VIEWER 11 项写入口全隐藏、只读可见；VIEWER 深链 `?open=workflow-canvas` 被「权限不足」遮罩拒绝；切项目（ADMIN→非成员项目）菜单 16→11 收敛 + 重拉 `/projects/{id}/me`

**Checkpoint**: 角色/菜单隔离可用。

> **D 路接缝遗留（D1/D2 回报，收尾方汇总）**：① `agent_action` 无 `project_id` 列 → 审批授权暂按请求头项目（requireCurrent）；补列属 C 路 schema 面。② `/{id}/run`（task/workflow）、`/preview-params` 与 ops 运维动作（rerun/kill）未接角色授权——运行类走 PolicyEngine 闸门，非定义编辑；若产品要求 VIEWER 禁 run 另立任务。③ MetricMarketplaceController 的 GET 端点仍 `defaultValue="1"`（只读，归 B 路/收尾方后续）。④ MCP `project_push` 直调 service 不经控制器，风险自适应闸门语义不变。⑤ `AssetCatalogIT`（failsafe 才跑）含 marketplace 写调用，启用 failsafe 时需补 `X-Project-Id: 1` 头。⑥ 血缘 `recordTaskIo` 内部 1L/1L 占位未动。⑦ 调度 E2E 类（CrossCycleDependency 等 ~10 红）为 main 既有时序/环境问题，归 A 路治理。⑧ **项目创建者未自动成为成员**（收尾方 T054 实证发现）：`POST /api/projects` 只写 ownerId 不插 project_member → 创建者在新项目 member=false、无任何权限，且因 addMember 需 project:manage 陷入死锁（新项目无人可管理，只能 DB 直插）；建议后续：create 时自动插入创建者为 ADMIN 成员。⑨ `CatalogApiTest` 夹具曾用虚构项目 id（8101~8105）依赖旧的无守卫行为，收尾方已修（setUp 真造项目+成员行）——后续新测试涉及受 ProjectScope 守卫的端点须造真成员关系。

---

## Phase 7: Polish & 集成兜底（收尾方）

- [x] T055 [FND] 集成合并：**C 路 schema 先合** → A/B/D → 重跑地基接缝 + 共享面测试 ✅ 收口方本轮：全量回归(alert+api, 71 类 0 失败)；修复 036 遗留的共享面红——`AlertRuleCrossTenantQueryTest` 内联建表补 project_id；`OpsWorkflowFilter/OpsLatestInstance/OpsDataCenter/LineageGraphEndpointTest` 补 `X-Project-Id` 头（US1/US2 加了 ProjectScope.require 却漏更新既有端点测试）
- [x] T056 [FND] 补漏遗漏的"含隔离列却未收口"读路径：✅ CatalogController（remove hardcoded defaultValue=1 + ProjectScope on tree/create/update/delete）、AssetCatalogController（9 处 defaultValue=1 → required=false + resolveProjectId + ProjectScope）、TagController（list/create 去硬编码默认值）、schema.sql 豁免反转（cron_fire/sla_baseline 统一补列）、Domain 层 CronFire/SlaBaseline 补 tenantId/projectId 字段
- [x] T057 [FND] 产出并勾选 **SC-001 受隔离接口全盘清单**（已隔离/本次收口/平台级豁免，每'本次收口'项对应测试）✅ `specs/036-project-isolation-sweep/sc-001-isolation-inventory.md`
- [x] T058 [FND] 端到端验证：✅ SC-002 关键测试通过（ProjectScopeTest 5/5、ProjectRoleAuthzTest 8/8、AlertCrossProjectGuardTest 6/6，全部 0 failures）；SC-005 迁移 schema 0.6.1 编译通过；SC-001 全盘清单已更新（豁免反转）；SC-003/SC-004/SC-006 剩余需四路联调后浏览器验证
- [x] T059 [P] [FND] i18n 键集一致性 ✅ 40 keys 双 bundle 一致；后端错误码 project.required/forbidden/role.forbidden 已接入 GlobalExceptionHandler
- [x] T060 [FND] 更新 CLAUDE.md 知识库导航 ✅ SPECKIT 指针指向 036-project-isolation-sweep

---

## Dependencies & Execution Order

- **Phase 1 Setup** → **Phase 2 Foundational（地基，阻塞）** → **Phase 3~6 并行（A/B/C/D）** → **Phase 7 Polish（收尾方）**
- **地基（T003~T010）冻结前，四路不得开工**（硬依赖）。
- 集成顺序：**C 路（schema）先合**，再 A/B/D，最后收尾方回归（R2/R3）。

### Parallel Opportunities
- 地基冻结后 US1(A)/US2(B)/US3(C)/US4(D) **完全并行**（互斥冲突面，见 spec 拆分表）。
- 各故事内 `[P]` 任务（不同文件）可并行；前端 fetcher 各 panel `[P]` 独立。

## Implementation Strategy
1. 收尾方先跑 Setup + Foundational（地基），冻结契约广播。
2. 分发 launch-prompts.md 的 A/B/C/D 给外部 agent 并发。
3. US1(A) 作为 MVP 优先验证（项目切换首次真隔离）。
4. 收尾方按 C→A/B/D 顺序集成，跑 Phase 7 全盘清单与端到端验证兜底。

## Notes
- 冲突面互斥：A=ops、B=metrics/lineage、C=alert/quality/schema、D=权限菜单；地基文件四路只读不改。
- 每任务后即 `./mvnw -q -pl <module> compile` / `pnpm typecheck`；长跑 `setsid` 脱离（WSL2）。
- 每个收口点必须有双项目不串数据测试。
