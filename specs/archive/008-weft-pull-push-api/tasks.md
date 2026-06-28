# Tasks: Weft 子特性 C —— 服务器 pull/push 同步 API

**Feature**: `008-weft-pull-push-api` | **Input**: spec.md / plan.md / research.md / data-model.md / contracts/

**约定**:后端 Java 25 / Spring Boot 4 / Jackson 3。落点 `backend/dataweave-master`(application+domain)+ `backend/dataweave-api`(interfaces)。每改一文件后 `cd backend && ./mvnw -q -pl <module> compile` 零错再继续。测试用显式类名跑(`-Dtest='ClassName'`,build-cache 会短路,改源码才真跑)。**复用 B(`filecontract`)与现有内核,零新表、零新第三方依赖。**

---

## Phase 1: Setup(基础脚手架)

- [x] T001 [P] 新增 `backend/dataweave-master/src/main/java/com/dataweave/master/application/ProjectSyncDtos.java`:定义 records `SyncBundle(Map<String,String> files)`、`PullResult`、`PushCommand`、`PushResult(含 Counts/SnapshotRef)`、`DiffPreview(含 EntityRef)`,字段照 data-model.md §1。
- [x] T002 [P] 在 `domain/EntityTagRepository.java` 增 `void deleteByEntityTypeAndEntityId(String entityType, Long entityId)`(push 重建实体标签用);在 `domain/DatasourceRepository.java` 增 `Optional<Datasource> findByProjectIdAndName(Long projectId, String name)`(name→id 解析用)。
- [x] T003 [P] 新增空壳 `backend/dataweave-api/src/main/java/com/dataweave/api/interfaces/ProjectSyncController.java`:`@RestController @RequestMapping("/api/projects")`,注入 `ProjectSyncService`,留 `pull/push/diff` 三个 `@PostMapping` 方法签名返回 `ApiResponse<?>`(先 TODO 抛 unsupported)。

---

## Phase 2: Foundational(阻塞所有 story,先于 US1/US2/US3)

**目标**:抽取状态中立建快照内核(D1)、基线令牌(D4)、隔离守卫、身份对账引擎(D2 的纯计算部分)。完成后 publish 行为不回归。

- [x] T004 [US-FND] 在 `application/TaskService.java` 抽出 `writeTaskVersionSnapshot(TaskDef task, Long publishedBy, String remark): Integer`:建 `TaskDefVersion` 行 + `versionNo=currentVersionNo+1` + 回写 `task.currentVersionNo`,**不改 status、不做无草稿守卫**;重构 `publish()` = 调该内核 + 晋级 ONLINE + 引用闸门 + 无变更守卫(行为零回归)。
- [x] T005 [US-FND] 在 `application/WorkflowService.java` 抽出 `writeWorkflowVersionSnapshot(Long workflowId, String remark): Integer`:复用现有 `buildSnapshotJson` 建 `WorkflowDefVersion` + `versionNo+1` + 回写 currentVersionNo,**不晋级 ONLINE、不做引用/空 DAG 守卫**;重构 `publish()` 复用之(零回归)。
- [x] T006 [P] [US-FND] 新增 `application/ProjectRevisionToken.java`(或 SyncService 私有方法):对 project 下 task/workflow/catalog/tag 的 `(id, version|updatedAt)` 稳定排序拼接取 SHA-256 前 16 hex →不透明基线令牌(D4);空项目产出确定值。
- [x] T007 [US-FND] 新增 `application/ProjectSyncService.java` 骨架:注入 8 聚合 repo + Datasource repo + TaskService/WorkflowService + TenantContext;实现 `requireOwnedProject(Long pid): Project`(校验 `project.tenantId==TenantContext.tenantId()`,否则 `BizException("project.access_denied")`,FR-012);实现身份对账纯函数 `reconcile(ProjectImport local, 服务器现状) → {added,modified,removed,matched}`(D2 身份键:catalog=path / task=(catalogPath,slug) / workflow=slug / tag=name),**只算不写**(供 US2 写、US3 读复用)。
- [x] T008 [P] [US-FND] 新增测试 `application/TaskServiceSnapshotTest.java` + `WorkflowServiceSnapshotTest.java`:断言 `writeXxxVersionSnapshot` 建版本但不改 status;断言重构后既有 `publish()` 行为不回归(晋级 ONLINE + 无变更/引用守卫仍生效)。

