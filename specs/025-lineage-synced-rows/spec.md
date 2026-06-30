# Feature Specification: 运行态同步行数采集（recordSynced 接入）

**Feature Branch**: `025-lineage-synced-rows`

**Created**: 2026-06-30

**Status**: Draft

**Input**: 后续待办（lineage L2 收口遗留）：运行态 recordSynced 接入——今日同步行数的生产写入点未接（neo4j syncSummary 读侧就绪、recordSynced 写实现就绪，但全仓零生产调用）。

> **范围边界**: 打通"今日同步行数"端到端链路。`recordSynced` 接口 + neo4j 实现已就绪、零调用；本特性补齐**上游采集**——SQL 执行器 per-statement affected-rows → `ExecutionResult` → 上报 DTO → `reportFinished` 逐表 `recordSynced`。scope = **SQL 任务**（Spark/Python/Shell 出范围，无可靠结构化采集手段）。不改 syncSummary 读侧、不改 recordSynced neo4j 实现契约。列 catalog（024）是独立 feature。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - SQL 任务跑完后 syncSummary 有真实数据 (Priority: P1)

一个 INSERT/MERGE 的 SQL 任务执行成功后，其 affected-rows 按"写表"落到 neo4j `:TaskRun-[:SYNCED]->:Table`；`GET /api/lineage/sync-summary` 当日返回真实 SUM，不再恒为 null（"估算中"）。

**Why this priority**: 本 feature 的价值核心——syncSummary 从"永远估算中"变可用；recordSynced 实现已就绪，缺的只是上游数据与接线。

**Independent Test**: 跑一个写单表的 SQL 任务，断言 neo4j 出现 `:SYNCED{rowCount=<affected>}`，syncSummary 返回该值。

**Acceptance Scenarios**:

1. **Given** `INSERT INTO orders_clean(total) SELECT amount FROM orders`、affected=1000, **When** 任务成功, **Then** `(:TaskRun{instanceId})-[:SYNCED{rowCount:1000,bizDate}]->(:Table{orders_clean})`，syncSummary 当日返回 1000。
2. **Given** 多 statement SQL 写两表（A 100 行、B 50 行）, **When** 成功, **Then** 两表各 recordSynced，syncSummary SUM=150。
3. **Given** 任务失败/跳过, **Then** 不写 `:SYNCED`。
4. **Given** 非 SQL 任务（Python/Shell/Spark）, **Then** 不写 `:SYNCED`（零回归）。

---

### User Story 2 - 降级绝不阻断主链路 (Priority: P1)

任何环节（执行器无 affected-rows、Calcite 解析写表失败、UPDATE/DELETE 不识别、neo4j 不可达、上报链路异常）都不阻断任务执行/回报主链路；syncSummary 顶多少几行，不报错。

**Why this priority**: 运行态采集是增强项，绝不阻断（与现有 lineage 写入降级一致）；降级行为错误会拖垮任务回报。

**Acceptance Scenarios**:

1. **Given** statement 是 SELECT/DDL（updateCount<0）, **Then** 跳过该 statement，不 recordSynced。
2. **Given** statement 是 UPDATE/DELETE（MVP 不识别写表）且 updateCount>0, **Then** 跳过 + **WARN 日志**（显式降级，不静默丢）。
3. **Given** neo4j 不可达, **Then** reportFinished try-catch 吞，任务仍 SUCCESS，不阻断。

---

### User Story 3 - 多写表近似归属 (Priority: P2)

单 statement 写多表（INSERT ALL 等）时，每个 writeTable 共享该 statement 的 updateCount（JDBC 无 per-table 分解，近似正确）。

**Why this priority**: 覆盖少见但真实的"一语句多表"形态，保证归属规则明确、不崩溃。

**Acceptance Scenarios**:

1. **Given** 一条 statement 写 tableA、tableB（updateCount=100）, **Then** tableA、tableB 各 recordSynced(rowCount=100)。

---

### Edge Cases

