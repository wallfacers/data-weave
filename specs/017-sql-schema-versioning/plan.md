# Implementation Plan: SQL 脚本重梳理与严格 Schema 版本设计

**Branch**: `017-sql-schema-versioning` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/017-sql-schema-versioning/spec.md`

## Summary

把后端散落的、未被启动加载的历史增量改表脚本收口进**唯一权威 schema**，删除该死目录，清理 AI 拆除遗留的过时 SQL（`task_diagnosis`/`finding` 等已不存在表的种子 INSERT、指向废弃设计文档的真相源注释），并为 schema 赋予一个**等于项目发布版本**的严格 SemVer 标识（基线 `0.0.1`）——版本字面量写在 schema 文件头部、并以单行 `schema_version` 表落库可查，三处恒等。发布模型沿用既有的 drop-and-recreate 覆盖式（与 Constitution「No Legacy Migration」硬规则一致），不为老数据建任何迁移。本特性为**结构等价的脚本治理**：零新增业务能力、零 API、零内核改动；唯一新增的库对象是基础设施性质的 `schema_version` 单行表。

技术路径已由勘探与澄清确定，无 NEEDS CLARIFICATION：现有 `schema.sql` 已含全部 8 个 migration 文件的改动效果（逐项核对 100% 覆盖），故收口=**删 `db/migration/` 目录 + 补版本标识 + 清过时残留**，而非重写 DDL。

## Technical Context

**Language/Version**: Java 25 / Spring Boot 4.0；DDL 为 PostgreSQL / H2 双方言兼容的通用 SQL。

**Primary Dependencies**: Spring `spring.sql.init`（启动按 `schema-locations` + `data-locations` 加载，`mode=always`，脚本含 `DROP IF EXISTS` 幂等重建）；Spring Data JDBC / JdbcTemplate（测试期查询版本）。

**Storage**: PostgreSQL（默认，Docker `:5432`）· H2 内存（测试/`h2` profile，`MODE=PostgreSQL`，DDL 兼容）。单一权威文件 `backend/dataweave-api/src/main/resources/schema.sql`（+ `data.sql` 种子）；测试复用 main 的 schema（api 模块 test 资源下无第二份）。

**Testing**: JUnit 5 + AssertJ + `@SpringBootTest`（H2，遵守 backend 测试隔离不变量：每套件唯一随机库 `jdbc:h2:mem:dataweave-${random.uuid}`、redis health off、`@DirtiesContext` 防 seed 漂移）。

**Target Platform**: Linux server（后端 JVM 进程）。

**Project Type**: Web 后端多模块（DDD）——本特性仅触及 `dataweave-api` 模块的资源文件与一个 api 集成测试。

**Performance Goals**: N/A（脚本治理，不涉运行时性能）。

**Constraints**: 单一权威 schema、零孤立脚本、PG/H2 双库建库 100%、文件声明与实际建表零漂移、删表零残留引用、不改业务行为/API。

**Scale/Scope**: 删除 8 个 migration 文件 + 1 个死目录；schema.sql ~890 行内补 1 个 `schema_version` 表 + 版本头注释；清理 `demo-data.sql` 过时 INSERT（2 张已移除表）；修 1 处废弃真相源注释；新增 1 个 api 集成测试。

## Constitution Check

*GATE：Phase 0 前必过；Phase 1 设计后复检。*

| 原则 | 评估 | 结论 |
|------|------|------|
| **I. Files-First** | schema/种子本就是可读、可 diff、可评审的纯文本文件；收口为单一权威文件**增强**而非削弱文件优先。 | ✅ PASS |
| **II. Server is Source of Truth** | 不触碰 pull/push/快照/隔离；schema 是服务端治理底座，版本化使「库结构属于哪个版本」可判定，正向支撑治理。 | ✅ PASS |
| **III. Two-Legged Debugging** | 不触碰 CLI/本地 runtime/执行器语义。 | ✅ N/A |
| **IV. AI Lives in Local Agent** | 本特性**进一步清除** AI 驾驶舱拆除残留（`task_diagnosis`/`finding` 死种子，致 demo profile 实际已坏）；不引入任何服务端 AI；不损伤 ops/metrics/run-logs/DAG 观测与调度内核。 | ✅ PASS（强化 IV） |
| **V. Reuse the Kernel** | 无内核重写；调度/快照/执行器/PolicyEngine/MCP 框架均不动。新增 `schema_version` 为被动记录表，无写网关路径。 | ✅ PASS |
| **附则：No Legacy Migration（存量不予考虑，硬规则）** | 本特性的 drop-and-recreate 覆盖式、不为老数据建迁移，与该硬规则**完全同向**。 | ✅ PASS（直接印证） |
| **附则：Round-trip / Sub-spec isolation** | 不改定义文件格式与 pull/push round-trip；边界自洽，不与 A–E 子特性重叠。 | ✅ PASS |
| **质量门：测试必备** | 交付含 api 集成测试（版本可查 + SemVer + 双库建库 + 无 demo 死引用）。 | ✅ PASS |

**跨特性意识（Parallel-Feature Isolation）**：017 自 `main`（=014）切出。**016-spark-runtime-parity**（Spark runtime，主要在 `dataweave-worker` 模块，commits `286e19b`/`44d2fcf`，尚未在 main）理论上不触 `dataweave-api/schema.sql`；**合并前**须 `git diff` 复核 016 是否对 schema.sql/data.sql 增列加表——若有，先合 016、再把其结构改动一并并入权威 schema，避免我方收口/整理覆盖其新增（防「编译通过但 sibling 落地即破」）。实现须在 **017 分支/worktree** 进行（当前工作树在 main，实现前先切分支）。

无违规项，**Complexity Tracking 不适用**。

## Project Structure

### Documentation (this feature)

```text
specs/017-sql-schema-versioning/
├── plan.md              # 本文件
├── spec.md              # 已澄清
├── research.md          # Phase 0：漂移核对结论 + 版本/存储决策
├── data-model.md        # Phase 1：schema_version 表 + 收口/删除清单
├── quickstart.md        # Phase 1：PG/H2 双库验证 + 漂移核对步骤
├── contracts/
│   ├── README.md        # 说明本特性无 HTTP 契约（FR-014）
│   └── schema-version.contract.md  # schema_version 表形态 + 版本戳规则（可测契约）
├── checklists/
│   └── requirements.md  # 已通过
└── tasks.md             # /speckit-tasks 生成（非本命令产物）
```

### Source Code (repository root)

```text
backend/dataweave-api/src/main/resources/
├── schema.sql                 # 【权威 schema·唯一真相源】
│                              #   + 头部声明 Schema Version: 0.0.1（= 项目版本）
│                              #   + 新增 CREATE TABLE schema_version（单行）
│                              #   + 同文件内 INSERT 版本字面量（与头注释同源，防漂移）
│                              #   + 修正废弃真相源注释（openspec/...→现行文档/自述）
│                              #   + (可选) 归一 master_nodes 等尾部 IF NOT EXISTS 块入主 DROP+CREATE 结构
├── data.sql                   # 种子；随删表同步清理相关条目（如有）
├── demo-data.sql              # 清理过时 INSERT（finding/task_diagnosis 已移除表）；若清后无实义则删除并撤 demo data-locations
├── application.yml            # （仅核对 sql.init 配置，无需改）
├── application-demo.yml       # 若删 demo-data.sql，则同步移除其 data-locations 引用
└── db/migration/              # 【整目录删除】8 个文件效果已 100% 并入 schema.sql，零代码/配置引用
    ├── V__add-master-nodes-pg.sql
    ├── V__add-next-trigger-pg.sql
    ├── catalog-pg.sql
    ├── datasource-connection-status-pg.sql
    ├── datasource-driver-isolation-pg.sql
    ├── distributed-scheduler-m1-uuidv7-pg.sql   # 含对已删 task_diagnosis 的 ALTER（过时）
    ├── task-instance-locale-pg.sql
    └── workflow-canvas-pg.sql

backend/dataweave-api/src/test/java/com/dataweave/api/
└── schema/SchemaVersionIT.java   # 【新增】版本可查 + SemVer + 单行 + 启动建库（H2）+ demo 死引用回归
```

**Structure Decision**：纯后端、SQL 资源治理，作用域限于 `dataweave-api` 单模块。唯一权威 schema 文件为 `dataweave-api/src/main/resources/schema.sql`（启动加载、测试复用的同一份），故所有收口/版本化/清理集中于此 + 配套种子文件；新增一个 api 集成测试固化契约。不涉及 master/worker/alert 模块与前端。

## Complexity Tracking

> 无 Constitution 违规，无需填写。
