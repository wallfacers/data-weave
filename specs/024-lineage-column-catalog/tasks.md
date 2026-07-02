# Tasks: 声明驱动的列血缘 Catalog

**Input**: Design documents from `/specs/024-lineage-column-catalog/`（spec.md / plan.md / research.md / data-model.md / contracts/ / quickstart.md）

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: 本项目 CLAUDE.md 规定「新特性必须有测试」，故含单测 + testcontainers-neo4j 集成测任务。

**Organization**: 按 user story 组织（US1 P1 MVP / US2 P1 / US3 P2），每 story 可独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 归属 user story
- 含精确文件路径（dataweave-master 模块，DDD 分层）

---

## Phase 1: Setup

**Purpose**: baseline 留照，确认改动起点干净

- [ ] T001 确认 baseline 编译绿 + 无声明任务现状表级行为留照（`cd backend && ./dev-install.sh -q`；跑一次既有 lineage 测试记录"列血缘为空/UNVERIFIED"基线，供零回归对比）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 跨 story 的共享改动，MUST 先于任何 US 完成

- [x] T002 [P] `ColumnLineageCatalog.lookupTable` 签名加 `(long tenantId, long projectId)`（research R3）—— 改接口 `application/lineage/ColumnLineageCatalog.java` + `EmptyColumnLineageCatalog.java`（签名同步、恒 empty）+ `CalciteColumnLineage.analyze` 透传 + 两个 extract 调用点 `TaskService.java:485` / `ProjectSyncService.java:858` 传 tenant/project（手里已有这俩值）
- [x] T003 [P] Confidence 枚举新增 `DECLARED`（沿用 019 `{CONFIRMED,UNVERIFIED,CONFLICT}`，不重定义）—— `application/lineage/`（随 ColumnEdge 的 confidence 枚举）；检查所有 switch/exhaustive 用法

**Checkpoint**: catalog 签名 + confidence 枚举就绪，US 实现可开始

---

## Phase 3: User Story 1 - 声明 schema 破鸡生蛋 (Priority: P1) 🎯 MVP

**Goal**: Agent 在 `.task.yaml` 声明 `schema`（表→列+类型）→ push 时 `recordTaskIo` 独立 seed `:Column`（带 type/ordinal，不经 ColumnEdge）→ `Neo4jColumnLineageCatalog` 读得到 → 019 出 CONFIRMED 列边

**Independent Test**: 声明 schema 的任务 push 后，neo4j 出现 `:Column`(type/ordinal) + CONFIRMED 列边；不声明的任务维持现状（零回归）

### Implementation

- [x] T004 [P] [US1] `TaskMapper.fromYaml` 解析 `schema` 块（表名→有序 `{name,type}`）→ `TaskDoc` 加 `declaredSchema` 字段 —— `filecontract/mapping/TaskMapper.java` + `filecontract/dto/TaskDoc.java`
- [x] T005 [US1] `TaskMapper.toYaml` 序列化回写 `declaredSchema`（round-trip integrity，Constitution II / SC-005）—— `filecontract/mapping/TaskMapper.java`（依赖 T004）
- [x] T006 [P] [US1] `ProjectMapper.deserialize` 挂 `taskDeclaredSchema` 透传 map（taskId→声明）；`ProjectImport.Builder` 加 map —— `filecontract/mapping/ProjectMapper.java` + `filecontract/ProjectImport.java`
- [x] T007 [US1] `ProjectSyncService.push` 取 `taskDeclaredSchema` 喂 `recordTaskIo` —— `application/ProjectSyncService.java`（依赖 T006）
- [x] T008 [P] [US1] 新增 `Neo4jColumnLineageCatalog implements ColumnLineageCatalog`：`lookupTable(tenantId,projectId,name)` Cypher `(:Table)-[:HAS_COLUMN]->(:Column)` 有序回组 `TableSchema`；`@ConditionalOnProperty(name="lineage.column-catalog.type", havingValue="neo4j")`；内部 try-catch → `Optional.empty()` —— `application/lineage/Neo4jColumnLineageCatalog.java`（新文件）
- [x] T009 [US1] `EmptyColumnLineageCatalog` 加 `@ConditionalOnProperty(name="lineage.column-catalog.type", havingValue="empty", matchIfMissing=true)` —— `application/lineage/EmptyColumnLineageCatalog.java`
- [x] T010 [US1] `recordTaskIo` 加 `declaredSchemas` 入参；事务内**先** `ensureColumn(tx, tableKey, colName, dataType, ordinal, ...)`（参数已就绪、当前 `Neo4jLineageStore.java:110-111` 传 null 待改）独立 seed `:Column` —— `domain/lineage/LineageStore.java`（接口）+ `infrastructure/lineage/Neo4jLineageStore.java`（实现）
- [x] T011 [US1] 调用序修正（FR-009）：`TaskService.recordLineage` / `ProjectSyncService.push` 把声明 schema 的 seed **提至 extract 之前**（现状 extract:485→recordTaskIo:494 逆序）—— `application/TaskService.java` + `application/ProjectSyncService.java`（依赖 T007、T010）
- [x] T012 [US1] 配置：`application.yml` 加 `lineage.column-catalog.type`（neo4j env 显式 `neo4j`）；`application-h2.yml` 默认走 empty —— `dataweave-api/src/main/resources/application.yml` + `application-h2.yml`

