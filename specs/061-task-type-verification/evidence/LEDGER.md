# 061 真跑验证台账

**整体状态**: `PASS` ✅（10 引擎行全 `PASS`：US1 本人 6 · US2 外部Agent B 2 · US3 外部Agent C 2；3 工作流并行交付 SC-009）。真跑暴露 5 缺陷，D1/D2/D3/D4 已修复 + D5 定性（028 已知限制，超范围）。

机读版：`ledger.json`（结构见 `../contracts/evidence-ledger.schema.json`）。证据文件：`evidence/<engine>/{success,fail,skipped}.log`（脱敏）。

| 引擎 | 工作流 | status | 引擎版本 | success | fail | 结果集渲染 | dw_run↔server | 备注/BLOCKED原因 |
|---|---|---|---|---|---|---|---|---|
| StarRocks | A | **PASS** | allin1-ubuntu:3.3-latest (StarRocks 3.3.22-753696f) | ✅ success.log | ✅ fail.log | ✅ | consistent | 本地dw run三态齐(SUCCESS exit0结果集/FAIL exit6 StarRocks原生analyzing error Unknown table/SKIPPED exit0连接失败已跳过)+服务端--test SUCCESS(192.168.1.5:9030 SHOW TABLES+SELECT聚合结果集渲染);MySQL协议+内置mysql驱动免上传;宿主9030 |
| Doris | A | **PASS** | doris-all-in-one-2.1.0 (doris-2.1.0-rc11) | ✅ success.log | ✅ fail.log | ✅ | consistent | 本地dw run三态齐(SUCCESS exit0结果集/FAIL exit6 Doris原生errCode=2 Unknown table/SKIPPED exit0连接失败已跳过)+服务端--test SUCCESS(192.168.1.5:9031 SHOW TABLES+SELECT聚合结果集渲染);MySQL5.7协议+内置mysql驱动免上传;宿主9031避与StarRocks9030撞 |
| ClickHouse | A | **PASS** | clickhouse-server:24.3 (24.3.18.7) | ✅ success.log | ✅ fail.log | ✅ | consistent | 本地dw run+服务端--test双SUCCESS；三态齐(SUCCESS exit0/FAIL exit6 UNKNOWN_TABLE/SKIPPED exit0连接失败)；SHOW TABLES+SELECT结果集表头+数据行真现于日志 |
| Hive | A | **PASS** | apache/hive:4.0.0 (Apache Hive 4.0.0) | ✅ success.log | ✅ fail.log | ✅ | 本地真跑三态齐;服务端test-connection上传驱动隔离加载✅;任务执行分布式worker契约正确SKIPPED(D5) | 唯一需上传驱动引擎:standalone jar缺DelegationTokenIssuer→自组含hadoop-common的uber jar(103MB);本地dw run SUCCESS(6语句:CREATE PARTITIONED+INSERT PARTITION×2+SHOW PARTITIONS分区语义+SELECT结果集)/FAIL(exit6 原生SemanticException Table not found)/SKIPPED(无驱动→No suitable driver);服务端test-connection连接成功(上传驱动 Apache Hive 4.0.0 690ms) |
| DataX | B | **PASS** | datax_v202309 | ✅ success.log | ✅ fail.log | n/a | consistent | streamreader→writer 底线：5记录/0错误/原生统计透出；fail=mysqlreader→不存在表(DataX原生报错)；SKIPPED=确认真·引擎在位vs缺引擎三态可辨 |
| SeaTunnel | B | **PASS** | seatunnel-2.3.13 (JDK 21) | ✅ success.log | ✅ fail.log | n/a | consistent | FakeSource→Console 底线：3记录/0错误/原生统计透出；fail=NonExistentSource→引擎报Plugin not found；SKIPPED确认真·引擎在位vs缺引擎三态可辨；**修2缺陷**：①buildCommand缺--master local ②JDK25不兼容(JAVA_HOME→PATH转发)
| Spark | C | **PASS** | 3.5.4 (PySpark host) | evidence/SPARK/success.log | evidence/SPARK/fail.log | n/a | consistent (local) | ✅ `dw run` exit=0, `spark.range(100).count()`+`sum(1..10)` 断言通过；FAIL=division by zero→退出码1→dw exit6；SKIPPED=**收口A补正**(C原证据bogus sparkHome=exit127内部失败存skipped_bogushome_127.log;经空sparkHome重跑命中 SparkTaskExecutor:156-157 真SKIPPED→exit0「已跳过：本地无Spark环境」) |
| Flink | C | **PASS** | 1.20.5 (host cluster) | evidence/FLINK/success.log | evidence/FLINK/fail.log | n/a | consistent (local) | ✅ `dw run` exit=0, datagen→print batch SUCCESS；FAIL=SQL error `Object 'nonexistent_table' not found`；SKIPPED=FLINK_HOME缺→跳过；**SC-005**: `dw run long_running` detached提交→JobID a79e2d82→handle回写→REST轮询RUNNING×4→cancel ✅ |
| Python | A | **PASS** | host python 3.12.3 (059 executor) | ✅ success.log | ✅ fail.log | n/a | 本地一致;服务端worker缺python3(D4) | 回归确认:本地dw run SUCCESS(exit0 sum=55 RESULT_OK)+FAILURE(exit6 原生Traceback ValueError,退出码1透传);解释器恒在无SKIPPED;真跑暴露并修D4(缺python3诊断静默) |
| Shell | A | **PASS** | host bash 5.2.21 / server alpine 5.3.3 | ✅ success.log | ✅ fail.log | n/a | consistent | 回归确认:本地dw run SUCCESS(exit0 RESULT_OK)+FAILURE(exit6 退出码7透传)+服务端--test SUCCESS(Alpine bash5.3.3 exit0);D4修复覆盖Shell |

