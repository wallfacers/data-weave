# Tasks: 深化执行 —— Spark 协议 + runtime 语义对齐

**Feature**: `016-spark-runtime-parity` | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

**Input**: spec.md（3 US / 17 FR / 6 SC）· research.md（D1–D7）· data-model.md · contracts/（executor-and-dispatch / spark-task-file）· quickstart.md

**测试要求**：本特性测试是**强制**的（原则 III fidelity 不变量 + spec FR-016「无测试=未完成」）。测试任务与实现任务并列，非可选。

**路径约定**：后端模块根 `backend/dataweave-worker|master|api/src/main/java/com/dataweave/...`；CLI 根 `cli/`；Skill 根 `.claude/skills/weft-task-authoring/`。

---

## Phase 1: Setup（共享地基）

- [x] T001 在 `specs/016-spark-runtime-parity/` 确认设计包齐全（spec/plan/research/data-model/contracts/quickstart），作为实现唯一真相源；实现前通读 contracts/executor-and-dispatch-contract.md 全部 C1–C6 条款。
- [x] T002 [P] 勘查并记录现有四执行器（`ShellTaskExecutor`/`SqlTaskExecutor`/`PythonTaskExecutor`/`EchoTaskExecutor`）的 `ProcessBuilder` 起进程 + 逐行回调 + 超时中止范式（以 `ShellTaskExecutor` 为样板），SparkTaskExecutor MUST 复用同范式，不另起炉灶。

---

## Phase 2: Foundational（阻塞所有 US，必须先完成）

**目的**：SKIPPED 三态语义 + Spark 提交配置载体是 US1/US2/US3 共同依赖的运行时结构。

