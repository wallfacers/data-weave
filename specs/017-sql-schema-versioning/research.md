# Phase 0 Research: SQL 脚本重梳理与严格 Schema 版本设计

**Feature**: 017-sql-schema-versioning | **Date**: 2026-06-29

本特性无 NEEDS CLARIFICATION（spec 已澄清，版本语义经用户拍板）。本文记录支撑规划的勘探结论与关键设计决策。

---

## R1. `db/migration/` 是否仍是活脚本？

**Decision**: 整目录（8 文件）判定为**死目录，删除**。

**Rationale**:
- 工程未引入 Flyway/Liquibase——`grep -rni 'db/migration|flyway|liquibase'` 在 `*.java/*.yml/*.xml` 中**零命中**。启动仅由 `spring.sql.init` 加载 `schema.sql` + `data.sql`（`application.yml` 第 17–21 行）。
- 逐项核对 8 个 migration 的目标改动是否已在 `schema.sql`：

  | migration 文件 | 目标改动 | 已在 schema.sql？ |
  |---|---|---|
  | `catalog-pg.sql` | catalog_node / tag / entity_tag + task_def/workflow_def.catalog_node_id | ✅ |
  | `datasource-driver-isolation-pg.sql` | driver_jars + datasources.driver_jar_id | ✅ |
  | `datasource-connection-status-pg.sql` | datasources.connection_status | ✅ |
  | `V__add-master-nodes-pg.sql` | master_nodes | ✅（尾部 IF NOT EXISTS 块） |
  | `V__add-next-trigger-pg.sql` | workflow_def.next_trigger_time / schedule_interval_ms | ✅ |
  | `task-instance-locale-pg.sql` | task_instance.locale | ✅ |
  | `workflow-canvas-pg.sql` | workflow_node.node_type / task_id 可空 | ✅ |
  | `distributed-scheduler-m1-uuidv7-pg.sql` | task_instance/workflow_instance 改 UUID + **ALTER task_diagnosis** | ✅（UUID 已并入）；task_diagnosis **已不存在** |

  → 效果 100% 覆盖；保留只会制造「哪份算数」的漂移源。`distributed-scheduler-m1-uuidv7-pg.sql` 还 ALTER 了已被移除的 `task_diagnosis`，本身即过时。

**Alternatives considered**:
- *引入 Flyway/Liquibase 把这些变成正式版本化迁移链* —— 被否：与用户「老数据不考虑兼容、直接删除覆盖」及 Constitution「No Legacy Migration」硬规则相悖；迁移链是为存量兼容而生，本项目刻意不要。
- *保留目录作历史归档* —— 被否：零加载、零引用的脚本留在 `resources/` 下会被误读为「仍在用」，git 历史已是足够的归档。

---

## R2. 版本语义：schema 版本 与 项目版本的关系

**Decision**: **schema 版本 == 项目发布版本**（同一个号，**无独立映射**）。基线 `0.0.1`。任何 schema 结构变更必须伴随项目版本升级。

**Rationale**:
- 用户在 /speckit-clarify 中明确：「两者相同即可……加字段必须有版本升级吧，用映射不太好，哪天漏了不方便查找」。
- 单一版本号消除「映射漏更新」隐患；版本即真相，结构变更与版本号强绑定形成纪律。
- 项目当前版本：后端 `dataweave-backend` pom `0.0.1-SNAPSHOT`、前端 `0.0.1`——与诉求的基线 `0.0.1` 天然对齐（schema 版本取去 `-SNAPSHOT` 的发布号 `0.0.1`）。

**Alternatives considered**:
- *独立 SemVer + 映射表*（/speckit-clarify 的初始推荐）—— 被用户否决（多一处易漏难查）。
- *仅用项目版本、不写 schema 侧版本* —— 被否：FR-006 要求库内可查；且无法在不启动应用时仅看 schema 文件就知道结构版本。

**版本递增规则（递增的就是项目版本）**：破坏性（删表/删列/改类型）→ MAJOR；新增表/列等向后可加 → MINOR；修正 → PATCH；一次多类取最高级。

---

## R3. schema 版本在何处记录、如何防漂移、如何可查？

**Decision**: 三处同源、恒等——
1. **schema.sql 头部注释**声明 `Schema Version: 0.0.1`（人读真相）。
2. **schema.sql 内新增 `schema_version` 单行表 + 同文件 INSERT 版本字面量**（机读真相，库内可查）。
3. 该值即**项目发布版本**。

把 `CREATE TABLE schema_version` 与其唯一一行 `INSERT ... VALUES ('0.0.1', ...)` 都放在 **schema.sql 同一文件内**（而非 data.sql），使「版本字面量」与「结构定义」物理同源、随同一份文件加载——天然防漂移（改结构必看到同文件头部的版本，改版本即改 schema.sql）。

