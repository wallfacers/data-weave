# Research: 运行态同步行数采集（recordSynced 接入）

**Feature**: 025-lineage-synced-rows | **Phase**: 0 | **Date**: 2026-06-30

针对 spec 的代码现状调研（来自执行器→上报→master 链路详查 + 设计评审）。每条 Decision / Rationale / Alternatives。证据 `file:line`。

## R1. SQL 执行器采集模型

**Decision**: `SqlTaskExecutor` 把 per-statement `(sqlText, updateCount)` 收集进 `ExecutionResult.statementMetrics`（新增字段），替代当前只 emit `onLine`。SELECT/DDL（updateCount<0）不收。

**Rationale**: `SqlTaskExecutor` 已 per-statement 执行（`splitStatements` 按 `;` 拆，`SqlTaskExecutor.java:132`）且每条已调 `st.getUpdateCount()`（`:74-81`），只是 emit 进 `onLine` 诊断日志、`ExecutionResult.stdout` 对 SQL 是空串（`:86`）。即数据已在、只差结构化。

**Alternatives**: ① 从 onLine/LogBus 文本解析 affected-rows——否决，脆弱（日志格式变即崩）且 stdout 对 SQL 是空。② 整脚本一次性 execute 拿总 updateCount——否决，已是 per-statement 且需 per-table 归属。

## R2. 双上报路径

**Decision**: `statementMetrics` 经两条路径透传——(a) all-in-one 进程内：`InProcessTaskExecutionGateway.run`（`:142 result=executor.execute`）从 ExecutionResult 取 statementMetrics 透传 reportFinished；(b) distributed HTTP：worker `WorkerExecService` → `ReportCallback.reportToMaster`（`:204` 手拼 JSON）→ api `ClusterController.report` → reportFinished。HTTP 序列化**优先 Jackson `ObjectMapper`**（避免手拼 JSON 数组的引号/换行转义坑）。

**Rationale**: 两条路径都调 `reportFinished(instanceId, exitCode, tailLog)`（InProcess`:160` / ClusterController`:78`），签名要扩 statementMetrics。HTTP 路径现是 `StringBuilder` 手拼（`:204-217`），扩 list 结构易错（SQL 文本含 `"`/换行）。

**Alternatives**: ① 只改 HTTP 路径——否决，all-in-one（`scheduler.mode=all-in-one`，默认）不经 HTTP 会漏。② 新开上报端点——否决，复用现有 `/api/cluster/report` 更一致。

## R3. reportFinished 接线

**Decision**: `WorkerReportService.reportFinished` 注入 `LineageStore` + 复用 `SqlTableExtractor`；逐 statement 解析写表 → 每个 writeTable 调 `recordSynced`。bizDate/tenantId/projectId 取自 `TaskInstance`（reportFinished 已持有 ti，`:68` findById）。

**Rationale**: reportFinished 现零 LineageStore 耦合（构造器 `:37-53` 无）；`TaskInstance` 字段齐全（tenantId`:52`/projectId`:55`/taskId`:64`/bizDate`:100`）。写表**不查 neo4j `:WRITES`**，而是直接从执行的 statement 文本用 `SqlTableExtractor` 解析（runtime 真相，避免与设计态 `:WRITES` 漂移）。

**Alternatives**: ① 查 neo4j `:WRITES` 拿写表集合——否决，设计态与 runtime 可能不一致（临时改 SQL 未 push），runtime statement 解析更准。② 在 worker 解析写表——否决，worker 无 Calcite、不引。

## R4. recordSynced 已实现、`:TaskRun` 孤立

**Decision**: 直接接线已实现的 `recordSynced`（`LineageStore:42-46` + `Neo4jLineageStore:155-177`）；建议 `:TaskRun` 顺手 SET `taskDefId`（reportFinished 有 `ti.getTaskId()`）。

**Rationale**: `recordSynced` 是三个 lineage 写入方法里唯一零调用的；实现完整（MERGE :TaskRun → ensureTable → MERGE :SYNCED{rowCount,bytes,bizDate}）。当前 `:TaskRun` 孤立（无 :Task 链接），但 syncSummary 读侧（`MATCH :TaskRun-[:SYNCED]->:Table by bizDate`）不需要该链接——MVP 孤立即可；SET taskDefId 零成本给后续"按任务查"留口子。

**Alternatives**: 建 `(:TaskRun)-[:RUN_OF]->(:Task)` 边——本期不需要，延后。

## R5. 写表识别仅 INSERT/MERGE（UPDATE/DELETE 记债）

**Decision**: MVP 写表识别沿用 `SqlTableExtractor` 现状（仅 `SqlInsert` + `MERGE`，`:79-95`）；UPDATE/DELETE 落 `collectSources` 被当读表 → 找不到写表 → 跳过 + WARN（updateCount>0 不静默丢）。

**Rationale**: 补 UPDATE/DELETE 识别约 10 行，但会同时改设计态 `:WRITES` 行为（爆炸半径扩大、可能动 019/018 测试）；INSERT/MERGE 是同步主力；MVP 聚焦接线。

**Alternatives**: ① 本期就补 `SqlTableExtractor` 的 UPDATE/DELETE——可做但扩大范围；记为后续债（同时修设计态 `:WRITES` 同款缺口）。

## R6. Spark/Python/Shell 出范围

**Decision**: 仅 SQL；Spark/Python/Shell 不采（statementMetrics 空 list）。

**Rationale**: Spark 三形态全 `spark-submit` 子进程 + stdout 文本，无 SparkListener/metrics/accumulator，`sql_runner.py` 连 `df.count()` 都没调；Python/Shell 纯子进程 stdout，无结构化行数通道。Spark 需真插桩（独立大工程），stdout 解析太脆。

**Alternatives**: spark-sql 改 `sql_runner.py` 打 `df.count()` + 解析 stdout——否决，脆、不值得塞进该可靠的指标。

## 落地决策摘要

| 项 | 决策 |
|---|---|
| 采集 | `SqlTaskExecutor` 收 `(sqlText, updateCount)` 进 ExecutionResult（数据已在，只差结构化） |
| 上报 | 双路径（in-process + HTTP）；HTTP 用 Jackson |
| 接线 | reportFinished 注入 LineageStore + SqlTableExtractor 逐 statement 解析写表 |
| recordSynced | 接已实现；`:TaskRun` 顺带 SET taskDefId |
| 写表识别 | INSERT/MERGE（UPDATE/DELETE 记债 + WARN） |
| scope | SQL only |

Phase 0 无残留 NEEDS CLARIFICATION，Technical Context 全绿。Phase 1 据此出 data-model.md / contracts / quickstart.md。
