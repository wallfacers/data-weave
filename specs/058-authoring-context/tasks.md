# Tasks: 数据开发 LSP —— 血缘/依赖接地的创作上下文服务

**Input**: Design documents from `/specs/058-authoring-context/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/
**Tests**: 含测试任务——宪法「no test = not done」+ CLAUDE.md 强制。
**Organization**: 按 user story（P1→P2→P3）分期，每期独立可测可用。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[US#]**: 归属的 user story（Setup/Foundational/Polish 无标签）

## Path Conventions

- 后端 master：`backend/dataweave-master/src/main/java/com/dataweave/master/`
- 后端 api：`backend/dataweave-api/src/main/java/com/dataweave/api/`
- CLI：`cli/`
- Skill：`.claude/skills/weft-task-authoring/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 包结构就位。

- [x] T001 创建编排包目录 `backend/dataweave-master/src/main/java/com/dataweave/master/application/authoring/` 与 `package-info.java`，并 `cd backend && ./mvnw -q -pl dataweave-master compile` 零错误

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有 story 共用的地基——草稿抽取复用壳 + 服务骨架 + 共享契约对象。

**⚠️ CRITICAL**: 本阶段完成前，任何 user story 不得开工。

- [x] T002 [P] 定义共享契约记录 `AuthoringContext`/`TableFact`/`NodeRef`/`TruncationNote`/`MissingNote`（对齐 data-model.md）于 `backend/dataweave-master/src/main/java/com/dataweave/master/application/authoring/AuthoringContext.java` 等
- [x] T003 [P] 实现 `DraftLineage` 及草稿抽取适配 `DraftExtractionSupport`——**只调既有** `ScriptLineageService`/`SqlTableExtractor`（research D3 硬不变量，禁止第二套抽取），于 `backend/dataweave-master/.../application/authoring/DraftLineage.java`
- [x] T004 创建 `AuthoringContextService` 骨架（构造注入既有 bean：`LineageQueryService`/`ScriptLineageService`/`CatalogGroundingService`/`DatasourceBoundCatalog`/`WorkflowEdgeRepository`/`WorkflowNodeRepository`/`TaskDefRepository`），方法留桩，于 `backend/dataweave-master/.../application/authoring/AuthoringContextService.java`
- [x] T005 [P] 地基测试：`DraftLineage` 抽取复用既有 extractor 且与 push 时抽取语义等价，于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/DraftLineageTest.java`

**Checkpoint**: 地基就绪——user story 可开工。

---

## Phase 3: User Story 1 - 创作上下文接地 + 依赖 (Priority: P1) 🎯 MVP

**Goal**: 对任务/工作副本草稿返回意图接地包（读写表→上下游 + 表/列血缘 + 数据源 schema），双面暴露，修 MCP 血缘漂移。

**Independent Test**: 多层链路 A←T1←B←T2；对 T1（及其未 push 草稿）请求上下文，返回读表 B/其上游 T2/写表 A/其下游/列血缘均正确且草稿等价。

### Tests for User Story 1 ⚠️

- [ ] T006 [P] [US1] 上下文装配测试：多层链路夹具（A←T1←B←T2）验读写表→上下游 + 列血缘（h2+neo4j），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/AuthoringContextServiceTest.java`
- [ ] T007 [P] [US1] 草稿等价 + 无副作用测试：工作副本草稿与已 push 语义等价，且分析零持久化（FR-004），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/DraftAnalyzeStatelessTest.java`
- [ ] T008 [P] [US1] 防幻觉 + 降级测试：未接地表不虚构上游（SC-005）；某事实源不可用返回部分 + 标注缺失（FR-005），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/AuthoringContextGroundingTest.java`
- [ ] T009 [P] [US1] 依赖合并测试：声明（WorkflowEdge）+ 推导（血缘）合并带 origin（FR-006），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/TaskDependencyViewTest.java`

### Implementation for User Story 1

- [ ] T010 [US1] 实现 `AuthoringContextService.context(...)`：读写表经 `LineageQueryService.upstream/downstream/neighborhood/columnUpstream/expandColumns`；接地经 `CatalogGroundingService`；深度=调用方参数、默认多跳，广度按 `clampLimit`/邻域截断并标注（FR-001/002/003/018）
- [ ] T011 [US1] 实现 `AuthoringContextService.taskDependencies(...)`：合并 `WorkflowEdgeRepository` 声明边 + 推导血缘边 → `TaskDependencyView`（带 origin，FR-006），新增 `TaskDependencyView.java`/`DependencyEdge.java`
- [ ] T012 [US1] 实现工作副本无状态分析：多草稿跨任务依赖先草稿内解析再回退服务端图谱、草稿覆盖同名已 push（FR-004/019），于 `AuthoringContextService` + 请求装配器
- [ ] T013 [US1] REST 控制器 `AuthoringContextController`：`POST /api/authoring-context/analyze` + `GET /api/authoring-context/{taskDefId}`（depth/include 参数，租户+项目隔离），于 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/AuthoringContextController.java`
- [ ] T014 [US1] MCP：在 `McpToolRegistry.registerTools()` 注册 `query_authoring_context` + `query_task_deps`（`requireTenant`，复用服务），于 `backend/dataweave-api/src/main/java/com/dataweave/api/application/mcp/McpToolRegistry.java`
- [ ] T015 [US1] MCP 漂移修正：新增表/列级只读血缘查询承载新面、**保留** `query_lineage` 旧签名不破坏（FR-015，research D4），于 `McpToolRegistry.java`
- [ ] T016 [P] [US1] CLI：`dw context` + `dw deps` 子命令（收集工作副本草稿→`POST /analyze`→`--json`），于 `cli/main.go` + `cli/context/analyze.go`
- [ ] T017 [P] [US1] CLI 测试：analyze 往返 + 输出契约，于 `cli/context/analyze_test.go`
- [ ] T018 [US1] Skill 扩展：`.claude/skills/weft-task-authoring/SKILL.md` 教「编辑前 `dw context` 取接地事实」回路
- [ ] T019 [US1] 双面等价测试：同一已 push 任务 `dw context` 与 MCP `query_authoring_context` 语义一致（SC-006），于 `backend/dataweave-api/src/test/java/com/dataweave/api/AuthoringContextParityIT.java`