**Checkpoint**:Foundational 完成 → 三个 story 可独立推进。

---

## Phase 3: User Story 1 - Pull(Priority: P1)🎯 MVP

**Goal**:按 project 装配定义 → 经 B 序列化为文件集返回;零凭据;隔离。
**Independent Test**:对含任务/任务流/标签的项目 pull,文件完整可读、目录=类目、数据源仅逻辑名无凭据。

- [x] T009 [US1] 在 `ProjectSyncService` 实现 `pull(Long pid): PullResult`:`requireOwnedProject` → 按 data-model.md §2.1 装配 `ProjectExport`(8 聚合 + slug 经 `filecontract` SlugRules + datasource `id→name` **只取 name 不取连接字段**,D7/FR-003)→ `FileContract.serialize` → 填 `baseline`(T006)+ `fileCount`。空项目返回骨架不报错(US1-4)。
- [x] T010 [US1] 在 `ProjectSyncController` 实现 `POST /{projectId}/pull` → `ApiResponse.ok(service.pull(pid))`;`project.not_found`/`project.access_denied` 经 `BizException` + `GlobalExceptionHandler`。
- [x] T011 [P] [US1] 新增 i18n 错误码到 backend Messages/错误码表:`project.not_found`、`project.access_denied`(两 locale 对齐,数据术语英文)。
- [x] T012 [US1] 测试 `application/ProjectSyncServiceTest.java#pull_*`:① pull 装配文件含 project/tags/task/sql/flow;② datasource 仅逻辑名、断言**无** host/password 串(SC-005);③ 空项目骨架;④ 跨租户 pull 抛 `project.access_denied`(SC-002)。
- [x] T013 [P] [US1] 测试 `dataweave-api/.../ProjectSyncControllerTest.java#pull`:WebTestClient/MockMvc 验 `ApiResponse` 信封 + HTTP 200 + 越权 errorCode。

**Checkpoint**:US1 独立可交付(开发者可 pull 浏览/diff 整个项目定义)。

---

## Phase 4: User Story 2 - Push(Priority: P1)

**Goal**:反序列化 + 校验(可定位错误、全有或全无)+ datasource 解析 + 幂等覆盖 + 生成快照(不上线)+ 乐观并发 + 删除守卫。
**Independent Test**:pull→改→push 更新+快照;坏定义可定位拒绝且零落库;陈旧 baseline 拒;删在线引用拒。

- [x] T014 [US2] 在 `ProjectSyncService` 实现 `push(Long pid, PushCommand cmd): PushResult`,标 `@Transactional`,**校验全前置**(data-model §3):`requireOwnedProject` → `FileContract.deserialize`(warnings 非空即 `project.sync.invalid` 可定位)→ 完整性(fileCount/expectedFileCount,`project.sync.incomplete`,FR-017)→ 基线(T006 重算≠baseline 且 !force → `project.sync.stale`,D4)→ datasource 名解析(未知 → `project.sync.unknown_datasource`,FR-007)→ 删除候选在线引用守卫(D6,`project.sync.delete_referenced`)。
- [x] T015 [US2] 在 `push` 实现落库(校验全过后):基于 `reconcile`(T007)三态——upsert task/workflow/catalog/tag(回填真实 id,新建 status=DRAFT,既有 hasDraftChange=1)、软删本地缺失(deleted=1)、workflow node/edge 按 workflowId 删旧插新(node.taskId 经身份解析、edge from/to 经 nodeKey 解析)、entityTags 按 (entityType,entityId) 重建。
- [x] T016 [US2] 在 `push` 落库后对 insert/update 的 task/workflow 调 `writeTaskVersionSnapshot`/`writeWorkflowVersionSnapshot`(T004/T005,**不晋级 ONLINE**,Q1/FR-009);组装 `PushResult`(counts + snapshots + newBaseline);写 `agent_action` 审计。
- [x] T017 [US2] 在 `ProjectSyncController` 实现 `POST /{projectId}/push`;映射全部错误码到 `GlobalExceptionHandler`。
- [x] T018 [P] [US2] 新增 i18n 错误码:`project.sync.invalid`、`project.sync.stale`、`project.sync.unknown_datasource`、`project.sync.delete_referenced`、`project.sync.incomplete`(两 locale 对齐)。
- [x] T019 [US2] 测试 `ProjectSyncServiceTest#push_*`:① 改任务 push→updated+新快照、status 仍非 ONLINE;② 缺必填→`project.sync.invalid` 且服务器侧定义**不变**(全有或全无,SC-003);③ 未知数据源→`unknown_datasource` 零落库;④ 删除被 ONLINE workflow 引用→`delete_referenced` 整单拒;⑤ 陈旧 baseline 非 force→`stale`,force→通过(SC-007);⑥ 跨租户 push→`access_denied`。
- [x] T020 [US2] 集成测试 `ProjectSyncServiceTest#roundTrip_pullPushPull_semanticEquivalent`:pull→改一处→push→再 pull,断言改动保留 + 未改部分语义等价(SC-001,复用 B 的 R3)。
- [x] T021 [P] [US2] 控制器测试 `ProjectSyncControllerTest#push`:信封 + 各错误码 + 越权。

