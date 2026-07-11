# 061 外部 Agent 分派提示词

两条并行工作流分派给两个外部 Agent；本人自留 US1（OLAP+Hive，P1 风险最高）。派活前置：本人已完成 Setup+Foundational（harness 骨架、`net.yml`、`up.sh`/`down.sh`/`capture.sh`、`evidence/ledger.json`+`LEDGER.md`、059 保真基线绿），并已 commit 到分支 `061-task-type-verification`——两 Agent 从该分支建各自 worktree。

**共同铁律（两个提示词都含）**：
- **证伪优先·绝不冒充**：只有连真实引擎、真提交、真产出成功证据链（日志+结果/退出码 0）才算 PASS。单测绿、SKIPPED 正确、假替身脚本一律**不算** SUCCESS。起不来是阻塞项，须解决（加内存/换镜像/换接入）；穷尽仍不可行 → 台账记 `BLOCKED` + 原因 + 已尝试手段（计未达标），**严禁伪造/标注冒充**。撒谎式「已跑通」是最严重违规。
- **worktree 隔离**：`git worktree add ../dw-061-<b|c> -b 061-task-type-verification-<b|c>`（基线 `061-task-type-verification`）。只改自己那批 `compose.<family>.yml` / `clients/` / `jobs/` / `scripts/verify-*`；**不碰他人文件、不覆盖他人产出**（仓库多 Agent 硬规则）。共享只读面（`net.yml`/台账格式/任务类型枚举/`dw` 用法）如需改，先停下报本人对齐。
- **错峰 Docker**：只起自己 profile，跑完 `./scripts/down.sh <profile>`；禁全量常驻（单台 WSL2 防 OOM）。
- **WSL2 长命令必 `setsid` 脱离**（maven/test），单次秒回轮询，禁前台 sleep 循环。
- **后端 all-in-one 已由本人起在宿主机 :8000**（`dw run` 复用真执行器子进程，保原则 III）；`export DW_API=http://localhost:8000 DW_TOKEN=<本人给>`；CLI 已构建 `cli/dw`。
- **三态硬门每引擎必过**：SUCCESS + 真实 FAILURE（作业自身错，非缺引擎）+ SKIPPED 对照（停引擎跑同 success 夹具→已跳过）。
- **证据**：每次真跑 → `verification/061-task-types/scripts/capture.sh <engine> <success|fail|skipped> <exit> <rawlog> <version> [run_via] [extra_json]` → 落 `specs/061-task-type-verification/evidence/<engine>/` + upsert `ledger.json`；写 `LEDGER.md` 行。
- **缺陷即修**：真跑暴露执行器 bug → 外科式修 + 补/更新对应单测（`*TaskExecutorTest`）+ 重跑取证 + 台账记 defects。改后 `cd backend && ./mvnw -q -pl dataweave-worker compile` 零错再继续。
- **完成即报**：逐引擎报 SUCCESS/FAILURE/SKIPPED 三态证据路径、引擎版本、`dw run↔--test` 一致性、暴露/修复的缺陷；未通过项如实标 BLOCKED。

---

## 提示词 A —— 外部 Agent 1（工作流 B：DataX + SeaTunnel，US2，任务 T019–T029）

> 你是数据集成引擎真跑验证的执行 Agent。任务：证明 Weft 的 `DATAX` 与 `SEATUNNEL` 任务类型能以子进程提交**真实安装的引擎**、真搬一份最小 source→sink 数据、真透引擎原生日志与退出码——不是「缺引擎时优雅跳过」（那已被单测覆盖），而是**真引擎在位时真能干活**。
>
> **背景**：059 给平台加了 `DataXTaskExecutor`/`SeaTunnelTaskExecutor`（`verification` 无关，源码在 `backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/`），走「子进程 + `DATAX_HOME`/`SEATUNNEL_HOME` 探测」范式，但从未对真实引擎跑通过。规格全文 `specs/061-task-type-verification/`（先读 spec.md 的 US2 + Edge Cases、plan.md 分工表、tasks.md T019–T029、research.md 决策 B1/B2、quickstart.md）。
>
> **你的交付（对齐 tasks T019–T029）**：
> 1. `git worktree add ../dw-061-b -b 061-task-type-verification-b`，全部改动在此。
> 2. `verification/061-task-types/clients/install-datax.sh`：下载 DataX tarball 装宿主机 `DATAX_HOME=/opt/datax`，自检 `python bin/datax.py`。DataX 官方需 Python2.7——**先解决可运行性**：JDK25+Python3 不行则用社区镜像/Python2 旁路，但**仍须真 DataX**（不可用假脚本）。版本记台账。
> 3. `clients/install-seatunnel.sh`：装 SeaTunnel `SEATUNNEL_HOME=/opt/seatunnel`（Zeta local `-e local`）+ 内置 `connector-fake`/`connector-console`（jdbc 进阶再加 `connector-jdbc`+驱动）。
> 4. `compose.integration.yml`（profile `integration`）：**连既有 backend `dataweave-mysql`(localhost:3307) 作源，不重定义同名容器**；目标汇复用 A 的数仓或新增独立 sink（端口/容器名不冲突）；建 source→sink 种子表。引用 `net.yml`（`external: true`）。
> 5. 夹具 `jobs/datax.success.json`（`streamreader→streamwriter` 免外部依赖底线）+ `jobs/datax.mysql2sink.json`（进阶）+ `jobs/datax.fail.json`（reader 指不存在表）；`jobs/seatunnel.success.conf`（`FakeSource→Console` HOCON 底线）+ 进阶 jdbc→jdbc + `jobs/seatunnel.fail.conf`。
> 6. `scripts/verify-datax.sh` / `verify-seatunnel.sh`：设 `*_HOME` → 用 `dw`（先 `dw pull`/编辑或直接建任务，走既有 push 闸门）建 DATAX/SEATUNNEL 任务 → `dw run` 跑 success/fail + `dw run --test`（服务端）→ `capture.sh` 取证。断言：引擎**原生统计/同步日志逐行透出**、退出码忠实透传、`dw run` 与 `--test` 结果分类一致。
> 7. SKIPPED 对照：清 `DATAX_HOME`/`SEATUNNEL_HOME` 跑同 success 夹具 → 「已跳过：<原因>」不阻塞下游 → `capture.sh ... skipped`。
> 8. 缺陷即修 + 补测（`DataXTaskExecutorTest`/`SeaTunnelTaskExecutorTest`）+ 重跑。
> 9. 写 DATAX/SEATUNNEL 两行台账。
>
> **底线**：DataX 的 Python 依赖是主要坑——先用 `streamreader→streamwriter` 拿到「引擎真在位+真提交+原生日志」的 SUCCESS 底线，再上 mysql→sink 进阶。真起不来就 `BLOCKED` 如实记，别糊。跑完 `./scripts/down.sh integration`。

