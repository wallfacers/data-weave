# Tasks: 文件化定义契约(Weft File Contract)

**Input**: Design documents from `specs/007-weft-file-contract/`
**Feature Branch**: `007-weft-file-contract`(单目录 main 直推)
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: 本特性以契约正确性为核心交付物,测试是必需的(非可选)——spec 明确定义 SC-001~SC-006 可度量验收标准。

**Organization**: Tasks 按 user story 组织,每个 story 独立可测。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件,无依赖)
- **[Story]**: 所属 user story(US1/US2/US3/US4)
- 描述含精确文件路径

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: 创建包结构,声明直接依赖

- [x] T001 Create package directory tree `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/` with subpackages `dto/`, `yaml/`, `mapping/`, `naming/`, `error/`
- [x] T002 Declare SnakeYAML direct dependency `org.yaml:snakeyaml`(version from Spring Boot parent) in `backend/dataweave-master/pom.xml`

---

## Phase 2: Foundational(Blocking Prerequisites)

**Purpose**: DTO + 核心类型 + YAML 引擎 + 命名规则 —— 所有 user story 的前置依赖

**⚠️ CRITICAL**: 此阶段完成前,任何 user story 均无法开始。

### Core Types & Error

- [x] T003 [P] Create `FileContractException`(file + locus + message) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/error/FileContractException.java`
- [x] T004 [P] Create `ProjectFileBundle` record(`Map<String,String>` 内存文件树) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/ProjectFileBundle.java`
- [x] T005 [P] Create `ProjectExport` record(聚合领域对象:project + catalogs + tasks + workflows + tags) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/ProjectExport.java`
- [x] T006 [P] Create `ProjectImport` record(聚合领域对象 + 校验结果列表) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/ProjectImport.java`

### DTO Records(file shapes)

- [x] T007 [P] Create `ProjectDoc` record(formatVersion/code/name) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/dto/ProjectDoc.java`
- [x] T008 [P] Create `TagsDoc` record(formatVersion/tags list,name+color) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/dto/TagsDoc.java`
- [x] T009 [P] Create `FolderDoc` record(name/sortOrder) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/dto/FolderDoc.java`
- [x] T010 [P] Create `TaskDoc` record(formatVersion/name/type/description/priority/timeoutSec/retryMax/frozen/datasource/targetDatasource/params/tags) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/dto/TaskDoc.java`
- [x] T011 [P] Create `WorkflowDoc` + `NodeDoc` + `EdgeDoc` records(formatVersion/name/description/schedule/nodes/edges/tags/priority/preemptible/timeoutSec) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/dto/WorkflowDoc.java`

### YAML Engine & Naming

- [x] T012 Create `SlugRules`(validate `[a-z0-9_-]+`, case-collision check, reserved names) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/naming/SlugRules.java`
- [x] T013 Create `DeterministicYaml`(SnakeYAML dump/load wrapper, fixed DumperOptions: block style, indent=2, no `---`, LF, no anchors; dump `LinkedHashMap`/load → `Map`) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/yaml/DeterministicYaml.java`

**Checkpoint**: Foundation ready — DTO 形状 + YAML 引擎 + 命名规则就绪,mapper 实现可以开始。

---

## Phase 3: User Story 1 - AI agent 把项目当代码读懂并手改(Priority: P1) 🎯 MVP

**Goal**: 实现完整 DTO↔领域对象 mapper + FileContract facade,使人/AI agent 能仅凭纯文本文件树理解全部任务/任务流/类目/标签语义,并能编辑后保持文件合法。

**Independent Test**: 给定 quickstart.md 的 analytics 示例树,调用 `FileContract.deserialize(bundle)` 得到 `ProjectImport`,其内 TaskDef/WorkflowDef/CatalogNode/Tag 字段与示例语义一致;调用 `FileContract.serialize(export)` 产生合法 YAML 文件树。

### Implementation for User Story 1

- [x] T014 [P] [US1] Create `TagMapper`(TagsDoc ↔ List<Tag> + 出域内联 tags 收集) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/TagMapper.java`
- [x] T015 [P] [US1] Create `TaskMapper`(TaskDoc ↔ TaskDef: type→script extension, params↔规范 JSON, datasource↔code, content 读写) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/TaskMapper.java`
- [x] T016 [P] [US1] Create `WorkflowMapper`(WorkflowDoc+NodeDoc+EdgeDoc ↔ WorkflowDef+WorkflowNode+WorkflowEdge: task 相对路径引用, STRONG edge 省略, node key 稳定标识) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/WorkflowMapper.java`
- [x] T017 [US1] Create `CatalogMapper`(目录树 ↔ List<CatalogNode>: `_folder.yaml` 读写, sortOrder, 身份=相对路径) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/CatalogMapper.java`
- [x] T018 [US1] Create `ProjectMapper`(project.yaml ↔ Project + 编排所有子 mapper 的序列化/反序列化流程) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/ProjectMapper.java`
- [x] T019 [US1] Create `FileContract` facade(`serialize(ProjectExport) → ProjectFileBundle`, `deserialize(ProjectFileBundle) → ProjectImport`) in `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/FileContract.java`