**Checkpoint**:US1+US2 = pull→edit→push→pull 闭环(主链路 MVP 完整)。

---

## Phase 5: User Story 3 - Diff 预览(Priority: P2)

**Goal**:push 前只读增/改/删预览 + stale 提示;不写库。
**Independent Test**:本地增改删各一→三列与实际一致;完全一致→空;执行后服务器 0 变化。

- [x] T022 [US3] 在 `ProjectSyncService` 实现 `diff(Long pid, PushCommand cmd): DiffPreview`:`requireOwnedProject` → `FileContract.deserialize` → 复用 `reconcile`(T007)算 added/modified/removed(modified=身份匹配且经 B 序列化内容有别)→ 填 `stale`(基线比对);**断言无任何写**(FR-011)。
- [x] T023 [US3] 在 `ProjectSyncController` 实现 `POST /{projectId}/diff`。
- [x] T024 [US3] 测试 `ProjectSyncServiceTest#diff_*`:① 增/改/删各一→三列一致;② 完全一致→空差异;③ diff 后服务器侧定义 0 变化(只读,SC-006);④ 越权拒。

**Checkpoint**:US3 完成,覆盖防误覆盖知情(FR-006)。

---

## Phase 6: Polish & 跨切面

- [x] T025 [P] 全模块回归:`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-master,dataweave-api test -Dtest='ProjectSyncServiceTest,ProjectSyncControllerTest,TaskServiceSnapshotTest,WorkflowServiceSnapshotTest'` + 现有 TaskService/WorkflowService publish 测试,确认 publish 零回归、filecontract(B)零触碰。
- [x] T026 [P] 按 quickstart.md 跑端到端手工验收(h2 profile):pull→改→push→再 pull + 6 条负路径,逐条对照 SC-001..007。
- [x] T027 [P] 文档:在 CLAUDE.md「Knowledge Base Navigation」加一行 `ProjectSyncService`(pull/push/diff)指引;确认 spec/plan 与实现无漂移(端点路径/错误码/快照语义一致)。

---

## Dependencies & 执行顺序

- **Setup(P1)** → **Foundational(P2)** → 阻塞全部 story。
- **US1(P3)** 仅依赖 Foundational;**US2(P4)** 依赖 Foundational(T004/T005 快照内核、T007 reconcile);**US3(P5)** 依赖 Foundational(T007 reconcile)。US1/US2/US3 之间**逻辑独立**(US2/US3 复用 T007,但不互相依赖)。
- **Polish(P6)** 依赖全部 story。
- MVP = Setup + Foundational + **US1 + US2**(主链路闭环)。US3 为增量。

## 并行机会

- Setup:T001/T002/T003 全 [P](不同文件)。
- Foundational:T004 与 T005 [P](不同 service);T006/T008 [P]。
- 各 story 内:i18n 任务(T011/T018)与服务实现 [P];控制器测试(T013/T021)与服务测试 [P]。
- 跨 story:US1 与 US3 可并行(都不依赖 US2);US2 落库后再接快照。

## 格式校验

所有任务均 `- [ ] Txxx [P?] [Story?] 描述 + 文件路径`;Setup/Foundational/Polish 无 story 标签(Foundational 用 [US-FND] 标识阻塞性),US1/US2/US3 任务带对应标签。