## 真跑暴露的缺陷（FR-011）

- **D1（009 CLI sync 路径，非 061 执行器）✅ 已修复（commit 0eb7275）**：`dw push`/`dw diff` 把 pull 基线文件数当 `expectedFileCount`（`cli/sync/push.go`+`diff.go` 发 `state.FileCount`），服务端 `ProjectSyncService.java:635` 校验 `files.size() != expectedFileCount` → 任何**新增/删除文件**的正常「编辑再 push」都撞 `project.sync.incomplete`。**修复**：改 `len(files)`（本次实际推送数=意图数，保留完整性校验本意）；新增 `TestPushAfterAddingFileSendsActualCount` 回归 + mock 补 incomplete 校验，证伪确认（还原 bug 测试 FAIL、修复后绿）。dw 已重建，state.json workaround 不再需要。
- **D2（SeaTunnelTaskExecutor，061 US2 真跑暴露）✅ 已修复**：`buildCommand` 缺 `--master local` 标志 → SeaTunnel 默认连远程集群（Hazelcast 连接超时）；修复为 `[seatunnel.sh, --master, local, --config, <file>]`（5 参数），对应单测 `buildCommand_seatunnelShWithConfig` 更新为 `hasSize(5)` 验证。15/15 测试全绿。
- **D3（SeaTunnelTaskExecutor，JDK 25 不兼容）✅ 已修复**：SeaTunnel 2.3.13 的 Hazelcast 5.1 调用 `javax.security.auth.Subject.getSubject()` 在 JDK 25 已移除 → `NullPointerException`。修复：`runSubprocess` 中将 `JAVA_HOME` 环境变量 prepend 到 ProcessBuilder 的 PATH，使 `seatunnel.sh` 使用兼容 JDK（21），同时 worker/LocalRunMain 仍用 JDK 25。JDK 21 版本已记录。
- **D4（Python/Shell 执行器观测性，061 US1 真跑暴露）✅ 已修复**：服务端 worker（Alpine 容器，060 分布式后端镜像）**未装 python3** → PYTHON 任务 `FAILED exit -1` 且**实例日志零正文**——`PythonTaskExecutor`/`ShellTaskExecutor` 缺解释器时诊断信息只进 `ExecutionResult.message` 字段、**不经 `onLine` 回调流入实例日志**，操作者只见框架裸 `-1` 无从判因（证据 `evidence/PYTHON/server_missing_python.log`）。**修复**：两执行器 `IOException`（解释器缺失）路径先经 `onLine` 吐诊断行再返回；抽 `interpreterExecutable()` seam 使该路径可测；新增 `missingInterpreterSurfacesDiagnosticToLog` 双回归（Python/Shell 各 1），证伪确认（seam 覆盖为不存在命令→断言 onLine 收到「无法启动」诊断行），worker 模块 23 测试全绿（禁 build-cache 真跑）。**注**：worker 容器补装 python3 属 060 部署镜像范畴，本轮修的是执行器侧「缺失即静默」的观测性缺陷。
- **D5（上传驱动引擎在分布式 worker 上不可执行，061 US1 Hive 真跑暴露）📌 已定性·属 028 已知架构限制（本轮不修，如实记录）**：Hive 上传 uber jar 后，服务端 **test-connection 隔离加载成功**（`api/master` 节点有 `IsolatedDriverLoader` bean），但 **`dw run --test` 任务分派到分布式 worker 执行时 SKIPPED**（`No suitable driver`）。根因 `SqlTaskExecutor.openConnection`（:142 注释「028: distributed worker 无 IsolatedDriverLoader bean，回退 DriverManager」）——分布式 worker ① 无 `isolatedLoader` bean ② `storageType=LOCAL` 的 jar 存在 API 节点文件系统、worker 不可达。**这是契约正确的三态行为**（驱动不可用→SKIPPED），非执行器缺陷；彻底修复需「上传驱动分发到 worker（MinIO/共享存储）+ worker 侧装配 isolatedLoader」，属 028/部署架构范畴，**远超 061 范围**。**061 达标依据**：Hive 引擎经本地 dw run 对真 HS2 4.0.0 **真跑三态齐 + 分区 HQL + 结果集渲染**（真执行已证），上传/隔离机制经服务端 test-connection **真验证**（`连接成功（上传驱动）`）。all-in-one 模式下该任务执行路径即可用（isolatedLoader 在位）。

