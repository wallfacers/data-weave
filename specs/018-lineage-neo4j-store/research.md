# Phase 0 Research: 血缘图底座 —— neo4j 存储与写入链路

**Feature**: 018-lineage-neo4j-store | **Date**: 2026-06-30

本特性的核心技术取舍多数已在共享设计文档 [docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md) §3「已锁定决策」中由 brainstorming 评审锁定。本研究将其中与「图底座 + 写入链路」直接相关的决策落到实现层，并解决 SB4 接入、去重身份、事务边界、schema 收口的具体形式。**无 NEEDS CLARIFICATION**。

---

## D1. neo4j 访问：`neo4j-java-driver` vs Spring Data Neo4j（SDN）

**Decision**: 用官方 `neo4j-java-driver`（`org.neo4j.driver`），自建 `Driver` `@Bean`；**不引入 Spring Data Neo4j**。

**Rationale**:
- Spring Boot 4 / Framework 7 无 neo4j driver 自动配置（与 `WebClient.Builder` 同类坑：CLAUDE.md 明列 SB4 须自建 `@Bean`）。引入 SDN 会带来一套额外的 starter/自动配置/对象映射层，与本项目「Spring Data JDBC + 手写 Cypher」的轻量取向相悖。
- 写入链路是少量、确定的 Cypher（replace-per-task 的 DELETE/MERGE/CREATE），手写 Cypher + driver `Session`/`Transaction` 控制最直接，事务边界清晰（单事务原子）。
- 对标现有 `dataweave-api/.../infrastructure/WebClientConfig.java` 的自建 Bean 模式，团队已有同型实践。

**Alternatives considered**:
- *Spring Data Neo4j*：对象-图映射方便，但 SB4 自动配置缺位、引入 repository 抽象与本项目手写 Cypher 取向不一致；replace-per-task 的「先删后建」用 OGM 反而绕。拒。
- *Cypher over HTTP API*：免 driver 依赖，但失去连接池/事务/类型安全，重复造轮子。拒。

---

## D2. 测试：Testcontainers neo4j vs 嵌入式（embedded）

**Decision**: Testcontainers neo4j（真容器，`org.testcontainers:neo4j`）做集成测试；不使用嵌入式 neo4j。

**Rationale**:
- 设计 §3 已锁「Testcontainers neo4j（真容器）」。真容器与生产 neo4j 行为一致（Cypher 方言、约束语义、事务隔离），避免 embedded 与真库的语义漂移。
- 与现有后端测试隔离不变量兼容：每个 IT 类用独立容器/独立库实例，`@DirtiesContext` 防 Spring 上下文污染，redis health off，H2 唯一库——neo4j 容器只在血缘 IT 启动，不进普通单测。
- 嵌入式 neo4j（test-harness）在新版授权/打包上更重，且与生产版本对齐成本高。

**Alternatives considered**:
- *Embedded Neo4j（neo4j-harness）*：启动快但需对齐版本与授权，且 community 嵌入式与 server 行为可能有差异。拒。
- *纯 mock Driver*：无法验证真 Cypher / 约束去重语义（去重正是本特性的地基），拒——韧性/去重必须打到真库。

**实现注意**：IT 通过 `@DynamicPropertySource` 把 Testcontainers 暴露的 `boltUrl`/auth 注入 `Neo4jConfig` 读取的配置键（如 `lineage.neo4j.uri/username/password`），保证被测的真实 `@Bean` 连到容器。

---

## D3. 数据源去重身份键（`:Datasource` 唯一键）

**Decision**: `:Datasource` 身份 = 规范化 `(tenantId, ip, port, database)`。规范化规则：
- `ip` 取连接坐标的 host（小写、trim）；`port` 缺省补该 `datasource_type` 的 `default_port`；`database` 小写 trim。
- 凭据（username/password）**不进**身份键 —— 同库不同凭据归一到同一节点（FR-004 / SC-002）。
- 缺连接坐标（如本地文件源、无 ip/port/database）→ 确定性降级身份 `datasource:<normalized-name>`（或 `datasource:<datasourceId>`），仍唯一不重复。

**Rationale**:
- 设计 §4「数据源去重（MVP 必做）」：同一物理连接无论被多少任务/凭据引用归一同节点，否则同表挂多个「同库」节点 → 血缘断裂。
- 现 PG 实现以 `datasource_id`（数据源配置主键）作表的归属（`uk_data_table_ds_name (datasource_id, qualified_name)`），但同一物理库可能有多个 datasource 配置（不同凭据/别名）→ 会重复建库节点。图模型必须按**物理坐标**而非配置主键去重，这是本特性纠正现状的关键。
- `database` 进键：同 ip/port 不同 database 是不同物理库 → 不同节点（FR-004 验收 #2 / 设计验收）。

**身份映射来源**：`datasources` 表的 `host`/`port`/`database_name`（schema.sql 域 B），结合 `datasource_types.default_port` 补端口。`tenantId` 进键保证跨租户隔离。