### Tests

- [x] T013 [P] [US1] 单测：`TaskMapper.fromYaml` 解析 schema 块（合法/非法/缺失）+ `toYaml` round-trip 不丢字段 —— `filecontract/mapping/TaskMapperTest.java`（或既有测试类）
- [ ] T014 [US1] 集成测（testcontainers-neo4j）：seed `:Column` → `Neo4jColumnLineageCatalog.lookupTable` round-trip；端到端声明 schema → push → 断言 `:Column`(type/ordinal) + CONFIRMED 列边 —— `infrastructure/lineage/Neo4jColumnLineageCatalogIT.java`（或既有 lineage IT）

**Checkpoint**: 鸡生蛋闭环打通，US1 独立可验（MVP 达成）

---

## Phase 4: User Story 2 - columnLineage cross-check 激活 (Priority: P1)

**Goal**: Agent 声明 `columnLineage`（期望列边）→ 激活 019 FR-006 零调用的 `extractAndCrossCheck` → 声明 vs 推导对账（CONFIRMED/DECLARED/CONFLICT），CONFLICT 不阻断 push

**Independent Test**: 四种对账情形 confidence 正确；CONFLICT 边写入且 push 成功

### Implementation

- [x] T015 [P] [US2] `TaskMapper.fromYaml` 解析 `columnLineage` 块（`{from:表.列, to:表.列}` 列表）→ `TaskDoc.declaredColumnLineage`；`toYaml` round-trip —— `filecontract/mapping/TaskMapper.java` + `filecontract/dto/TaskDoc.java`
- [x] T016 [P] [US2] `ProjectMapper` 挂 `taskDeclaredColumnEdges` 透传 map；`ProjectImport.Builder` —— `filecontract/mapping/ProjectMapper.java` + `filecontract/ProjectImport.java`
- [x] T017 [US2] 激活 cross-check：`TaskService:485` / `ProjectSyncService:858` 由 `extract(...)` 改 `extractAndCrossCheck(sql, catalog, declaredEdges)`；`ColumnLineageCrossCheck.crossValidate` union(D,R) 打 confidence —— `application/TaskService.java` + `application/ProjectSyncService.java`（+ 确认 `SqlColumnLineageExtractor.extractAndCrossCheck` 可用）
- [x] T018 [US2] `recordTaskIo` 落对账边集：declared edges 组装 `ColumnEdge(confidence=DECLARED)` 并入 `columnEdges`；`MERGE :DERIVES_FROM{confidence}`（含 CONFIRMED/DECLARED/CONFLICT）—— `infrastructure/lineage/Neo4jLineageStore.java`（依赖 T010）
- [x] T019 [US2] CONFLICT 不阻断验证（FR-008）：确认 CONFLICT 边写入 + push 不拦（由对账逻辑 + T021 测试保证，无额外生产代码 unless 发现拦截点）

### Tests

- [x] T020 [P] [US2] 单测：`ColumnLineageCrossCheck.crossValidate` 四情形（D∩R=CONFIRMED / D\R=DECLARED / R\D=沿用 019 / 映射矛盾=CONFLICT）—— `application/lineage/ColumnLineageCrossCheckTest.java`
- [ ] T021 [US2] 集成测：CONFLICT 场景（声明 A→B / SQL 推导 A→C）→ 边标 CONFLICT、push 成功；D∩R → CONFIRMED —— 既有 lineage IT

**Checkpoint**: US1+US2 均独立可验；cross-check 真实生效

---

