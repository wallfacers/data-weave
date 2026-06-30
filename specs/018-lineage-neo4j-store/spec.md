# Feature Specification: 血缘图底座 —— neo4j 存储与写入链路

**Feature Branch**: `018-lineage-neo4j-store`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 共享设计:[docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)(本 spec 为其中「A · 图底座 + 写入链路」一份,与 019/020 并行,共享同一图模型与 `LineageStore` 契约)

> **范围边界**:本特性只负责**存储底座 + 写入链路 + 数据源去重**。列级 SQL 解析在 `019-lineage-column-lineage`;查询/API/前端在 `020-lineage-graph-api`。本特性提供 `LineageStore` 写入接口与图模型,作为 019/020 的依赖底座。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 建任务即把血缘落入 neo4j(Priority: P1)

开发者在平台建一个 SQL 任务(`createAndOnline`)。系统解析其读写表,把「数据源→表→(列由 019 提供)」结构与「任务读写表」血缘**写入 neo4j 图**,而非 PG 关系表。

**Why this priority**: 这是换底座的核心——没有它,整个 neo4j 血缘无从谈起;它是 019/020 的前置。

**Independent Test**: 建一个 `INSERT INTO dwd_order SELECT * FROM ods_order` 任务,查 neo4j 应有 `:Table {ods_order}`、`:Table {dwd_order}`、`(:Task)-[:READS]->(ods_order)`、`(:Task)-[:WRITES]->(dwd_order)`、`(ods_order)-[:FLOWS_TO]->(dwd_order)`。

**Acceptance Scenarios**:

1. **Given** 一个引用两张表的 SQL 任务, **When** `createAndOnline`, **Then** neo4j 出现对应 `:Table` 节点与 `:Task` 读写边、表级 `FLOWS_TO` 流边,且 PG 不再写 data_table/task_table_io。
2. **Given** 同一任务被改写后重新上线, **When** 再次记录血缘, **Then** 该任务旧的读写/流边被**整体替换**(replace-per-task),无残留陈边。

### User Story 2 - CLI push 也建血缘(补齐缺口)(Priority: P1)

开发者本地 `dw push` 同步任务定义到服务端。每个新增/修改的任务都触发血缘写入,使「CLI 创作的任务」在图中血缘完整。

**Why this priority**: 现状 push 路径不落血缘是已知缺口;企业级闭环要求"任务即代码"的血缘也闭环。与 US1 同为 P1,因为 CLI 是主创作路径。

**Independent Test**: 对一个含 SQL 任务的项目 `dw push`,查 neo4j 应出现该任务的血缘边,与经 `createAndOnline` 建的语义一致。

**Acceptance Scenarios**:

1. **Given** 本地项目含一个新 SQL 任务, **When** `dw push`, **Then** neo4j 出现该任务血缘。
2. **Given** push 含对已有任务的修改, **When** push, **Then** 该任务血缘按 replace-per-task 更新。

### User Story 3 - 同一物理库去重为单一节点(Priority: P1)

同一个数据库(`ip:port/database` 相同)被多个任务、甚至不同凭据引用时,图中只有**一个** `:Datasource` 节点,其下挂唯一的表集合。

**Why this priority**: 节点身份正确性的地基。不去重则同一张表挂在多个"同库"节点下,血缘断裂、影响面分析失真。

**Independent Test**: 用两个连接配置(同 ip/port/database、不同用户名)各建一个引用同表的任务,查 neo4j `:Datasource` 节点应只有一个,目标表只有一份。

**Acceptance Scenarios**:

1. **Given** 两个任务引用 `10.0.0.1:5432/warehouse` 上的同一张表(凭据不同), **When** 各自建血缘, **Then** 图中 `:Datasource` 唯一、该 `:Table` 唯一。
2. **Given** 两个任务引用同 ip/port 但不同 database, **Then** 产生两个不同的 `:Datasource` 节点。

### Edge Cases

