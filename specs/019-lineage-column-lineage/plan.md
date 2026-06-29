# Implementation Plan: 列级 SQL 血缘解析

**Branch**: `019-lineage-column-lineage` | **Date**: 2026-06-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/019-lineage-column-lineage/spec.md`;共享设计契约 `docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md`(§6 列级解析 + §10 拆分)

## Summary

把 SQL 血缘解析从"仅表级"升级到"列级":新增 `SqlColumnLineageExtractor`,用 Apache Calcite 三段式(Parser → Validator+catalog → RelNode + `RelMetadataQuery.getColumnOrigins`)从一条 SQL 推出 `目标列 ← {源列}` 的派生关系,产出稳定的 `ColumnEdge` 契约喂给 018 的 `LineageStore`。核心不变量:**韧性优先,任何解析失败都按降级阶梯退到表级/UNVERIFIED,绝不抛出阻断建任务/push 主链路**。本特性是纯解析组件,不直接写 neo4j、不做查询。

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Apache Calcite(`org.apache.calcite`:parser / validator / `RelMetadataQuery.getColumnOrigins` / `RelOptTable` catalog);复用既有 `SqlTableExtractor`(表级降级底座)。位于 Maven 模块 `dataweave-master`。

**Storage**: N/A —— 本特性不持久化;产物 `ColumnEdge` 经 018 `LineageStore.recordTaskIo()` 写入 neo4j。解析期所需列元数据(catalog)由 018 提供(从图查已注册 `:Column`)。

**Testing**: JUnit 5 + AssertJ,**纯单测**(catalog fixture 构造已知表/列,无需 neo4j),覆盖各 SQL 形态 + 降级阶梯。

**Target Platform**: Linux server(Spring Boot 4 后端,`dataweave-master` 模块内的应用层组件)。

**Project Type**: 后端单模块库组件(Maven 多模块的一员)。

**Performance Goals**: 解析单条任务级 SQL 的耗时相对建任务/push 链路可忽略;失败快速降级不阻塞。

**Constraints**(领域硬约束): 解析失败 MUST NOT 抛出阻断主链路的异常;列名规范化 MUST 与表级 `SqlTableExtractor` 一致;仅覆盖 **SQL 类型**任务(SHELL/Python 复杂类型属未来 LLM TODO)。

**Scale/Scope**: 任务级 SQL(非海量),覆盖 DIRECT/EXPRESSION/AGGREGATE + JOIN/CTE/UNION/子查询/`SELECT *` 展开。

## Constitution Check

*GATE: Must pass before Phase 0. Re-check after Phase 1.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I. Files-First | 血缘是从任务文件(SQL)派生的元数据,非新定义文件;不破坏文件优先 | ✅ PASS |
| II. Server source of truth + 隔离 | 列血缘是服务端治理元数据;租户隔离在 018 写入时按 tenantId scope | ✅ PASS |
| III. Two-legged debugging | 服务端解析,不涉 CLI 运行时;不分叉执行引擎 | ✅ N/A |
| IV. AI 归位本地(无服务端 AI 大脑) | 本特性是**纯 Calcite 代码解析,零 AI/推理**;LLM 解析是文档化的未来 TODO,**不在本特性**。未来引入 LLM 解析时须确保其为"解析工具"而非"服务端 agent 大脑",届时单独走宪法评审 | ✅ PASS(并标注未来约束) |
| V. Reuse the Kernel | **复用** `SqlTableExtractor`(表级)作降级底座与交叉校验,不重写;复用 Calcite | ✅ PASS |
| 测试硬规则 | 纯单测 catalog fixture,新功能必有测试 | ✅ PASS |
| Sub-spec 隔离/不闭环 | 与 018(存储/写入)、020(查询/前端)边界清晰;只产出 `ColumnEdge`,经 018 接口落库——单独编译可测,落地后与 018 接口对桩闭环 | ✅ PASS |

无违规,无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/019-lineage-column-lineage/
├── plan.md              # 本文件
├── research.md          # Phase 0:技术决策
├── data-model.md        # Phase 1:ColumnEdge 等实体
├── quickstart.md        # Phase 1:如何跑解析单测
├── contracts/           # Phase 1:ColumnEdge / catalog 输入契约
└── tasks.md             # speckit-tasks 输出
```

### Source Code (repository root)

```text
backend/dataweave-master/src/main/java/com/dataweave/master/application/
├── SqlTableExtractor.java          # 既有(表级)——保留,作降级底座/交叉校验
├── SqlColumnLineageExtractor.java  # 新增:列级解析主入口 extract(sql, catalog)
├── lineage/                        # 新增列级解析支撑(按需)
│   ├── ColumnEdge.java             # 输出契约 record
│   ├── ColumnLineageCatalog.java   # 解析期列元数据输入(由 018 适配填充)
│   ├── CalciteColumnLineage.java   # Calcite 三段式:validate→RelNode→getColumnOrigins
│   └── ColumnLineageDegrade.java   # 降级阶梯(AST 启发式 / 退表级)

backend/dataweave-master/src/test/java/com/dataweave/master/application/
├── SqlColumnLineageExtractorTest.java   # 纯单测:各 SQL 形态 + 降级(catalog fixture)
└── lineage/CalciteColumnLineageTest.java
```

**Structure Decision**: 列级解析组件落在 `dataweave-master` 应用层,与既有 `SqlTableExtractor` 并置;新建 `lineage/` 子包收纳列级解析的契约与实现,保持单一职责、与表级解耦但共享列名规范化。不引入新模块。`ColumnEdge`/`ColumnLineageCatalog` 是与 018 的接缝契约,本特性定义、018 适配实现 catalog 数据源。

## Complexity Tracking

> 无 Constitution 违规,无需填写。
