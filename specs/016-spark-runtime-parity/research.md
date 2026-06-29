# Phase 0 Research: 深化执行 —— Spark 协议 + runtime 语义对齐

研究目标：把头脑风暴拍板的决策落成可实现的技术决定，消解实现层未决点。无 spec 级 NEEDS CLARIFICATION（已由头脑风暴 4 轮问答消解）；本文件解决 plan 派生的实现细节。

---

## D1. Spark 执行形态 = spark-submit 子进程（非嵌入式）

- **Decision**: SparkTaskExecutor 拼 `spark-submit` 命令行起子进程，复用现有 `ProcessBuilder` 模式（同 Shell/Python 执行器），忠实采集 exitCode/stdout/stderr、按 timeoutSeconds 中止。
- **Rationale**: ① 本地 `--master local[*]` 与 on-YARN `--master yarn` 仅差一个参数，子进程天然统一；② 不把 spark-core 拉进 worker 常驻 classpath（嵌入式会带来巨大依赖 + classloader 冲突 + driver 语义难本地复刻）；③ 与原则 III"复用平台执行器作子进程"一脉相承，fidelity 可由 parity 测试钉死。
- **Alternatives rejected**: 嵌入式 `SparkSession.builder().master("local[*]")`——依赖膨胀、cluster 提交语义无法本地复刻、违背 YAGNI。

## D2. 三种 sparkMode 的统一提交矩阵（决策 A1）

单一 `spark-submit` 提交路径，按 sparkMode 拼不同尾参：

| sparkMode | 脚本体/资产 | spark-submit 命令（[confs] = --master/--deploy-mode/--queue/--conf...） |
|-----------|------------|----------------------------------------------------------------|
| `pyspark` | `.py` 脚本体落临时文件 | `spark-submit [confs] <body>.py` |
| `spark-sql` | `.sql` 脚本体落临时文件 | `spark-submit [confs] <sql_runner.py> <body>.sql`（runner 读 .sql 逐句 `spark.sql()`） |
| `jar` | 上传的 application jar 资产 + mainClass | `spark-submit [confs] --class <mainClass> <app>.jar` |

- **Decision A1（确认）**: Spark SQL 不调发行版 `bin/spark-sql`，而由仓库自带的极小 pyspark runner（`backend/dataweave-worker/src/main/resources/spark/sql_runner.py`）经同一 `spark-submit` 提交。runner 仅 ~20 行：建 SparkSession、读传入 .sql 文件、按分号切分逐句 `spark.sql(stmt)`、异常即非 0 退出。
- **Rationale**: 三形态单一提交路径（少分叉、命令构造可统一测试）；不依赖发行版是否带 `spark-sql` 脚本；SQL 切分逻辑与平台可控（复用 SqlTaskExecutor 的朴素分号切分思路）。
- **临时文件**: 脚本体（.py/.sql）写入临时文件后提交，执行完清理（复用 DatasourceResolver.cleanup 式 best-effort 删除；或 try-finally）。runner 作为 classpath 资源，提交前释放到临时路径。

## D3. SPARK 集群配置载体 = SPARK 类型数据源（复用 props_json，零 schema）

- **Decision**: 新增 `type_code='SPARK'` 的数据源，提交配置存 `datasources.props_json`（JSON）：`{ "sparkHome": "...", "master": "local[*]|yarn", "deployMode": "client|cluster", "queue": "...", "conf": { "spark.executor.memory": "..." } }`。`DatasourceResolver` 增 `case "SPARK" -> buildSparkRef(ds)` 解析为新 `ResolvedConnection.spark(...)`。
- **Rationale**: 零 schema 变更（`props_json` 已存在，PYTHON 解析已用它存 props）；与 SQL/SHELL/PYTHON 解析同位、同范式（原则 V）。
- **"按环境解析"如何成立**: 数据源**本就环境隔离**——本地开发用 `datasources.local.yaml`（CLI 装配 ds-json），服务端用 `datasources` 表记录。同一逻辑数据源**名**在本地 yaml 配 `master: local[*]`、在服务端记录配 `master: yarn`。故"同一 .task 零改动漂移"是数据源环境隔离的自然结果，**无需新增环境解析逻辑**。
- **Alternatives rejected**: 任务级 config 块（环境差异硒进任务，违原则 II）；环境级 spark-defaults（脱离数据源体系，逻辑名无法统一管理）。两者已在头脑风暴否决。

## D4. SKIPPED 保真态建模（不新增状态机状态）

- **Decision**: `TaskExecutor.ExecutionResult` record 增 `boolean skipped` 字段。语义三态：
  - **成功**：`success=true, skipped=false, exitCode=0`
  - **失败**：`success=false, skipped=false, exitCode≠0`（作业自身错误 / 资产缺失 / 真实超时）
  - **跳过**：`skipped=true`（环境缺失：无数据源 / 无 SPARK_HOME / spark-submit 不可用）；调度层按"非失败完成、不阻塞下游"处理，`message`/banner 显式标注"已跳过：<原因>"。
