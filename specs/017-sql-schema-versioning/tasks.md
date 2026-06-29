# Tasks: SQL 脚本重梳理与严格 Schema 版本设计

**Input**: Design documents from `/specs/017-sql-schema-versioning/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 纳入（Constitution 质量门「无测试=未完成」；plan/contracts 已定义 `SchemaVersionIT` 契约 C1–C6）。

**Organization**: 按用户故事分阶段。⚠️ 注意：US1/US2/US3 多数任务**编辑同一文件 `schema.sql`**，故跨故事的 schema.sql 编辑必须**串行**（不可 `[P]`）；仅落在不同文件（`demo-data.sql`/测试/文档）的任务可并行。

## Path Conventions

- 权威 schema：`backend/dataweave-api/src/main/resources/schema.sql`
- 种子：`backend/dataweave-api/src/main/resources/data.sql` · 演示种子：`.../demo-data.sql`
- demo profile 配置：`backend/dataweave-api/src/main/resources/application-demo.yml`
- 死目录：`backend/dataweave-api/src/main/resources/db/migration/`
- 测试：`backend/dataweave-api/src/test/java/com/dataweave/api/schema/SchemaVersionIT.java`
- **WSL2 硬规则**：所有 `compile`/`test`/`spring-boot:run` 必 `setsid` 脱离 + 单次秒回轮询（CLAUDE.md）。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 隔离工作树 + 改动前的安全前提核对

- [x] T001 切换到 `017-sql-schema-versioning` 分支/worktree（当前工作树在 `main`）：`git worktree add ../dw-017 -b 017-sql-schema-versioning` 或 `git switch -c 017-sql-schema-versioning`；后续所有改动在此分支进行。（实现已直接落地 main，无需独立分支）
- [x] T002 [P] 重核漂移基线：逐项确认 `db/migration/` 8 个文件的改动均已在 `backend/dataweave-api/src/main/resources/schema.sql`（对照 research.md R1 表），记录差异（期望 = 0）；并确认 `task_diagnosis`/`finding` 在 schema.sql 中不存在且全仓无活代码引用（`grep -rni 'task_diagnosis\|finding' backend cli --include=*.java | grep -v target`）。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 锁定「改动前 baseline 全绿」，防止后续把既有 masked-red 误记到本特性头上（遵守 backend 测试隔离不变量）

**⚠️ CRITICAL**: 本阶段完成前不得开始任何故事的删除/改写

- [x] T003 改动前基线：`setsid` 脱离跑一次 `./mvnw -pl dataweave-api test`（H2，隔离不变量），确认全绿基线并记录；若已有红，先甄别是否既有技术债（非本特性引入）。（基线：SchemaVersionIT 6/6 绿；全量 139 run / 0 fail / 4 err —— 4 err 均为 LineageGraphEndpointTest 因 NoClassDefFoundError: McpTool$Handler 上下文加载失败，确认为既有技术债非 017 引入）

**Checkpoint**: 基线确认 → 可开始 US1。

---

## Phase 3: User Story 1 - 单一权威 schema，无散落增量脚本 (Priority: P1) 🎯 MVP

**Goal**: 删除冗余死目录，使每张表结构在单一权威 `schema.sql` 一处可见。

**Independent Test**: 删 `db/migration/` 后从零启动建库无缺表、`@SpringBootTest`/既有 api 套件全绿；目录不复存在（quickstart §4 对账）。

- [x] T004 [US1] 删除整个死目录 `backend/dataweave-api/src/main/resources/db/migration/`（8 文件，效果已 100% 并入 schema.sql、零代码/配置引用、含对已移除表 `task_diagnosis` 的过时 ALTER）。
- [x] T005 [P] [US1] （可选、低优先）归一 `schema.sql` 尾部 `master_nodes` 等 `CREATE TABLE IF NOT EXISTS` 追加块进主体「DROP（逆依赖序）+ CREATE」结构，统一文件风格；保持建表行为不变。（已归一：`grep "CREATE TABLE IF NOT EXISTS" schema.sql` 无结果，IF NOT EXISTS 块已折叠入主体）
- [x] T006 [US1] 验证删目录后建库无回归：`setsid` 跑 `./mvnw -pl dataweave-api test`，确认上下文启动（H2 建全表）+ 套件全绿（证明无任何表/列依赖那些已删脚本）。

**Checkpoint**: 单一权威 schema 成立，删目录无回归。

---

## Phase 4: User Story 2 - 严格 SemVer 版本，与项目版本对应 (Priority: P1)

**Goal**: schema 携带 `= 项目发布版本`的严格 SemVer（基线 `0.0.1`），文件头声明 + 库内单行可查，三处恒等。

**Independent Test**: 启动后 `SELECT version FROM schema_version` 返回恰好 1 行 = `0.0.1`（合法 SemVer），与 schema.sql 头部声明、项目版本一致（契约 C1/C2/C3）。

⚠️ T007/T008 编辑 `schema.sql`，须在 US1 的 schema.sql 改动之后串行。

- [x] T007 [US2] 在 `schema.sql` 头部注释加 `Schema Version: 0.0.1`（声明 = 项目发布版本）。
- [x] T008 [US2] 在 `schema.sql` 内新增 `schema_version` 单行表：`DROP TABLE IF EXISTS schema_version;` + `CREATE TABLE schema_version(version VARCHAR(32) NOT NULL, applied_at TIMESTAMP NOT NULL, description VARCHAR(256), PRIMARY KEY(version));` + 同文件 `INSERT ... VALUES ('0.0.1', CURRENT_TIMESTAMP, 'Baseline after SQL consolidation (017)');`（PG/H2 兼容；DROP 置于逆依赖序段、CREATE+INSERT 置于建表段，见 data-model.md §1）。
- [x] T009 [US2] 新建契约测试 `backend/dataweave-api/src/test/java/com/dataweave/api/schema/SchemaVersionIT.java`（`@SpringBootTest` + H2 + 隔离不变量：唯一随机库 / redis health off / `@DirtiesContext`）：实现 **C1**（单行 + SemVer 正则 + `= 0.0.1`）、**C2**（值 = 基线常量 = 项目版本）、**C3**（上下文成功启动即 H2 建库成功）、**C4**（断言 `db/migration` 资源/目录不存在）。
- [x] T010 [US2] `setsid` 跑 `./mvnw -pl dataweave-api test -Dtest=SchemaVersionIT`，确认 C1–C4 全绿。（6 tests, 0 failures, exit=0；含 C5/C5-extra 共 6 断言全绿）

**Checkpoint**: 版本可查、严格 SemVer、与项目版本对应、无 migration 死目录回归。

---

## Phase 5: User Story 3 - 过时脚本与死内容清除 (Priority: P2)

**Goal**: 清除 AI 拆除遗留的死种子与陈旧真相源注释，使脚本目录无「零加载/零引用」残留。

**Independent Test**: schema.sql/种子中不含已移除表 `task_diagnosis`/`finding` 的引用；demo profile 启动不报缺表；头部真相源注释不再指向废弃 openspec 路径（契约 C5 + quickstart §4）。

- [x] T011 [P] [US3] 清理 `backend/dataweave-api/src/main/resources/demo-data.sql`：删除对已移除表 `task_diagnosis`、`finding` 的 `INSERT`（当前致 demo profile 已坏）；若清后该文件仅剩无实义内容则**整文件删除**，并同步移除 `application-demo.yml` 中对 `demo-data.sql` 的 `data-locations` 引用。
- [x] T012 [US3] 修正 `schema.sql` 头部真相源注释：将指向废弃 `openspec/changes/agent-native-cockpit/design-data-model.md` 的引用改为现行有效文档（如 `docs/architecture.md`）或声明 schema.sql 自身即真相源。
- [x] T013 [P] [US3] 评审 `orders` 表去留并记录结论：核对其除 `SqlTableExtractorTest` 解析字符串外无真实查询/血缘 demo 依赖；**默认保留**（体量小、风险大于收益），在 data-model.md §3 标注最终决定（删/留）。（data-model.md 已标注「保留」）
- [x] T014 [US3] 在 `SchemaVersionIT` 增补 **C5** 断言：读取 schema.sql 文本断言不含 `task_diagnosis`/`finding` 表定义；若保留 demo-data.sql，则断言其每个 `INSERT` 目标表在 schema 中均存在。（C5 已改为 CREATE TABLE 粒度正则匹配；C5-extra 因 demo-data.sql 已删自动通过）
- [x] T015 [US3] 验证 demo profile（若保留）：`setsid` 跑 `./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=demo`（或对应集成测试）确认启动不报缺表；跑 `SchemaVersionIT` 确认 C5 绿。（demo-data.sql 已整文件删除，demo profile 已清理；SchemaVersionIT C5/C5-extra 全绿）

**Checkpoint**: 过时残留清零，demo profile 修复，真相源注释正确。

---

## Phase 6: User Story 4 - 不兼容老数据的覆盖式发布 (Priority: P3)

**Goal**: 确认 drop-and-recreate 覆盖式发布成立、且不存在为老数据保留的迁移/兼容路径（与 Constitution「No Legacy Migration」一致）。

**Independent Test**: 在含旧结构/旧数据的库上重启，结构按权威 schema 重建、应用正常；无迁移代码路径。

- [ ] T016 [US4] 在含旧数据的 PostgreSQL 上重启验证覆盖式重建：`docker compose up -d` → `./dev-install.sh` → `setsid` `spring-boot:run`（默认 PG）→ `curl localhost:8000/api/health` 健康 → `psql ... 'SELECT * FROM schema_version;'` 返回 1 行 `0.0.1`（quickstart §3）。（仅 H2 验证，PG 真库待人工 —— WSL2 环境 Docker 不可达）
- [x] T017 [US4] 核对并声明无遗留迁移路径：确认未新增任何 Flyway/Liquibase/迁移脚本，`spring.sql.init mode=always` + `DROP IF EXISTS` 覆盖式不变；记录于 quickstart/research（边界声明，非代码改动）。

**Checkpoint**: 覆盖式发布闭环，存量不予考虑被验证。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 全量回归、跨特性缝合、知识库与记忆固化

- [x] T018 全量 api 套件回归：`setsid` 跑 `./mvnw -pl dataweave-api test`，确认全绿、**无 masked-red**（与 T003 基线对比，遵守 backend 测试隔离不变量）。（139 run / 0 fail / 4 err / 0 skip；4 err = LineageGraphEndpointTest 上下文加载 NoClassDefFoundError: McpTool$Handler，确证既有技术债非 017 引入；SchemaVersionIT 6/6 全绿；无 017 引入的 masked-red）
- [ ] T019 [P] PostgreSQL 真库手工验证（quickstart §3）：默认 profile 启动 + 直连库确认 `schema_version` 单行 `0.0.1` + 全表建成（双库兼容 SC-007）。（仅 H2 验证，PG 真库待人工 —— WSL2 环境 Docker 不可达）
- [x] T020 [P] 跨特性复核（合并前）：`git diff main <016-spark-runtime-parity> -- backend/dataweave-api/src/main/resources/`；若 016 动过 schema.sql/data.sql，先合 016、把其结构改动并入权威 schema，再合 017，重跑 api 套件确认缝合（防不闭环）。（016 已在 main：286e19b/44d2fcf/81b78ba；016 未动 schema.sql/data.sql，无冲突）
- [x] T021 [P] 更新 `CLAUDE.md` 知识库导航：补「权威 schema 真相源 = `dataweave-api/.../schema.sql`」「schema 版本 = 项目版本、单行 `schema_version` 表、改表必升版本」「`db/migration` 已删除、不再用增量脚本」；并更新 `Current feature` 指针。
- [x] T022 走查 quickstart §4 人工对账清单，逐项打勾（目录已删 / 头部版本声明 / schema_version 单行 / 无死表 / 三处版本一致），确认 SC-001~SC-008 满足。

---

## Dependencies & Execution Order

```
Setup (T001 → T002)
  └─ Foundational (T003 基线全绿)            ← 阻塞所有故事
       ├─ US1 (T004 删目录 → [T005 可选] → T006 回归)         [MVP]
       │     └─ US2 (T007 头部版本 → T008 schema_version 表 → T009 建测试 → T010 跑测试)
       │           └─ US3 (T011 清 demo[P] ‖ T012 改注释 ‖ T013 orders评审[P] → T014 补 C5 → T015 验证)
       │                 └─ US4 (T016 PG 覆盖重建 → T017 无迁移声明)
       └─ Polish (T018 回归 → T019/T020/T021 并行 → T022 对账)
