# Phase 1 Data Model: neo4j 血缘图模型

**Feature**: 018-lineage-neo4j-store | **Date**: 2026-06-30

严格遵循共享设计 [§4 图数据模型](../../docs/superpowers/specs/2026-06-30-lineage-neo4j-design.md)。**所有节点带 `tenantId`/`projectId`**，所有写入/查询按其 scope 隔离（沿用 MCP 租户隔离）。

本特性是图模型的**写入方**：负责建节点/结构边/设计态读写边/表级流边/指标边，并预留列级边写入；查询路径在 020。

---

## 1. 节点标签（Node Labels）

| 标签 | 关键属性 | 唯一键（身份） | 来源 | 本期归属 |
|------|---------|--------------|------|---------|
| `:Datasource` | `name, type, ip, port, database` | `(tenantId, ip, port, database)` **去重** | 数据源（库） | **本期建** |
| `:Table` | `qualifiedName, layer` | `(datasourceId, qualifiedName)` | 现 `data_table` → 迁入 | **本期建** |
| `:Column` | `name, dataType, ordinal` | `(tableId, name)` | 新增（列级） | **本期建节点**；列血缘流由 019 写 |
| `:Metric` | `metricType(ATOMIC/DERIVED), name` | `(tenantId, metricType, id)` | 现 `metric_lineage` → 迁入 | **本期建** |
| `:Task` | `name, versionNo` | `(tenantId, taskDefId)` | 镜像 `task_def`（主体留 PG） | **本期建** |
| `:TaskRun` | `bizDate` | `instanceId` | 运行态（可选） | 结构定义本期建；运行态写入随 `SYNCED` |

**身份策略**（设计 §4）：
- `:Table`/`:Column` 只活在 neo4j（PG 的 `data_table`/`task_table_io` 拆除）；其图内 id 由应用层生成（UUID 或 app-long 稳定 id），不再依赖 PG 自增主键。
- `:Task`/`:Metric` 镜像 PG 主键（`taskDefId` / metric `id`），便于与 PG 任务主体/指标定义关联。
- `:Datasource` 身份按**物理坐标**去重（见下），而非 `datasource` 配置主键。

**数据源去重身份（FR-004，MVP 必做）**：
- 唯一键 = 规范化 `(tenantId, ip, port, database)`：`ip` 取 host（小写 trim）；`port` 缺省补 `datasource_type.default_port`；`database` 小写 trim。
- **凭据（username/password）不进键** —— 同物理库不同凭据归一到同一 `:Datasource` 节点（SC-002）。
- 同 `ip/port` 不同 `database` → 不同 `:Datasource` 节点。
- 缺连接坐标（本地文件源等）→ 确定性降级身份 `datasource:<normalized-name>`（或 `datasource:<datasourceId>`），仍唯一。
- `datasourceId`（用于 `:Table` 唯一键）= 该去重后 `:Datasource` 节点的稳定图内 id。

---

## 2. 关系类型（Relationships）

```text
结构层级:  (:Datasource)-[:HAS_TABLE]->(:Table)-[:HAS_COLUMN]->(:Column)
设计态读写: (:Task)-[:READS  {source,confidence,version}]->(:Table)
           (:Task)-[:WRITES {source,confidence,version}]->(:Table)
           (:Task)-[:READS_COL  ]->(:Column)        // 019 填充
           (:Task)-[:WRITES_COL ]->(:Column)        // 019 填充
血缘流:     (:Table)-[:FLOWS_TO     {taskDefId}]->(:Table)            // 表级，本期写
           (:Column)-[:DERIVES_FROM {taskDefId,transform}]->(:Column)// 列级，本期能写、019 产生
指标:       (:Metric)-[:COMPUTED_FROM]->(:Table|:Column)
运行态:     (:TaskRun)-[:SYNCED {rowCount,bytes,bizDate}]->(:Table)
```

**边属性语义**：
- `READS`/`WRITES`：`source ∈ {AGENT, SQL_PARSED, FORM}`、`confidence ∈ {CONFIRMED, UNVERIFIED, CONFLICT}`（沿用现 A×B 交叉校验语义）、`version`=任务版本号。
- `FLOWS_TO {taskDefId}`：表→表流边，**必带 `taskDefId`** —— replace-per-task 时按此精确删除本任务旧流边（设计 §4/§5）。语义上由该任务的 READ 表 × WRITE 表派生（对标现 `loadFlowEdges`）。
- `DERIVES_FROM {taskDefId, transform}`：列→列流边，`transform ∈ {DIRECT, EXPRESSION, AGGREGATE}`；本期写入路径能落，列映射由 019 提供。
- `COMPUTED_FROM`：指标→表/列，镜像现 `metric_lineage`（`metric_type`/`metric_id` → `downstream_type`/`downstream_id`）。
- `SYNCED {rowCount, bytes, bizDate}`：运行态同步行数/字节（对标现 `task_run_table_io`），供「今日同步」运行态聚合。

**replace-per-task 边归属规则**（写入正确性关键）：
- **节点**（`:Datasource`/`:Table`/`:Column`/`:Task`/`:Metric`）共享、跨任务，写入用 `MERGE`（upsert），**不随单任务删除**。
- **边**（`READS`/`WRITES`/`READS_COL`/`WRITES_COL`/`FLOWS_TO`/`DERIVES_FROM`）按 `taskDefId` 私有，replace 时**先删本任务的、再 CREATE 新的**。

---

## 3. 约束与索引（Constraints / Indexes）

启动期由 `Neo4jSchemaInitializer` 幂等创建（`IF NOT EXISTS`）。对每个唯一键建 `CONSTRAINT ... IS UNIQUE`（FR-006），`tenantId/projectId` 建索引。