- **UPDATE/DELETE**：MVP 不识别写表（`SqlTableExtractor` 现仅 INSERT/MERGE），行数跳过 + WARN；记为后续债（补识别约 10 行，同时改善设计态 `:WRITES` 覆盖）。
- **字符串字面量内分号**：worker 朴素 `;` 切分边角，该 statement 解析失败降级跳过（切分一致性绑定到 Calcite 侧，字面量边角 MVP 接受）。
- **旧 worker + 新 master**：statementMetrics 缺失 → null/empty，reportFinished 跳过 recordSynced（向后兼容）。
- **新 worker + 旧 master**：TaskReportRequest 多发 statementMetrics 被 `@JsonIgnoreProperties` 忽略，不崩。
- **bizDate/tenantId/projectId**：从 `TaskInstance` 取（reportFinished 已持有 ti；bizDate=`ti.getBizDate()`）。
- **all-in-one 模式**：`InProcessTaskExecutionGateway` 进程内直调 executor、不经 HTTP 上报——reportFinished 签名扩展须同时覆盖此路径（从 ExecutionResult 取 statementMetrics 透传）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（采集）**: `SqlTaskExecutor` MUST 把 per-statement `(sqlText, updateCount)`（updateCount≥0）收集进 `ExecutionResult`（新增 `statementMetrics` 字段），替代当前只 emit `onLine`。SELECT/DDL（updateCount<0）不收。
- **FR-002（DTO 扩展·双路径）**: `statementMetrics` MUST 经两条上报路径透传到 master——(a) distributed HTTP：扩 `ReportCallback` 签名 + `TaskReportRequest` DTO + 序列化（**优先 Jackson `ObjectMapper`**，避免手拼 JSON 数组的引号/换行转义坑；若保留手拼则必须定义 `record StatementMetric(sqlText, updateCount)` + `escapeJson`）；(b) all-in-one 进程内：`InProcessTaskExecutionGateway` 从 `ExecutionResult` 取 `statementMetrics` 透传 reportFinished。
- **FR-003（写表解析）**: `reportFinished` MUST 复用 `SqlTableExtractor`（Calcite）逐 statement 解析写表；每个 writeTable 调一次 `recordSynced`（共享该 statement 的 updateCount，1:N 近似）。**MVP 写表识别仅 INSERT/MERGE**（`SqlTableExtractor` 现状）。
- **FR-004（recordSynced 接线）**: `reportFinished` MUST 注入 `LineageStore`，对每个 (writeTable, updateCount) 调已实现的 `recordSynced(tenantId, projectId, instanceId, tableRef, updateCount, bytes=null, bizDate)`；bizDate/tenantId/projectId 取自 `TaskInstance`；建议 `:TaskRun` 顺手 SET `taskDefId`（reportFinished 有 `ti.getTaskId()`，零成本留口子）。
- **FR-005（降级·零阻断）**: 任何采集/解析/写入失败 MUST 降级（跳过+日志或退表级），MUST NOT 阻断任务执行/回报；updateCount>0 但无写表（UPDATE/DELETE/解析失败）MUST WARN（不静默丢）；statementMetrics null/empty（旧 worker）MUST 跳过；neo4j 不可达 MUST try-catch 吞。
- **FR-006（仅成功路径）**: recordSynced 只在 `reportFinished`（SUCCESS）调；`reportFailed`/skipped 不采。
- **FR-007（边界）**: 本特性不涉及 `.task.yaml` 声明（运行态采集，非定义态）；不改 recordSynced neo4j 实现契约、不改 syncSummary 读侧。
- **FR-008（内核复用）**: MUST 复用 `SqlTableExtractor`、`recordSynced` 已实现、`WorkerReportService` 主链路；MUST NOT 新建第二条执行/回报引擎（Constitution V）。

### Key Entities *(include if feature involves data)*

- **StatementMetric**: `{sqlText, updateCount}` —— worker 收集的 per-statement 原始对。
- **ExecutionResult.statementMetrics**: `List<StatementMetric>`（SQL 填、其他执行器空 list）。
- **TaskReportRequest.statementMetrics**: 上报 DTO 字段（HTTP 路径）。
- **`:TaskRun` 节点**: `{instanceId, tenantId, projectId, bizDate, taskDefId}`（taskDefId 本期顺带 SET，供后续"按任务查同步行数"）。
- **`:SYNCED` 边**: `{rowCount, bytes(null), bizDate}` —— recordSynced 已实现。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: SQL（INSERT/MERGE）任务成功后，syncSummary 当日返回真实 affected-rows SUM（不再恒 null）。
- **SC-002**: 多写表任务（多 statement 或单 statement 多表）按表 recordSynced，SUM 正确/近似正确。
- **SC-003**: 失败/跳过/非 SQL 任务不写 `:SYNCED`（零回归）。
- **SC-004**: 任何采集/解析/写入异常干净降级，0 阻断主链路；updateCount>0 无写表不静默丢（WARN）。
- **SC-005**: 新旧 worker/master 组合向后兼容（不崩、缺失字段优雅跳过）。

## Assumptions

- 行数源 = 执行器上报（SQL affected-rows）；**Spark/Python/Shell 出范围**（无可靠结构化采集，后续债——Spark 需 SparkListener/metrics 真插桩）。
- MVP 写表识别仅 INSERT/MERGE（`SqlTableExtractor` 现状）；UPDATE/DELETE 写表识别是后续小增强（约 10 行，同时改善设计态 `:WRITES`）。
- JDBC 不提供 per-table/per-statement 字节分解 → bytes=null；单 statement 多表共享 updateCount（近似）。
- `reportFinished` 签名扩展覆盖 in-process + HTTP 双路径。
- 实现期开 `dw-025-lineage-synced-rows` worktree（与 021/022/023/024 惯例一致），合并序 021→022→023→024→**025**。