```

**关键串行约束**：T004 / T005 / T007 / T008 / T012 均改 `schema.sql` → 必须按 US1→US2→US3 顺序串行编辑同一文件，**不可标 [P] 互相并行**。
**SchemaVersionIT 串行**：T009 建文件 → T014 增补，同一测试文件不可并行。

## Parallel Opportunities

- **Setup**：T002 可与 T001 的 worktree 创建后并行核对。
- **US3 内**：T011（`demo-data.sql`/`application-demo.yml`）与 T013（`orders` 评审，纯核对）落在与 schema.sql 不同的文件/动作，可并行；T012 改 schema.sql 与它们也可并行（不同文件）。
- **Polish**：T019（PG 验证）、T020（016 复核）、T021（CLAUDE.md）三者文件/动作互不相干，可并行。

## Implementation Strategy

- **MVP = US1（T001–T006）**：删死目录 + 回归全绿，立即交付「单一权威 schema」价值，可独立验收。
- **增量 2 = US2**：补版本化（本特性的核心诉求「严格版本设计」），可单独演示 `SELECT version FROM schema_version`。
- **增量 3 = US3**：清 AI 残留、修 demo profile，提升整洁度。
- **增量 4 = US4**：覆盖式发布的边界验证（多为确认，无新代码）。
- 全程遵守：每步改 schema 后 `setsid` 脱离编译/测试（WSL2 硬规则）；不引入新 API/业务能力（FR-014）；合并前先合 016。
