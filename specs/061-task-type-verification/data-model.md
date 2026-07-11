# Phase 1 Data Model: 真实引擎验证

**Feature**: 061-task-type-verification | **Date**: 2026-07-10

本特性是**验证/加固型**，**不新增数据库表、不改 schema**（除非真跑暴露执行器缺陷需改，届时按红线 bump `schema_version`）。这里的「实体」是验证工件的逻辑模型（落盘为文件），非 DB 表。

## E1. 验证环境（Verification Environment）

一套让某引擎家族真实可跑的运行环境。落盘 = `verification/061-task-types/compose.<family>.yml` + `clients/install-*.sh`。

| 字段 | 说明 |
|---|---|
| family | `olap` / `hive` / `integration` / `compute` |
| engine | `STARROCKS`/`DORIS`/`CLICKHOUSE`/`HIVE`/`DATAX`/`SEATUNNEL`/`SPARK`/`FLINK` |
| image_or_tarball | Docker 镜像:tag 或宿主机客户端 tarball 来源 + 版本 |
| access | JDBC URL 模板 / `*_HOME` / master / REST endpoint |
| driver_jar | 是否需上传驱动 jar（仅 HIVE=是；CLICKHOUSE/StarRocks/Doris=否，worker 内置） |
| ports | 发布到 localhost 的端口（9030/8123/10000/8081/7077…） |
| bringup / healthcheck | 起停命令 + 就绪判据 |
| version_pinned | 实测钉死的确切版本（真跑时记录） |

**关系**：一个 family 含 1..N engine；一个 engine 绑 0..1 platform datasource（JDBC 类）或 0..1 `*_HOME`（子进程类）。

## E2. 作业夹具（Job Fixture）

每引擎的最小可跑作业，成功/失败各一。落盘 = `verification/061-task-types/jobs/<engine>.<success|fail>.<ext>`。

| 字段 | 说明 |
|---|---|
| engine | 同上 |
| kind | `success` / `fail` |
| content | SQL/HQL 文本 / DataX job JSON / SeaTunnel HOCON / pyspark / Flink SQL |
| expects | success→退出码0+结果证据；fail→非0退出码+引擎原生错误（非 SKIPPED） |
| long_running | 仅 FLINK：是否无界流式（触发 detached + reattach 验证） |

**不变量**：`fail` 夹具必须是**作业自身错误**（源表不存在/语法错），而非「缺引擎」——后者是 SKIPPED 对照，由「不装引擎时跑同一 success 夹具」验证。

## E3. 真跑证据（Real-Run Evidence）

一次对真实引擎的端到端运行留痕。落盘 = `specs/061-task-type-verification/evidence/<engine>/<kind>.log`（脱敏）。

| 字段 | 说明 |
|---|---|
| engine / kind | 引擎 + success/fail/skipped |
| run_via | `dw run`（本地）/ `server`（服务端 TEST 调度） |
| engine_version | 实测引擎镜像/客户端版本 |
| banner_start / banner_end | 起止 banner（模式/类型/数据源或引擎/版本/时间） |
| body | 逐行执行日志 |
| result_evidence | SQL/HQL：结果集渲染（表头+数据行，含截断标注）+ 影响行数；子进程：引擎原生 stdout/stderr |
| exit_code | 忠实透传的退出码 |
| duration | 耗时 |
| classification | `SUCCESS` / `FAILURE` / `SKIPPED`（三态须与其余两态证据可辨） |
| flink_handle | 仅 long_running：真实 JobID + external_job_handle + reattach 轮询轨迹 |

**三态判据**：真引擎在位+作业成功=SUCCESS；真引擎在位+作业错=FAILURE（退出码透传）；真引擎缺失=SKIPPED（不阻塞下游、不新增状态）。

## E4. 验证工作流（Verification Workflow）

按引擎家族切分、可独立交付给一个 Agent 的一束验证任务。

| 字段 | 说明 |
|---|---|
| id | A（本人）/ B（外部 Agent1）/ C（外部 Agent2） |
| worktree | `../dw-061-<a|b|c>`，分支 `061-task-type-verification-<a|b|c>` |
| scope | 覆盖的 engine 集合（见 plan 分工表） |
| deliverables | compose + jobs + verify 脚本 + evidence + 台账行 + (缺陷则)执行器修复+单测 |
| shared_surface | 只读复用：任务类型枚举 / dw CLI / 台账格式 / net.yml；合并前对齐 |

## E5. 验证台账（Verification Ledger）

全体任务类型的真跑结论汇总。落盘 = `specs/061-task-type-verification/evidence/LEDGER.md`（表格）+ 机读 `evidence/ledger.json`（schema 见 contracts）。

| 字段 | 说明 |
|---|---|
| engine | 8 引擎逐行 |
| status | `PASS`（真跑成功+失败双证据齐） / `BLOCKED`（起不来，含原因+已尝试手段，**计未达标**） / `PENDING` |
| success_evidence / fail_evidence | 证据文件路径 |
| engine_version | 实测版本 |
| dw_run_vs_server | 一致性确认（SC-006） |
| defects | 真跑暴露的缺陷 + 修复 commit + 补测 |

**硬门**：整体达标 ⟺ **10 条引擎行**（StarRocks/Doris/ClickHouse/Hive/DataX/SeaTunnel/Spark/Flink + Python/Shell 回归；Flink 的 long_running 是 FLINK 行内 `flink_reattach` 子验证，不单独计行）status 全 `PASS`；任一 `BLOCKED` = 整体未完成（不冒充 PASS）。
