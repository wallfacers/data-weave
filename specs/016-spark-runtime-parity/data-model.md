# Phase 1 Data Model: 深化执行 —— Spark 协议 + runtime 语义对齐

本特性"实体"主要是执行域的运行时结构与文件契约，**零数据库 schema 变更**（复用既有 `datasources` / `driver_jars` 表）。

---

## 1. ExecutionResult（执行结果）— 增 SKIPPED 态

`com.dataweave.worker.domain.TaskExecutor.ExecutionResult`（record）增字段 `boolean skipped`。

| 字段 | 类型 | 语义 |
|------|------|------|
| success | boolean | 真实执行成功（exitCode==0 且未超时且未跳过） |
| **skipped**（新增） | boolean | 环境缺失而未真实执行（无数据源 / 无 SPARK_HOME / spark-submit 不可用）。**与 success 互斥**：skipped=true 时 success 不得为伪装的 true |
| exitCode | int | 进程退出码（超时/启动失败 -1；跳过约定为 0 但靠 skipped 区分） |
| stdout / stderr | String | 输出分流 |
| truncated | boolean | 输出截断 |
| timedOut | boolean | 超时中止 |
| message | String | 面向用户/审计摘要（跳过时含可辨识"已跳过：<原因>"） |

**三态判定**（FR-008/009）：
- 成功：`success=true, skipped=false`
- 失败：`success=false, skipped=false`
- 跳过：`skipped=true`（success=false，调度层按非失败完成处理）

**兼容**：新增静态工厂 `ExecutionResult.skipped(String reason)`；现有构造点逐一过审。`AbstractTaskExecutor.execute` 的异常包装路径 skipped=false（异常是失败非跳过）。

**状态转换**：执行器产出 result → 调度层（`InProcessTaskExecutionGateway` / `WorkerExecService`）读 skipped：
- skipped=true → `reportFinished`（不阻塞下游）+ SKIPPED banner/tail；**不**新增 `task_instance` 状态枚举（FR-012）。

---

## 2. SparkSubmitRef（Spark 提交配置）— ExecutionContext 新字段

`com.dataweave.worker.domain.ExecutionContext` 增可空字段 `SparkSubmitRef spark`（与 `DataSourceRef datasource` 并列）。

```
record SparkSubmitRef(
    String sparkHome,      // SPARK_HOME；空/不存在 → SKIPPED
    String master,         // local[*] | yarn | spark://... ；空 → SKIPPED
    String deployMode,     // client | cluster（可空，默认 client）
    String queue,          // yarn 队列（可空）
    Map<String,String> conf, // 附加 spark.* 配置（可空）
    String sparkMode,      // pyspark | spark-sql | jar（内容形态判别）
    String jarPath,        // jar 形态：本地/已取的 application jar 路径（其它形态 null）
    String mainClass       // jar 形态：--class 主类（其它形态 null）
)
```

来源：由 `DatasourceResolver.buildSparkRef` 从 SPARK 数据源 `props_json` 解析（master/sparkHome/deployMode/queue/conf）+ 任务声明的 sparkMode/jar 资产/mainClass 合成。脚本体（.py/.sql）经 content 传递，执行器落临时文件。

---

## 3. SPARK 数据源（复用 datasources 表，零 schema）

| 列 | 取值 | 说明 |
|----|------|------|
| type_code | `'SPARK'` | 新增类型枚举值（数据层无约束变更，应用层 switch 增 case） |
| props_json | `{"sparkHome","master","deployMode","queue","conf":{...}}` | 提交配置载体（复用既有列） |
| host/port/database/username/password | 可空 | Spark 提交一般不需要；保留以备 thrift/连接型扩展 |

**环境隔离即漂移**：同一逻辑数据源**名**在本地 `datasources.local.yaml`（CLI 装配）配 `master: local[*]`、在服务端 `datasources` 记录配 `master: yarn`。无需新增"环境解析"逻辑——数据源本就环境隔离（原则 II）。

`DatasourceResolver.ResolvedConnection` 增 spark 槽位 + 工厂 `ResolvedConnection.spark(SparkSubmitRef-ish 字段)`；`resolve()` switch 增 `case "SPARK"`。

---

## 4. Spark 任务文件契约（.task.yaml）

| 字段 | 取值 | 说明 |
|------|------|------|
| type | `SPARK` | 任务类型 |
| sparkMode | `pyspark` \| `spark-sql` \| `jar` | 内容形态判别（FR-002） |
| 脚本体文件 | `<task>.py`（pyspark）/ `<task>.sql`（spark-sql） | 独立脚本体文件，复用现有脚本体分发（`TaskMapper.getScriptExtension` 增 SPARK→按 sparkMode 给扩展名） |
| jarAssetId / jarRef | 资产标识（jar 形态） | 引用上传的 application jar 资产（复用 `driver_jars` 资产链路；或独立 spark-jar 资产——见 contracts 决策） |
| mainClass | 主类全名（jar 形态） | `--class` 值 |
| datasource | SPARK 数据源逻辑名 | 绑定提交配置 |

**脚本扩展名**：`TaskMapper.getScriptExtension(type)` 当前按 type 返回扩展名；SPARK 需按 sparkMode 细分（pyspark→.py, spark-sql→.sql, jar→无脚本体）。这是 round-trip 保真点（参考 [[slug-roundtrip-fidelity]] 类似严谨度）。

---

## 5. Spark JAR 资产（复用 driver_jars 机制）

JAR 形态的 application jar 复用既有 `DriverJar` + `DriverJarStorage` + `IsolatedDriverLoader` 资产链路（上传/ACTIVE 状态/storageKey/隔离存储）。**MVP 边界**：本地 `dw run` 的 jar 走本地文件路径引用；服务端运行时按 storageKey 取 jar。是否需独立"spark-jar"资产类型 vs 直接复用 driver_jars，contracts 定（倾向复用，标记用途）。

---

## 6. distributed 下发体（exec body）— 增数据源序列化

distributed 修复需在 `/internal/worker/exec` body 中补：`taskType`（已有，待消费）+ **解析后连接信息**（SQL 的 DataSourceRef 字段 / SHELL 的 env / PYTHON 的 config / SPARK 的 SparkSubmitRef），由 `DistributedTaskExecutionGateway`（api 层，有 `DatasourceResolver`）解析后序列化，worker 侧 `WorkerExecController` 反序列化消费——**worker 不新增 DB 依赖**，与 all-in-one 对称。

---

## 关联记忆

- [[long-running-h2-workers-go-offline]]：distributed/心跳相关测试需注意 worker 离线导致卡 WAITING。
- [[h2-shared-mem-db-test-pollution]]：新增执行器/解析测试用独立库名防串台。
