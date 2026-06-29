# Contract: 执行器行为 + 分发 + SKIPPED + 数据源解析

本特性核心契约。所有行为 MUST 由测试钉死（原则 III fidelity）。

---

## C1. SparkTaskExecutor 行为契约

`type() == "SPARK"`，extends `AbstractTaskExecutor`。

### C1.1 命令构造（可单测的纯函数）

把命令拼接抽为 `List<String> buildCommand(SparkSubmitRef ref, String scriptPath)`，不依赖真 Spark：

| sparkMode | 命令（顺序） |
|-----------|------------|
| pyspark | `${sparkHome}/bin/spark-submit` `--master <master>` [`--deploy-mode <m>`] [`--queue <q>`] [`--conf k=v`...] `<body.py>` |
| spark-sql | `${sparkHome}/bin/spark-submit` `[confs]` `<sql_runner.py>` `<body.sql>` |
| jar | `${sparkHome}/bin/spark-submit` `[confs]` `--class <mainClass>` `<app.jar>` |

- conf map 每项展开为 `--conf key=value`（顺序确定，便于断言）。
- deployMode/queue 为空则省略对应参数。

### C1.2 执行与透传

- 起子进程（`ProcessBuilder`，复用 Shell/Python 模式），逐行 `onLine` 回调 stdout/stderr，按 `timeoutSeconds` 中止。
- exitCode/stdout-stderr 分流/超时（timedOut=true, exitCode=-1）忠实透传——**与 Shell/Python 执行器逐项一致**。

### C1.3 SKIPPED 触发（环境缺失）

返回 `ExecutionResult.skipped(reason)`（FR-008）当：
- `spark` ref 为 null（未绑定 SPARK 数据源）；或
- `sparkHome` 空 / `${sparkHome}/bin/spark-submit` 不存在；或
- `master` 空。

reason 文案可辨识（如"已跳过：本地无 Spark 环境（SPARK_HOME 未配置）"）。

### C1.4 真实失败（非 SKIPPED）

- 作业自身失败（脚本异常/SQL 错/主类抛错）→ exitCode≠0, success=false, skipped=false。
- jar 形态 jarPath 不存在/不可读 → 真实失败（资产问题非环境问题），message 可定位。

---

## C2. Spark SQL runner 契约（决策 A1）

`backend/dataweave-worker/src/main/resources/spark/sql_runner.py`：

- 入参：`sys.argv[1]` = .sql 文件路径。
- 行为：建/取 SparkSession → 读 .sql → 朴素分号切分 → 逐句 `spark.sql(stmt)` → 任一句异常则打印 stderr 并 `sys.exit(非0)`；全部成功 `sys.exit(0)`。
- 作为 classpath 资源，执行前释放到临时文件传给 spark-submit。

---

## C3. SKIPPED 在调度层呈现契约

`InProcessTaskExecutionGateway` + `WorkerExecService`（distributed）：

- result.skipped==true → 走"完成"回报（`reportFinished`，**不阻塞下游**），但：
  - banner/tail 使用 SKIPPED 专门文案（i18n 规则②，按触发者 locale，新增 message key 如 `taskrun.banner.status.skipped`，zh-CN/en-US 双 bundle）。
  - tail 含可辨识标记，使 `dw run` 与人/AI 能区分跳过 vs 成功。
- **不新增** `task_instance` 状态枚举值（FR-012）。
- SqlTaskExecutor 现有三处 `[simulated]` 成功 → 改 `ExecutionResult.skipped(...)`。

---

## C4. distributed 分发修复契约（FR-007）

### C4.1 执行器按类型选择（必做，高优先）

- `WorkerExecService` 构造建 `byType` 映射（同 `InProcessTaskExecutionGateway` 构造逻辑）。
- `submit`/`executeSync`/`doRun` 接受 `taskType`，`resolveExecutor(taskType)` 按类型取；未知类型与 all-in-one 同样可辨识处理（不静默 SHELL、不伪装成功）。
- `WorkerExecController` 从 exec body 读 `taskType` 透传（body 已含 taskType，gateway 已发）。

### C4.2 数据源随下发序列化（完成 FR-007 完整 ctx）

- `DistributedTaskExecutionGateway`（api 层，有 `DatasourceResolver`）解析数据源 → 把解析后连接信息（SQL DataSourceRef 字段 / SHELL env / PYTHON config / SPARK SparkSubmitRef）序列化进 exec body。
- `WorkerExecController` 反序列化 → 构建完整 `ExecutionContext`（taskType + 数据源/env/spark）→ `WorkerExecService`。
- worker **不新增 DB 依赖**，与 all-in-one 对称。
- **分级**：C4.1（按类型选执行器）可独立先交付并测试；C4.2（数据源 over-wire）紧随。两者都属本特性，tasks 内分步。

---

## C5. DatasourceResolver SPARK 解析契约

- `resolve(datasourceId, "SPARK")` → `case "SPARK" -> buildSparkRef(ds)`。
- `buildSparkRef`：从 `ds.getPropsJson()` 解析 `{sparkHome,master,deployMode,queue,conf}` → `ResolvedConnection.spark(...)`。
- props_json 缺关键字段（master/sparkHome）→ 返回部分填充，执行器侧据空字段判 SKIPPED（解析不抛错，保零依赖底线）。

---

## C6. LocalRunMain parity 契约（FR-010/011）

- `selectExecutor` 覆盖全集：SHELL/SQL/PYTHON/**ECHO**/**SPARK**。
- `buildContext` SPARK 分支：ds-json → SparkSubmitRef；pyspark/spark-sql 脚本体经 content 落临时文件；jar 经本地 jarPath。
- `LocalRunMainParityTest` 断言每类型经 LocalRunMain 与经服务端执行器（同 new 实例）exitCode/stdout-stderr/timeout 逐项相等；SPARK 在无 Spark CI 上对照"双方都 SKIPPED"。
