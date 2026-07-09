# Contract: TaskExecutor（新任务类型须遵守）

新增执行器实现 `com.dataweave.worker.domain.TaskExecutor`（继承 `AbstractTaskExecutor`），并满足以下契约。既有 `SparkTaskExecutor` 是参照实现。

## C1 — 注册与分发

- **C1.1** 执行器为 `@Component`；`type()` 返回大写类型代号（`HIVE`/`FLINK`/`DATAX`/`SEATUNNEL`）。
- **C1.2** 两个网关（`WorkerExecService`、`InProcessTaskExecutionGateway`）经注入的 `Map<String,TaskExecutor>` 自动建 `byType` → **禁止改任何中央注册表**。本地 `LocalRunMain.selectExecutor` 须显式 `new`（不经 Spring）。
- **C1.3** 未知 type 由网关报 `NO_EXECUTOR`（可辨识），执行器无需处理。

## C2 — 执行与输出

- **C2.1** `execute(ctx, onLine)` 逐行调用 `onLine`（stdout/stderr 合并按行），实时喂日志管道。
- **C2.2** 子进程范式：`ProcessBuilder` → 独立读线程 → `waitFor(timeout, SECONDS)` → 超时 `destroyForcibly()` → `exitValue()`。捕获行数上限 5000，超出置 `truncated=true`。
- **C2.3** 注入环境变量 `DW_ATTEMPT`；`bizDate` 非空注入 `DW_BIZ_DATE`（同 Spark/Python）。
- **C2.4** 内容行尾规范化 `\r\n`/`\r` → `\n`。

## C3 — 三态结果（硬约束）

- **C3.1 成功**：`exitCode==0 && !timedOut && !skipped` → `success=true`。
- **C3.2 失败**：作业自身错误 / 资产缺失（jar/job 文件指定但不存在）/ 真实超时 → `success=false, skipped=false`，`exitCode` **忠实透传**（超时/启动失败为 -1）。
- **C3.3 跳过**：运行环境缺失引擎/驱动/可执行程序 → `ExecutionResult.skipped(reason)`（`success=false, exitCode=0, skipped=true`），reason 含可辨识「已跳过：<原因>」；**不得伪装成功、不得抛错中断、不得阻塞下游**。
- **C3.4** 不新增状态机状态；跳过由网关按「非失败完成」处理（既有 `WorkerExecService.doRun` 逻辑）。

### 各执行器 SKIPPED 触发条件

| 执行器 | SKIPPED 当 |
|---|---|
| `HiveTaskExecutor` | 未绑定 HIVE 数据源 / jdbcUrl 空 / 连接失败 / 无 Hive 驱动（复用 `SqlTaskExecutor` 连接失败判定语义） |
| `FlinkTaskExecutor` | `FLINK_HOME` 空 / `${FLINK_HOME}/bin/flink` 不存在 |
| `DataXTaskExecutor` | `DATAX_HOME` 空 / `${DATAX_HOME}/bin/datax.py` 不存在 |
| `SeaTunnelTaskExecutor` | `SEATUNNEL_HOME` 空 / `${SEATUNNEL_HOME}/bin/seatunnel.sh` 不存在 |

### 失败（非跳过）触发

- 指定 jar/job 文件但不存在 → 真实失败（对齐 `SparkTaskExecutor` jar 缺失语义）。
- 内容为空 → 失败 `exitCode=-1`。

## C4 — 命令构造纯函数（可单测）

- **C4.1** 提交命令构造为 `static` 纯函数（如 Spark 的 `buildCommand`），不依赖真引擎，便于单测断言参数顺序。
- **C4.2** SKIPPED 判定为 `static` 纯函数（如 Spark 的 `skipReason`）。

## C5 — 保真（Constitution III，NON-NEGOTIABLE）

- **C5.1** 服务端与本地 `dw run` 复用**同一执行器实现**（`LocalRunMain` 直接 `new`）。
- **C5.2** parity 测试断言同一 `(type, content, ctx)` 下 exitCode / stdout-stderr 分流 / 超时中止 / SKIPPED 逐项相等。

## C6 — 测试（no test = not done）

每个新执行器至少：
1. `buildCommand`/提交参数构造纯函数正确性；
2. SKIPPED 判定路径（缺 HOME/驱动）；
3. 失败退出码透传（模拟非零退出，或内容为空/文件缺失）；
4. 在无外部引擎的 CI 环境跑通 SKIPPED 闭环（不 fail 构建）；
5. **日志断言**（见 C7）：SQL/HQL 执行器断言结果集被渲染进日志；引擎执行器断言原生输出行被逐行透出。

## C7 — 日志可观测（FR-011/011a/011b，SC-007，硬约束）

- **C7.1 统一 banner**：日志由 `WorkerExecService.emitStartBanner/emitEndBanner` 包裹（运行模式/类型/数据源/时间 → 执行过程 → 状态/退出码/耗时/时间）。新执行器零改动继承，**禁止**出现「跑了但日志空白」。
- **C7.2 SQL/HQL 结果集渲染**（`SqlTaskExecutor`、`HiveTaskExecutor`）：`hasResultSet==true` 的语句（`SHOW TABLES`/`DESCRIBE`/`SELECT`）MUST 将结果集按「表头 + 数据行」渲染进 `emitLine` 日志；带**行数上限**（`MAX_RESULT_ROWS`，如 200）+ 单元格长度截断，超限追加「已截断，仅显示前 N 行」。DML/DDL MUST 输出「影响 N 行 + 耗时」。**修订现状**「本期不打印结果集」。
- **C7.3 引擎原生日志**（子进程类执行器）：`redirectErrorStream(true)` + 逐行 `onLine`，MUST 忠实透出引擎/进程 stdout+stderr（不吞、不改写语义、保留进度/错误行）。
- **C7.4 安全**：渲染/透出时 MUST NOT 回显数据源密码、连接串密文、job 内嵌凭据（FR-017）。
- **C7.5 保真**：结果集渲染同样经本地 `dw run`（复用同执行器）→ 与服务端日志内容一致（parity）。
