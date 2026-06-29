# Feature Specification: 列级 SQL 血缘解析

**Feature Branch**: `019-lineage-column-lineage`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 共享设计:[docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)(本 spec 为其中「B · 列级血缘解析」一份,全特性技术风险最集中处)

> **范围边界**:本特性只负责**从 SQL 推出列级派生关系**(`目标列 ← {源列}`),产出 `ColumnEdge` 喂给 018 的 `LineageStore`。存储/写入在 018;查询/API/前端在 020。本特性是纯解析能力,不直接写库(经 018 的写入接口)。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 直传列血缘(SELECT 直引列)(Priority: P1)

一条 `INSERT INTO dst (a,b) SELECT x,y FROM src` 被解析出 `dst.a ← src.x`、`dst.b ← src.y` 两条列级派生(transform=DIRECT)。

**Why this priority**: 列级血缘的最基本形态,覆盖绝大多数 ETL;没有它列级无从谈起。

**Independent Test**: 给定该 SQL + 已知 src/dst 列元数据,`SqlColumnLineageExtractor.extract()` 返回 2 条 `ColumnEdge`,源/目标列、transform、confidence 正确。

**Acceptance Scenarios**:

1. **Given** `INSERT INTO dst(a,b) SELECT x,y FROM src` 且列元数据已知, **When** 解析, **Then** 得 `dst.a←src.x`、`dst.b←src.y`,transform=DIRECT,confidence=CONFIRMED。
2. **Given** `INSERT INTO dst SELECT * FROM src`(列元数据已知), **When** 解析, **Then** `*` 按 src 列顺序展开为逐列直传。

### User Story 2 - 穿透 JOIN/CTE/UNION/子查询(Priority: P1)

复杂查询(多表 JOIN、WITH CTE、UNION、子查询)中,目标列能正确溯源到最终的物理源列,穿透中间临时结构。

**Why this priority**: 真实数仓 SQL 普遍含 JOIN/CTE;只支持直传等于没用。借 Calcite `RelMetadataQuery.getColumnOrigins` 原生穿透。

**Independent Test**: 给定含 JOIN+CTE 的代表性 SQL,目标列溯源命中正确的物理表.列集合。

**Acceptance Scenarios**:

1. **Given** `WITH t AS (SELECT id,amt FROM o JOIN u ON ...) INSERT INTO r(id,total) SELECT id,sum(amt) FROM t GROUP BY id`, **When** 解析, **Then** `r.id ← o.id`(或 u.id,按 ON)、`r.total ← o.amt`,聚合列 transform=AGGREGATE。
2. **Given** `UNION` 多分支, **Then** 目标列溯源为各分支对应列的并集。

### User Story 3 - 解析不了时干净降级(Priority: P1)

当源列元数据缺失、`*` 无法展开、SQL 是 DDL/动态/存储过程,或表达式无法溯源时,解析**不报错**,按阶梯降级(列级 UNVERIFIED / 退表级 / 留空),绝不阻断主链路。

**Why this priority**: 韧性是血缘的硬约束(沿用现有"血缘是增强,不阻断建任务")。降级行为错误会拖垮建任务/push。

**Independent Test**: 给定一组无法精确解析的 SQL,`extract()` 返回明确的降级结果(空/表级/UNVERIFIED)且不抛异常。

**Acceptance Scenarios**:

1. **Given** 源表列元数据未知的 `SELECT *`, **When** 解析, **Then** 列级留空、降级到表级,不抛异常。
2. **Given** DDL / 动态 SQL / 存储过程, **When** 解析, **Then** 返回 unparsed(列级空),与现 `SqlTableExtractor` 表级行为一致。
3. **Given** 含窗口函数/UDF 的列, **When** `getColumnOrigins` 返回空, **Then** 该列降级 UNVERIFIED,其余列正常。

### Edge Cases