**Checkpoint**: AI agent 或开发者可调用 FileContract API 完整读写项目文件树,所有字段语义正确映射。

---

## Phase 4: User Story 2 - 往返语义无损,支撑 push/pull(Priority: P1)

**Goal**: 用完整测试证明双向 round-trip 契约成立:模型→文件→模型语义等价,文件→模型→文件逐字节稳定。

**Independent Test**: 用覆盖全字段的样例,两条往返链均通过:① model→file→model 语义等价 ② file→model→file 逐字节相同。

### Tests for User Story 2

- [x] T020 [P] [US2] Create gold file tree `sample-project/`(quickstart.md analytics 示例的完整 YAML + 脚本文件) under `backend/dataweave-master/src/test/resources/filecontract/sample-project/`
- [x] T021 [US2] Create `RoundTripTest.java` — model→file→model semantic equivalence(R2) + file→model→file byte stability(R3) + script fidelity(R6) in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/RoundTripTest.java`
- [x] T022 [US2] Create `GoldenFileTest.java` — serialize known model → compare byte-for-byte with `sample-project/` gold files in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/GoldenFileTest.java`

**Checkpoint**: R2/R3/R6 不变量通过,往返契约数学保证成立,push/pull 安全。

---

## Phase 5: User Story 3 - 确定性、最小 diff、可 code review(Priority: P2)

**Goal**: 证明序列化确定性(100x 相同)与 diff 局部性(改单字段=1 文件 1 处 diff)。

**Independent Test**: 同一模型 100 次序列化全字节相同;打乱集合顺序产物不变;修改单字段 git diff 仅该处。

### Tests for User Story 3

- [x] T023 [US3] Create `DeterminismTest.java` — 100x serialize identical(R1) + collection order independence(nodes/edges/tags/params shuffled,R4) in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/DeterminismTest.java`
- [x] T024 [US3] Create `DiffLocalityTest.java` — change single field → exactly 1 file changed, minimal diff(R5/SC-004) in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/DiffLocalityTest.java`

**Checkpoint**: R1/R4/R5 不变量通过,git diff/code review 体验保证。

---

## Phase 6: User Story 4 - 非法/残缺/未知输入的可预期处理(Priority: P3)

**Goal**: 证明解析对各类错误输入给出明确可定位错误,对未知字段按前向兼容规则处理不崩溃。

**Independent Test**: 喂入构造的坏样例(缺必填字段/类型错误/悬挂引用/命名违规/未知字段),全部得到指明文件+问题的错误或预期兼容处理。

### Tests for User Story 4

- [x] T025 [US4] Create `ErrorHandlingTest.java` — missing required fields, type mismatch, dangling node/edge references, TASK node missing task, VIRTUAL node with task, script content exceeds server limit in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/ErrorHandlingTest.java`
- [x] T026 [US4] Create `NamingConstraintTest.java` — invalid chars in slug/dirname, same-dir case-only collision(FR-007a), reserved filename violation in `backend/dataweave-master/src/test/java/com/dataweave/master/filecontract/NamingConstraintTest.java`
- [x] T027 [US4] Add forward-compatibility test cases to `ErrorHandlingTest.java` — unknown fields ignored, higher formatVersion → parse with warning, known fields correctly parsed(FR-016/SC-006)

**Checkpoint**: FR-015/FR-016/FR-007a 全部验证,健壮性与前向兼容保证。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 编译验证与 quickstart 端到端跑通

- [x] T028 Run quickstart.md validation: compile `./mvnw -q -pl dataweave-master compile` and run full test suite `./mvnw -q -pl dataweave-master test -Dtest='com.dataweave.master.filecontract.*'` from `backend/`
- [x] T029 Verify all success criteria SC-001~SC-006 against test results, document any gaps

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(Phase 1)**: No dependencies — can start immediately
- **Foundational(Phase 2)**: Depends on Setup completion(T001) — BLOCKS all user stories
- **User Story 1(Phase 3)**: Depends on Foundational(Phase 2) completion
- **User Story 2(Phase 4)**: Depends on US1(all mappers + facade) + T020(gold files)
- **User Story 3(Phase 5)**: Depends on US1(all mappers + facade) — tests use FileContract API
- **User Story 4(Phase 6)**: Depends on US1(all mappers + facade) — tests use FileContract API
- **Polish(Phase 7)**: Depends on all user stories complete

### User Story Dependencies

- **User Story 1(P1)**: Can start after Foundational — No dependencies on other stories. **BLOCKS US2/US3/US4**(all test phases need mappers).
- **User Story 2(P1)**: Depends on US1 + T020(gold files). Independent of US3/US4.
- **User Story 3(P2)**: Depends on US1. Independent of US2/US4.
- **User Story 4(P3)**: Depends on US1. Independent of US2/US3.

### Within Each User Story

- In US1: TagMapper/TaskMapper/WorkflowMapper[P] → CatalogMapper → ProjectMapper → FileContract
- DTO records(T007-T011) all [P] — all can be written in parallel
- Core types(T003-T006) all [P] — all can be written in parallel
- Tests(US2/US3/US4) can run in parallel once US1 + gold files complete

### Parallel Opportunities

```
Phase 2 parallelism:
  T003, T004, T005, T006  (core types — parallel)
  T007, T008, T009, T010, T011  (DTOs — parallel, also parallel with core types)
  T012, T013  (sequential: SlugRules then DeterministicYaml, or parallel with each other)