**唯一约束**（neo4j 5 node key / uniqueness 语法，单属性身份用合成 key 属性或 node key）：

```cypher
// Datasource：去重身份。用合成属性 dsKey = 规范化(tenantId|ip|port|database) 承载唯一性
CREATE CONSTRAINT datasource_key IF NOT EXISTS
  FOR (d:Datasource) REQUIRE d.dsKey IS UNIQUE;

// Table：(datasourceId, qualifiedName)。合成 tableKey = datasourceId|qualifiedName
CREATE CONSTRAINT table_key IF NOT EXISTS
  FOR (t:Table) REQUIRE t.tableKey IS UNIQUE;

// Column：(tableId, name)。合成 columnKey = tableId|name
CREATE CONSTRAINT column_key IF NOT EXISTS
  FOR (c:Column) REQUIRE c.columnKey IS UNIQUE;

// Metric：(tenantId, metricType, id)。合成 metricKey
CREATE CONSTRAINT metric_key IF NOT EXISTS
  FOR (m:Metric) REQUIRE m.metricKey IS UNIQUE;

// Task：(tenantId, taskDefId)。合成 taskKey
CREATE CONSTRAINT task_key IF NOT EXISTS
  FOR (n:Task) REQUIRE n.taskKey IS UNIQUE;

// TaskRun：instanceId
CREATE CONSTRAINT taskrun_key IF NOT EXISTS
  FOR (r:TaskRun) REQUIRE r.instanceId IS UNIQUE;
```

> 合成 key 属性（`dsKey`/`tableKey`/...）由应用层在写入时按规范化规则拼装，作为 `MERGE` 的匹配键 —— 既满足单属性 `IS UNIQUE` 约束，又承载多列复合身份与去重规范化。各节点同时保留可读的拆分属性（`tenantId`/`ip`/`port`/`database` 等）供查询/展示。

**索引**（FR-006，按租户/项目隔离查询加速）：

```cypher
CREATE INDEX ds_scope    IF NOT EXISTS FOR (d:Datasource) ON (d.tenantId, d.projectId);
CREATE INDEX table_scope IF NOT EXISTS FOR (t:Table)      ON (t.tenantId, t.projectId);
CREATE INDEX col_scope   IF NOT EXISTS FOR (c:Column)     ON (c.tenantId, c.projectId);
CREATE INDEX task_scope  IF NOT EXISTS FOR (n:Task)       ON (n.tenantId, n.projectId);
CREATE INDEX metric_scope IF NOT EXISTS FOR (m:Metric)    ON (m.tenantId, m.projectId);
```

---

## 4. 与现 PG 模型的映射（迁移对照，无迁移工具，仅语义对照）

| 现 PG 表 | 图目标 | 说明 |
|----------|--------|------|
| `data_table (datasource_id, qualified_name, layer)` | `:Table` + `(:Datasource)-[:HAS_TABLE]->` | `datasource_id` 经物理坐标去重映射到 `:Datasource` 图 id |
| `task_table_io (task_def_id, table_id, direction, source, confidence)` | `(:Task)-[:READS\|WRITES {source,confidence}]->(:Table)` | direction → READS/WRITES |
| `loadFlowEdges`（READ×WRITE 派生）| `(:Table)-[:FLOWS_TO {taskDefId}]->(:Table)` | 派生流边显式落图（带 taskDefId） |
| `task_run_table_io (row_count, bytes, biz_date)` | `(:TaskRun)-[:SYNCED {rowCount,bytes,bizDate}]->(:Table)` | 运行态 |
| `metric_lineage (metric_type, metric_id, downstream_type, downstream_id)` | `(:Metric)-[:COMPUTED_FROM]->(:Table\|:Column)` | 指标血缘 |
| （新增）列 | `:Column` + `HAS_COLUMN` + `DERIVES_FROM` | 节点本期建；列流 019 写 |

**这些 PG 表与其 domain/repository 在本特性删除**（`DataTable`/`TaskTableIo`/`TaskRunTableIo`/`MetricLineage` 及对应 Repository / JdbcTemplate 读写），`schema.sql`/`data.sql` 同步删 DDL+seed，`schema_version` 递增（FR-009，三处恒等）。

---

## 5. greenfield 种子数据集（对齐现规模，FR-010）

由 `Neo4jLineageSeeder` 经 `LineageStore` 写入路径幂等播种（tenantId=1, projectId=1）：

- `:Datasource` ×1：库 id=1（host/port/database 取 data.sql datasources#1 坐标）。
- `:Table` ×5：`ods_order`(ODS)、`ods_user`(ODS)、`dwd_order`(DWD)、`dws_user_order`(DWS)、`ads_gmv`(ADS)。
- `:Task` ×3：9001「订单明细加工 ODS→DWD」、9002「用户订单聚合 DWD→DWS」、9003「GMV 汇总 DWS→ADS」。
- 设计态边（对齐现 7 条 `task_table_io`）+ 派生 `FLOWS_TO`：
  - 9001：READS ods_order，WRITES dwd_order → FLOWS_TO ods_order→dwd_order
  - 9002：READS ods_user、READS dwd_order，WRITES dws_user_order → FLOWS_TO {ods_user,dwd_order}→dws_user_order
  - 9003：READS dws_user_order，WRITES ads_gmv → FLOWS_TO dws_user_order→ads_gmv
- `:Metric` ×1：ATOMIC#1 → `COMPUTED_FROM` → `orders` 表（对齐现 metric_lineage#1）。

种子用 MERGE/replace-per-task，重复启动不产生重复节点/边（去重地基的活体冒烟）。
