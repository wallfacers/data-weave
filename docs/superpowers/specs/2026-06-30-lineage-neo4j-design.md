# 设计:企业级血缘迁移到 neo4j 图数据库

**日期**:2026-06-30
**状态**:已通过 brainstorming 评审,作为 3 份 speckit spec(018/019/020)的共享契约
**作者**:Claude(总设计) + 2 个 Agent 员工(分工实现)

---

## 1. 背景与动机

现有血缘(lineage)是 JDBC 实现的「表=节点·任务=边」二部图(`LineageGraphService`),上下游/邻域查询是 **Java 内存 BFS**,SQL 解析(`SqlTableExtractor`,Calcite)**仅表级**,且 **push 路径不落血缘**(已知缺口)。

本特性把血缘**整体迁移到 neo4j 图数据库**,并升级为**企业级多粒度血缘**:维护 **数据库/数据源 → 表 → 字段(列)** 的结构层级 + **指标** + 它们之间的**血缘关系**。换底座的核心收益:Cypher 变长路径(`[:FLOWS_TO|DERIVES_FROM*]`)原生取代内存 BFS,支撑任意深度的影响面分析与列级血缘。

## 2. 目标与非目标

**本期目标(MVP)**:
- neo4j **完全替换** JDBC 血缘存储(拆除 PG 血缘表),docker-compose 加 neo4j 硬依赖。
- 节点:`:Datasource` / `:Table` / `:Column` / `:Metric` / `:Task`(镜像)/ `:TaskRun`。
- **表级 + 列级**血缘流;**指标血缘**一并迁入。
- **push 路径补齐血缘**(企业级闭环)。
- **数据源去重**:同 `(ip, port, database)` 归一到同一 `:Datasource` 节点。
- API/前端**可大改**为多粒度(库→表→列下钻 + 影响面)。
- 测试用 **Testcontainers neo4j**。
- 存量数据 **greenfield 重新播种**(不做迁移工具)。

**非目标 / 未来工作(本期不实现,文档化在案,见 §8)**:
- LLM 血缘解析(SHELL/Python 等脚本内直连数据库的复杂类型)。
- 专做血缘解析任务的 LLM 微调。

## 3. 已锁定决策(brainstorming 澄清结论)

| 决策点 | 结论 |
|--------|------|
| 核心驱动 | 换存储底座(neo4j),为规模/未来铺路,能力先对齐再增强 |
| 存储关系 | neo4j **完全替换** JDBC,拆 PG 血缘表,硬依赖 |
| 迁移范围 | 表级 + 指标血缘**都进** neo4j;再加**列级** |
| 测试 | Testcontainers neo4j(真容器) |
| 存量数据 | Greenfield 重新播种 |
| 分期 | **一期全做**(含列级) |
| `:Task` | 镜像(id+名进图,任务主体留 PG) |
| push 血缘 | 本期**补上** |
| 数据源去重 | **本期 MVP 必做**(节点身份地基) |
| LLM 解析/微调 | 文档化**未来 TODO**,本期不写代码 |

## 4. 图数据模型(§1 · 共享地基)

所有节点带 `tenantId`/`projectId`,所有查询按其 scope(沿用 MCP 租户隔离)。

**节点标签**:

| 标签 | 关键属性 | 唯一键(身份) | 来源 |
|------|---------|--------------|------|
| `:Datasource` | `name, type, ip, port, database` | `(tenantId, ip, port, database)` **去重** | 数据源(库) |
| `:Table` | `qualifiedName, layer` | `(datasourceId, qualifiedName)` | 现 data_table → 迁入 |
| `:Column` | `name, dataType, ordinal` | `(tableId, name)` | **新增**(列级) |
| `:Metric` | `metricType(ATOMIC/DERIVED), name` | `(tenantId, metricType, id)` | 现 metric_lineage → 迁入 |
| `:Task` | `name, versionNo` | `(tenantId, taskDefId)` | **镜像** task_def |
| `:TaskRun` | `bizDate` | `instanceId` | 运行态(可选) |

**关系**:
```
结构层级:  (:Datasource)-[:HAS_TABLE]->(:Table)-[:HAS_COLUMN]->(:Column)
设计态读写: (:Task)-[:READS|WRITES {source,confidence,version}]->(:Table)
           (:Task)-[:READS_COL|WRITES_COL]->(:Column)
血缘流:     (:Table)-[:FLOWS_TO {taskDefId}]->(:Table)                  // 表级
           (:Column)-[:DERIVES_FROM {taskDefId,transform}]->(:Column)  // 列级
指标:       (:Metric)-[:COMPUTED_FROM]->(:Table|:Column)
运行态:     (:TaskRun)-[:SYNCED {rowCount,bytes,bizDate}]->(:Table)
```