## Phase 5: User Story 3 - DECLARED 兜底建图 (Priority: P2)

**Goal**: SQL 解析失败（DDL/动态/方言不支持）时，声明 columnLineage 的边仍以 DECLARED 写入——列血缘视图不因解析失败而空

**Independent Test**: 019 unparsed 的 SQL + 声明 columnLineage → DECLARED 边写入

### Implementation

- [x] T022 [US3] 兜底接线：确保 extract 返回空（SQL 解析失败）时 declared edges 仍流到 `recordTaskIo` 落 DECLARED 边（对账 `union(D,R)` 当 R=空仍含 D）—— `application/TaskService.java` / `application/ProjectSyncService.java`（extract→recordTaskIo 路径；依赖 T017/T018）

### Tests

- [ ] T023 [US3] 集成测：SQL 为 019 unparsed 的 DDL/动态 + 声明 columnLineage → DECLARED 边落 neo4j、`:Column` 由 schema seed；未声明则无列边 —— 既有 lineage IT

**Checkpoint**: 列血缘可用性下限成立（解析失败也有 DECLARED 兜底）

---

## Phase 6: Polish & Cross-Cutting

- [x] T024 [P] 零回归测：无声明任务维持表级 + UNVERIFIED 启发式（与 T001 baseline 对比）—— 既有测试
- [ ] T025 [P] 文档：更新 `CLAUDE.md`「Table lineage」导航条目 + `docs/architecture.md`（声明驱动列血缘 + catalog 配置项）—— `CLAUDE.md` + `docs/architecture.md`
- [ ] T026 跑 `quickstart.md` 端到端验证（破循环 / cross-check 四情形 / 兜底 / H2 降级 / 首次 push 排序）
- [ ] T027 cross-feature 检查：与 018/019/020 边界（不改 019 解析器/020 读侧契约）、H2 profile 启动不崩、合并序 021→022→023→**024**

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (T001)**: 无依赖，立即开始
- **Foundational (T002, T003)**: 依赖 Setup；**BLOCKS 所有 US**（catalog 签名 + confidence 枚举是跨 story 前提）
- **US1 (T004–T014)**: 依赖 Foundational；MVP
- **US2 (T015–T021)**: 依赖 Foundational；与 US1 可并行（不同 yaml 块/不同 map），但 T018 落边依赖 T010（recordTaskIo 扩参，US1 引入）
- **US3 (T022–T023)**: 依赖 US2（对账 union 逻辑）；本质是 US2 union(D,R) 在 R=空 的验证 + 接线
- **Polish (T024–T027)**: 依赖所有目标 US 完成

### User Story Dependencies
- **US1 (P1)**: Foundational 后可开始，不依赖其他 story —— **MVP**
- **US2 (P1)**: Foundational 后可开始；T018 落边复用 US1 的 recordTaskIo 扩参（T010），建议 US1 落 recordTaskIo 后再接 US2 落边
- **US3 (P2)**: 依赖 US2 的对账逻辑（union 含 D）

### Parallel Opportunities
- Foundational T002 ∥ T003（不同文件）
- US1 内 T004 ∥ T006 ∥ T008 ∥ T013（不同文件）；T005→T004、T007→T006、T011→T007/T010
- US2 内 T015 ∥ T016 ∥ T020（不同文件）
- Polish T024 ∥ T025

---

## Implementation Strategy

### MVP First (US1 Only)
1. T001 Setup → T002/T003 Foundational → T004–T014 US1
2. **STOP 验证**：声明 schema 的任务 push 后出 CONFIRMED 列边（鸡生蛋闭环）；不声明任务零回归
3. 至此 syncSummary 不涉及（那是 025），但列血缘真实流出

### Incremental Delivery
- +US1 → 列血缘破循环可用（MVP）
- +US2 → 列血缘可断言/校验（CONFLICT 抓 SQL 与意图不符）
- +US3 → 解析失败也有 DECLARED 兜底（可用性下限）

---

## Notes
- `[P]` = 不同文件、无依赖可并行
- 每 task 后编译（`cd backend && ./mvnw -q -pl dataweave-master -am compile`）；WSL 长跑用 setsid 脱离（见 CLAUDE.md）
- 实现期开 `dw-024-lineage-column-catalog` worktree（与 021/022/023 惯例一致）
- 改 `recordTaskIo`/`ensureColumn`/catalog 签名后，注意既有调用点（含测试 `TaskServiceSnapshotTest`）同步