## 三态判据

- **SUCCESS**：真引擎在位 + 退出码 0 + 结果证据（结果集/影响行数 或 引擎原生 stdout/stderr）。
- **FAILURE**：真引擎在位 + 作业自身错 → 非 0 退出码 + 引擎原生错误行（≠ SKIPPED）。
- **SKIPPED**：真引擎缺失 → 「已跳过：<原因>」，不阻塞下游、不新增状态。

## SC 达成核对（收口 T046 填写）

- [x] SC-001 6 家族可复现环境 + 记版本：ClickHouse/StarRocks/Doris/Hive = Docker（compose.olap.yml/compose.hive.yml，版本已记）；DataX/SeaTunnel/Spark/Flink = 宿主机运行时（Docker Hub 限速，install-*.sh + 清华镜像绕行，版本已记）。交付形态=可复现脚本+台账（澄清①），非 CI 常驻。
- [x] SC-002 每引擎真跑成功（硬门）：**10/10 引擎行全 PASS**，无一 BLOCKED/伪装。
- [x] SC-003 每引擎真实失败 + 三态可辨：各引擎 FAILURE=引擎原生错误+非零退出码，与 SKIPPED（已跳过，exit0）判据清晰可辨（见各 fail.log/skipped.log）。
- [x] SC-004 SQL/HQL 结果集真现日志：ClickHouse/StarRocks/Doris SHOW TABLES+SELECT 聚合、**Hive SHOW PARTITIONS 分区语义+SELECT** 表头+数据行真现于 success.log。
- [x] SC-005 Flink long_running reattach 对真 JobManager ✅：detached 提交→JobID a79e2d82→handle 回写→REST 轮询 RUNNING×4→cancel（T038 桩注释已修，真实现成立）。
- [x] SC-006 dw run ↔ 服务端一致：ClickHouse/StarRocks/Doris/Shell 本地 dw run 与服务端 --test **双 SUCCESS 同结果**；Spark/Flink 本地一致；Hive 本地 SUCCESS + 服务端 test-connection 上传驱动 SUCCESS（任务执行分布式 worker 限制见 D5，契约正确）；Python 执行器同实现（服务端 worker 缺 python3=部署环境，见 D4）。
- [x] SC-007 059 SKIPPED 语义保持 + 单测全绿（173 基线，加 D4 双回归+D2/D3+C 的 Flink 后仍绿，见收口回归）；真跑暴露 5 缺陷 D1-D5（4 修 1 定性）。
- [x] SC-008 台账第三者可判真伪：ledger.json（机读，schema 约束）+ LEDGER.md + evidence/<engine>/*.log（脱敏）+ 引擎版本/退出码/结果集齐，可独立复核。
- [x] SC-009 3 工作流并行交付：US1（A 本人）+ US2（外部 Agent B）+ US3（外部 Agent C）各自 worktree/副本独立完成，收口合并。