**约束/索引**:对每个唯一键建 `CONSTRAINT ... IS UNIQUE`;`tenantId/projectId` 建索引。

**身份策略**:`:Table`/`:Column` 只活在 neo4j(PG 的 data_table/task_table_io 拆除),id 由应用层生成(UUID 或 app-long);`:Task`/`:Metric` 镜像 PG 主键。API 的 `/tables/{id}` 改用图内稳定 id。

**数据源去重(MVP 必做)**:`:Datasource` 身份 = 规范化的 `(tenantId, ip, port, database)`。同一物理连接无论被多少任务/凭据引用,归一到同一节点,不重复建点——否则同一张表会挂在多个"同库"节点下,血缘断裂。

## 5. 写入链路(§2)

**三个写入触发点**统一收敛到 `LineageStore.recordTaskIo()`:

| 触发点 | 现状 | 本期 |
|--------|------|------|
| `createAndOnline` | ✅ 写表级 | 扩到列级 + 写进图 |
| `publish`(DRAFT→ONLINE) | 沿用设计态 | 不变 |
| **`push`(CLI 同步)** | ❌ 不落血缘 | **补上**——每个增/改任务触发 `recordTaskIo` |

**A×B 交叉校验(升级到列级)**:AGENT 声明 × 解析结果,产出 `source(AGENT/SQL_PARSED) + confidence(CONFIRMED/UNVERIFIED/CONFLICT)`。列级走同一套:Agent 可在 `.task.yaml` 选择性声明列级 I/O,与解析的列映射交叉校验;不声明则纯靠解析。

**写入语义(replace-per-task)**:先 `MATCH...DELETE` 清该 `taskDefId` 的旧 READS/WRITES/FLOWS_TO/DERIVES_FROM,再 `MERGE` 节点(按唯一键 upsert)+ `CREATE` 新边,整段在**单个 neo4j 事务**内(对标现 `@Transactional`)。

**韧性不变量**:血缘是**增强**,解析/列抽取/neo4j 写失败**绝不阻断**建任务/push 主链路——`recordTaskIo` 内部 try-catch,降级为表级或 UNVERIFIED,记日志不抛。

**跨库 schema 收口**:`schema.sql` 里 `data_table`/`task_table_io`/`task_run_table_io`/`metric_lineage` **删除**,并按 017 约定**升 `schema_version`**(改表必升版本)。这是本特性与 017 schema 纪律的接触点。

## 6. 列级血缘解析(§3 · 技术核心,Claude 亲自实现)

**目标**:从一条 SQL 推出 `目标列 ← {源列...}`(喂给 `DERIVES_FROM`),支持 JOIN/子查询/CTE/UNION/表达式/`SELECT *`。

**Calcite 三段式**:
1. **Parser**:SQL → `SqlNode` AST(沿用现方言配置)。
2. **Validator + catalog**:用 neo4j 已注册的 `:Table`/`:Column` 元数据构造 Calcite `Schema`,validate → 解析列引用、展开 `*`、绑定别名。
3. **RelNode + `RelMetadataQuery.getColumnOrigins(rel, i)`**:转关系代数后,Calcite **原生**给出每个输出列的源列集合(穿透 join/CTE/union/表达式)。

**降级阶梯(韧性优先,绝不阻断主链路)**:
- 有源列元数据 → Calcite 全路径,精确列血缘(CONFIRMED)。
- 缺部分列 / `*` 无法展开 → AST 启发式按名/别名匹配(UNVERIFIED)。
- 完全解析不了(DDL/动态 SQL/存储过程)→ 退**仅表级**(现有行为),列级留空。
- 任何异常 try-catch → 退表级 + 记日志。

**输出契约(给写入链路的稳定接口)**:
```
record ColumnEdge(TableRef srcTable, String srcCol,
                  TableRef dstTable, String dstCol,
                  Transform transform,   // DIRECT/EXPRESSION/AGGREGATE
                  Confidence confidence)
```

