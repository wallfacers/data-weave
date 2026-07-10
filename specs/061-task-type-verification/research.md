# Phase 0 Research: 真实引擎验证环境选型

**Feature**: 061-task-type-verification | **Date**: 2026-07-10

研究目标：为每类引擎家族确定 ①可真跑的 Docker 镜像/接入方式 ②最小「成功」作业夹具 ③「真实失败」作业夹具 ④与平台执行器接入的关键参数。所有版本号在环境搭建阶段（tasks）**实测钉死并记录进台账**——本文件给推荐锚点，硬门要求真跑成功，起不来须换镜像/加资源而非降级为跳过。

## 决策 0：后端与 worker 的运行位形

- **Decision**: 后端 all-in-one（`./mvnw -pl dataweave-api spring-boot:run`，PG profile）+ `dw run` 均跑在**宿主机**；引擎在 Docker，端口发布到 `localhost`。
- **Rationale**: 保持原则 III 保真（真执行器子进程 = `dw run` 与服务端同一执行器）；子进程引擎的客户端二进制装宿主机即可被 worker 直接调用；JDBC 引擎经 `localhost:<port>` 直连。避免把整个后端塞进容器再装 8 种引擎客户端的重编排。
- **Alternatives rejected**: ①全 distributed docker（worker 镜像内置全部引擎客户端）——镜像巨大、构建慢、与「按 profile 错峰」冲突；②引擎客户端也 docker exec——破坏「worker 子进程直调 `*_HOME`」的真实路径（失真）。
- **Flink reattach 失效重连验证**：单 worker 进程内，reattach 由「重启 worker 进程 / 实例带 `external_job_handle`」触发；无需多节点集群即可对真 JobManager 验证 SC-005。

## 决策 A1：StarRocks（OLAP SQL，MySQL 协议）

- **Decision**: `starrocks/allin1-ubuntu`（FE+BE 单容器），MySQL 协议端口 `9030`。数据源 typeCode=`STARROCKS`（data.sql id=10，driver=`com.mysql.cj.jdbc.Driver` 已内置于 worker）。
- **接入**: JDBC `jdbc:mysql://localhost:9030/<db>`；worker 内置 mysql 驱动，**无需上传驱动 jar**。
- **成功作业**: `CREATE DATABASE/TABLE`（DUPLICATE KEY + 分桶）→ `INSERT` → `SELECT`/`SHOW TABLES`（验证结果集渲染 FR-008）。
- **失败作业**: 向不存在的表 `INSERT`，或语法错误 → 真失败 + 退出码透传。
- **风险**: allin1 首启需较大内存与就绪等待；健康检查用 `mysql -P9030 -uroot -e "show frontends"`。

## 决策 A2：Doris（OLAP SQL，MySQL 协议）

- **Decision**: `apache/doris:doris-all-in-one-*`（或 `selectdb/doris.all-in-one`），Query 端口 `9030`。typeCode=`DORIS`（id=11，mysql 驱动内置）。
- **接入/作业/失败**: 与 StarRocks 同构（同 MySQL 协议、同结果集契约）；夹具可复用 StarRocks 的 DDL（注意 Doris 建表属性差异 `properties("replication_num"="1")`）。
- **风险**: FE/BE 就绪顺序；单机副本数须设 1。

## 决策 A3：ClickHouse（OLAP SQL，HTTP 协议）

- **Decision**: `clickhouse/clickhouse-server`，HTTP 端口 `8123`。typeCode=`CLICKHOUSE`（id=9，driver=`com.clickhouse.jdbc.ClickHouseDriver` **已内置于 worker pom**）。
- **接入**: JDBC `jdbc:clickhouse://localhost:8123/<db>`；**无需上传驱动 jar**（worker 已打包 clickhouse-jdbc）。
- **成功作业**: `CREATE TABLE ... ENGINE=MergeTree ORDER BY ...` → `INSERT` → `SELECT`/`SHOW TABLES`。
- **失败作业**: 未知表/列 → 真失败。
- **风险**: 语句分隔与影响行数语义沿用现有 SQL 执行器；ClickHouse 多语句需逐条执行。