---

## 提示词 B —— 外部 Agent 2（工作流 C：Spark + Flink，US3，任务 T030–T040）

> 你是计算/流式引擎真跑验证的执行 Agent。任务：证明 Weft 的 `SPARK` 与 `FLINK` 任务类型能对**真实 Spark/Flink 集群**真提交、真出日志、真透退出码；尤其证明 **Flink `long_running` 流式作业的 detached 提交 + JobID 回写 + reattach 对真实 JobManager 轮询到真终态**（060 刚从桩转真实现，这是最需要真机证据的一环）。
>
> **背景**：059 加了 `FlinkTaskExecutor`/`SparkTaskExecutor`（`backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/`）。Spark 历史仅以「假 spark-submit 替身」跑过（016 教训），真集群从未证明——**本轮必须真 Spark**。规格全文 `specs/061-task-type-verification/`（先读 spec.md US3 + Edge Cases、tasks.md T030–T040、research.md 决策 C1/C2、quickstart.md）。
>
> **⚠️ 关键核实点（T038）**：`FlinkTaskExecutor.executeLongRunning` 源码注释（≈line 164）仍写「桩实现」，但 `FlinkJobStatusFetcher.http()` 已存在（060 去桩）。SC-005 真跑要么**证明 reattach 链路真成立**（→ 修订过期注释），要么**暴露注释/实现不一致的真缺陷**（→ 修复 + 补 `FlinkTaskExecutorTest` + 重跑）。用真机结果裁决，别信注释也别信记忆。
>
> **你的交付（对齐 tasks T030–T040）**：
> 1. `git worktree add ../dw-061-c -b 061-task-type-verification-c`。
> 2. `compose.compute.yml`（profile `compute`）：`apache/spark`(standalone master 7077 + worker) + `flink:1.20`(jobmanager REST 8081 + taskmanager)；healthcheck；引用 `net.yml`；tag 记台账。
> 3. `clients/install-spark.sh`（宿主机 `SPARK_HOME`，`local[*]` 或指 7077）+ `clients/install-flink.sh`（宿主机 `FLINK_HOME`，**与集群同版本**，`bin/flink`）。
> 4. 夹具：`jobs/spark.success.py`（pyspark `spark.range(100).count()`+`df.show()`）+ `jobs/spark.fail.py`；`jobs/flink.bounded.success.sql`（batch datagen→print 有界）+ `jobs/flink.bounded.fail.sql` + `jobs/flink.streaming.sql`（无界 datagen→blackhole，`long_running=true`）。
> 5. `scripts/verify-spark.sh`：设 `SPARK_HOME`+master → 建 SPARK 任务 → `dw run` success/fail + `--test` → `capture.sh`。断言 spark-submit 原生日志 + 退出码 + 本地↔服务端一致(SC-006)。
> 6. `scripts/verify-flink-bounded.sh`：有界 FLINK 任务 success/fail + `--test` → 原生日志 + 退出码透传。
> 7. `scripts/verify-flink-longrunning.sh`（SC-005 核心）：流式 `long_running` → `flink run -d` 拿**真实 JobID** → 断言 `external_job_handle` 真回写 → 触发 reattach（重启 worker 进程 / 实例带句柄）→ `FlinkJobStatusFetcher.http()` 对真 8081 轮询到 RUNNING/终态；`flink cancel` 造终态收尾。证据含 JobID + 句柄 + 轮询轨迹，台账 FLINK 行填 `flink_reattach.{job_id,handle_written,reattach_polled_terminal}`。
> 8. T038 核实/修订/修缺陷 + 补测。
> 9. SKIPPED 对照（停 compute/清 `*_HOME`）+ 写 SPARK/FLINK 两行台账。
>
> **底线**：Spark 用 `local[*]` 即真 Spark 运行时（最省），standalone 更贴近真集群——取一形态跑通即可，但**必须真 Spark，不许假 spark-submit**。Flink 无界作业务必 `cancel` 收尾避免占资源。跑完 `./scripts/down.sh compute`。

---

## 派活方式（本人执行）

- 用 `Agent`/`SendMessage` 工具把上面提示词 A/B 分别发给两个外部 Agent，附：分支名 `061-task-type-verification`、`DW_TOKEN`、后端已在 :8000、CLI 已 `cli/dw`。
- 本人并行做 US1（OLAP+Hive）；三工作流错峰共享 Docker。
- 收口：本人合并三 worktree（T042，对齐共享只读面不覆盖）→ 保真回归复核（T043）→ 汇总台账 overall（T044）→ 收口评审 SC-001~009（T046）。
