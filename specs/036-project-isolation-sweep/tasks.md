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
- [ ] T008 [P] [FND] 前端约定：受隔离请求附 `X-Project-Id` 头或 `?projectId=`（沿用 catalog/datasource 既有约定，无需新封装）— **待前端联调时随四路带入**
- [x] T009 [FND] 成员校验测试 `ProjectScopeTest`（成员放行/非成员 forbidden/缺失 required/403 映射）✅ 已写，隔离 worktree 验证
- [x] T010 [FND] **契约已冻结并修订** `contracts/foundation-contract.md`（落地修正：master @Service + 显式参数 + 真校验）✅

> **⚠️ 并行隔离告警**：发现外部 agent 未用隔离 worktree、直接在主副本并发改 A 路(Ops)/schema，致主副本编译崩坏。地基验证已改用**隔离 worktree**（`../dw-036-fnd`）完成。四路**必须**各自 worktree，否则持续互相打断（见 launch-prompts 硬约束①②）。

**Checkpoint**: 地基就绪 → 四路可并行开工。

---

## Phase 3: User Story 1 — 运维/运行态 + 日期联动（P1，worktree A）🎯 MVP

**Goal**: ops 调度/运行态总览/实例表按 (projectId, bizDate) 收敛，消除裸查，下钻保项目上下文。

### Tests（先写并 FAIL）
- [ ] T011 [P] [US1] WebTestClient 双项目实例隔离测试：`/api/ops/instances`、`/api/ops/workflow-instances`、`summary`、`eta-summary` 跨项目 0 串（`dataweave-master/src/test/.../OpsProjectIsolationTest.java`）
- [ ] T012 [P] [US1] bizDate 收敛测试：(projectId, bizDate) 联合过滤正确

### Implementation
- [ ] T013 [US1] `OpsService.instances()` 去 `findAll()`，改按 `TenantContext.projectId()` 过滤（新增/调用 `findByTenantIdAndProjectId...`）
- [ ] T014 [US1] `OpsService.periodicWorkflows()` 及其余无隔离查询接入项目作用域
- [ ] T015 [US1] `OpsController` instances/workflow-instances/periodic/backfill/summary/eta-summary 端点接入 `ProjectScope.require`，(projectId,bizDate) 传递
- [ ] T016 [US1] SSE 日志/DAG 事件流补项目订阅校验（若可跨项目订阅）
- [ ] T017 [P] [US1] 前端 `ops/periodic-instances-panel.tsx` fetcher 附 projectId
- [ ] T018 [P] [US1] 前端 `ops/workflow-instances-panel.tsx` fetcher 附 projectId
- [ ] T019 [P] [US1] 前端 `ops/backfill-panel.tsx` fetcher 附 projectId
- [ ] T020 [US1] 下钻详情 tab（DAG/instance-log/workflow-instance-detail）params 透传 projectId
- [ ] T021 [US1] 浏览器验证：切项目/切日期实例表刷新、下钻不串

**Checkpoint**: US1 独立可用 = 项目切换首次成为真隔离（MVP）。

---

## Phase 4: User Story 2 — 指标 + 血缘 + 时效 + 日期观察（P1，worktree B）

**Goal**: metrics/lineage/freshness 按项目隔离；指标看板支持 bizDate；移除血缘硬编码。

### Tests
- [ ] T022 [P] [US2] 双项目指标隔离测试 `/api/metrics`（`dataweave-master/src/test/.../MetricProjectIsolationTest.java`）
- [ ] T023 [P] [US2] `LineageService` 用上下文而非常量的单测；neo4j 双项目血缘不串
- [ ] T024 [P] [US2] 指标按 bizDate 返回对应快照测试

### Implementation
- [ ] T025 [US2] `MetricService.listLatest()/findLatestByCode()` 改按 `TenantContext.projectId()` 过滤（atomic/derived_metrics 已有列）
- [ ] T026 [US2] `MetricsController` 接入 `ProjectScope.require`；增加 bizDate 观察查询路径
- [ ] T027 [US2] `LineageService.lineageOf()` 移除硬编码 `1L,1L`，从上下文取 tenantId/projectId（neo4j 查询按项目作用域）
- [ ] T028 [US2] freshness/时效及其余含隔离列未收口读路径改按 projectId 过滤
- [ ] T029 [P] [US2] 前端 `metrics-view.tsx`：附 projectId + bizDate 选择（复用 ops 模型），缺数据空态
- [ ] T030 [P] [US2] 前端 `freshness-view.tsx`：附 projectId
- [ ] T031 [P] [US2] 前端血缘图视图（若有）：附 projectId
- [ ] T032 [US2] 浏览器 + neo4j（etl-neo4j）真验