**Checkpoint**: US1 完整可用、可独立演示（MVP）。

---

## Phase 4: User Story 2 - 复用推荐 (Priority: P2)

**Goal**: 对任务/草稿返回写表目标重叠的复用候选，确定性排序。

**Independent Test**: 已有任务 X 产出 `dw.user_daily`；对意图产出同表/高重叠列的草稿返回含 X 的候选，无关草稿返回空。

### Tests for User Story 2 ⚠️

- [ ] T020 [P] [US2] 复用测试：写表目标重叠命中 + 无重叠空候选 + 确定性排序稳定（SC-003），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/ReuseCandidateTest.java`

### Implementation for User Story 2

- [ ] T021 [US2] 实现 `ReuseScorer`（写表目标表/列重叠为主 + 名称相似加权，确定性，FR-008），于 `backend/dataweave-master/.../application/authoring/ReuseScorer.java`
- [ ] T022 [US2] 实现 `AuthoringContextService.reuseCandidates(...)`：以草稿写表目标对既有 `TaskDef`/表定义检索重叠 + 打分排序，无重叠返回空（FR-007/009），新增 `ReuseCandidate.java`
- [ ] T023 [US2] 暴露：REST `analyze`/`GET` 的 `include=reuse` + MCP `query_reuse_candidates`，于 `AuthoringContextController.java` + `McpToolRegistry.java`
- [ ] T024 [P] [US2] CLI：`dw reuse` 子命令 + 测试，于 `cli/main.go` + `cli/context/reuse_test.go`

**Checkpoint**: US1 + US2 均独立可用。

---

## Phase 5: User Story 3 - 一致性诊断 (Priority: P3)

**Goal**: 返回建议性诊断——悬空上游/下游列契约破坏/重复定义/声明 DAG vs 实际血缘背离。

**Independent Test**: 造「读 B 但未声明对 B 生产者依赖」→ 报缺依赖；造「声明上游但不读其产物」→ 报僵依赖。

### Tests for User Story 3 ⚠️

- [ ] T025 [P] [US3] 诊断测试：缺依赖/僵依赖/列契约破坏/一致零误报（SC-004），于 `backend/dataweave-master/src/test/java/com/dataweave/master/application/authoring/ConsistencyDiagnosticTest.java`

### Implementation for User Story 3

- [ ] T026 [US3] 实现 `AuthoringContextService.diagnose(...)`：悬空上游、下游列契约破坏、重复定义、声明（WorkflowEdge）vs 推导（血缘）背离（缺/僵依赖），三级严重度建议性（FR-010/011/012），新增 `ConsistencyDiagnostic.java`
- [ ] T027 [US3] 暴露：REST `include=diagnostics` + MCP `query_lineage_diagnostics`，于 `AuthoringContextController.java` + `McpToolRegistry.java`
- [ ] T028 [P] [US3] CLI：`dw check` 子命令（建议性、诊断非空不改退出码）+ 测试，于 `cli/main.go` + `cli/context/check_test.go`

**Checkpoint**: 三个 story 均独立可用。

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T029 [P] 更新知识地图：`CLAUDE.md` Knowledge Map 增 authoring-context 行 + 指向 spec
- [ ] T030 [P] 性能核验 SC-002：默认深度、数千定义夹具下 analyze < 5s，记录数据于 `specs/058-authoring-context/` 备注
- [ ] T031 [P] Quickstart 验证：按 `quickstart.md` 端到端真跑（context/deps/reuse/check）
- [ ] T032 合并前跨特性缝检：复核与 057-system-settings / 054 无共享改面冲突，跑共享面测试（宪法治理）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**：无依赖，立即开始
- **Foundational (P2)**：依赖 Setup；**阻塞所有 user story**
- **User Stories (P3-5)**：均依赖 Foundational；P1 是 MVP
- **Polish (P6)**：依赖所需 story 完成

### User Story Dependencies

- **US1 (P1)**：Foundational 后即可开工——地基，独立可测
- **US2 (P2)**：Foundational 后可开工；复用 US1 的服务视图但独立可测（可在 US1 后并行）
- **US3 (P3)**：Foundational 后可开工；复用 US1 抽取+接地但独立可测

### Within Each User Story

- 测试（若先写）→ 服务方法 → REST/MCP 暴露 → CLI → 等价/集成
- 同一文件的任务顺序执行；`[P]` 标记的不同文件任务可并行

### Parallel Opportunities

- Foundational：T002/T003/T005 可并行（不同文件）
- US1 测试：T006/T007/T008/T009 可并行；实现里 T016/T017（CLI）与后端 T010-T015 可并行推进
- 跨 story：US2、US3 在 Foundational 后可与 US1 收尾并行（各自独立可测）

---

## Parallel Example: User Story 1

```text
# 先并行启动 US1 全部测试：
T006 多层链路上下文装配  |  T007 草稿等价+无副作用  |  T008 防幻觉+降级  |  T009 依赖合并
# 后端实现（T010-T015 多为同/邻文件，按序）与 CLI（T016/T017 并行）分头推进
```

---

## Implementation Strategy

- **MVP = US1（Phase 1+2+3）**：地基 + 创作上下文 + 依赖 + 双面暴露 + 修 MCP 漂移——交付即让 agent 获得链路视野，独立可用。
- **增量**：US2 复用推荐、US3 一致性诊断依次叠加，各自独立可测可演示。
- **硬不变量贯穿**：草稿抽取复用既有 extractor（禁第二套引擎）；纯读无写（不过写闸门）；确定性零 LLM；租户+项目隔离；防幻觉虚构=0。