- 表达式列(`a+b AS c`)→ `c ← {a,b}` 多源,transform=EXPRESSION。
- 同名列跨表歧义 → 靠 validator 的别名/限定名绑定消歧;无法消歧则 UNVERIFIED。
- 列大小写/引号 → 规范化规则 MUST 与表级 `SqlTableExtractor` 一致。
- Agent 在 `.task.yaml` 声明的列级 I/O 与解析结果冲突 → 标 confidence=CONFLICT(交叉校验),不静默丢弃。
- catalog "鸡生蛋":首见表无列元数据 → 本条 SQL 列级降级,后续该表列被注册后再建的任务可精确解析。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `SqlColumnLineageExtractor.extract(sql, catalog)`,从 SQL 推出列级派生关系,返回 `ColumnEdge` 集合。
- **FR-002**: 解析 MUST 采用 Calcite 三段式:Parser(SqlNode)→ Validator(用列元数据建 Schema、展开 `*`、绑定别名)→ RelNode + `RelMetadataQuery.getColumnOrigins`。
- **FR-003**: 系统 MUST 支持 JOIN / WITH CTE / UNION-INTERSECT-EXCEPT / 子查询 / 表达式列 / 聚合列 / `SELECT *` 展开 的列溯源。
- **FR-004**: 每条 `ColumnEdge` MUST 标注 `transform ∈ {DIRECT, EXPRESSION, AGGREGATE}` 与 `confidence ∈ {CONFIRMED, UNVERIFIED, CONFLICT}`。
- **FR-005**: 系统 MUST 实现降级阶梯:全元数据→CONFIRMED;缺元数据/`*` 不可展开→AST 启发式 UNVERIFIED;完全不可解析→退表级(列级空);任何异常→catch + 退表级 + 日志。**任何情况下 MUST NOT 抛出阻断主链路**。
- **FR-006**: 系统 MUST 实现列级 A×B 交叉校验:Agent 在 `.task.yaml` 可选声明的列级 I/O 与解析结果比对,产出 source/confidence(含 CONFLICT)。
- **FR-007**: 列名规范化(大小写、引号、schema 前缀)MUST 与表级 `SqlTableExtractor` 保持一致,确保列能正确挂到 018 的 `:Column` 节点。
- **FR-008**: `ColumnEdge` 输出契约 MUST 与共享设计 §6 一致,作为 018 `LineageStore.recordTaskIo()` 的入参;本特性 MUST NOT 直接写 neo4j。
- **FR-009**: 现有表级 `SqlTableExtractor` MUST 保留并作为列级解析的降级底座与表级交叉校验来源(二者不重复造轮子)。

### Key Entities *(include if feature involves data)*

- **ColumnEdge**:列级派生边。属性:`srcTable, srcCol, dstTable, dstCol, transform, confidence`。本特性的核心产物。
- **Catalog/ColumnSchema**(解析期输入):已知表的列元数据(来自 018 的 `:Column`/`:Table`),供 validator 解析列引用。
- **Transform**:`DIRECT`(直引)/ `EXPRESSION`(表达式)/ `AGGREGATE`(聚合)。
- **Confidence**:`CONFIRMED`(元数据+解析一致或纯解析可信)/ `UNVERIFIED`(降级推断)/ `CONFLICT`(与 Agent 声明冲突)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 对一组代表性 SQL(直传/`*`/JOIN/CTE/UNION/表达式/聚合)≥ 目标覆盖,目标列正确溯源到物理源列。
- **SC-002**: 所有无法精确解析的输入(DDL/动态/存储过程/缺元数据)均干净降级,**0** 抛出阻断主链路的异常。
- **SC-003**: 列名规范化与表级 100% 一致(同一张表/列在表级与列级解析得到同一标识)。
- **SC-004**: 与 Agent 声明冲突的列被正确标 CONFLICT,不静默丢弃。
- **SC-005**: `SqlColumnLineageExtractor` 纯单测(catalog fixture,覆盖各 SQL 形态 + 降级)全绿,无需 neo4j。

## Assumptions

- 列血缘的**存储与写入**由 `018-lineage-neo4j-store` 负责;本特性只产出 `ColumnEdge`,经 018 写入接口落库。
- 解析期所需的列元数据(catalog)由 018 提供(从图查已注册 `:Column`);首见表无元数据时降级,符合"建任务即逐步补全"的现有节奏。
- 仅 **SQL 类型**任务做代码级列解析;SHELL/Python 等脚本内直连数据库的复杂类型不在本期(共享设计 §9 未来工作:LLM 解析)。
- 沿用现 Calcite 方言与解析失败容错范式(`SqlTableExtractor`)。