**Checkpoint**: 指标/血缘/时效独立隔离可用。

---

## Phase 5: User Story 3 — 告警 + 质量 + Schema 迁移（P2，worktree C）

**Goal**: alert_*/quality_* 升级为项目隔离，含补列+回填；判定 cron_fire/sla_baseline 归属。

### Schema（仅改 `backend/dataweave-api/src/main/resources/schema.sql`）— ✅ C agent 已完成
- [x] T033 [US3] `alert_rule/event/channel/route` 增 `project_id` + 索引 ✅
- [x] T034 [US3] `quality_rule/quality_check_run` 增 `project_id` + 索引 ✅
- [x] T035 [US3] cron_fire/sla_baseline **文档化豁免**（调度护栏，加列破去重语义/不增查询价值）✅
- [x] T036 [US3] 存量幂等回填到租户默认项目 ✅（schema.sql 内）
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
- [ ] T045 [P] [US4] 后端角色授权测试：VIEWER/EDITOR/OWNER 越权写被拒（`dataweave-master/src/test/.../ProjectRoleAuthzTest.java`）
- [ ] T046 [P] [US4] 前端 vitest：三角色菜单可见差异

### Implementation
- [ ] T047 [US4] 后端"当前用户在当前项目角色/权限集"解析（project_member + role/role_permission），复用现有角色枚举
- [ ] T048 [US4] 受保护写端点接入项目角色授权，越权抛 `project.role.forbidden`（复用 GatedActionService/PolicyEngine，零 bypass）
- [ ] T049 [US4] 前端权限查询接口 + `lib/auth.tsx` 结合当前项目权限
- [ ] T050 [P] [US4] `left-nav.tsx` NavItem 按权限过滤（无权限入口不渲染）
- [ ] T051 [P] [US4] `registry.tsx`/`nav-groups.ts` 视图级权限过滤（无权限视图不可直达）
- [ ] T052 [US4] 切项目触发角色/菜单/权限重算（配合 useProjectContext）
- [ ] T053 [P] [US4] i18n：messages/{zh-CN,en-US}.json 补 project/role/permission/menu 命名空间 + 越权提示，双 bundle 键集一致
- [ ] T054 [US4] 浏览器验证三角色菜单差异 + 切项目重算

**Checkpoint**: 角色/菜单隔离可用。

---

## Phase 7: Polish & 集成兜底（收尾方）

- [x] T055 [FND] 集成合并：**C 路 schema 先合** → A/B/D → 重跑地基接缝 + 共享面测试 ✅ 收口方本轮：全量回归(alert+api, 71 类 0 失败)；修复 036 遗留的共享面红——`AlertRuleCrossTenantQueryTest` 内联建表补 project_id；`OpsWorkflowFilter/OpsLatestInstance/OpsDataCenter/LineageGraphEndpointTest` 补 `X-Project-Id` 头（US1/US2 加了 ProjectScope.require 却漏更新既有端点测试）
- [ ] T056 [FND] 补漏遗漏的"含隔离列却未收口"读路径（四路回报后逐项）
- [x] T057 [FND] 产出并勾选 **SC-001 受隔离接口全盘清单**（已隔离/本次收口/平台级豁免，每'本次收口'项对应测试）✅ `specs/036-project-isolation-sweep/sc-001-isolation-inventory.md`
- [ ] T058 [FND] 端到端验证：SC-002 跨项目 0 泄漏、SC-003 日期收敛、SC-004 角色矩阵一致、SC-005 迁移无损、SC-006 四路可独立交付
- [ ] T059 [P] [FND] i18n 键集一致性 CI + `pnpm design:lint`（若涉视觉）
- [ ] T060 [FND] 更新 CLAUDE.md 知识库导航（项目隔离入口）+ checklists 收口

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
