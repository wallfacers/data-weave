# 收口 024/025 交接（跨机续接用）

> lineage L2 收口（018/019/020 neo4j 单一底座）遗留两个 TODO 的收尾交接。
> **024（声明驱动列 catalog）+ 025（recordSynced 运行态行数）均已合并 main（`5ac79d7`）+ neo4j IT 真验证。**
> 本文件随 git 走，供回家续接 024 剩余 US2/US3。

## 状态总览

| # | 分支（已合并删除） | 状态 | 说明 |
|---|------|------|------|
| **024** | `024-lineage-column-catalog` | ✅ **US1 MVP 合并 main（`96aca5f`）** | 声明 schema → push seed `:Column`(type/ordinal) → Neo4jColumnLineageCatalog → CONFIRMED 列边（破鸡生蛋）。**US2/US3/T014 待续** |
| **025** | `025-lineage-synced-rows` | ✅ **全 feature 合并 main（`ad20e03`+`209ab97`）** | recordSynced 接入：per-statement affected-rows → 双路径 → reportFinished → `:SYNCED` → syncSummary 真实 SUM（不再恒 null） |

提交点：024=`96aca5f`；025=`ad20e03`(US1 MVP)+`209ab97`(neo4j 验证修俩 bug)；main merge=`5ac79d7`；docs=`0d3e37b`。**已 push 远程**。

## 025 recordSynced 接入（done，全 feature 含 US2/US3）

`SqlTaskExecutor` 收 per-statement `(sqlText, updateCount≥0)` → `ExecutionResult.statementMetrics` → 双路径（all-in-one `InProcessTaskExecutionGateway` + HTTP `WorkerExecController` Jackson）→ `WorkerReportService.reportFinished` 逐 statement `SqlTableExtractor.extract().writes()` → `recordSynced`（`:TaskRun-[:SYNCED]->:Table`）→ syncSummary 当日真实 SUM。
- T004 `recordSyncedRows` 天然覆盖 US2（降级 try-catch + UPDATE/DELETE 无写表 WARN + null/empty 跳过）+ US3（多写表循环共享 updateCount）。
- 关键决策：① `StatementMetric` 置 `master.domain.lineage`（worker→master 依赖方向，先例 DriverJar）；② `ExecutionResult` compat 构造器（21 处既有构造点零改动）；③ `recordSynced` 加 `taskDefId`（additive，零现有调用）。
- **修俩 recordSynced 零调用期 latent bug**（neo4j 真验才现形）：
  1. syncSummary 按节点 `r.bizDate=date()` 聚合，但旧 recordSynced 只在 `:SYNCED` 边设 bizDate、`:TaskRun` 节点从不设 → 恒空。修：`ON CREATE SET r.bizDate=date($bd)`。
  2. `MERGE (r)-[:SYNCED {rowCount,bytes:null,bizDate}]->(t)` 违反 neo4j 约束（MERGE 模式不允许 null 属性值，bytes 恒 null）→ 改 `MERGE (r)-[s:SYNCED {bizDate:$bd}]->(t) SET s.rowCount=$rc, s.bytes=$b`（顺带修幂等：重跑更新 rowCount 而非建二边）。
- 测试：SqlTaskExecutorTest 6/6、WorkerReportServiceTest 4/4、**RecordSyncedNeo4jIT 2/2（直连 etl-neo4j 真跑）**、WorkerExecServiceTest 7/7、ClusterReportTest 7/7、024 列血缘 28/28。

## 回家后续接清单（024 剩余，specs/024-lineage-column-catalog/tasks.md）

1. **T014 Neo4jColumnLineageCatalogIT**：列 catalog 的 neo4j 读侧 IT（catalog 逻辑已被 28 单测+fixtures 覆盖，neo4j 读 :Column 路径未集成测）。用 Neo4jTestSupport 外部模式跑：`NEO4J_TEST_URI=bolt://localhost:7687 NEO4J_TEST_PASSWORD=etl-neo4j-secret mvnd -pl dataweave-master test -Dtest=Neo4jColumnLineageCatalogIT -Dmaven.build.cache.enabled=false`。
2. **US2（T015-T021）columnLineage 声明对账激活**：声明 `columnLineage`（期望列边）→ 激活 019 FR-006 零调用的 `extractAndCrossCheck` → 声明 vs 推导对账（CONFIRMED/DECLARED/CONFLICT），CONFLICT 不阻断 push。`Confidence` 加 `DECLARED`。
3. **US3（T022-T023）DECLARED 兜底建图**：SQL 解析失败（DDL/动态/方言）时，声明 columnLineage 的边仍以 DECLARED 写入——列血缘视图不因解析失败而空。
4. **T024-T027 polish**：零回归验证 / 文档 / quickstart 端到端。

实现期开 `dw-024-lineage-column-catalog` worktree（specs/024-lineage-column-catalog/ 在 main 已入库）。

## 踩过的坑（务必复用）

- **mvnd build-cache 喂陈旧 master**：改 master 共享类型（如 `StatementMetric`）后，`-pl X`（无 `-am`）解析上游 master 从陈旧 `~/.m2` + build-cache 命中旧产物 → 下游"cannot find symbol"。修：`-Dmaven.build.cache.enabled=false` + 先 `mvnd -pl dataweave-master install -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false` 装新 master，再跑下游。
- **JDK25**：非交互 shell（setsid 脱离）默认 JDK21 → 用 **mvnd**（自带 JDK25，路径 `~/.local/opt/jdk-25.0.3+9`）；裸 `./mvnw` 须 `export JAVA_HOME` 指向 JDK25。
- **Docker Desktop WSL 集成未开** → 非交互 shell 无 `/var/run/docker.sock` → testcontainers 跑不了。绕过：`Neo4jTestSupport` 外部模式（`NEO4J_TEST_URI` env），直连手工启动的 neo4j 容器。
- **本地 neo4j**：`etl-neo4j` 容器 `bolt://localhost:7687`，`neo4j` / `etl-neo4j-secret`（密码定义在 `knowledge-builder/` Python 脚本里，非 docker-compose）。
- **WSL2 长跑**：build/test 用 `setsid bash -c '... >log 2>&1; echo $? >exit' </dev/null >/dev/null 2>&1 & disown` 脱离（见 CLAUDE.md 硬规则）。
- **worktree 隔离**：每特性独立 worktree；`.specify/feature.json` per-worktree 不提交（避免合并污染 main 指针）。
