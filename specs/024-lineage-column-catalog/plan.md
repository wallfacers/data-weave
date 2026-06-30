# Implementation Plan: 声明驱动的列血缘 Catalog

**Branch**: `024-lineage-column-catalog` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/024-lineage-column-catalog/spec.md`

## Summary

破解列级血缘的「鸡生蛋」：019 产 `ColumnEdge` 依赖 catalog、018 写 `:Column` 又依赖 `ColumnEdge`，接入点 `EmptyColumnLineageCatalog` 恒返回空导致列血缘事实为空。本特性在 `.task.yaml` 引入可选声明（`schema` 表→列+类型、`columnLineage` 期望列边），驱动 neo4j **独立 seed `:Column`/`:DERIVES_FROM`**（不经 ColumnEdge）破循环，新增 `Neo4jColumnLineageCatalog` 替换空实现让 019 真正产出 CONFIRMED 列边，并激活 019 FR-006 已写零调用的 `extractAndCrossCheck` 交叉校验（声明 vs 推导 → CONFIRMED/DECLARED/CONFLICT）。技术路径：复用 `ensureColumn`/`ColumnLineageCrossCheck`/`recordTaskIo`/Calcite，neo4j 单一底座（方案 1，与 L2 收口同向）。

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.0 / Spring Framework 7 (Jackson 3), WebFlux; 声明表面为 YAML (`.task.yaml`)。

**Primary Dependencies**: Neo4j（血缘图单一底座，复用 018 `Neo4jLineageStore`）、Apache Calcite（列级 SQL 解析，复用 019 `CalciteColumnLineage`）、Spring Data JDBC。

**Storage**: Neo4j（`:Table`/`:Column`/`:HAS_COLUMN`/`:DERIVES_FROM` 图模型，018 已定义）；`.task.yaml` 明文文件承载声明（Constitution I 文件优先）。

**Testing**: JUnit 5 + AssertJ；testcontainers-neo4j 跑集成（seed→catalog round-trip、cross-check 四情形、CONFLICT/DECLARED）；纯单测覆盖 yaml 解析 + 对账逻辑（catalog fixture，无需 neo4j）。

**Target Platform**: Linux server（后端 JVM，dataweave-master 模块）。

**Project Type**: web-service（Spring Boot 多模块 DDD 后端；本特性纯后端 + `.task.yaml` 格式扩展，无前端改动——020 读侧契约不变，CONFLICT 透出复用现有列视图）。

**Performance Goals**: 列血缘为增强项；catalog lookup 与 seed MUST NOT 阻塞 push 主链路（降级优先于精确）。

**Constraints**: 「解析是增强、绝不阻断」硬约束（019 FR-005 / 024 FR-010）；任何声明/catalog/seed 异常 MUST 退表级 + 日志，零阻断；无声明任务零回归。

**Scale/Scope**: SQL 类型任务受益于列解析；`columnLineage` 声明对任意任务类型可作 DECLARED 兜底边。多任务同表声明漂移检测出范围。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | 状态 | 依据 |
|---|---|---|
| I. Files-First（文件优先） | ✅ PASS | 声明落在 `.task.yaml` 明文（`schema`/`columnLineage` 块），可读/diff/review；非 DB-only 配置（FR-001/FR-002）。 |
| II. Server is Source of Truth（round-trip） | ✅ PASS | 声明经 `pull`/`push` 文件同步 round-trip，不丢字段（FR-002）；push 仍是唯一写入方向。 |
| III. Two-Legged Debugging | N/A | 本特性不涉及 CLI 本地 runtime / TEST 提交；不触碰两条腿调试。 |
| IV. AI Lives in Local Agent | ✅ PASS | 不引入服务端 AI 大脑；声明由本地 Agent 创作，服务端纯解析/存储。 |
| V. Reuse the Kernel（内核复用） | ✅ PASS | 复用 `ensureColumn`/`ColumnLineageCrossCheck`/`recordTaskIo`/Calcite/020 读侧契约，不重写 019 解析内核或 020 查询（FR-011）。 |
| Additional: round-trip integrity | ✅ PASS | push→pull 声明字段无丢失（FR-002，SC-005 验收）。 |
| Additional: sub-spec isolation | ✅ PASS | 与 018/019/020 边界清晰：不改 019 解析器、不改 020 契约，仅补 catalog 实现 + 激活 cross-check + 加 seed 路径。 |

**Gate 结果**: 无违规，无需 Complexity Tracking。进入 Phase 0。

## Project Structure

### Documentation (this feature)

```text
specs/024-lineage-column-catalog/
├── plan.md              # 本文件
├── research.md          # Phase 0 产出
├── data-model.md        # Phase 1 产出
├── quickstart.md        # Phase 1 产出
├── contracts/           # Phase 1 产出（.task.yaml 声明契约）
└── tasks.md             # Phase 2 (/speckit-tasks，本命令不生成)
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/
├── application/
│   ├── TaskService.java                      # [改] 调用序：声明 schema 的 seed 提至 extract(485) 之前（FR-009）
│   ├── ProjectSyncService.java               # [改] extract 调用点(858) → extractAndCrossCheck（激活 cross-check）
│   └── lineage/
│       ├── ColumnLineageCatalog.java         # [既有接口] lookupTable 契约不变
│       ├── EmptyColumnLineageCatalog.java    # [既有] 保留作 H2/非 neo4j profile fallback
│       ├── Neo4jColumnLineageCatalog.java    # [新增] @Primary(neo4j profile) 读 :Column 回组 TableSchema
│       ├── ColumnLineageCrossCheck.java      # [既有] 本次激活 crossValidate（union D,R + 置信度）
│       ├── SqlColumnLineageExtractor.java    # [既有] 激活 extractAndCrossCheck 调用路径
│       └── CalciteColumnLineage.java         # [既有 不改] catalog 现有数据，解析自然生效
├── domain/lineage/
│   ├── LineageStore.java                     # [既有接口] recordTaskIo 入参扩展收 declared edges
│   ├── TableSchema.java / ColumnMeta.java    # [既有] dataType/ordinal 已建模
│   └── ColumnEdge.java                       # [既有] confidence 枚举 +DECLARED
└── infrastructure/lineage/
    ├── Neo4jLineageStore.java                # [改] recordTaskIo 独立 seed :Column(吃 type/ordinal) + 落 :DERIVES_FROM{confidence}；ensureColumn 去硬编码 null
    └── Neo4jLineageGraphReader.java          # [既有 不改] 020 读侧契约不变

# 声明解析（新增）：扩展 .task.yaml 既有解析器，把 schema/columnLineage 块 → TableSchema 列表 + declared ColumnEdge 列表
backend/dataweave-master/.../ （既有 yaml 解析处扩展，具体类 research.md 落实）

.task.yaml（任务定义文件，声明表面）
├── schema:          { 表名: [{name, type}, ...] }     # 新增可选块
└── columnLineage:   [{from: 表.列, to: 表.列}, ...]   # 新增可选块
```

**Structure Decision**: 纯后端特性（dataweave-master 模块），无前端改动；按 DDD 分层（domain ← application ← infrastructure），全部落在既有 `lineage` 包内。新增仅 `Neo4jColumnLineageCatalog` + `.task.yaml` 声明解析扩展；改动集中在 `TaskService`/`ProjectSyncService` 调用点（调用序 + cross-check 激活）与 `Neo4jLineageStore.recordTaskIo`（独立 seed + 落边）。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