**Rationale**:
- FR-006 要求库内可查 → 必须落库；选最轻量的单行专用表，语义直白（`SELECT version FROM schema_version`）。
- 放 schema.sql 而非 data.sql：版本属「结构元数据」，应随 schema 走；即便某 profile 不加载 data.sql，版本仍在。
- 契合项目既有约定「metric definitions immutable：版本只增不改写」的精神——`schema_version` 表可设计为单行覆盖（drop-recreate 下每次启动重写当前版本），或追加历史行（保留升级轨迹）。**取单行**：与「覆盖式发布、老数据不留」一致，最简。

**Alternatives considered**:
- *PostgreSQL `COMMENT ON DATABASE` / 不落表* —— 被否：H2 兼容性差、查询不统一，FR-006「库内可查」体验弱。
- *键值元数据表存多 key* —— 被否：当前只需一个版本值，专用单行表更直白、零过度设计。
- *构建期从 pom 版本注入字面量（resource filtering / build-info）* —— **作为可选增强**：可加 `spring-boot-maven-plugin` build-info 生成 `BuildProperties`，由测试断言「库内 schema_version == 项目版本」实现自动化等值守护。当前阶段**不强制**（避免引入构建配置复杂度）；手工维护版本字面量 + 测试断言 SemVer/单行/基线即可。若后续频繁发版，再开 build-info 闭合 pom↔schema 自动等值。

---

## R4. 过时内容清单（删除/修正对象）

**Decision**: 除删 `db/migration/` 外，清理以下 AI 拆除遗留与陈旧注释：

| 对象 | 现状 | 处置 |
|---|---|---|
| `demo-data.sql` 的 `INSERT INTO task_diagnosis` / `finding` | 两表已在 Weft AI 拆除（特性 A）中从 schema.sql 移除，**demo profile 实际已坏** | 删除这些过时 INSERT；若 demo-data.sql 清后仅剩无实义内容则整文件删除 + 撤 `application-demo.yml` 的 data-locations 引用 |
| `schema.sql` 头部真相源注释 | 指向已废弃 `openspec/changes/agent-native-cockpit/design-data-model.md` | 改指现行有效文档，或声明 schema.sql 自身即真相源 |
| `master_nodes` 等尾部 `CREATE TABLE IF NOT EXISTS` 追加块 | 以「Batch B 后补」风格附在文件尾，风格与主体 DROP+CREATE 不一致 | （可选、低优先）归一进主 DROP（逆依赖序）+ CREATE 结构，保持单一风格一致 |

**Rationale**:
- `task_diagnosis`/`finding` 全仓核对：仅存于 `demo-data.sql` 与一处测试注释（`DefaultPlatformActionExecutorTest`「随 TaskDiagnosis 移除」），无活代码引用 → 安全删，且当前 demo profile 已因此 broken，删除即修复。
- 真相源注释指向不存在的 openspec 路径，误导维护者。

**Alternatives considered**:
- *删 `orders` 表* —— **不删（保留待评审）**：`orders` 仅在 `SqlTableExtractorTest` 中作为被解析的 SQL 字符串出现（非真实查询），且可能作血缘 demo 数据；体量极小、删除风险/收益不划算。列为 review 项，默认保留。

---

## R5. 测试与验证策略

**Decision**: 新增 1 个 api 集成测试 `SchemaVersionIT`（`@SpringBootTest` + H2，遵守 backend 测试隔离不变量），覆盖：
1. **版本可查 + SemVer + 单行**：`SELECT` 出恰好一行，值匹配 `^\d+\.\d+\.\d+$`，等于基线 `0.0.1`。
2. **双库建库**：H2 上 `@SpringBootTest` 能起即证 H2 建库成功；PostgreSQL 由 quickstart 手工 `spring-boot:run` 验证（CI 默认 H2）。
3. **demo 死引用回归**：断言 schema.sql 不再存在 `task_diagnosis`/`finding`，且（若保留 demo profile）demo profile 能起。
4. **无孤立脚本回归**：断言 `db/migration/` 目录不存在（防回退重现死目录）。

**Rationale**: Constitution 质量门「无测试=未完成」；上述把 spec 的 SC-001/003/004/005/007 转成可执行断言。WSL2 下编译/测试须 `setsid` 脱离（见 CLAUDE.md 硬规则）。

**Alternatives considered**:
- *仅手工验证* —— 被否：违反测试门，且漂移/回退无自动守护。

---

## 结论

无遗留未知项。设计要点：删死目录 + 清 AI 残留 + 补「schema 版本=项目版本」的单行落库与头注释 + 1 个 api 集成测试固化契约；覆盖式发布不变，零业务/API/内核改动。