## 决策 A4：Hive（HQL，独立执行器，HiveServer2 JDBC）

- **Decision**: `apache/hive:4.0.0`（可单容器起 HMS + HS2，`SERVICE_NAME=hiveserver2`，内嵌 Derby 元数据），HS2 端口 `10000`。typeCode=`HIVE`（id=7，driver=`org.apache.hive.jdbc.HiveDriver`）。
- **接入（关键差异）**: **worker 未打包 hive-jdbc** → 必须经 `POST /driver-jars`（multipart，typeCode=HIVE）上传 `hive-jdbc-<ver>-standalone.jar`，数据源绑定 `driver_jar_id` → 走 `IsolatedDriverLoader` 隔离加载。这是 US1 唯一需要「上传驱动」的引擎。
- **成功作业**: 多语句 HQL——`CREATE TABLE ... PARTITIONED BY (dt string)` → `INSERT ... PARTITION(dt=...)` → `SHOW PARTITIONS` / `SELECT`（验证分区语义 + 结果集渲染 + 不套朴素分号切分）。
- **失败作业**: 向不存在的分区列写 / 语法错误 → 真失败 + 退出码透传。
- **风险**: apache/hive 4 镜像就绪慢；若单容器不稳，退化为 `HMS(postgres 元数据) + HS2` 双容器；确不可行须按 FR-004 记阻塞（不降级为 SKIPPED 冒充）。

## 决策 B1：DataX（数据集成，子进程）

- **Decision**: DataX 无稳定官方镜像 → **下载 DataX tarball 装宿主机**，`DATAX_HOME=/opt/datax`，`bin/datax.py`（需 Python + JDK，宿主已具备 JDK25；DataX 官方需 Python2.7，实测可用 Python3 补丁或社区构建）。`install-datax.sh` 负责下载+解压+自检。
- **接入**: `EngineSubmitRef.engineHome=DATAX_HOME`；worker 子进程 `python bin/datax.py job.json`。
- **成功作业**: 最小 `streamreader → streamwriter`（内置，无需外部源，最稳）作为「引擎真在位 + 真提交 + 原生统计日志」的底线证据；进阶 `mysqlreader（compose.integration 的 mysql）→ starrockswriter/mysqlwriter`。
- **失败作业**: reader 指向不存在的表 → DataX 原生报错 + 退出码透传（区别于缺 `DATAX_HOME` 的 SKIPPED）。
- **风险**: DataX 的 Python 依赖是主要坑；streamreader→streamwriter 绕开外部依赖先拿 SUCCESS 底线。

## 决策 B2：SeaTunnel（数据集成，子进程）

- **Decision**: `apache/seatunnel` 镜像或下载 tarball 装宿主机，`SEATUNNEL_HOME=/opt/seatunnel`，`bin/seatunnel.sh`（Zeta 本地引擎 `-e local`，无需额外 Spark/Flink）。
- **接入**: `EngineSubmitRef.engineHome=SEATUNNEL_HOME`；worker 子进程 `bin/seatunnel.sh --config <hocon> -e local`。
- **成功作业**: `FakeSource → Console`（内置，最稳底线）；进阶 `Jdbc source（mysql）→ Jdbc sink（数仓）`。
- **失败作业**: source 指向不存在表 / 配置非法 → 引擎原生错误 + 退出码透传。
- **风险**: SeaTunnel 需安装对应 connector 插件（`connector-fake`/`connector-console` 内置，jdbc 需 `connector-jdbc` + 驱动）；先用 fake→console 拿底线。

## 决策 C1：Spark（计算，子进程三形态）

