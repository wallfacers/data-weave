# Phase 0 Research: 大数据开发任务类型补全（MVP）

代码勘探（backend 四模块 + frontend + cli）后，所有 spec 的 NEEDS CLARIFICATION 已解，以下为关键决策记录。

## D1 — OLAP 数据仓（StarRocks / Doris / ClickHouse）接入方式

- **Decision**: 复用现有 `SqlTaskExecutor`，任务为 `SQL` 类型绑定对应数据源；**这些数据源类型已全链路就绪，本轮工作收敛为「验证 + 暴露 + 驱动 jar 可加载」，零新执行器**。
- **Rationale**: `data.sql:56-73` 已 seed `CLICKHOUSE(9,8123)` / `STARROCKS(10,9030)` / `DORIS(11,9030)`，且四处同步点全部已覆盖：
  1. DB 注册表 `datasource_types`；
  2. `DatasourceResolver.buildJdbcUrl`（:240，STARROCKS/DORIS→`jdbc:mysql://`，CLICKHOUSE 独立）；
  3. `JdbcConnectionTester`（`JDBC_TYPES` 白名单 :43 + `buildJdbcUrl` :174 + `builtinFallbackDriver` :151 + `validationQuery`）；
  4. 前端 `frontend/lib/datasource-type-config.ts:23`（`DATASOURCE_TYPE_CONFIG` + `jdbcUrlTemplate`）。
  StarRocks/Doris 走 MySQL 协议驱动，ClickHouse 用 `com.clickhouse.jdbc.ClickHouseDriver`；驱动 jar 经既有驱动隔离上传机制加载。
- **验证要点（tasks 阶段落实）**：
  - ClickHouse 多语句/无 `updateCount` 语义在 `SqlTaskExecutor.splitStatements` 朴素分号切分下的表现（ClickHouse 不支持 `;` 批量的部分驱动 → 逐条 execute 即可，验证影响行数汇报不误报）。
  - StarRocks/Doris 的 affected-rows 经 MySQL 驱动 `getUpdateCount` 正确回填 `StatementMetric`（feature 025 recordSynced 复用）。
  - 缺驱动 jar → `SqlTaskExecutor` 已有 `isConnectionFailure`→SKIPPED 路径，保 CI 零依赖闭环。
- **Alternatives rejected**: 为每个 OLAP 写独立执行器——重复代码、放弃已就绪的驱动隔离与血缘复用，无收益。

## D2 — Hive 接入方式

- **Decision**: 新增独立任务类型 `HIVE` + `HiveTaskExecutor`，**内部经 HiveServer2 JDBC 建连**（复用 `HIVE(7)` 数据源类型 + `org.apache.hive.jdbc.HiveDriver` + 驱动隔离），**不依赖 beeline 二进制**。
- **Rationale**:
  - spec/需求方明确「Hive 走独立执行器（HQL/分区语义差异）」——保留 `HIVE` 为独立 `type()`，使 HQL 多语句、分区写入、`SET` 会话变量等语义与通用 `SqlTaskExecutor` 隔离演进，不污染 OLAP SQL 路径。
  - 实现载体选 HiveServer2 JDBC 而非 beeline 子进程：① 复用已就绪的 `HIVE` 数据源类型与驱动隔离；② 无需环境安装 beeline 二进制，缺连接即走 JDBC 连接失败→SKIPPED，保真 + CI 友好；③ 与 `SqlTaskExecutor` 建连范式同构，实现成本低。
  - HQL 差异处理点：多语句可含 `SET k=v;` 会话指令，需按序 execute 且不当作影响行数；分区写入 `INSERT OVERWRITE TABLE t PARTITION(...)` 无 updateCount，如实汇报「执行完成」不误报行数。
- **Alternatives rejected**:
  - **Hive 完全并入 SqlTaskExecutor（不加新类型）**：违背 spec 决策，且 HQL 会话指令/分区语义会侵入通用 SQL 路径。
  - **beeline 子进程**：引入二进制依赖，SKIPPED 判定更脆（需探测 beeline 存在），CI 不友好。
- **血缘**：`HIVE` 任务内容为 HQL，接入现有 `SqlTableExtractor`（Calcite）表/列级血缘，方言不识别处最小降级（不产错血缘，FR-016）。

## D3 — DataX 执行器

- **Decision**: 新增 `DATAX` 任务类型 + `DataXTaskExecutor`；内容 = DataX job JSON；以 `${DATAX_HOME}/bin/datax.py <job.json>` 子进程提交（复用 `SparkTaskExecutor` 子进程范式）。
- **Rationale**: DataX 官方运行方式即 `python datax.py job.json`；job JSON 自含 reader/writer 连接信息（MVP 内联即可）。`DATAX_HOME` 缺失 / `datax.py` 不存在 → `ExecutionResult.skipped`。source/sink 可选绑定 `task_def.datasource_id`/`target_datasource_id`（已存在列），后续增强为占位符注入；MVP 先支持内联 job JSON。
- **环境探测（SKIPPED 判定）**：`DATAX_HOME` 空 或 `${DATAX_HOME}/bin/datax.py` 不存在 → 跳过。
- **Alternatives rejected**: 直接嵌入 DataX 引擎库——重量级、与「引擎由环境提供」假设冲突。