**Alternatives considered**:
- *用 `datasource_id` 直接当身份*：简单但不去重（同物理库多配置 → 多节点），违背 SC-002。拒。
- *把 username 纳入身份*：会按凭据拆分同一物理库，正是要避免的断裂。拒。

---

## D4. replace-per-task 写入语义与事务边界

**Decision**: `LineageStore.recordTaskIo(taskDefId, ...)` 在**单个 neo4j 事务**内顺序执行：
1. `MATCH (t:Task {tenantId,taskDefId})-[r:READS|WRITES]->() DELETE r`，并删该 `taskDefId` 标注的 `FLOWS_TO`/`DERIVES_FROM`（边属性 `taskDefId` 过滤）。
2. `MERGE` `:Datasource`/`:Table`/`:Column`/`:Task` 节点（按各自唯一键 upsert，幂等）。
3. `CREATE` 新的 `READS`/`WRITES`/`FLOWS_TO`（及 019 提供 `ColumnEdge` 时的 `READS_COL`/`WRITES_COL`/`DERIVES_FROM`）。

整段用 driver 的 `session.executeWrite(tx -> { ... })`（managed transaction，自动重试瞬时错误），对标现 `recordDesignTimeIo` 的 `@Transactional`。

**Rationale**:
- 设计 §5「写入语义（replace-per-task）」：先 `MATCH...DELETE` 旧边 → `MERGE` 节点 → `CREATE` 新边，单事务保证原子与幂等（SC-003：重复记录边集合一致、无残留陈边）。
- 节点用 `MERGE`（共享，可能被多任务引用，不能删）；边用「先删该任务的、再建」（边属 taskDefId，是该任务私有的）。这区分是幂等正确性的关键：节点 upsert，边 replace。
- `FLOWS_TO`/`DERIVES_FROM` 必须带 `taskDefId` 属性，否则 replace 时无法精确删「本任务产生的流边」而误删他任务的。设计 §4 关系定义已含 `FLOWS_TO {taskDefId}` / `DERIVES_FROM {taskDefId,transform}`。

**并发**：同一任务并发两次 recordTaskIo → 两个 managed write tx 串行化到该任务边集合，最终一致无重复边（边的 `CREATE` 在 DELETE 之后，事务隔离保证看不到对方中间态）。Edge case「同一任务并发两次记录」由此覆盖。

**Alternatives considered**:
- *全 MERGE 边（不先删）*：无法表达「这次解析不再读某表」→ 残留陈边，违背 replace 语义。拒。
- *删整个 :Task 子图重建*：会误删共享节点与他任务的边。拒。

---

## D5. 韧性：血缘写失败绝不阻断主链路

**Decision**: `recordTaskIo` 的**调用方**（TaskService.recordLineage / ProjectSyncService.push）以 try-catch 包裹血缘写入，任何异常（解析失败 / neo4j 不可达 / driver 异常）只记可诊断日志（含 taskDefId/原因），不向上抛、不回滚主事务。neo4j 连接以**短超时 + 不阻塞**配置，避免不可达时拖垮建任务/push 延迟。

**Rationale**:
- FR-007 / SC-004 / 设计 §5「韧性不变量」：血缘是增强，失败不得阻断建任务或 push。现 `recordLineage` 已是 try-catch 吞异常模式，本特性沿用并扩展到 push 路径。
- neo4j 与元数据 PG 是两套存储，血缘写在主业务事务**之外**（主事务先提交/或血缘写在其后），neo4j 异常不污染 PG 事务。

**Alternatives considered**:
- *血缘写入纳入主事务（XA/2PC）*：跨 PG+neo4j 分布式事务复杂且与「血缘是增强」定位矛盾。拒。
- *失败即重试阻塞*：违背不阻断。本期降级记日志即可（补偿/重放是未来工作，spec Edge Cases 已注「可后续补偿」）。

---

## D6. schema 四表删除与 `schema_version` 升法（017 接触点）

**Decision**:
- `schema.sql` 删除 `data_table`/`task_table_io`/`task_run_table_io`/`metric_lineage` 的 DROP + CREATE TABLE + CREATE INDEX 三段（域 F 表级血缘段 + 域 D 的 `metric_lineage`）。
- `data.sql` 删对应 seed（域 F 血缘 seed + `metric_lineage` seed + 相关 `ALTER ... RESTART`），迁到 `Neo4jLineageSeeder`。
- **`schema_version` 递增**：现基线 `0.0.1`（017）。删表是结构变更 → 升版本。按 SemVer，删表（移除结构）取 **MINOR 升到 `0.1.0`**（发布前、向后不兼容删除，但项目仍 0.x 阶段以 MINOR 表达结构演进；最终版号以实现时项目约定为准，关键是**三处恒等**）。三处同步：① `schema_version` 单行 `INSERT` 的 version；② schema.sql 文件头 `-- Schema Version:` 注释；③ 项目发布版本（如约定与之绑定）。

