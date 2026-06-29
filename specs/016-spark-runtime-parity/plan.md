# Implementation Plan: 深化执行 —— Spark 协议 + runtime 语义对齐

**Branch**: `016-spark-runtime-parity` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-spark-runtime-parity/spec.md`

## Summary

新增 SPARK 任务类型，以 **spark-submit 子进程**方式执行（与 Shell/Python 执行器同构，忠实透传 exitCode/stdout-stderr/timeout），覆盖 PySpark / Spark SQL / JAR 三种内容形态，统一单一提交路径；集群提交配置由 **SPARK 类型数据源**（复用 `Datasource.propsJson`，零 schema 变更）承载并因数据源天然环境隔离而实现"本地 local[*] / 服务端 yarn 同一任务文件零改动漂移"。同时收口三处执行语义裂缝：① 修 distributed 路径 `WorkerExecService` 写死 SHELL/忽略 taskType 的分发缺陷；② 引入 **SKIPPED 保真态**（`ExecutionResult.skipped`），把"环境缺失→伪装成功"改为可辨识跳过，不阻塞调度、不新增状态机状态；③ 补 `LocalRunMain` 的 ECHO + SPARK 使本地 runtime 类型集合与服务端一致。全部由原则 III fidelity 不变量约束、`LocalRunMainParityTest` 等测试钉死。**无前端改动。**

## Technical Context

**Language/Version**: Java 25 / Spring Boot 4（worker + master + api 执行层）、Go 1.2x（`dw` CLI 本地 runtime 调起）、Markdown（Skill）。

**Primary Dependencies**: 既有 worker 执行器框架（`TaskExecutor`/`AbstractTaskExecutor`/`ExecutionContext`/`ExecutionResult`）、`InProcessTaskExecutionGateway`（all-in-one，已正确按 type 分发，作对齐基准）、`WorkerExecService`（distributed，待修）、`DatasourceResolver` + `Datasource`（数据源解析）、`DriverJar`/`DriverJarService`/`IsolatedDriverLoader`/`DriverJarStorage`（jar 资产上传/隔离存储，JAR 形态复用）、`LocalRunMain`（本地 runtime）。外部运行时依赖 **Spark 发行版（spark-submit）**——非构建依赖，按 SPARK_HOME 探测；缺失即 SKIPPED。

**Storage**: 零 schema 变更。SPARK 数据源复用既有 `datasources` 表（`type_code='SPARK'` + `props_json` 承载 SPARK_HOME/master/deployMode/queue/confs）。JAR 资产复用既有 `driver_jars` 表 + 存储后端。

**Testing**: JUnit5 + AssertJ（执行器单测、distributed 分发单测、SKIPPED 语义、`LocalRunMainParityTest` 扩展）；Go `go test`（CLI 本地 SPARK run 路径 + ds-json 装配）；H2 in-memory（`@ActiveProfiles("h2")`，零外部依赖，Spark 在 CI 表现为 SKIPPED）。

**Target Platform**: 开发者本地（Linux/macOS/WSL2，可选装 Spark）+ 后端 JVM（all-in-one / distributed）。

**Project Type**: backend（多模块 DDD）+ CLI（Go）+ Skill（Markdown）。无 web 前端。

**Performance Goals**: N/A（正确性/保真特性，非性能特性）。spark-submit 启动开销由 Spark 自身决定，不在本特性优化范围。

**Constraints**: 不触 PolicyEngine L0–L4 授权/审计内核；不改同步 API 契约；不改前端；统一 spark-submit 子进程**不引入嵌入式 SparkSession**（不把 spark-core 拉进 worker classpath）；SKIPPED **不新增调度状态机状态**；distributed 修复以 all-in-one `byType` 实现为正确基准。

**Scale/Scope**: 1 个新执行器（三 sparkMode）+ 1 个 SPARK 数据源解析分支 + `ExecutionResult`/`ExecutionContext` 字段扩展 + `WorkerExecService` 分发修复 + `LocalRunMain` 两类型补全 + 文件契约/Skill/CLI 对齐 + 配套测试。

## Constitution Check

*GATE: Phase 0 前通过；Phase 1 设计后复检。*

| 原则 | 评估 | 结论 |
|------|------|------|
| **I. Files-First** | Spark 任务仍是文件（`.task.yaml` + 脚本体 `.py`/`.sql` 独立文件 / jar 资产引用）；文件契约文档化 `sparkMode` 与资产引用语义。 | PASS（强化） |
| **II. Server is Source of Truth** | SPARK 数据源走既有 pull/push 与数据源解析；"环境漂移"靠数据源本就环境隔离实现，不改 push 幂等/快照语义。 | PASS |
| **III. Two-Legged Debugging（NON-NEGOTIABLE）** | SparkTaskExecutor 与服务端同一实现，本地经 `LocalRunMain` 子进程复用，**不分叉第二引擎**；exitCode/stdout-stderr/timeout 逐项相等由 parity 测试钉死。distributed 分发修复 + SKIPPED 统一正是 fidelity 不变量的兑现。 | PASS（兑现 fidelity） |
| **IV. AI Lives in the Local Agent（NON-NEGOTIABLE）** | 创作仍由本地 agent + Skill + `dw` 承载，无服务端 AI；本特性只扩执行协议与 Skill 知识，不动 agent 归位。 | PASS |
| **V. Reuse the Kernel** | 复用 `DatasourceResolver`/`Datasource`/`DriverJar` 资产/隔离加载/`InProcessTaskExecutionGateway` 分发范式，非重写；写/执行路径仍过既有治理。 | PASS |

**结论**：无违规需 Complexity Tracking 记录。统一 spark-submit（而非嵌入式）是**降低**复杂度与依赖面的选择，非新增复杂度。

## Project Structure

### Documentation (this feature)

```text
specs/016-spark-runtime-parity/
├── spec.md              # 已完成（3 US / 17 FR / 6 SC）
├── plan.md              # 本文件
├── research.md          # Phase 0 输出（spark-submit 调用矩阵 + SKIPPED 建模 + distributed 对齐决策）
├── data-model.md        # Phase 1 输出（SPARK 数据源 / ExecutionResult.skipped / Spark 任务文件契约 / jar 资产引用）
├── contracts/           # Phase 1 输出（执行器行为契约 + SPARK 数据源解析契约 + Spark 任务文件契约 + CLI run 契约）
├── quickstart.md        # Phase 1 输出（三层验证：单测 / 本地真跑 / 服务端 TEST）
└── checklists/
    └── requirements.md  # 已完成（16/16）