- SQL 无法解析(DDL/动态 SQL/存储过程)→ 血缘记录降级,**不阻断**建任务/push 主链路。
- neo4j 不可达 → 建任务/push 主链路仍成功(血缘是增强),失败记日志;血缘写入可后续补偿(本期至少不阻断)。
- 同一任务并发两次记录血缘 → 单事务 + replace-per-task 保证最终一致,无重复边。
- 数据源缺 ip/port/database(如本地文件源)→ 用确定性降级身份(如 `datasource:<name>`),仍唯一不重复。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 把表级血缘(数据源、表、任务读写、表级流)存入 neo4j 图,作为唯一血缘存储;PG 不再写 data_table/task_table_io/task_run_table_io/metric_lineage。
- **FR-002**: 系统 MUST 提供统一写入接口 `LineageStore.recordTaskIo()`,被 `createAndOnline`、`publish`、`push` 三个触发点复用;`push` 路径 MUST 接入该接口(补齐现缺口)。
- **FR-003**: 写入 MUST 为 replace-per-task 语义且在**单个 neo4j 事务**内完成(先删该任务旧边,再 upsert 节点,再写新边),保证原子与幂等。
- **FR-004**: `:Datasource` 节点身份 MUST 按规范化的 `(tenantId, ip, port, database)` 去重;同一物理库归一到单一节点。缺连接坐标时用确定性降级身份。
- **FR-005**: 所有节点 MUST 带 `tenantId`/`projectId`,所有写入/查询 MUST 按其隔离(沿用 MCP 租户隔离)。
- **FR-006**: 系统 MUST 为每个节点唯一键建 neo4j 约束(`IS UNIQUE`),并为 `tenantId/projectId` 建索引。
- **FR-007**: 血缘写入失败(解析失败/neo4j 不可达/异常)MUST NOT 阻断建任务或 push 主链路;MUST 记录可诊断日志。
- **FR-008**: 系统 MUST 迁移 `metric_lineage` 语义到图(`:Metric`-[:COMPUTED_FROM]->`:Table`),指标节点身份镜像 PG `(tenantId, metricType, id)`。
- **FR-009**: 系统 MUST 在 `schema.sql` 删除 data_table/task_table_io/task_run_table_io/metric_lineage 四表,并按项目约定**递增 `schema_version`**(改表必升版本,三处恒等)。
- **FR-010**: 系统 MUST 提供 greenfield 种子,在 neo4j 初始化演示血缘(对齐现 data.sql 的 5 表/7 边规模),不做 PG→neo4j 迁移工具。
- **FR-011**: `LineageStore` 写入契约 MUST 接受列级边(`ColumnEdge`,由 019 产出)作为可选入参——本期至少定义契约形参并能写入 `:Column`/`DERIVES_FROM`,列映射的产生由 019 负责。
- **FR-012**: neo4j 访问 MUST 用 `neo4j-java-driver` + 自建 `@Bean`(Spring Boot 4 无自动配置),不引入 Spring Data Neo4j。

### Key Entities *(include if feature involves data)*

- **:Datasource**:物理数据库/源;身份 `(tenantId, ip, port, database)`,去重。
- **:Table**:数据表;`(datasourceId, qualifiedName)` 唯一;属性 `layer`。
- **:Column**:表的列;`(tableId, name)` 唯一(节点由本特性建,列血缘流由 019 写)。
- **:Metric**:指标;镜像 PG。
- **:Task**:任务镜像;`(tenantId, taskDefId)`,任务主体仍在 PG。
- **关系**:`HAS_TABLE`/`HAS_COLUMN`(结构)、`READS`/`WRITES`(设计态)、`FLOWS_TO`(表级流)、`COMPUTED_FROM`(指标)、`SYNCED`(运行态)。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 建任务/`dw push` 后,对应库/表/任务读写/表级流血缘 100% 落入 neo4j;PG 血缘四表不再被写入。
- **SC-002**: 同一 `(ip, port, database)` 无论被多少任务/凭据引用,图中 `:Datasource` 节点数恒为 1。
- **SC-003**: 同一任务重复记录血缘后,其血缘边集合与单次记录完全一致(无重复、无残留陈边)。
- **SC-004**: neo4j 不可达时,建任务与 `dw push` 成功率不受影响(血缘降级,主链路 100% 不阻断)。
- **SC-005**: 后端血缘写入与去重测试经 Testcontainers neo4j 全绿;`schema_version` 库内/文件头/项目版本三处恒等。

## Assumptions

- 共享图模型与 `LineageStore`/`ColumnEdge` 契约以 [设计文档](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md) §4/§6 为准;本特性是契约的"实现 + 写入"方,019/020 是消费方。
- 项目处于发布前,存量血缘仅 seed/演示数据,故 greenfield 重新播种,无需迁移工具。
- neo4j 作为新基础设施加入 docker-compose,与 PG/Redis 同级;测试用 Testcontainers,不依赖常驻 neo4j。
- 列级血缘的"产生"(SQL 列映射解析)不在本特性,由 `019-lineage-column-lineage` 提供;本特性只负责把 `ColumnEdge` 写入图。
- 查询/API/前端不在本特性,由 `020-lineage-graph-api` 负责;本特性只保证数据正确入图。
