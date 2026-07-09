# Implementation Plan: 血缘目录接地（Catalog Grounding）

**Branch**: `055-lineage-catalog-grounding` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/055-lineage-catalog-grounding/spec.md`

## Summary

把 053 已落地的 `DatasourceBoundCatalog`（缓存→neo4j→数据源实时抓列组合链，当前仅用于 `SELECT *` 列展开）**复用为表级真伪判据**：候选表向任务绑定数据源目录查存在性，得**三态** `PRESENT`/`ABSENT`/`UNKNOWN`。`PRESENT`→标 `catalog-verified`（confidence 升 CONFIRMED）高置信采纳；`ABSENT`（**仅推断类通道** SCRIPT_INFERRED/SCRIPT_MODEL/SCRIPT_AGENT）→剔除+落审计表；`UNKNOWN`→原样保留零惩罚；系统/元数据 schema→即使 PRESENT 也排除。全程在 **053 既有异步富化路径**（`LineageAgentEnricher`，每次 push 由 `LineageEnrichmentTrigger` 无条件触发）内执行，push 同步返回零开销。

技术核心增量三块：① **三态存在性探针**——`DatasourceSchemaResolver` 新增 `probeTable`（用 `DatabaseMetaData.getTables` 区分"连上+无行=ABSENT"与"连不上/超时=UNKNOWN"，比 getColumns 轻）；② **系统命名空间分类器**——按引擎的内置系统 schema 集合 + 可覆盖；③ **grounding 阶段 + 处置审计表**——在 enricher 的 merge→recordTaskIo 之间插入过滤，新增 `lineage_grounding_disposition` 表（schema 0.11.0→0.12.0）。

## Technical Context

**Language/Version**: Java 25（后端 dataweave-master 模块）

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7、Spring Data JDBC + JdbcTemplate、JDBC `DatabaseMetaData`、neo4j（既有列目录，只读复用）、Jackson 3（事件序列化）

**Storage**: PostgreSQL（新增 `lineage_grounding_disposition` 审计表；schema.sql 单一权威 DDL，`schema_version` 0.11.0→**0.12.0**）· neo4j（既有列目录 + 边元数据 `catalog-verified`/confidence，只读复用与既有写路径）· 进程内 TTL 缓存（复用 `DatasourceBoundCatalog.CACHE`）

**Testing**: JUnit 5 + AssertJ；H2 profile（in-memory、DDL 兼容）跑 grounding 逻辑与审计落库；`@SpringBootTest` 集成 enricher 异步阶段；带目录评测夹具（H2 建已知全表集合）量化 precision（US3）

**Target Platform**: Linux server（后端 WebFlux 进程内）

**Project Type**: web-service 后端单模块改动（无前端改动，v1）

**Performance Goals**: push 同步返回时延增幅 = 0（grounding 全异步，FR-008/SC-005）；grounding 目录查询命中 cache/neo4j 时零真连库；live probe 复用 053 的 3s 超时预算

**Constraints**: grounding 绝不抛异常打断主链路（任一层失败→该候选 UNKNOWN，整阶段失败→全 UNKNOWN，FR-007/SC-004）；`ABSENT` 剔除仅作用推断类通道，确定性（SQL_PARSED/SCRIPT_SQL/null/AGENT/FORM）永不剔除（FR-011）；未绑定数据源行为与今天完全一致（SC-003 误杀率 0）

**Scale/Scope**: 单任务候选表量级通常 <50；grounding 逐表查目录，靠 TTL 缓存 + neo4j 层摊薄；改动集中在 `application/lineage`（+grounding 子包）+ 1 张审计表 + resolver 1 方法

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**：不涉及任务/目录文件表示，纯血缘图谱质量增强。✅ 无冲突。
- **II. Server is Source of Truth**：grounding 在服务端 push 后异步富化，隔离沿用既有 tenant/project；不改 pull/push 语义。✅
- **III. Two-Legged Debugging**：不触 CLI/executor。✅ 无关。
- **IV. AI Lives in Local Agent（NON-NEGOTIABLE）**：grounding 是**确定性目录元数据查询**，零 AI 推理、零服务端 AI 大脑；反而**降低**对 053 云 AI 通道的依赖（把 AI 边用真实目录校准）。✅ 强化而非违反内核第 1 条。
- **V. Reuse the Kernel（NON-NEGOTIABLE）**：**全程复用** 053 `DatasourceBoundCatalog`/`DatasourceSchemaResolver`/`LineageAgentEnricher` 异步管线/`LineageStore.recordTaskIo` keyed replace/`AgentConfigRepository` 审计模式；不重写目录存储、不建第二条富化管线。✅ 教科书级内核复用。

**结论**：零违规，无 Complexity Tracking 需填。唯一治理动作 = schema 版本 bump（0.12.0），符合"改结构必升版本"约定。

## Project Structure

### Documentation (this feature)

```text
specs/055-lineage-catalog-grounding/
├── plan.md              # 本文件
├── research.md          # Phase 0：三态探针/来源分类/异步接缝/系统 schema 集合 决策
├── data-model.md        # Phase 1：lineage_grounding_disposition 表 + 领域实体
├── quickstart.md        # Phase 1：H2 夹具跑通 grounding 三态 + 审计的最短路径
├── contracts/           # Phase 1：三态探针 / grounding 阶段 / 系统分类器 契约
│   ├── table-existence-probe.md
│   ├── grounding-stage.md
│   └── system-namespace-classifier.md
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   ├── DatasourceSchemaResolver.java                 # [改] +probeTable(dsId, qn) → TableExistence 三态
│   └── lineage/
│       ├── DatasourceBoundCatalog.java               # [改] +probeExistence(...) 三态（cache/neo4j 命中=PRESENT，miss→live probe）
│       └── grounding/                                 # [新] grounding 子包
│           ├── TableExistence.java                    #   [新] enum PRESENT/ABSENT/UNKNOWN
│           ├── SystemNamespaceClassifier.java         #   [新] 按引擎判系统/元数据 schema
│           ├── CatalogGroundingService.java           #   [新] 三态裁决 + 来源分类 + 系统排除 + 处置
│           └── GroundingDisposition.java              #   [新] 处置记录值对象
│       └── agent/
│           └── LineageAgentEnricher.java              # [改] merge→recordTaskIo 间插 grounding；解除 AI-config 早退对 grounding 的门控
├── infrastructure/lineage/
│   └── GroundingDispositionRepository.java           # [新] insert 审计（镜像 AgentConfigRepository.insertCall）
└── ...