```

### Source Code (repository root)

```text
backend/dataweave-worker/src/main/java/com/dataweave/worker/
├── infrastructure/
│   └── SparkTaskExecutor.java            # 新增：type()="SPARK"，三 sparkMode 拼 spark-submit 子进程
├── domain/
│   ├── TaskExecutor.java                 # 改：ExecutionResult 增 boolean skipped（+ 兼容工厂/语义）
│   └── ExecutionContext.java             # 改：增 SparkSubmitRef（SPARK_HOME/master/deployMode/queue/confs）+ sparkMode/jar 资产引用
├── application/
│   └── WorkerExecService.java            # 改：resolveExecutor 按 taskType 分发（镜像 byType）+ 构建完整 ExecutionContext
└── localrun/
    ├── LocalRunMain.java                 # 改：selectExecutor 补 ECHO + SPARK；buildContext 增 SPARK 分支（ds-json→SparkSubmitRef）
    └── LocalRunArgs.java                 # 改（按需）：SPARK 入参（sparkMode、jar 路径）

backend/dataweave-worker/src/main/resources/
└── spark/sql_runner.py                   # 新增：决策 A1 的极小 pyspark SQL-runner（spark.sql 逐句执行 .sql 文件）

backend/dataweave-master/src/main/java/com/dataweave/master/application/
└── DatasourceResolver.java               # 改：增 case "SPARK" → buildSparkRef；ResolvedConnection 增 spark 字段 + 工厂

backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/
└── InProcessTaskExecutionGateway.java    # 改：SPARK 分支装配 SparkSubmitRef 入 ExecutionContext；SKIPPED banner 呈现

.claude/skills/weft-task-authoring/
├── SKILL.md                              # 改：补 Spark 创作知识（type/sparkMode/数据源/jar 资产）
├── file-contract.md                      # 改：补 Spark 任务文件契约
└── examples/
    └── sample-spark.task.yaml + .py      # 新增：最小可跑 Spark 示例

cli/run/
├── local.go                              # 改：SPARK 类型本地 run 调起（DW_WORKER_CP + LocalRunMain）
└── datasource.go                         # 改（按需）：SPARK 数据源 ds-json 装配（SPARK_HOME/master/confs）

# 测试
backend/dataweave-worker/src/test/java/com/dataweave/worker/
├── infrastructure/SparkTaskExecutorTest.java    # 新增：三 sparkMode 命令构造 + 透传 + 超时 + SKIPPED
├── application/WorkerExecServiceDispatchTest.java # 新增：按 taskType 选对执行器 + 完整 ctx
├── infrastructure/SqlTaskExecutorTest.java       # 改：无数据源→skipped=true（非伪装成功）
└── localrun/LocalRunMainParityTest.java          # 改：扩展 ECHO + SPARK parity
cli/run/run_local_test.go                          # 改：SPARK 本地 run（含无 Spark→SKIPPED）
```

**Structure Decision**: 多模块后端（worker 执行层为主，master 数据源解析 + api 组合根装配）+ Go CLI 本地 runtime + Markdown Skill。SparkTaskExecutor 落 worker/infrastructure（与既有四执行器同位）；SQL-runner 作 worker 资源随 jar 分发；SPARK 数据源解析落 master（与 SQL/SHELL/PYTHON 解析同位）。无前端。

## Complexity Tracking

> 无 Constitution 违规需记录。统一 spark-submit 子进程相较嵌入式 SparkSession 是更低复杂度/更小依赖面的选择；SKIPPED 不新增状态机状态以保持有界。