- **调度层处理**: `InProcessTaskExecutionGateway` / `WorkerExecService` 见 `skipped=true` → 走 `reportFinished`（不阻塞下游），但 banner/tail 用专门的 SKIPPED 文案（i18n 规则②，按触发者 locale）。**不**新增 `task_instance` 状态枚举值——保持有界（FR-012）。
- **可辨识性（FR-009）**: ① 结果对象 `skipped` 标志（测试/程序可读）；② 日志 banner + tail 含可辨识中文/本地化文案（人/AI 可读）。`dw run` 据 `skipped` 输出区分于成功。
- **现状迁移**: `SqlTaskExecutor` 三处"`[simulated]` 模拟成功 `success=true`"全部改为 `skipped=true`（无数据源 / 连接失败 / 驱动加载失败）。这会改变现有断言——`SqlTaskExecutorTest` 同步更新。
- **CI 影响**: H2/零依赖环境下 SQL 无真实业务数据源时变 SKIPPED（不再"成功"）——但 SKIPPED 不阻塞、不报错，构建仍绿；既有依赖"模拟成功"语义的测试需改判为 SKIPPED。**风险点**：排查所有断言 `success` 且实际无数据源的测试，逐一改为断言 `skipped`。

## D5. distributed 分发缺陷修复（对齐 all-in-one 基准）

- **现状缺陷**: `WorkerExecService.resolveExecutor()` 写死 `executors.get("SHELL")` 否则取第一个，**忽略 taskType**；`executeSync`/`doRun` 用 4 参 `ExecutionContext`（无 taskType/datasource）。distributed 模式下 SQL/PYTHON/SPARK 全被当 SHELL 跑。
- **Decision**: ① `WorkerExecService` 构造时建 `byType` 映射（同 `InProcessTaskExecutionGateway` 构造逻辑：遍历注入的 executors，`type().toUpperCase()` 为键）；② `submit`/`executeSync`/`doRun` 签名增 `taskType` + 数据源信息（或一个携带它们的 ctx 入参），`resolveExecutor(taskType)` 按类型取，未知类型与 all-in-one 同样处理（可辨识，不静默当 SHELL）；③ 构建完整 `ExecutionContext`（含 taskType + 解析后 DataSourceRef/spark/env）。
- **调用方**: distributed 模式下 worker 的 HTTP exec 端点（`WorkerExecController`）→ `WorkerExecService`。需核对 `WorkerExecController` 当前传参，补 taskType/datasourceId 透传（master 下发命令已含 taskType——见 `TaskExecutionGateway.DispatchCommand`）。
- **数据源解析位置**: all-in-one 在 api 层 `InProcessTaskExecutionGateway` 调 `DatasourceResolver`；distributed worker 侧是否能访问 `DatasourceResolver`？—— worker 模块依赖 master（SqlTaskExecutor 已 import master 的 IsolatedDriverLoader/DriverJar），但数据源解析需 DB 访问。**待定细节**：distributed 下数据源解析应在 master 下发前完成并随 DispatchCommand 传递，还是 worker 侧重查。**研究结论**：沿用现有下发契约——master 侧解析后把连接信息放进下发命令（与 all-in-one 对称），worker 侧只消费。Phase 1 contracts 固化此边界；若现有 DispatchCommand 未带解析后连接，则在 master 下发路径补（不在 worker 侧新增 DB 依赖）。

## D6. LocalRunMain 协议完整性 + parity 扩展

- **Decision**: `selectExecutor` 增 `case "ECHO" -> new EchoTaskExecutor()` 与 `case "SPARK" -> new SparkTaskExecutor(...)`；`buildContext` 增 SPARK 分支（从 ds-json 读 SparkSubmitRef，pyspark/spark-sql 写脚本体临时文件、jar 读本地 jar 路径）。
- **parity 测试**: `LocalRunMainParityTest` 扩展断言 ECHO 与 SPARK 经 LocalRunMain 与经服务端执行器（直接 new 同实现）exitCode/stdout-stderr/timeout 逐项相等。SPARK 在无 Spark 的 CI 上对照"双方都 SKIPPED"（同实现→同 SKIPPED，parity 成立）。
- **Go CLI 侧**: `cli/run/local.go` 已按 type 调起 LocalRunMain；确认 SPARK 类型透传 + ds-json 装配 SPARK 字段。jar 形态本地路径解析（资产在本地工作副本？还是需先 pull）——MVP：jar 走本地文件路径引用；上传/服务端取 jar 是 push 后服务端行为。

## D7. 测试与零依赖底线

- 单测全部 H2/无外部依赖：Spark 测试在无 SPARK_HOME 环境断言 SKIPPED + 命令构造（可注入假 spark-submit 路径或断言拼接的命令数组，不真起 Spark）。
- 命令构造测试：把 spark-submit 命令拼接抽为可单测的纯函数（输入 sparkMode/confs/脚本路径 → 输出 `List<String>` 命令），断言三形态命令正确，不依赖真 Spark。
- 真起 spark-submit 的验证留给 quickstart 手工/按需层（装 Spark 的开发机），不进 CI。

---

## 未决细节移交 Phase 1 contracts

1. distributed 下数据源解析归属（master 下发前解析 vs worker 重查）——**倾向 master 侧解析、随命令下发**，contracts 固化 DispatchCommand 字段。
2. `ExecutionContext` 承载 spark 配置的精确字段形状（新增 record `SparkSubmitRef` vs 复用 DataSourceRef 扩展）——**倾向独立 `SparkSubmitRef`**。
3. SKIPPED 在 `WorkerReportService` 回报路径的精确呈现（reportFinished + 特殊 tail vs 新 reportSkipped 方法不改状态）——data-model/contracts 定。