- [x] T003 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/domain/TaskExecutor.java` 给 `ExecutionResult` record 增 `boolean skipped` 字段（位置见 data-model §1），新增静态工厂 `ExecutionResult.skipped(String reason)`（success=false, skipped=true, exitCode=0, message=reason）；保留现有构造点编译兼容（逐一过审，补 skipped=false）。
- [x] T004 修 `backend/dataweave-worker/src/main/java/com/dataweave/worker/domain/AbstractTaskExecutor.java`：异常包装路径 skipped=false（异常是失败非跳过）。
- [x] T005 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/domain/ExecutionContext.java` 增可空字段 `SparkSubmitRef spark`（record 定义见 data-model §2：sparkHome/master/deployMode/queue/conf/sparkMode/jarPath/mainClass），补向后兼容构造（现有构造点不传 spark→null）。
- [x] T006 在 `frontend/messages/zh-CN.json` 与 `frontend/messages/en-US.json` 增 SKIPPED banner 文案 key `taskrun.banner.status.skipped`（两 bundle 同键集，CI 校验）；zh-CN「已跳过」、en-US「Skipped」。**注**：纯 message 文案，非前端视图改动。
- [x] T007 在 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/InProcessTaskExecutionGateway.java` 的 `run()` 增 SKIPPED 分流：`result.skipped()==true` → `reportService.reportFinished`（不阻塞下游）+ `emitEndBanner` 用 skipped 状态文案（按 locale，i18n 规则②），tail 含可辨识跳过标记。**不**新增 task_instance 状态枚举（FR-012）。

**Checkpoint**：T003–T007 编译通过（`./mvnw -q -pl dataweave-worker,dataweave-api compile`）后方可进 US。

---

## Phase 3: User Story 1 — Spark(PySpark) 本地真跑 + 服务端运行（P1）🎯 MVP

**目标**：开发者能创作 PySpark 任务，本地 `dw run` 真跑（local[*]）/ 无 Spark 则 SKIPPED，push 后服务端 yarn 真跑。
**独立测试**：写一个 pyspark `.task` → 本地 `dw run`（装 Spark 真跑 / 不装 SKIPPED）→ push → `dw run --test`。

### 实现

- [x] T008 [US1] 新建 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/SparkTaskExecutor.java`：`type()="SPARK"`，extends `AbstractTaskExecutor`；先实现**纯函数** `List<String> buildCommand(SparkSubmitRef ref, String scriptPath)`（按 contracts C1.1，**本任务先做 pyspark 分支**：spark-submit + master/deployMode/queue/conf 展开 + body.py）。
- [x] T009 [US1] 在 `SparkTaskExecutor` 实现 `doExecute`：pyspark 形态——脚本体 content 落临时 `.py` 文件 → `ProcessBuilder` 起 spark-submit 子进程（复用 T002 范式）→ 逐行 onLine 回调 + timeout 中止 + exitCode/stdout-stderr 忠实透传；执行后清理临时文件。
- [x] T010 [US1] 在 `SparkTaskExecutor` 实现 SKIPPED 判定（contracts C1.3）：spark ref 为 null / sparkHome 空 / `${sparkHome}/bin/spark-submit` 不存在 / master 空 → `ExecutionResult.skipped("已跳过：本地无 Spark 环境（...）")`。
- [x] T011 [P] [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/application/DatasourceResolver.java` 增 `case "SPARK" -> buildSparkRef(ds)`；`buildSparkRef` 从 `ds.getPropsJson()` 解析 `{sparkHome,master,deployMode,queue,conf}`（用注入的 ObjectMapper，解析失败不抛错、留空字段交执行器判 SKIPPED）；`ResolvedConnection` 增 spark 槽位 + 静态工厂 `ResolvedConnection.spark(...)`（data-model §3 / contracts C5）。
- [x] T012 [US1] 在 `InProcessTaskExecutionGateway.run()` 增 SPARK 装配：`resolveDatasource(datasourceId,"SPARK")` → 用 ResolvedConnection 的 spark 字段 + cmd 的 sparkMode/jar/mainClass 合成 `SparkSubmitRef` 放入 `ExecutionContext`。
- [x] T013 [P] [US1] 在 `backend/dataweave-master/src/main/java/com/dataweave/master/filecontract/mapping/TaskMapper.java` 的 `getScriptExtension`：type=SPARK 时按 sparkMode 返回扩展名（pyspark→`.py`），保证 push/pull round-trip 脚本体扩展名不漂移（contracts F2）。**本任务先做 pyspark**，spark-sql/jar 在 US3 补。
- [x] T014 [US1] 在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/localrun/LocalRunMain.java`：`selectExecutor` 增 `case "SPARK"`；`buildContext` 增 SPARK 分支（从 ds-json 读 SparkSubmitRef 字段，pyspark 脚本体经 content 落临时文件）。按需扩 `LocalRunArgs.java`（sparkMode 入参）。
- [x] T015 [P] [US1] 在 `cli/run/local.go` + `cli/run/datasource.go`：SPARK 类型本地 run 调起（透传 sparkMode），ds-json 装配 SPARK 字段（sparkHome/master/deployMode/queue/conf 从 datasources.local.yaml）；无 Spark→LocalRunMain 返回 SKIPPED，CLI 退出码**不作失败处理**（不报 6，参考 contracts F3）。

### 测试

- [x] T016 [P] [US1] 新建 `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/SparkTaskExecutorTest.java`：断言 pyspark `buildCommand` 命令构造正确（master/conf 展开顺序）；无 SPARK_HOME → skipped=true（非 success 伪装）；模拟作业失败 → exitCode≠0 且 skipped=false。不依赖真 Spark。
- [x] T017 [US1] 在 `backend/dataweave-worker/src/test/java/com/dataweave/worker/localrun/LocalRunMainParityTest.java` 扩展 SPARK：经 LocalRunMain 与经 new SparkTaskExecutor 在无 Spark 环境下对照"双方都 SKIPPED"（同实现→同 SKIPPED，parity 成立）。
- [x] T018 [P] [US1] 新建/扩展 `cli/run/run_local_test.go`：SPARK 本地 run（无 Spark→SKIPPED，退出码非失败）。
- [x] T019 [P] [US1] 在 `.claude/skills/weft-task-authoring/examples/` 加最小可跑 `sample-spark.task.yaml`（type=SPARK, sparkMode=pyspark）+ `sample-spark.py`（print + 简单 DataFrame）。

**Checkpoint**：US1 独立可测——pyspark 任务本地 SKIPPED/真跑 + parity 绿。

---

## Phase 4: User Story 2 — 跨模式语义一致 + 环境缺失忠实 SKIPPED（P2）

**目标**：distributed 选对执行器、无数据源→SKIPPED 非伪装、本地 runtime 类型集合与服务端一致。
**独立测试**：不涉 Spark——SQL distributed 选 SQL 执行器、无数据源→skipped、parity 覆盖 ECHO。

### 实现

- [x] T020 [US2] 修 `backend/dataweave-worker/src/main/java/com/dataweave/worker/application/WorkerExecService.java`：构造建 `byType` 映射（同 InProcessTaskExecutionGateway 逻辑）；`submit`/`executeSync`/`doRun` 增 `taskType` 入参，`resolveExecutor(taskType)` 按类型取（删除写死 "SHELL"/取第一个）；未知类型与 all-in-one 同样可辨识处理（不静默 SHELL、不伪装成功）。（contracts C4.1）
- [x] T021 [US2] 修 `backend/dataweave-worker/src/main/java/com/dataweave/worker/interfaces/WorkerExecController.java`：exec body 读 `taskType`（body 已含）透传给 `execService.submit`。
- [x] T022 [P] [US2] 修 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/SqlTaskExecutor.java`：三处 `[simulated]` 模拟成功（无数据源 / 连接失败 / 驱动加载失败）改为 `ExecutionResult.skipped("已跳过：<原因>")`（contracts C3 / data-model §1）。
- [x] T023 [US2] 修 `LocalRunMain.selectExecutor` 增 `case "ECHO" -> new EchoTaskExecutor()`，使本地 runtime 类型集合 = 服务端全集 SHELL/SQL/PYTHON/ECHO/SPARK（FR-010）。

### 测试

- [x] T024 [P] [US2] 修 `backend/dataweave-worker/src/test/java/com/dataweave/worker/infrastructure/SqlTaskExecutorTest.java`：把现有断言"无数据源→success"改为"→skipped=true"（且 success 非伪装）；连接失败 / 驱动加载失败同改。
- [x] T025 [P] [US2] 新建 `backend/dataweave-worker/src/test/java/com/dataweave/worker/application/WorkerExecServiceDispatchTest.java`：注入多执行器，断言 SQL 任务 `submit(...,taskType="SQL")` 选 SQL 执行器（非 SHELL）；PYTHON→PYTHON；未知类型可辨识处理；ctx 携带 taskType。
- [x] T026 [US2] 在 `LocalRunMainParityTest` 扩展 ECHO：经 LocalRunMain 与经 new EchoTaskExecutor exitCode/stdout-stderr/timeout 逐项相等。

### C4.2 数据源 over-wire（完成 FR-007 完整 ctx，紧随 C4.1）

- [x] T027 [US2] 修 `backend/dataweave-api/src/main/java/com/dataweave/api/infrastructure/DistributedTaskExecutionGateway.java`：调 `DatasourceResolver` 解析数据源 → 把解析后连接信息（SQL DataSourceRef / SHELL env / PYTHON config / SPARK SparkSubmitRef）序列化进 exec body（data-model §6 / contracts C4.2）。
- [x] T028 [US2] 修 `WorkerExecController` 反序列化 body 中的数据源信息 → 经 `WorkerExecService` 构建完整 `ExecutionContext`（taskType + 数据源/env/spark）；worker 不新增 DB 依赖。
- [x] T029 [P] [US2] 新建测试覆盖 C4.2：distributed exec body 含解析后连接 → worker 构建的 ctx 携带数据源（可用 WebTestClient 或直接对 WorkerExecController body 解析单测）。

**Checkpoint**：US2 独立可测——distributed 分发 + SKIPPED 保真 + ECHO parity 全绿（SC-003/004）。

---

## Phase 5: User Story 3 — Spark 三形态补全（spark-sql + jar）（P3）

**目标**：在 pyspark（US1）之上补 spark-sql 与 jar 两形态。
**独立测试**：spark-sql/jar 任务各自本地 `dw run` 真跑；jar 缺失→真实失败非 SKIPPED。
**依赖**：US1（SparkTaskExecutor 基座 + DatasourceResolver SPARK）。

### 实现

- [x] T030 [P] [US3] 新建 `backend/dataweave-worker/src/main/resources/spark/sql_runner.py`（决策 A1 / contracts C2）：读 argv[1] 的 .sql → 朴素分号切分 → 逐句 `spark.sql(stmt)` → 异常即 stderr+非0退出，全成功 exit 0。
- [x] T031 [US3] 在 `SparkTaskExecutor.buildCommand` 增 spark-sql 分支：脚本体落临时 `.sql` + 从 classpath 释放 sql_runner.py 到临时路径 → `spark-submit [confs] <sql_runner.py> <body.sql>`（contracts C1.1）。
- [x] T032 [US3] 在 `SparkTaskExecutor.buildCommand` 增 jar 分支：`spark-submit [confs] --class <mainClass> <jarPath>`；`doExecute` jar 形态校验 jarPath 存在/可读，缺失→**真实失败**（非 SKIPPED，contracts C1.4）。
- [x] T033 [P] [US3] `TaskMapper.getScriptExtension`：SPARK + sparkMode=spark-sql→`.sql`；jar→无脚本体（round-trip 保真，contracts F2）。
- [x] T034 [US3] jar 资产引用：本地 `dw run` jar 走本地文件路径（LocalRunMain buildContext jar 分支读 jarPath）；服务端运行按 storageKey 取（复用 driver_jars 链路，data-model §5）。`LocalRunMain` 补 spark-sql + jar 两分支。

### 测试

- [x] T035 [P] [US3] 扩展 `SparkTaskExecutorTest`：spark-sql 命令构造（含 sql_runner.py）；jar 命令构造（--class）；jar 缺失→真实失败（exitCode≠0, skipped=false）。
- [x] T036 [P] [US3] 扩展 `cli/run/run_local_test.go`：spark-sql / jar 形态本地 run（无 Spark→SKIPPED）。

**Checkpoint**：三形态命令构造 + 失败/跳过区分全绿（SC-005）。

---

## Phase 6: Polish & 收尾（跨切面）

- [x] T037 [P] 扩 `.claude/skills/weft-task-authoring/SKILL.md`：补 Spark 创作段（三 sparkMode 何时用、SPARK 数据源配法、jar 资产引用、SKIPPED 怎么读）。
- [x] T038 [P] 扩 `.claude/skills/weft-task-authoring/file-contract.md`：补 Spark 任务文件契约速查（contracts F1）。
- [x] T039 [P] 更新 skill_lint（`cli/sync/skill_lint_test.go` 或 skill 自省）：Spark 示例引用的 dw 命令/flag 真实存在，防文档漂移。
- [x] T040 [P] 更新 `docs/architecture.md` §8 演进路线：worker 多协议补齐 Spark（SQL/Spark/Python 全到位）；MCP/CLI 小节按需提及 SPARK 类型。
- [x] T041 全量门禁：`cd backend && ./mvnw -q -pl dataweave-worker,dataweave-master,dataweave-api compile`（零错）+ 受影响测试全绿（setsid 脱离，见 quickstart 第 0 层）；`cd cli && go build ./... && go test ./...`（全绿）。验证既有任务类型无回归（SHELL/SQL/PYTHON/ECHO 现有测试仍绿）。
- [x] T042 更新记忆 `[[track1-spark-and-runtime-parity]]`：标注 016 实现完成 + 实测遗留观察（如有）。

---

## Dependencies & 执行顺序

```
Setup(T001-T002)
  └─> Foundational(T003-T007) ──阻塞所有 US──┐
        ├─> US1(T008-T019, P1) 🎯MVP         │ US1 与 US2 互相独立（US2 不需 Spark）
        ├─> US2(T020-T029, P2)               │ 可并行推进
        └─> US3(T030-T036, P3) ──依赖 US1 基座（SparkTaskExecutor/DatasourceResolver SPARK）
              └─> Polish(T037-T042)
```

- **Foundational 是硬阻塞**：T003（ExecutionResult.skipped）/ T005（SparkSubmitRef）/ T007（gateway SKIPPED 分流）不完成，US 无法编译。
- **US1 ⟂ US2**：US2 全程不碰 Spark，可与 US1 并行（不同文件为主；交叉点 LocalRunMain 由 T014/T023 分别加分支，注意合并顺序）。
- **US3 依赖 US1**：复用 SparkTaskExecutor 与 SPARK 解析。

## Parallel 机会（[P] 标记）

- Foundational 内：T006（i18n 文案）与 T003/T005（Java 结构）不同文件可并行。
- US1 内：T011（DatasourceResolver）/ T013（TaskMapper）/ T015（CLI）/ T016（执行器测试）/ T018（CLI 测试）/ T019（示例）相互独立可并行。
- US2 内：T022（SqlExecutor 迁移）/ T024（SqlExecutor 测试）/ T025（dispatch 测试）多文件可并行。
- US3 内：T030（sql_runner）/ T033（TaskMapper）/ T035/T036（测试）可并行。

## MVP 范围建议

**MVP = Setup + Foundational + US1**（T001–T019）：交付 Spark pyspark 端到端（本地真跑/SKIPPED + 服务端 yarn），是能独立 demo 的最小垂直切片。US2（语义对齐修复）虽 P2 但独立价值高（修潜在缺陷），建议紧随。US3（spark-sql+jar）增量补全。

## 格式校验

所有任务遵循 `- [x] T### [P?] [US?] 描述 + 文件路径` 格式：Setup/Foundational/Polish 无 [US] 标签，US 阶段任务带 [US1]/[US2]/[US3]，[P] 仅标不同文件无依赖项。