**Rationale**:
- FR-009 / 设计 §5「跨库 schema 收口」 + 017 纪律「改表必升版本，库内/文件头/项目版本三处恒等」。
- 删表后这些表的 domain/repository（`DataTable`/`TaskTableIo`/`MetricLineage` 等 PG 实体与 JdbcTemplate 读写）需一并清理或改走图，否则启动期 JDBC 引用悬空。本特性的删表必须连带改造 `LineageGraphService`/`LineageService` 的 PG 读写路径（见 plan 改造清单），保证「编译过且不悬空」——**不闭环 = 未完成**。

**版本号实现注**：研究阶段不锁死具体数字（`0.1.0` 还是 `0.0.2`），交由实现按当时项目发布版本与 017 约定确定；plan 与 tasks 只强约束「必升 + 三处恒等 + 有测试校验」。

**Alternatives considered**:
- *保留 PG 表做双写过渡*：违背设计 §2「neo4j 完全替换、拆 PG 血缘表」与「greenfield 重新播种、无迁移工具」。拒。
- *不升 schema_version*：违反 017 硬纪律。拒。

---

## D7. greenfield 种子载体（Neo4jLineageSeeder）

**Decision**: 演示血缘种子从 `data.sql`（PG）迁到 `Neo4jLineageSeeder`（应用启动期幂等播种到 neo4j），规模对齐现 data.sql 域 F：1 库 / 5 表（ods_order, ods_user, dwd_order, dws_user_order, ads_gmv）/ 3 任务（9001 ODS→DWD, 9002 DWD→DWS, 9003 DWS→ADS）/ 7 条 io 边 + 1 条 metric_lineage（ATOMIC#1 → orders 表）。播种用与运行期相同的 `LineageStore` 写入路径（recordTaskIo + recordMetricLineage），保证种子与真实写入语义一致。

**Rationale**:
- FR-010：提供 greenfield 种子，对齐现演示规模，不做 PG→neo4j 迁移工具（设计 §2「greenfield 重新播种」）。
- 用同一 `LineageStore` 写入路径播种 = 顺带成为写入链路的活体冒烟，且去重/replace 语义在种子上即被行使。
- 幂等：种子用 MERGE/replace-per-task，重复启动不产生重复节点边（与去重地基一致）。

**Alternatives considered**:
- *用 Cypher .cypher 脚本文件播种*：绕过 LineageStore，种子与运行期写入语义可能漂移。拒——种子走同一接口更可靠。
- *保留 PG seed 并旁路同步到图*：违背删 PG 血缘表。拒。

---

## D8. `ColumnEdge` 契约形参的本期边界

**Decision**: 本特性**定义并暴露** `ColumnEdge` 形参（对齐设计 §6 输出契约 `record ColumnEdge(TableRef srcTable, String srcCol, TableRef dstTable, String dstCol, Transform transform, Confidence confidence)`），`recordTaskIo` 接受 `List<ColumnEdge>` 可选入参并能写入 `:Column` 节点 + `DERIVES_FROM` 边；但**列映射的产生（SQL 列解析）不在本期**——本期写入路径传入空列表或由测试构造的固定 `ColumnEdge` 验证写入正确性。019 实现 `SqlColumnLineageExtractor` 后填充该入参。

**Rationale**:
- FR-011 + 设计 §10：A 先落契约（可先出接口桩），B（019）对桩并行。契约稳定是 019/020 并行的前提。
- 本期至少能写 `:Column`/`DERIVES_FROM`，用测试构造的 ColumnEdge 验证 Cypher 正确——保证 019 接上即生效，不返工。

**Alternatives considered**:
- *本期不定 ColumnEdge，留待 019*：会让 019 同时改契约 + 改 A 的写入路径，违背「先定契约三份并行」。拒。

---

## 决策小结（供 plan/tasks 引用）

| # | 决策点 | 结论 |
|---|--------|------|
| D1 | neo4j 访问 | `neo4j-java-driver` 自建 `@Bean`，不用 SDN |
| D2 | 测试 | Testcontainers neo4j 真容器，`@DynamicPropertySource` 注入 boltUrl |
| D3 | 数据源去重身份 | `(tenantId, ip, port, database)` 规范化；凭据不进键；缺坐标降级 `datasource:<name>` |
| D4 | 写入语义/事务 | replace-per-task 单 managed write tx：删本任务边 → MERGE 节点 → CREATE 新边 |
| D5 | 韧性 | 调用方 try-catch，失败记日志不阻断主链路；短超时不阻塞 |
| D6 | schema 收口 | 删四表 + 清 PG domain/repo 悬空引用；`schema_version` 必升、三处恒等、有测试 |
| D7 | 种子 | `Neo4jLineageSeeder` 走 LineageStore 同路径，幂等，对齐 5 表/7 边/3 任务/1 指标 |
| D8 | ColumnEdge | 本期定契约形参 + 能写 `:Column`/`DERIVES_FROM`，产生由 019 负责 |
