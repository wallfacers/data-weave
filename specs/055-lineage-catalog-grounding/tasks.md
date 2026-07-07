# Tasks: 血缘目录接地（Catalog Grounding）

**Input**: Design documents from `/specs/055-lineage-catalog-grounding/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/
**Tests**: 项目宪法「无测试=未完成」——测试任务为**必需**，非可选。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件、无未完成依赖）
- **[Story]**：US1/US2/US3；Setup/Foundational/Polish 无 Story 标
- 所有路径相对仓库根 `backend/dataweave-master/...` 或 `backend/dataweave-api/...`

## Path Conventions

后端单模块改动（`dataweave-master`）+ schema（`dataweave-api`）；无前端。grounding 领域逻辑新包 `application/lineage/grounding/`。

---

## Phase 1: Setup（共享基建）

**Purpose**：全局开关与包骨架就位

- [X] T001 新增全局 kill-switch 配置 `lineage.grounding.enabled`（默认 true）与 `lineage.grounding.system-schemas`（可选，默认空）到 `backend/dataweave-master/src/main/resources/application.yml`（若无则 `dataweave-api` 的 application.yml），并在 `LineageAgentEnricher` 以 `@Value("${lineage.grounding.enabled:true}")` 注入占位（先不接线）
- [X] T002 创建 grounding 子包目录 `backend/dataweave-master/src/main/java/com/dataweave/master/application/lineage/grounding/`（首个类落地时自然创建，占位说明写入 plan 引用）

---

## Phase 2: Foundational（阻塞所有 US 的先决）

**Purpose**：三态探针 + 审计表 + 值对象 + 仓储——所有用户故事的地基。**⚠️ 必须全部完成再进 US。**

- [X] T003 在 `backend/dataweave-api/src/main/resources/schema.sql` 新增 `lineage_grounding_disposition` 表（DDL 见 data-model.md §1）+ 头部 `DROP TABLE IF EXISTS lineage_grounding_disposition;` + 版本注释追加一行 + `INSERT INTO schema_version` 追加 `0.12.0`；文件头 `-- Schema Version:` 由 0.11.0 改 0.12.0（DB 行/文件头/项目版本三者一致）
- [X] T004 [P] 新增枚举 `application/lineage/grounding/TableExistence.java`（`PRESENT|ABSENT|UNKNOWN`，契约 table-existence-probe.md C3）
- [X] T005 [P] 新增值对象 `application/lineage/grounding/GroundingDisposition.java`（字段见 data-model.md §2，1:1 映射审计表行）
- [X] T006 [P] [测试] 新增 `src/test/java/com/dataweave/master/lineage/grounding/TableExistenceProbeTest.java`：H2 建 `dw.orders`→PRESENT；查不存在表→ABSENT；坏 jdbcUrl/不可达→UNKNOWN；非 JDBC typeCode→UNKNOWN（断言先红）
- [X] T007 在 `application/DatasourceSchemaResolver.java` 新增 `probeTable(long datasourceId, String qualifiedName) → TableExistence`：复用既有连接建立链 + `parseQualifiedName`，用 `DatabaseMetaData.getTables` 区分有行=PRESENT/无行=ABSENT/异常=UNKNOWN，永不抛（契约 C1）→ 使 T006 转绿
- [X] T008 在 `application/lineage/DatasourceBoundCatalog.java` 新增 `probeExistence(long tenantId, long projectId, String qualifiedName) → TableExistence`：cache/neo4j 命中=PRESENT、miss+绑定→`schemaResolver.probeTable`（命中 PRESENT 顺带回填列目录）、未绑定=UNKNOWN；不缓存 ABSENT/UNKNOWN（契约 C2，依赖 T004/T007）
- [X] T009 新增 `infrastructure/lineage/GroundingDispositionRepository.java`：JdbcTemplate 插 `lineage_grounding_disposition`（镜像 `AgentConfigRepository.insertCall`，契约 grounding-stage.md C2，依赖 T003/T005）

**Checkpoint**：三态探针可独立跑绿（T006）；审计表可落库；基建就绪。

---

## Phase 3: User Story 1 - 目录接地裁决候选表真伪（Priority: P1）🎯 MVP

**Goal**：候选边经三态裁决——PRESENT 采纳升 CONFIRMED、ABSENT（仅推断类）剔除留痕、UNKNOWN 原样保留；全程异步、AI-off 仍接地。

**Independent Test**：绑定数据源、库有 `dw.orders` 无 `tmp_stage`；push 含真表+CTE+幻觉候选的脚本→短时后 `dw.orders` 带 catalog-verified、`tmp_stage`/`ghost_tbl` 被剔除且审计有 DROPPED 行；解绑数据源→三候选全 RETAINED（回退既有行为）。

### 测试先行（US1）

- [X] T010 [P] [US1] 新增 `src/test/java/com/dataweave/master/lineage/grounding/CatalogGroundingServiceTest.java`：断言 ① 来源分类（SCRIPT_INFERRED/MODEL/AGENT 可剔、SQL_PARSED/SCRIPT_SQL/AGENT/FORM/null 不可剔）；② PRESENT→ADOPTED+confidence CONFIRMED；③ ABSENT+推断类→DROPPED；④ ABSENT+确定性→RETAINED；⑤ UNKNOWN→RETAINED；⑥ DROPPED 表连带剔除其列级边；⑦ 每处置产 GroundingDisposition（先红）
- [X] T011 [US1] 新增 `src/test/java/com/dataweave/master/lineage/grounding/GroundingEnricherIntegrationIT.java`：push→异步 grounding→断言图谱边升级/剔除 + 审计落库；**AI-off（config 缺席）仍接地**；解绑数据源→边集与无 grounding 一致；**故障注入（SC-004）：grounding stage 抛异常→断言 recordTaskIo 仍写既有边集、无异常逃逸、push 主链路不受影响**；**等价性（I2）：AI-off + grounding-off 时异步零 recordTaskIo**。**真跑 4/4 绿**——用真 `LineageAgentEnricher`+真 `InMemoryEventBus`（发布→订阅→有界池→handle→enrich 全链）+真 `CatalogGroundingService`+真 `DatasourceBoundCatalog`，仅桩接边界（taskDef 仓储/assembler/脚本通道/AI 配置/lineageStore/schemaResolver.probeTable 三态/neo4j 目录/审计仓储）；反射调 package-private `subscribe()` 模拟 `@PostConstruct`（比 `@SpringBootTest`+neo4j 更确定、无环境依赖）

### 实现（US1）

- [X] T012 [US1] 新增 `application/lineage/grounding/CatalogGroundingService.java` + 内部 `GroundingResult`：实现逐边算法（路由读/写目录→**grounding 前逐候选 `catalog.evict(规范化名)` 再 probe，兑现 FR-013 重 push 失效**→probeExistence→按来源类 ADOPT/DROP/RETAIN + 连带列级边剔除 + 收集 dispositions），永不抛（契约 grounding-stage.md C1；此阶段系统排除留空钩子，US2 填充）→ 使 T010 转绿
- [X] T013 [US1] 改 `application/lineage/agent/LineageAgentEnricher.java`：注入 `CatalogGroundingService`+`GroundingDispositionRepository`+读/写 `DatasourceBoundCatalog` 构造；在 `dedupeIo/dedupeCol` 之后、`recordTaskIo` 之前调 `ground(...)`，用返回边集 replace 并逐条 insert 审计（契约 C3）
- [X] T014 [US1] 改 `LineageAgentEnricher.enrich()` 早退逻辑：解除 AI-config 早退对 grounding 的门控——`groundingEnabled && dsBound` 时即使 AI 未启用也重算确定性全集+grounding+replace；AI 边生成仍受 `cfg.enabled && shouldEnrich` 门控；kill-switch off 且 AI off 时保持今天的完全早退（研究 D3）→ 使 T011 的 AI-off 分支转绿
- [X] T015 [US1] 在 `LineageAgentEnricher.dedupeIo/dedupeCol` 的 source-priority 之上加 confidence 次级偏好：同键同 source 时 CONFIRMED 胜 UNVERIFIED（FR-012 冲突消解）

**Checkpoint**：US1 独立可测——三态裁决 + 异步接地 + AI-off 接地 + 审计全绿；MVP 达成。

---

## Phase 4: User Story 2 - 排除系统 / 元数据 schema（Priority: P2）

**Goal**：落系统命名空间（information_schema/pg_catalog/…）的候选即使 PRESENT 也排除（推断类）或留痕（确定性）。

**Independent Test**：push 候选含 `information_schema.columns` + `dw.orders`→前者 EXCLUDED 留痕、后者正常 ADOPTED。

### 测试先行（US2）

- [X] T016 [P] [US2] 新增 `src/test/java/com/dataweave/master/lineage/grounding/SystemNamespaceClassifierTest.java`：PG `pg_catalog.pg_class`=true、`dw.orders`=false；MySQL `information_schema.columns`=true；H2 `PUBLIC.orders`=false；裸名 `orders`=false；配置追加集合生效（先红）

### 实现（US2）

- [X] T017 [P] [US2] 新增 `application/lineage/grounding/SystemNamespaceClassifier.java`：按引擎 typeCode 的内置系统 schema 集合（契约 system-namespace-classifier.md C2）+ `lineage.grounding.system-schemas` 配置追加合并 → 使 T016 转绿
- [X] T018 [US2] 改 `CatalogGroundingService.ground(...)`：在 probeExistence 之前插系统命名空间判定——命中→verdict=SYSTEM_EXCLUDED，推断类→EXCLUDED（剔除+连带列级边），确定性→RETAINED（留痕不剔）；注入 classifier + 读/写引擎 typeCode（契约 C1 step 3）
- [ ] T019 [US2] 扩 `GroundingEnricherIntegrationIT`：新增系统表候选→断言 EXCLUDED 剔除 + 审计 `verdict=SYSTEM_EXCLUDED, disposition=EXCLUDED`；确定性系统候选→RETAINED

**Checkpoint**：US1+US2 组合——存在性剔除 + 系统排除双层生效。

---

## Phase 5: User Story 3 - 改写评测叙事：带目录精度夹具（Priority: P3）

**Goal**：带目录夹具量化 grounding on/off 的 precision 提升，可复现，诚实设界。

**Independent Test**：H2 已知全表集合 + 混入 FP 候选；on/off 两组 precision/recall；同种子两次一致；报告声明"不迁移到无目录 gold-A"。

### 测试 + 交付（US3）

- [ ] T020 [P] [US3] 新增 `src/test/java/com/dataweave/master/lineage/grounding/GroundedPrecisionFixtureTest.java`：H2 建已知全表集合，喂含 CTE/临时/幻觉/系统表的候选；断言 grounding off=含 FP 基线 precision、on=FP 100% 剔除+真表 100% 保留（precision 升、recall 不降）；同种子两次结论一致（SC-001/SC-007）
- [ ] T021 [US3] 新增评测叙事文档 `specs/055-lineage-catalog-grounding/eval-narrative.md`：记录夹具设计、on/off precision/recall 数字、诚实结论段（提升依赖目录可达、不可迁移到无目录 gold-A GitHub 语料）（FR-015/SC-007）

**Checkpoint**：三个用户故事全部独立可测并交付。

---

## Phase 6: Polish & Cross-Cutting

- [X] T022 [P] schema 版本一致性自检：`grep 0.12.0 schema.sql` 确认文件头/version 表/`schema_version.description` 三处齐；H2 profile 起库不报 DDL 错
- [ ] T023 [P] 更新 `docs/architecture.md` 或 CLAUDE.md 知识地图：加「目录接地」一行指向 `CatalogGroundingService` + `DatasourceBoundCatalog.probeExistence`（若知识地图有血缘条目则并入）
- [ ] T024 全模块回归：`setsid` 脱离跑 `./mvnw -pl dataweave-master test`（H2 profile），确认 grounding 全绿且 053/041 既有血缘测试零回归（WSL2 脱离+单次秒回轮询）
- [ ] T025 [P] 后端编译门：`./mvnw -q -pl dataweave-master compile` + `-pl dataweave-api compile` 零错误

---

## Dependencies & Execution Order

- **Setup（P1）** → **Foundational（P2）** → **US1（P3）** → **US2（P4）** → **US3（P5）** → **Polish（P6）**
- **Foundational 阻塞所有 US**：T003–T009 必须先完成。
- **US1 是 MVP**：T010–T015；独立可交付。
- **US2 依赖 US1**：T018 改的是 US1 建的 `CatalogGroundingService`；T017/T016 可与 US1 并行开发但集成（T018/T019）在 US1 后。
- **US3 依赖 US1**（运行时接地存在即可测精度）；US2 落地后精度更高但非阻塞。
- **Polish 依赖全部 US**。

### 并行机会

- Foundational 内：T004、T005、T006 可并行（不同文件）；T007 依赖 T006（红→绿），T008 依赖 T004/T007，T009 依赖 T003/T005。
- US1 内：T010 可与实现骨架并行起草；T011 集成测试在 T012–T014 后跑绿。
- US2 内：T016、T017 可并行（测试 + 分类器不同文件）。
- Polish：T022、T023、T025 可并行；T024 汇总最后。

## Implementation Strategy

1. **MVP = Setup + Foundational + US1**（T001–T015）：交付三态目录接地主干，绑定数据源即清 CTE/临时/幻觉残余 FP、AI-off 亦生效。可独立部署验证。
2. **增量 1 = US2**（T016–T019）：叠加系统/元数据 schema 排除，清掉系统表 FP。
3. **增量 2 = US3**（T020–T021）：带目录精度夹具改写评测叙事，落地可复现证据。
4. **收尾 = Polish**（T022–T025）：版本自检 + 知识地图 + 零回归。

**测试纪律**：每个 US 内测试先红后绿；长命令 setsid 脱离；改动后编译门零错误方可继续。