- **Decision**: `apache/spark`（standalone：`master` + `worker`），或宿主机装 Spark tarball 用 `local[*]`。`SPARK_HOME=/opt/spark`，`spark-submit`。数据源 typeCode=`SPARK`（`SparkSubmitRef.master`）。
- **接入**: worker 子进程 `spark-submit --master <spark://localhost:7077 | local[*]> ...`。至少验证一种形态（pyspark 最省事）。
- **成功作业**: pyspark 脚本 `spark.range(100).count()` + `df.show()` → 原生日志 + 退出码 0；`dw run` 与服务端各跑一次验证一致（SC-006）。
- **失败作业**: pyspark 抛异常 / spark-sql 语法错 → 真失败 + 退出码透传。
- **风险**: `local[*]` 只需 SPARK_HOME 即真跑（最省），standalone 更贴近「真集群」；016 曾用假 spark-submit，本轮必须真 Spark。

## 决策 C2：Flink（流式+有界，子进程 + REST reattach）

- **Decision**: `flink:1.20`（`jobmanager` + `taskmanager`，REST `8081`）+ 宿主机同版本 Flink 客户端 `FLINK_HOME=/opt/flink`（`bin/flink`）。
- **接入**: worker 子进程 `flink run [-d] -m localhost:8081 ...`；`resolveRestEndpoint→http://localhost:8081`。
- **有界成功作业**: Flink SQL（`SET 'execution.runtime-mode'='batch'`；`CREATE TABLE ... datagen → print`，有界）或自带 examples jar（`WordCount`）→ 原生日志 + 退出码。
- **long_running（SC-005 关键）**: 流式作业（`datagen`→`blackhole`/`print` 无界）走 `long_running=true` → `flink run -d` → 解析真实 JobID → `external_job_handle` 回写 → `FlinkJobStatusFetcher.http()` 对真 8081 轮询到 RUNNING；触发 reattach（带句柄重连）验证轮询到终态；人工 `flink cancel` 造终态。
- **失败作业**: 提交不存在 jar / SQL 语法错 → 真失败。
- **⚠️ 待真跑核实**: `FlinkTaskExecutor.executeLongRunning` 源码注释仍写「桩实现」（line ~164），但 `FlinkJobStatusFetcher.http()` 已存在（060 去桩）。**SC-005 的真跑要么证明 reattach 链路真成立、要么暴露注释/实现不一致的缺陷 → 按 FR-011 修复 + 补单测 + 重跑取证。**
- **风险**: 客户端与集群版本须一致；无界作业须能被 cancel 收尾避免占资源。

## 决策 D：PYTHON / SHELL（回归确认）

- **Decision**: 视为已真跑（既有执行器长期真运行）；本轮仅纳入回归确认——起任一 profile 期间顺带真跑一个 Python/Shell 任务确认无回归，不单列环境。

## 决策 E：证据抓取与台账

- **Decision**: 统一 `capture.sh` 抓取每次真跑的：起止 banner、逐行日志、结果集/影响行数或引擎原生 stdout/stderr、退出码、耗时、引擎镜像版本 → 落 `evidence/<engine>/<success|fail>.log` + 一行台账进 `evidence/LEDGER.md`；凭据脱敏。
- **Rationale**: 满足 FR-006/FR-015/SC-008「第三者仅凭台账即可判真伪与复现」。
- **台账字段**：见 `contracts/evidence-ledger.schema.json`。

## 决策 F：错峰资源策略

- **Decision**: 每 profile 独立 `compose.<family>.yml`，`up.sh <profile>` 起、跑完 `down.sh <profile>` 停；三 Agent 时间上错峰共享本机 Docker，禁止全量常驻。
- **Rationale**: 单台 WSL2 内存受限，StarRocks+Doris+Hive+Flink+Spark 同起极可能 OOM（FR-002a）。

## 未决/需实测钉死（进 tasks 首批）

1. 各引擎镜像**确切 tag** + 首启就绪判据（healthcheck）——实测记录，起不来即换镜像/加资源。
2. Hive `hive-jdbc` standalone jar 的确切版本（与 HS2 4.0.0 匹配）。
3. DataX 在宿主机（JDK25 + Python3）的可运行性——不行则用社区镜像/Python2 容器旁路（仍须真 DataX）。
4. SeaTunnel connector 插件安装范围（fake/console 底线 vs jdbc 进阶）。
5. Flink 客户端与集群版本对齐 tag；long_running reattach 真跑是否暴露「桩注释 vs 真实现」缺陷。