## D4 — SeaTunnel 执行器

- **Decision**: 新增 `SEATUNNEL` 任务类型 + `SeaTunnelTaskExecutor`；内容 = SeaTunnel 配置（HOCON/JSON）；以 `${SEATUNNEL_HOME}/bin/seatunnel.sh --config <file>` 子进程提交。
- **Rationale**: 与 DataX 同构子进程范式；`SEATUNNEL_HOME` 缺失 → SKIPPED。SeaTunnel 引擎选择（Zeta/Flink/Spark）由配置或默认决定，MVP 用默认 Zeta。
- **Alternatives rejected**: 同 D3。

## D5 — Flink 执行器

- **Decision**: 新增 `FLINK` 任务类型 + `FlinkTaskExecutor`，两种内容形态：
  - `sql`：Flink SQL，经 `${FLINK_HOME}/bin/sql-client.sh -f <file>` 子进程；
  - `jar`：`${FLINK_HOME}/bin/flink run [-c <mainClass>] <app.jar>`。
  形态经 `params_json._flinkMode`（镜像 Spark 的 `_sparkMode`）判别。
- **集群配置来源**: 优先绑定数据源的 `props_json`（镜像 Spark：`jobmanager`/`parallelism`/`savepoint` 等），否则 `FLINK_HOME` env。**多半无需新增 FLINK 数据源类型 seed 行**——Flink 非 JDBC，若走绑定数据源则新增一个 `FLINK` 数据源类型（含四处同步点），但 MVP 可先只依赖 `FLINK_HOME` env + `params`，把数据源型集群配置留作增强。
- **SKIPPED 判定**：`FLINK_HOME` 空 或 `${FLINK_HOME}/bin/flink` 不存在 → 跳过。
- **Alternatives rejected**: Flink 走 JDBC（flink-jdbc-driver）——覆盖场景窄（仅 SQL gateway），不覆盖 jar/流式，不选。

## D6 — 执行上下文如何承载引擎配置（共享类型设计）

- **Decision**: `ExecutionContext` 新增**单个**可空字段 `EngineSubmitRef engine`（通用，Flink/DataX/SeaTunnel 共用），Spark 保持独立 `SparkSubmitRef spark` 字段不动。
  - `EngineSubmitRef { String kind, String engineHome, String mode, String jarPath, String mainClass, String configPath, Map<String,String> props }`（`kind` ∈ FLINK/DATAX/SEATUNNEL）。
- **Rationale**:
  - 单字段（而非 3 个 per-engine 字段）最小化 `ExecutionContext` 的 telescoping 构造器膨胀（现已 4 个兼容构造器）；再加一个「全参 + 一个含 engine 的兼容构造」即可，老调用点零改动。
  - 不动 `SparkSubmitRef`：Spark 路径已上线、有测试，隔离新老避免回归（与 PythonTaskExecutor「刻意重复不改既有」同精神）。
  - 由**工作流 B 先落 main**（契约先行），工作流 C（Flink）消费——避免同文件并行对撞（多 Agent 硬规则）。
- **解析接线**：`DatasourceResolver.ResolvedConnection` 增 `engine()` 产物（Flink 从绑定数据源 props_json / DataX·SeaTunnel 从 env）；`InProcessTaskExecutionGateway.buildSparkRef` 旁增 `buildEngineRef`；`DistributedTaskExecutionGateway` over-wire 序列化同字段；`SchedulerKernel` 镜像 `_sparkMode` 提取增 `_flinkMode`。
- **Alternatives rejected**:
  - **3 个 per-engine 字段**：telescoping 更严重、可读性差。
  - **把 ExecutionContext 改成 builder**：触及全部调用点，多 Agent 并行下高冲突、高回归，拒。

## D7 — 文件契约 round-trip（Constitution I/II 合规）

- **Decision**: 引擎子模式与配置**优先写入既有 `TaskDoc.params`（`Map<String,Object>`）+ `datasource`/`targetDatasource` 字段**，不新增 `TaskDoc` 类型化字段（Spark 曾加 `sparkMode/jarRef/mainClass`，本轮不照抄以省 churn）。
- **Rationale**: `params` map 已随 push/pull 全量序列化 → round-trip 自动闭合，无字段静默丢失（Constitution II round-trip integrity）。`_flinkMode` 等键入 `params`，与 `SchedulerKernel` 从 `params_json` 提取一致。
- **验证**：push→pull 到干净目录语义等价的契约测试（tasks 阶段）。
- **Alternatives rejected**: 每引擎加 `TaskDoc` 字段 + `ProjectMapper` 映射——churn 大、round-trip 需逐字段维护，收益低。

