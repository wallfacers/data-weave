# Implementation Plan: 血缘图底座 —— neo4j 存储与写入链路

**Branch**: `018-lineage-neo4j-store` | **Date**: 2026-06-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/018-lineage-neo4j-store/spec.md`；共享设计契约 [docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)（§4 图模型 / §5 写入链路 / §10 拆分 = 三份 spec 的总地基）。

## Summary

把表级 + 指标血缘从 PG 关系表（`data_table`/`task_table_io`/`task_run_table_io`/`metric_lineage`）**整体迁移到 neo4j 图数据库**，作为唯一血缘存储底座。核心交付：

1. **图底座**：docker-compose 加 neo4j 硬依赖；`neo4j-java-driver` + 自建 `@Bean`（SB4 无自动配置，对标现 `WebClientConfig`）；启动期建唯一约束/索引。
2. **写入链路**：统一 `LineageStore.recordTaskIo()` 接口，replace-per-task 语义、单 neo4j 事务（先 `MATCH...DELETE` 旧边 → `MERGE` 节点 upsert → `CREATE` 新边）；被 `createAndOnline`/`publish`/`push` 三触发点复用，**补齐 push 路径不落血缘的已知缺口**。
3. **数据源去重**：`:Datasource` 身份 = 规范化 `(tenantId, ip, port, database)`，同一物理库归一单节点；缺连接坐标用确定性降级身份。
4. **韧性不变量**：血缘是增强，解析失败 / neo4j 不可达**绝不阻断**建任务/push 主链路（`recordTaskIo` 内 try-catch 降级记日志）。
5. **指标血缘迁图** + **greenfield 种子**（对齐现 data.sql 的 5 表 / 7 边 / 3 任务 / 1 指标规模）。
6. **schema 收口**：`schema.sql` 删四张血缘表，按 017 纪律**递增 `schema_version`**（库内/文件头/项目版本三处恒等）。
7. **`ColumnEdge` 契约形参**：写入接口预留列级边入参，能写 `:Column`/`DERIVES_FROM`；列映射的**产生**由 019 负责，本期只定契约并能写入。

**范围边界**：列级 SQL 解析在 019；查询/API/前端在 020。本特性是契约的**实现 + 写入方**，向 019/020 暴露 `LineageStore` 写入接口与图模型。

## Technical Context

**Language/Version**: Java 25（本机 symlink swap 保证非交互 shell 透明用 JDK 25）。

**Primary Dependencies**: Spring Boot 4.0 / Spring Framework 7（Jackson 3，`ObjectMapper` 在 `tools.jackson.*`）；WebFlux；**`neo4j-java-driver`（org.neo4j.driver）** —— **不引入 Spring Data Neo4j**（SB4 无自动配置，自建 `@Bean`，对标现 `dataweave-api/.../infrastructure/WebClientConfig.java`）。Calcite（现 `SqlTableExtractor` 表级解析，本期保留作降级底座，列级解析在 019）。

**Storage**: **neo4j 完全替换** PG 血缘四表。`schema.sql` 删 `data_table`/`task_table_io`/`task_run_table_io`/`metric_lineage`（含其 DROP/CREATE/INDEX 与 data.sql 对应 seed）；元数据库仍 PostgreSQL（默认）/ H2（`profiles=h2`）；neo4j 作为新基础设施加入 docker-compose（与 PG/Redis/MinIO 同级）。

**Testing**: JUnit 5 + AssertJ；**Testcontainers neo4j**（真容器，不依赖常驻 neo4j）；沿用后端测试隔离不变量（H2 唯一库 / redis health off / `@DirtiesContext` / 防 seed 漂移）。

**Target Platform**: Linux server（后端 JVM）；血缘逻辑落 `dataweave-master` 模块，HTTP/接入在 `dataweave-api`。

**Project Type**: Web service（Maven 多模块后端；本特性不动 frontend —— 前端血缘视图在 020）。

**Performance Goals**: 单任务 `recordTaskIo` 写入在单事务内完成（边数量为该任务读写表/列规模，量级十至百）；neo4j 不可达时主链路 0 阻断（韧性优先于完整性）。

**Constraints**:
- DDD 依赖方向 `domain ← application ← infrastructure ← interfaces`（仅外→内）；`LineageStore` 接口置于 `master` 的 application/domain 层，neo4j driver 实现置于 infrastructure 层。
- replace-per-task 必须单事务、幂等；同任务重复记录边集合一致（无重复、无残留陈边）。
- 所有节点带 `tenantId`/`projectId`，写入/查询按其隔离（沿用 MCP 租户隔离 `TenantContext`）。
- Spring Boot 4：自建 `@Bean`（无 driver 自动配置）；Jackson 3 包路径。

**Scale/Scope**: greenfield 种子规模对齐现 data.sql 血缘域 F：`:Datasource`×1（id=1 库）、`:Table`×5（ods_order/ods_user/dwd_order/dws_user_order/ads_gmv）、`:Task`×3（9001/9002/9003）、表级 io 共 7 条（派生 `READS`/`WRITES`/`FLOWS_TO`）、`:Metric`×1（`metric_lineage` id=1 → `COMPUTED_FROM` orders 表）。无 PG→neo4j 迁移工具。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

宪法五原则逐条核对（Weft Constitution v1.2.0）：

| 原则 | 适用性 | 结论 |
|------|--------|------|
| **I. Files-First** | 间接 | 本特性是血缘存储底座，不改任务/工作流文件格式；本期不变更文件契约。PASS。 |
| **II. Server is the Source of Truth** | 适用 | 血缘随 push 在服务端落图（补缺口），仍是服务器治理真相源；neo4j 是服务端基础设施。所有写入/查询按 `(tenantId, projectId)` 隔离，越权拒绝。PASS。 |
| **III. Two-Legged Debugging** | 不适用 | 本特性不动 CLI 本地 runtime / 执行器语义。N/A。 |
| **IV. AI Lives in the Local Agent** | 间接 | 不引入服务端 AI 大脑；LLM 血缘解析是明确**未来 TODO**（设计 §9），本期不写。不损伤运行态观测/调度内核（`:TaskRun`-`SYNCED` 运行态血缘保留迁图）。PASS。 |
| **V. Reuse the Kernel** | 适用 | 复用现 `SqlTableExtractor`（表级解析、A×B 交叉校验）作降级底座；复用 `TenantContext` 租户隔离；replace-per-task 对标现 `@Transactional` 语义。血缘写入是平台动作的派生记录（随主事务），非独立 side-effect 写动作，与现 `recordDesignTimeIo` 一致不另过 PolicyEngine 闸门，无 bypass 引入。PASS。 |

**项目级质量门**（CLAUDE.md / Development Workflow）：

- **DDD 依赖方向**：`LineageStore` 接口 + 领域 record（`TableRef`/`ColumnEdge`/`TaskIo`/`DatasourceCoord`）在 application/domain 层；neo4j `Driver` 注入与 Cypher 在 infrastructure 层实现；interfaces 不直接碰 driver。✅
- **改表必升 schema_version**：删四表 → `schema_version` 从 `0.0.1` 递增（三处恒等：库内单行 / schema.sql 文件头注释 / 项目发布版本）。本特性是与 017 schema 纪律的接触点，FR-009 明列。✅
- **新功能必须有测试**：Testcontainers neo4j 集成测试覆盖写入/去重/replace 幂等/韧性降级；`schema_version` 三处恒等校验。无测试 = 未完成。✅
- **Spring Boot 4 坑**：自建 driver `@Bean`（无自动配置）、Jackson 3 包路径，已在 Technical Context 锁定。✅

**结论**：无原则违反，**Complexity Tracking 留空**。neo4j 新硬依赖是设计 §3 已锁决策（换存储底座为规模/列级铺路），非过度工程。

## Project Structure

### Documentation (this feature)

```text
specs/018-lineage-neo4j-store/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出（关键技术决策）
├── data-model.md        # Phase 1 输出（neo4j 节点/关系/约束/索引）
├── quickstart.md        # Phase 1 输出（本地起 neo4j + 跑 Testcontainers + 验证入图）
├── contracts/
│   └── lineage-store.md # LineageStore 接口 + ColumnEdge 形参契约
├── checklists/          # 既有
├── spec.md              # 已写
└── tasks.md             # Phase 2 输出（/speckit-tasks，本计划不创建）
```

### Source Code (repository root)

血缘逻辑在 `dataweave-master`，HTTP/接入在 `dataweave-api`；本特性新增 neo4j 写入底座，改造现 `LineageGraphService`/`TaskService`/`ProjectSyncService` 的血缘写入点，删 PG 血缘表 DDL/seed。

```text
backend/
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── domain/lineage/                       # 新增：图模型领域契约（无框架依赖）
│   │   ├── LineageStore.java                 # 写入接口（recordTaskIo / recordMetricLineage / seed）
│   │   ├── TableRef.java                     # 表引用（DatasourceCoord + qualifiedName + layer）
│   │   ├── ColumnEdge.java                   # 列级边形参（019 产出，本期定契约）
│   │   ├── TaskIo.java                       # 任务读写边输入（替代旧 EdgeInput）
│   │   └── DatasourceCoord.java              # 数据源去重身份（tenantId/ip/port/database + 降级）
│   ├── infrastructure/lineage/               # 新增：neo4j driver 实现
│   │   ├── Neo4jConfig.java                  # Driver @Bean（SB4 无自动配置，对标 WebClientConfig）
│   │   ├── Neo4jLineageStore.java            # LineageStore 实现（Cypher MERGE / replace-per-task / 单事务）
│   │   ├── Neo4jSchemaInitializer.java       # 启动期建 CONSTRAINT IS UNIQUE + 索引
│   │   └── Neo4jLineageSeeder.java           # greenfield 种子（对齐 data.sql 5 表/7 边规模）
│   └── application/
│       ├── LineageGraphService.java          # 改造：写入改走 LineageStore（拆 PG 写路径）
│       ├── TaskService.java                  # 改造：recordLineage → LineageStore.recordTaskIo
│       ├── LineageService.java               # 改造：指标血缘写改走图
│       └── ProjectSyncService.java           # 改造：push 路径接入 LineageStore（补缺口）
└── dataweave-master/src/test/java/com/dataweave/master/lineage/   # 新增
    ├── Neo4jLineageStoreIT.java              # Testcontainers：写入/去重/replace 幂等/韧性
    ├── PushLineageIT.java                    # push 路径落血缘
    └── SchemaVersionConsistencyTest.java     # schema_version 三处恒等

backend/dataweave-api/src/main/resources/
├── schema.sql                                # 删 data_table/task_table_io/task_run_table_io/metric_lineage；升 schema_version
└── data.sql                                  # 删对应 PG 血缘 seed（迁到 Neo4jLineageSeeder）

backend/pom.xml / backend/dataweave-master/pom.xml   # 加 neo4j-java-driver + testcontainers-neo4j
docker-compose.yml                            # 加 neo4j service（与 PG/Redis/MinIO 同级）
```

**Structure Decision**: 沿用现有 Maven 四模块 DDD 布局，血缘域全部落 `dataweave-master`。新增 `domain/lineage`（纯契约，无框架）与 `infrastructure/lineage`（neo4j driver 实现），严守依赖方向 `domain ← application ← infrastructure ← interfaces`。HTTP 接入仍在 `dataweave-api`（查询 API 重设计在 020，本期仅随删表改 schema/seed 资源与必要编译修复）。前端零改动（020 负责）。

## Complexity Tracking

> 无 Constitution 违反，本节留空。neo4j 新硬依赖为设计 §3 已锁决策，非偏离。