backend/dataweave-api/src/main/resources/schema.sql   # [改] +lineage_grounding_disposition 表 + DROP + version 0.12.0

backend/dataweave-master/src/test/java/com/dataweave/master/lineage/grounding/
├── TableExistenceProbeTest.java                      # [新] H2 三态：真表 PRESENT / 不存在 ABSENT / 连不上 UNKNOWN
├── SystemNamespaceClassifierTest.java                # [新] information_schema/pg_catalog 命中，业务 schema 不命中
├── CatalogGroundingServiceTest.java                  # [新] 来源分类：推断类可剔除、确定性只标不剔；系统排除；处置落表
├── GroundingEnricherIntegrationIT.java               # [新] @SpringBootTest：push→异步 grounding→图谱/审计断言（含 AI-off 仍接地）
└── GroundedPrecisionFixtureTest.java                 # [新] US3 带目录夹具：grounding on/off precision 对比 + 可复现
```

**Structure Decision**: 单后端模块（`dataweave-master`）改动，无前端。新增 `application/lineage/grounding/` 子包承载 grounding 领域逻辑，与 053 `application/lineage/`（catalog）、`application/lineage/agent/`（AI 通道）平级。审计表进 `schema.sql` 单一权威 DDL。集成点是 `LineageAgentEnricher`——053 已建的、每次 push 无条件触发的异步富化消费者，是 grounding 唯一落点（避免第二条管线与 recordTaskIo replace 竞争）。

## Complexity Tracking

> Constitution Check 零违规，无需填写。