## D8 — 前端全类型暴露

- **Decision**: 把三处硬编码 `{SQL, SHELL}` 选择器（`catalog-tree.tsx:1115`、`workflow-canvas-view.tsx:1194`、`task-config-panel.tsx:120`）替换为全类型列表；`taskTypeToLang`（`params-table.tsx:6`）增 `HIVE→sql`、`FLINK→sql`、`DATAX→json`、`SEATUNNEL→hocon|text`；补 i18n `taskType*` 键（含当前缺失的 `taskTypeSpark`）。
- **Rationale**: 后端执行器 add-only 自动可用，用户价值卡在入口未暴露。数据源类型标签取 backend `name` 列（无需新 i18n），故 OLAP 数据源在创建 UI 已可见（`datasources-view.tsx` 数据驱动枚举）。
- **约束**：base-style 组件（`DropdownSelect` 用 `render` 非 `asChild`）、hugeicons、语义 token；两 i18n bundle key 对齐（CI 校验）。

## D9 — 本地两条腿调试保真（Constitution III，NON-NEGOTIABLE）

- **Decision**: `LocalRunMain.selectExecutor` 增 `HIVE/FLINK/DATAX/SEATUNNEL` 分支（直接 `new`，不经 Spring）；`buildContext` 合成 `EngineSubmitRef`；`LocalRunArgs` 增 `--flink-mode` 等 flag；`dw` CLI 透传。
- **Rationale**: 复用同一执行器子进程 = 代码级保真；parity 测试断言 exitCode/stdout-stderr/超时/SKIPPED 逐项相等。
- **注意**：Hive JDBC 本地需 `IsolatedDriverLoader`（同 SQL 分支的本地构造）。

## D10 — 日志可观测：SQL 结果集渲染 + 引擎原生日志（FR-011/011a/011b，SC-007）

- **Decision**: 统一「主管仅凭日志定位问题」为硬要求，分两条落地：
  1. **SQL/HQL 结果集渲染**：修订 `SqlTaskExecutor` 现状（源码明示「本期不打印结果集，仅汇报有返回」）——对 `hasResultSet==true` 的语句（`SHOW TABLES`/`DESCRIBE`/`SELECT`），遍历 `ResultSet` 按「表头 + 数据行」渲染进 `emitLine` 日志，带**行数上限**（如 200 行）与列宽/单元格长度截断，超限追加「已截断，仅显示前 N 行」。DML/DDL 维持「影响 N 行 + 耗时」。`HiveTaskExecutor` 同规范。
  2. **引擎原生日志**：Spark/Flink/DataX/SeaTunnel/Python/Shell 子进程已 `redirectErrorStream(true)` + 逐行 `onLine`，天然透出引擎 stdout/stderr——契约固化为「不吞、不改写、保留进度/错误行」，并经 `WorkerExecService` 起止 banner 包裹。
- **Rationale**: 运维价值核心是「跑起来能看到日志」。现状 SQL 只报摘要 → `SHOW TABLES` 看不到表名，无法定位。结果集渲染补齐这一最常用诊断路径；引擎日志忠实透出保证 Spark/Flink 报错行可见。
- **落点**:
  - `SqlTaskExecutor.doExecute`（backend/dataweave-worker/.../infrastructure/SqlTaskExecutor.java 87-90 行分支）——`hasResultSet` 分支从「不展示行数据」改为渲染结果集。
  - 行数/长度上限复用既有 `MAX_CAPTURED_LINES`(5000) 之下的语句级上限（新增常量，如 `MAX_RESULT_ROWS=200`），避免超大结果撑爆日志。
  - 统一 banner 已由 `WorkerExecService.emitStartBanner/emitEndBanner` 提供，新执行器零改动继承。
- **保真/安全**: 结果集渲染同样经本地 `dw run`（LocalRunMain 复用同执行器）→ parity 一致；渲染时不回显数据源密码等敏感连接串（FR-017）。
- **Alternatives rejected**:
  - **仅摘要不渲染结果**（现状）：`SHOW TABLES` 等诊断语句无法定位，违背 SC-007，拒。
  - **无上限全量打印结果集**：超大 `SELECT` 撑爆日志/LogBus，拒；用行数上限 + 截断标注。
  - **result 展示对齐 open-db-studio 交互式表格**：属前端富渲染，超本轮范围（留后续）；MVP 走纯文本表头+行渲染入日志即可。

## 未决/留作增强（不阻塞 MVP）

- DataX/SeaTunnel 的 source/sink 从绑定数据源自动注入 job 占位符（MVP 内联）。
- Flink/DataX/SeaTunnel 的引擎级血缘（源→汇）——best-effort，FR-016 为 SHOULD，可后续。
- FLINK 作为独立数据源类型（四处同步点）——MVP 靠 `FLINK_HOME` env，集群型配置留增强。
