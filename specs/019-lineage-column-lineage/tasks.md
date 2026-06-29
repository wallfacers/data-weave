---
description: "Task list — 列级 SQL 血缘解析"
---

# Tasks: 列级 SQL 血缘解析

**Input**: Design documents from `specs/019-lineage-column-lineage/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 含测试任务(项目硬规则:新功能必须有测试;本特性纯单测 catalog fixture,无需 neo4j)。

**Module root**: `backend/dataweave-master/src/{main,test}/java/com/dataweave/master/application/`

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 在 `application/lineage/` 新建子包目录,确认 `dataweave-master/pom.xml` 已含 Apache Calcite 依赖(`SqlTableExtractor` 已用;缺则补 core/calcite)。
- [ ] T002 [P] 抽列/表名规范化公共工具 `application/lineage/NameNormalizer.java`,从 `SqlTableExtractor` 提取同一套规则(大小写/去引号/schema 前缀),并改 `SqlTableExtractor` 复用之(保证表级/列级一致,契约 C3)。

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ 所有 user story 依赖本阶段**

- [ ] T003 [P] 定义输出契约 records:`application/lineage/ColumnEdge.java`、`TableRef.java`、`ColumnLineageResult.java`、`Transform.java`、`Confidence.java`(严格按 contracts/column-lineage-contract.md §2)。
- [ ] T004 [P] 定义解析期输入契约:`application/lineage/ColumnLineageCatalog.java`(接口)、`TableSchema.java`、`ColumnMeta.java`(契约 §3)。
- [ ] T005 创建解析主入口骨架 `application/SqlColumnLineageExtractor.java`,方法 `extract(String sql, ColumnLineageCatalog catalog)`,**外层 try-catch 兜底**:任何异常→退表级→返回 `parsed=false`(契约 C1,先把"绝不抛"的壳立住)。
- [ ] T006 [P] 建测试基座 `test/.../lineage/CatalogFixtures.java`:用内存 `ColumnLineageCatalog` 构造已知表(ods_order/ods_user/dwd_order 等)供各 story 单测复用。

**Checkpoint**: 契约与骨架就位,三个 story 可并行。

---

## Phase 3: User Story 1 - 直传列血缘(SELECT 直引/表达式/`*`)(Priority: P1) 🎯 MVP

**Goal**: `INSERT...SELECT` 的直引列与表达式列正确溯源,`*` 按 catalog 展开。

**Independent Test**: 给 `INSERT INTO dwd_order(id,uid,amt) SELECT id,user_id,amount*1.1 FROM ods_order` + 已知 catalog,得 3 条 `ColumnEdge`(2 DIRECT + 1 EXPRESSION,均 CONFIRMED)。

### Tests for User Story 1 ⚠️(先写,先失败)

- [ ] T007 [P] [US1] `test/.../SqlColumnLineageExtractorTest.java`:直引列→DIRECT/CONFIRMED 用例。
- [ ] T008 [P] [US1] 同测试类:表达式列(`amount*1.1`、`a+b`)→EXPRESSION、多源用例。
- [ ] T009 [P] [US1] 同测试类:`SELECT *`(catalog 已知)按列序展开为逐列直传用例。

### Implementation for User Story 1

- [ ] T010 [US1] `application/lineage/CalciteColumnLineage.java`:Parser→Validator(用 `ColumnLineageCatalog` 建 Calcite `Schema`/`Table`,展开 `*`、绑定别名)→ 转 RelNode(契约 C 主路径,research D1/D2)。
- [ ] T011 [US1] 在 `CalciteColumnLineage` 用 `RelMetadataQuery.getColumnOrigins(rel,i)` 提每个输出列的源列,产出 `ColumnEdge`(挂回规范化表/列名,C3)。
- [ ] T012 [US1] transform 分类逻辑:`isDerived`+1:1→DIRECT、标量表达式→EXPRESSION(research D4);接入 `SqlColumnLineageExtractor.extract` 主流程返回 CONFIRMED 边。
- [ ] T013 [US1] 列对去重 + 空白名丢弃(data-model 校验规则)。

**Checkpoint**: US1 可独立通过(直传/表达式/`*`)。

---

## Phase 4: User Story 2 - 穿透 JOIN/CTE/UNION/子查询/聚合(Priority: P1)

**Goal**: 复杂查询中目标列溯源到最终物理源列,聚合列标 AGGREGATE。

**Independent Test**: 含 JOIN+CTE+GROUP BY 的 SQL,目标列命中正确物理表.列;聚合列 transform=AGGREGATE。

### Tests for User Story 2 ⚠️

- [ ] T014 [P] [US2] 测试:JOIN 多表列溯源用例。
- [ ] T015 [P] [US2] 测试:`WITH` CTE 穿透 + 子查询用例。
- [ ] T016 [P] [US2] 测试:`UNION`/`INTERSECT`/`EXCEPT` 列溯源为各分支并集用例。
- [ ] T017 [P] [US2] 测试:聚合列(`SUM(amt) AS total`)→AGGREGATE 用例。

### Implementation for User Story 2

- [ ] T018 [US2] 验证/补全 `CalciteColumnLineage` 对 JOIN/CTE/UNION/子查询的覆盖(`getColumnOrigins` 多源返回正确映射);必要时处理多 origin 列。
- [ ] T019 [US2] AGGREGATE 分类:识别源自 `Aggregate` RelNode 的输出列,transform=AGGREGATE(research D4)。
- [ ] T020 [US2] 同名列跨表消歧:依赖 validator 别名/限定名绑定;无法消歧的列降级 UNVERIFIED(衔接 US3)。

**Checkpoint**: US1+US2 独立可用(覆盖真实数仓 SQL 主形态)。

---

## Phase 5: User Story 3 - 解析不了时干净降级(Priority: P1)

**Goal**: 缺元数据/`*`不可展开/DDL/动态/存储过程/异常,均按阶梯降级,**绝不阻断**。

**Independent Test**: 一组无法精确解析的 SQL,`extract` 返回明确降级结果(空/表级/UNVERIFIED),0 抛异常。

### Tests for User Story 3 ⚠️

- [ ] T021 [P] [US3] 测试:catalog 缺源表 → 列级降级 UNVERIFIED/退表级,不抛。
- [ ] T022 [P] [US3] 测试:DDL/动态 SQL/存储过程 → `parsed=false, edges=[]`,退表级(对齐 `SqlTableExtractor`)。
- [ ] T023 [P] [US3] 测试:窗口函数/UDF 列 `getColumnOrigins` 返回空 → 该列 UNVERIFIED、其余正常。
- [ ] T024 [P] [US3] 测试:海量异常输入 fuzz —— 断言任何输入都不抛(契约 C1)。

### Implementation for User Story 3

- [ ] T025 [US3] `application/lineage/ColumnLineageDegrade.java`:AST 启发式按名/别名匹配(缺元数据/`*`不可展开时),产出 UNVERIFIED 边。
- [ ] T026 [US3] 退表级:无法校验/转换时委托 `SqlTableExtractor`,列级留空,`ColumnLineageResult.parsed=false`(research D3 第 3/4 级)。
- [ ] T027 [US3] 把降级阶梯接入 `extract` 主流程,统一 `degraded` 标志与日志(`log.debug`,不上抛)。

**Checkpoint**: 三 story 全独立可用,韧性达标。

---

## Phase 6: 列级 A×B 交叉校验(承 spec FR-006)

- [ ] T028 实现列级交叉校验:Agent `.task.yaml` 列级声明 × 解析结果 → source/confidence;冲突标 **CONFLICT**(契约 C5);定义声明的入参形态(与 018 写入对接)。
- [ ] T029 [P] 测试:声明与解析一致→CONFIRMED、仅解析→CONFIRMED、冲突→CONFLICT 用例。

---

## Phase 7: Polish & Cross-Cutting

- [ ] T030 [P] 跑 `quickstart.md` 验证(`./mvnw -q -pl dataweave-master -am test -Dtest=SqlColumnLineageExtractorTest`)。
- [ ] T031 [P] 代码注释/javadoc 与契约对齐;`docs` 无需改(契约在 specs)。
- [ ] T032 编译/测试门:`cd backend && ./mvnw -q -pl dataweave-master compile` + 全列级测试绿。

---

## Dependencies & Execution Order

- **Setup(P1)** → **Foundational(P2,契约+骨架,阻塞)** → US1/US2/US3(均 P1,Foundational 后可并行)→ Phase 6 交叉校验(依赖 US1-3 的边产出)→ Polish。
- US1 是 MVP(直传/表达式/`*`);US2 扩到复杂查询;US3 补韧性。三者共享 `CalciteColumnLineage`,故 US2/US3 在 US1 的 Calcite 主路径落地后增量最顺(实操上 US1→US2→US3 串行更稳,虽逻辑上可并行)。

### Within Each Story
- 测试先写并先失败 → 实现 → 通过。
- Commit after each task or logical group。

## Implementation Strategy

- **MVP**: Setup + Foundational + US1(直传/表达式/`*`)→ 独立验证。
- **增量**: +US2(复杂查询穿透)→ +US3(降级韧性)→ +交叉校验 → Polish。
- 全程纯单测,无需 neo4j;与 018(catalog 数据源/写入)、020(展示)经契约对桩,落地后接缝闭环。

## Notes
- [P] = 不同文件、无依赖,可并行。
- 韧性(C1 绝不抛)是贯穿所有 story 的硬不变量,US3 专门加固 + T024 fuzz 兜底。
- 契约改动(ColumnEdge/Catalog)MUST 同步通知 018/020。
