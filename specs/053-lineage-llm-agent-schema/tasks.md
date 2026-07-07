# Tasks: 血缘解析扩展——可配置云 AI Agent 抽取通道 + 数据源实时 Schema 解析

**Input**: Design documents from `specs/053-lineage-llm-agent-schema/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 本仓库硬规则「新功能必须有测试，无测试=未完成」——测试任务纳入，且血缘须 neo4j IT 直连真验（勿只 mock）。

**Organization**: 按用户故事分组，每组可独立实现、独立验证、独立交付。

## Path Conventions

- backend master: `backend/dataweave-master/src/main/java/com/dataweave/master/`（下称 `M/`）
- backend 测试: `backend/dataweave-master/src/test/java/com/dataweave/master/`（下称 `MT/`）
- schema: `backend/dataweave-api/src/main/resources/schema.sql`
- frontend: `frontend/`

---

## Phase 1: Setup（共享基础）

- [ ] T001 在 `backend/dataweave-api/src/main/resources/schema.sql` 新增 `lineage_agent_config` 与 `lineage_agent_call` 两表（PG/H2 兼容 DDL，字段见 data-model.md §1；`enabled SMALLINT DEFAULT 0`、`UNIQUE(tenant_id, project_id, deleted)`、`idx_agent_call_task`），并把文件头 `Schema Version` 与末尾 `schema_version` 插入行升至 `0.11.0`（描述：053 血缘 AI Agent 配置+审计 2 表）。H2 与 PG 各建库验证 DDL 通过。
- [ ] T002 [P] 在 `M/domain/lineage/Source.java` 枚举追加 `SCRIPT_AGENT`（注释标注 041→053 云 AI 推断通道）。

---

## Phase 2: Foundational（阻塞前置——多故事共用）

**Purpose**: 配置存储与事件类型，US1/US4 均依赖；此阶段完成前 US1 无法落地。

- [ ] T003 [P] 在 `M/application/lineage/agent/` 新增归一产物 `AgentExtraction`（`reads/writes/columnEdges/confidence/modelVersion`，见 data-model.md §4）与 EventBus 事件 `LineageAgentEnrichmentRequested`（`tenantId/projectId/taskDefId/taskType/calciteParsed`）。
- [ ] T004 [P] 在 `M/domain/`+`M/infrastructure/` 新增 `LineageAgentConfig` 领域对象与 `AgentConfigRepository`（JdbcTemplate CRUD，按 tenant/project 唯一 upsert、软删；`api_key_enc` 字段读写；`lineage_agent_call` 插入方法）。方言注意：CONCAT 而非 `||`、GeneratedKeyHolder 取自增主键（见记忆 alert-jdbc-call-identity 教训）。

**Checkpoint**: 配置可持久化、事件类型就位。

---

## Phase 3: User Story 1 - 可配置云 AI Agent 抽取通道（Priority: P1）🎯 MVP

**Goal**: 配置驱动、兼容 Anthropic/OpenAI 双协议的异步 AI 抽取通道，push 不阻塞、AI 边随后并入图谱。
**Independent Test**: 配 OpenAI 兼容端点 → push 规则不覆盖的写表脚本 → push 立即返回、短时内出现 `SCRIPT_AGENT` 来源边；切 Anthropic 协议语义等价；Calcite 正常解析的 SQL 不外呼。

### 实现

- [ ] T005 [P] [US1] `M/application/lineage/agent/AgentLineageConfigService.java`：配置 CRUD + `DatasourceEncryptor` 加密 `apiKey` + 脱敏（`sk-…末4位`）+ `enabled`/完整性校验 + `isEnabledFor(tenant,project)`。apiKey 缺省=不改（PATCH null vs 缺失语义，见记忆）。
- [ ] T006 [P] [US1] `M/application/lineage/agent/LlmProtocolAdapter` 接口 + `AnthropicProtocolAdapter`（`/v1/messages`，`x-api-key`+`anthropic-version`，tool_use 结构化输出）+ `OpenAiProtocolAdapter`（`/v1/chat/completions`，Bearer，`response_format:json_schema`），两者归一为 `AgentExtraction`（契约见 contracts/llm-protocol-adapters.md）。
- [ ] T007 [US1] `M/application/lineage/agent/LlmAgentClient.java`：JDK `HttpClient` 按 `protocol` 分发适配器、超时=`timeout_ms`、解密即用即弃、日志脱敏 key（依赖 T005/T006）。
- [ ] T008 [P] [US1] `M/application/lineage/agent/LineageExtractionPrompt.java`：抽取指令 + 输出 schema（`{reads,writes,columnEdges,confidence}`）+ 宁缺毋滥约束（schema 接地留 US3 扩展位）。
- [ ] T009 [US1] `M/application/lineage/agent/AgentLineageExtractor.java` implements `ScriptLineageExtractor`：`supports` 判定（enabled + PYTHON/SHELL/SPARK 或 calciteParsed=false 的 SQL）；`extract` 调 client + 防幻觉校验（表名字面命中脚本，契约 C2）；产出 `channel=SCRIPT_AGENT`；绝不外抛。**不**注入同步 extractors 列表（依赖 T007/T008）。
- [ ] T010 [US1] 在 `M/application/lineage/script/ScriptLineageService.java` 的 `CHANNEL_PRIORITY` 插入 `SCRIPT_AGENT`（`SCRIPT_SQL > SCRIPT_INFERRED > SCRIPT_AGENT > SCRIPT_MODEL`，FR-004a）。
- [ ] T011 [US1] `M/application/lineage/agent/LineageAgentEnricher.java`：监听 `LineageAgentEnrichmentRequested`（有界线程池、异步）；旁路未启用项目；重载任务 content → 调 `AgentLineageExtractor` → 重算确定性全集并按优先级消解 → 一次全量 `lineageStore.recordTaskIo` keyed replace（不擦除确定性边，契约 C3）；写 `lineage_agent_call` 审计（依赖 T009/T004）。
- [ ] T012 [US1] 在 `M/application/TaskService.java`（`recordLineage`）与 `M/application/ProjectSyncService.java` push 路径：同步记录确定性血缘后 `eventBus.publish(LineageAgentEnrichmentRequested)`（携 `calciteParsed`）。失败不阻断 push（依赖 T003/T011）。
- [ ] T013 [P] [US1] `M/interfaces/LineageAgentConfigController.java`：`GET`/`PUT`/`POST test` `/api/lineage/agent-config`（契约 config-api.md），`ProjectScope.require` 成员校验，响应脱敏，错误码 `lineage_agent.*`（依赖 T005）。
- [ ] T014 [P] [US1] 后端 i18n 错误码消息（`Messages` / 相应 bundle）：`lineage_agent.config_incomplete/protocol_invalid/test_failed/forbidden`。
- [ ] T015 [P] [US1] 前端：数据源/连接配置视图内新增「血缘 AI Agent」配置表单（协议选择/baseUrl/model/apiKey 脱敏输入/enabled 开关/test 按钮），base-style + hugeicons + 语义 token；`frontend/messages/{zh-CN,en-US}.json` 双 bundle 同键补文案（依赖 T013）。

### 测试

- [ ] T016 [P] [US1] `MT/.../agent/ProtocolAdapterTest`：两协议 buildRequest/parseResponse 归一等价（mock HTTP body）。
- [ ] T017 [P] [US1] `MT/.../agent/AgentLineageExtractorTest`：防幻觉——脚本不含的表名被拒；不确定不出边。
- [ ] T018 [US1] `MT/.../agent/LineageAgentEnricherIT`（neo4j 直连）：异步富化后出现 `SCRIPT_AGENT` 边、确定性边未被擦除、`lineage_agent_call` 落审计；Calcite 正常解析的 SQL 无外呼。
- [ ] T019 [P] [US1] `LineageAgentConfigControllerIT`（WebTestClient + JWT）：PUT 加密/GET 脱敏/test；契约 200+$.code/$.data。
- [ ] T020 [P] [US1] 前端 Playwright 浏览器门：配置表单保存后 GET 回显脱敏、enabled 切换、test 反馈。

**Checkpoint**: US1 可独立 demo——配端点即用上云 AI 异步补血缘。

---

## Phase 4: User Story 2 - 数据源实时 Schema 解析补全未知列（Priority: P2）

**Goal**: `SELECT *` 等未知列场景经绑定数据源直连取真实列，喂确定性 Calcite 列级血缘并回填缓存。
**Independent Test**: 绑定数据源、`user` 表列未 seed 的 `INSERT INTO dw.snap SELECT * FROM user` → push 后 `dw.snap` 字段级血缘按 `user` 真实列逐列建立、neo4j 回填列（带 dataType/ordinal）。

### 实现

- [ ] T021 [P] [US2] `M/application/DatasourceSchemaResolver.java`：复用 `JdbcConnectionTester` 连接 + `IsolatedDriverLoader` 驱动隔离 + `DatasourceResolver.decryptPassword`；`DatabaseMetaData.getColumns` 取列名/序号/类型；视图同解；限定名规范化（`getSchema`/`getCatalog`，FR-015）；列数/超时上限保护（FR-014）；失败返回 empty 永不抛（契约 datasource-schema-resolver.md C1）。
- [ ] T022 [US2] `M/infrastructure/lineage/Neo4jColumnBackfillWriter.java`（或扩 `Neo4jLineageStore`）：MERGE `:Column` 回填 `dataType/ordinal`（复用 `ensureColumn` 预留位）；结构变更覆盖刷新（依赖 T021）。
- [ ] T023 [US2] `M/application/lineage/DatasourceBoundCatalog.java` implements `ColumnLineageCatalog`：组合链 进程 TTL 缓存 → `Neo4jColumnLineageCatalog` → `DatasourceSchemaResolver`，命中回填缓存+neo4j；datasourceId 由构造闭包持有（不改接口签名）；未绑定数据源退化纯 neo4j（契约 C2，依赖 T021/T022）。
- [ ] T024 [US2] 在 `TaskService.recordLineage` 与 `ProjectSyncService`：解析 SQL 列级前用任务 `datasource_id`/`target_datasource_id` 构造 `DatasourceBoundCatalog` 替代直注入的 neo4j-only catalog（契约 C4，依赖 T023）。

### 测试

- [ ] T025 [P] [US2] `MT/.../DatasourceSchemaResolverIT`（H2 + PG 各一遍）：真库取列、视图列、列数上限截断、不可达降级。
- [ ] T026 [US2] `MT/.../SelectStarExpansionIT`（neo4j 直连）：`SELECT *` 展开逐列字段血缘；无列清单 INSERT 位置映射；JOIN 裸列消歧；neo4j 回填列可查。
- [ ] T027 [P] [US2] `MT/.../DatasourceBoundCatalogTest`：组合链顺序、缓存命中、未绑定退化、全 miss 返回 empty。

**Checkpoint**: US2 独立于 US1 可交付——不需外部 AI 即消除 `SELECT *` 盲区。

---

## Phase 5: User Story 3 - Schema 接地抑制幻觉 + 缓存回填（Priority: P3）

**Goal**: 把真实 schema 注入 AI 提示、约束字段边落在真实列内；实时解析结果回填缓存越用越省。
**Independent Test**: 源列真实存在但未登记的脚本 + 开启 AI → AI 字段边全落真实列集内（越界拒收留痕）；同表二次 push 命中缓存不重复连库。
**Dependencies**: US1（AI 通道）+ US2（schema 解析）。

### 实现

- [ ] T028 [US3] 扩展 `LineageExtractionPrompt`（T008）：注入 `DatasourceBoundCatalog` 解析到的候选表真实列清单作接地上下文（FR-016）。
- [ ] T029 [US3] 在 `AgentLineageExtractor`（T009）加列级校验：字段边列名必须落在真实列集合内，越界拒收 + `lineage_agent_call.status=REJECTED` 留痕。
- [ ] T030 [US3] `DatasourceBoundCatalog` 新鲜度（T023）：push 时 evict 候选表缓存条目（重 push 失效）+ 条目 TTL 兜底（`lineage.schema-cache.ttl`，FR-018/Q4）。

### 测试

- [ ] T031 [P] [US3] `MT/.../agent/SchemaGroundingIT`（neo4j 直连）：AI 字段边无越界幻觉列；越界列被拒并留痕。
- [ ] T032 [P] [US3] `MT/.../SchemaCacheFreshnessIT`：二次解析命中缓存不连库；重 push evict 后反映新 schema；TTL 过期刷新。

**Checkpoint**: AI 与实时 schema 拧成一股——幻觉受控、连库次数下降。

---

## Phase 6: User Story 4 - AI 外呼治理与安全护栏（Priority: P3）

**Goal**: 默认关闭、凭据脱敏、审计、超时降级、频次上限——让直连云可安全上线。
**Independent Test**: 未开启项目 push 零外呼；GET 不回显明文 key；端点不可达时 push 仍在预算内成功且降级留痕。
**Dependencies**: US1。

### 实现

- [ ] T033 [P] [US4] `LineageAgentEnricher`（T011）加每配置 `rate_limit_per_min` 令牌桶 + 线程池容量上限（FR-023）；超限跳过并留痕。
- [ ] T034 [P] [US4] `LineageAgentConfigController` 加 `GET /api/lineage/agent-config/calls?taskDefId=`（`lineage_agent_call` 脱敏只读，FR-021）。

### 测试

- [ ] T035 [US4] `MT/.../agent/AgentGovernanceIT`：未启用项目零外呼（`lineage_agent_call` 无新行，SC-005）；端点不可达 → push 预算内成功 + `status=DEGRADED` + 确定性边不受影响；日志/响应无明文 key（脱敏）。

**Checkpoint**: 治理闭环，AI 外呼可控。

---

## Phase 7: Polish & 跨切面

- [ ] T036 [P] schema_version 三处一致核对（文件头 / DB 行 / 项目版本 = `0.11.0`）。
- [ ] T037 [P] i18n 双 bundle 键集一致性（CI 校验，`lineage_agent.*` 与配置面文案）。
- [ ] T038 按 `quickstart.md` 端到端跑四故事（neo4j 真验 + H2/PG 双方言 + Playwright 门），回填验收证据。
- [ ] T039 `cd backend && ./dev-install.sh && ./mvnw -pl dataweave-master test` 全绿（setsid 脱离，见长驻命令规则）。

---

## Dependencies & 执行顺序

- **Setup(P1-T001/2) → Foundational(P2-T003/4) → 各故事**。
- **US1（P3 phase）**：T005/6/8 [P] → T007 → T009 → T010 → T011 → T012；T013/14/15 [P]（配置面）；测试 T016-T020。
- **US2（P4 phase）**：T021 → T022 → T023 → T024；测试 T025-T027。**独立于 US1**，可并行另一 worktree。
- **US3（P5 phase）**：依赖 US1+US2 完成后启动。
- **US4（P6 phase）**：依赖 US1。
- **Polish（P7）**：全部故事后。

## 并行机会

- Setup 内 T002 与 T001 可并行。
- Foundational T003/T004 并行。
- **US1 与 US2 可分派两个独立 worktree 并行开发**（触点仅在 T024 装配 catalog 与 T012 事件发布，文件不同）。
- US1 内 T005/T006/T008 并行；T013/T014/T015 并行；测试 T016/T017/T019/T020 并行。

## MVP 建议

- **最小可交付 = US2**（P2 但零外部依赖、独立价值最高，直接消除 `SELECT *` 盲区）——可先行合并。
- **P1 主干 = US1**（云 AI 异步通道）。
- US2 + US1 落地后再叠 US3（接地）与 US4（治理）。