**已识别风险**:① catalog 的"鸡生蛋"——源列元数据缺失即降级,靠目标 `INSERT (col...)`/下游声明逐步补全;② `getColumnOrigins` 对窗口函数/UDF 返回空 → 降级;③ 多方言列大小写规范化要和表级一致。

**与现有关系**:`SqlTableExtractor`(表级)**保留**作为降级底座与交叉校验;新 `SqlColumnLineageExtractor` 是其上的列级增强,二者输出在写入链路汇合。

## 7. 查询 + API + 前端(§4)

Cypher 变长路径取代 Java 内存 BFS。`/api/lineage/*` 重设计为**多粒度**:
- `GET /datasources`、`tables/{id}/columns` —— 结构下钻(库→表→列)
- `tables/{id}/{upstream|downstream}?depth=&granularity=table|column` —— 变长路径血缘
- `columns/{id}/{upstream|downstream}` —— **列级流**
- `impact/{nodeId}` —— 影响面(全下游可达,`[:FLOWS_TO|DERIVES_FROM*]`)
- `metrics/{id}/lineage` —— 指标→表/列;`sync-summary` 运行态保留

前端:企业级血缘视图,画布支持 库/表/列 三级展开折叠 + 影响面高亮 + 指标叠加。

## 8. 错误处理 + 测试(§5)

- **韧性**(贯穿):血缘是增强,失败不阻断主链路;neo4j 不可达时血缘查询 API 返回明确错误码(`lineage.store_unavailable`),平台其余不受影响。
- **测试**:`SqlColumnLineageExtractor` 用 catalog fixture 做**纯单测**(列映射各形态:JOIN/CTE/UNION/表达式/`*`/降级);写入链路 + Cypher 查询用 **Testcontainers neo4j** 集成测试;沿用后端测试隔离不变量。

## 9. 未来工作 / Roadmap(本期不实现,文档化在案)

代码解析(Calcite)**只可靠覆盖 SQL 类型**。以下纳入"血缘解析能力演进路线",联动 `docs/architecture.md` §8:

1. **大模型血缘解析(复杂类型)**:SHELL/Python 这类**脚本内直连数据库**的任务,静态代码解析拿不到血缘 → 未来引入 **LLM 解析血缘**(读脚本/连接串/运行时上下文,推断 reads/writes)。
2. **专用模型微调**:支持**微调一个专做"血缘解析任务"的 LLM**,提升复杂类型解析准确率。

## 10. 拆分为 3 份 speckit feature(§6)

共享契约 = §4 图模型 + `LineageStore` 接口 + §6 `ColumnEdge` 输出契约。**先定契约,三份并行**(各自 git worktree 隔离,CLAUDE.md 硬规则):

| 份 | feature | 范围 | 归属 |
|----|---------|------|------|
| **A** | `018-lineage-neo4j-store` | neo4j docker-compose + driver `@Bean`(SB4 无自动配置) + 约束/索引 + `LineageStore`(Cypher MERGE/replace-per-task/单事务) + **数据源去重** + greenfield 种子 + `schema.sql` 删血缘表 & 升 `schema_version` + push 路径接入 + Testcontainers harness | Agent 员工 1 |
| **B** | `019-lineage-column-lineage` | `SqlColumnLineageExtractor`(Calcite column-origin 三段式)+ 降级阶梯 + 列级 A×B 交叉校验 + `ColumnEdge` 契约 | **Claude(最难)** |
| **C** | `020-lineage-graph-api` | Cypher 查询取代 BFS + `/api/lineage/*` 多粒度重设计 + 企业级前端血缘视图 | Agent 员工 2 |

**依赖**:B、C 依赖 A 的模型与 `LineageStore`/契约 → A 先落契约(可先出接口桩),B、C 对桩并行。本设计文档即共享契约,3 份 spec 各自引用。

## 11. 成功标准

- 建任务/push 后,库→表→列结构 + 表级/列级血缘流 + 指标血缘正确落入 neo4j,同一 `(ip,port,database)` 去重为单节点。
- 上下游/影响面查询经 Cypher 变长路径返回,深度不限,结果与设计态写入一致。
- 列级血缘:对 JOIN/CTE/UNION/表达式/`SELECT *` 的代表性 SQL,目标列能正确溯源到源列;无法解析时干净降级到表级,不阻断主链路。
- neo4j 不可达不影响平台其余功能;血缘 API 返回明确错误码。
- 后端测试经 Testcontainers neo4j 全绿。