Phase 3 parallelism within US1:
  T014(TagMapper), T015(TaskMapper), T016(WorkflowMapper) — parallel
  T017(CatalogMapper) — after T009, T012
  T018(ProjectMapper) — after T014-T017
  T019(FileContract) — after T018

Phase 4-6 parallelism across US:
  Once US1 complete + T020(gold files) done:
    US2(T021-T022), US3(T023-T024), US4(T025-T027) all run in parallel
```

---

## Parallel Example: User Story 1

```bash
# Phase 2: Launch all DTOs together:
Task: "Create ProjectDoc record in dto/ProjectDoc.java"
Task: "Create TagsDoc record in dto/TagsDoc.java"
Task: "Create FolderDoc record in dto/FolderDoc.java"
Task: "Create TaskDoc record in dto/TaskDoc.java"
Task: "Create WorkflowDoc + NodeDoc + EdgeDoc records in dto/WorkflowDoc.java"

# Phase 3: Launch independent mappers together:
Task: "Create TagMapper in mapping/TagMapper.java"
Task: "Create TaskMapper in mapping/TaskMapper.java"
Task: "Create WorkflowMapper in mapping/WorkflowMapper.java"

# Then sequential: CatalogMapper → ProjectMapper → FileContract
```

---

## Implementation Strategy

### MVP First(User Story 1 Only)

1. Complete Phase 1: Setup(T001-T002)
2. Complete Phase 2: Foundational(T003-T013) — CRITICAL
3. Complete Phase 3: User Story 1(T014-T019) — **MVP: full read/write capability**
4. **STOP and VALIDATE**: Run `./mvnw -q -pl dataweave-master compile`
5. Verify manually that FileContract API can serialize/deserialize

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. US1 mappers + facade → **MVP: 可读写, AI agent 可用**
3. US2 round-trip tests → 往返契约数学保证(与 US1 并列 P1)
4. US3 determinism + diff tests → code review 体验保证
5. US4 error handling + naming tests → 健壮性与前向兼容
6. Each phase adds verifiable guarantees without breaking previous phases

### 单开发者顺序

```
T001 → T002 → T003-T011(parallel) → T012 → T013
  → T014-T016(parallel) → T017 → T018 → T019
  → T020 → T021-T022(US2) | T023-T024(US3) | T025-T027(US4) (parallel across US)
  → T028 → T029
```

---

## Notes

- [P] tasks = 不同文件,无依赖
- [US?] label 映射 task 到 spec.md 的 user story,保证可追溯性
- 每个 user story 完成后可独立验证
- 所有 Java 文件使用 Java 25 record 语法(简洁不可变 DTO)
- SnakeYAML 已在 classpath(Spring Boot 4.0 传递依赖),不新增第三方依赖
- 纯单测,无需 `@SpringBootTest`、DB、Docker
- 编译验证命令:`cd backend && ./mvnw -q -pl dataweave-master compile`
- 测试命令:`cd backend && ./mvnw -q -pl dataweave-master test -Dtest='com.dataweave.master.filecontract.*'`
- Commit 粒度:每个 phase 或逻辑组提交一次
- 无测试不算完成(Constitution Check 硬规范)
