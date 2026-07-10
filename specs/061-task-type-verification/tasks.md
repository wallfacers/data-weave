---
description: "Task list for 061 大数据任务类型真实引擎验证"
---

# Tasks: 大数据任务类型真实引擎验证（Docker 环境实跑证明）

**Input**: Design documents from `/specs/061-task-type-verification/`

**Prerequisites**: plan.md ✅ · spec.md ✅ · research.md ✅ · data-model.md ✅ · contracts/ ✅

**Tests**: 本特性是**验证/加固型**——「测试」= 对真实引擎的**真跑取证**（SUCCESS + 真实 FAILURE + SKIPPED 对照）本身即验收凭据；另加：059 既有单测持续绿 + 缺陷修复补单测。

**Organization**: 三个 User Story = 三条并行工作流，各自独立 worktree、可独立交付给一个 Agent。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1(工作流A) / US2(工作流B) / US3(工作流C)
- 路径约定：harness → `verification/061-task-types/`；证据 → `specs/061-task-type-verification/evidence/`

## Agent 分派

| 工作流 | Story | 归属 | 覆盖 |
|---|---|---|---|
| A | US1(P1) | **本人** | StarRocks/Doris/ClickHouse SQL + Hive HQL |
| B | US2(P2) | **外部 Agent 1** | DataX + SeaTunnel |
| C | US3(P2) | **外部 Agent 2** | Spark + Flink（含 long_running reattach） |

三态硬门贯穿每引擎：SUCCESS（真跑成功+退出码0+结果证据）+ FAILURE（作业自身错+退出码透传）+ SKIPPED 对照（停引擎跑同夹具→已跳过）；起不来须解决，穷尽不可行记 `BLOCKED`（计未达标，不冒充）。

---

## Phase 1: Setup（共享基建）

**Purpose**: harness 骨架 + 后端/CLI 就位；不碰引擎家族专属文件（防 Agent 覆盖）。

- [ ] T001 创建 `verification/061-task-types/` 目录骨架（`clients/ jobs/ scripts/`）+ `README.md`（错峰使用约定：每 profile 独立起停、跑完 `down.sh`、禁全量常驻）+ 根 `.gitignore` 忽略 `evidence/**/*.log` 大日志与凭据
- [ ] T002 [P] 创建共享 `verification/061-task-types/net.yml`：外部 docker network `dw061net`（各 `compose.<family>.yml` 以 `external: true` 引用，避免同改一文件）
- [ ] T003 [P] 创建 `verification/061-task-types/scripts/up.sh <profile>` + `down.sh <profile>`（`docker compose -f compose.<profile>.yml up -d/down`，先 `docker network create dw061net` 幂等）
- [ ] T004 [P] 起后端 all-in-one（`cd backend && docker compose up -d postgres redis` + `./mvnw -pl dataweave-api spring-boot:run` PG profile）+ `cd cli && ./build.sh`；在 README 记录 `DW_API`/`DW_TOKEN` 获取方式与 `/api/health` 就绪判据

**Checkpoint**: harness 骨架 + 后端 :8000 + `dw` 就位。

---

## Phase 2: Foundational（阻塞前置 — 所有 Story 依赖）

**Purpose**: 统一证据/台账契约 + 保真回归基线；完成后三 Story 方可并行。

**⚠️ CRITICAL**: 本阶段完成前不得开始任一 User Story。

- [ ] T005 保真回归基线：`setsid` 脱离跑 `./mvnw -pl dataweave-worker test`（WSL2 硬规则），确认 059 单测 + SKIPPED 闭环全绿，记录基线 `Tests run: N` 到 `evidence/baseline.md`（后续任何缺陷修复须对比此基线不退化）
- [ ] T006 实现 `verification/061-task-types/scripts/capture.sh`：抓取一次真跑的 起止 banner + 逐行日志 + 结果集/影响行数或引擎原生 stdout/stderr + 退出码 + 耗时 + 引擎版本 → 写 `evidence/<engine>/<kind>.log`（凭据脱敏），并 upsert 一行进 `evidence/ledger.json`（结构遵 `contracts/evidence-ledger.schema.json`）
- [ ] T007 [P] 初始化 `specs/061-task-type-verification/evidence/ledger.json`（10 引擎行 status=PENDING）+ `evidence/LEDGER.md` 台账表头 + `evidence/README.md`（台账字段说明、三态自检清单引 quickstart）

**Checkpoint**: 证据抓取 + 台账 + 回归基线就绪 → 三 Story 可并行开工。

---

## Phase 3: User Story 1 - 数仓 SQL/HQL 真跑（OLAP + Hive）(P1) 🎯 MVP · 工作流 A · 本人

**Goal**: StarRocks/Doris/ClickHouse SQL 与 Hive HQL 连**真实引擎**跑通，结果集/影响行数真渲染进日志，真失败与退出码透传，SKIPPED 三态可辨。

**Independent Test**: 起 `olap`/`hive` profile → 建源 → `dw run` + `--test` 各跑 success/fail 夹具 → evidence 显示逐条执行 + `SHOW TABLES`/`SELECT` 结果集 + 退出码；停 profile 跑同夹具 → 已跳过。

- [ ] T008 [US1] 建 worktree `git worktree add ../dw-061-a -b 061-task-type-verification-a`，本 Story 全部改动在此隔离
- [ ] T009 [P] [US1] `verification/061-task-types/compose.olap.yml`（profile `olap`）：`starrocks/allin1-ubuntu`(宿主端口 9030) + doris allin1(**宿主端口 9031→容器 9030，因二者默认端口都是 9030 必须错开**) + `clickhouse/clickhouse-server`(8123)；各带 healthcheck；引用 `net.yml`；**数据源登记时 port 字段填各自的宿主发布端口**（`buildJdbcUrl` 取 `ds.port`，填错即连错库）；实测钉死镜像 tag 记入台账 `engine_version`
- [ ] T010 [P] [US1] `verification/061-task-types/compose.hive.yml`（profile `hive`）：`apache/hive:4.0.0`(HS2 10000，内嵌 Derby 或退化 HMS+PG 双容器) + healthcheck
- [ ] T011 [P] [US1] 作业夹具 `jobs/{starrocks,doris,clickhouse,hive}.success.sql` + `.fail.sql`：success=建库表+插入+`SELECT`/`SHOW TABLES`(Hive 含分区 `INSERT...PARTITION`+`SHOW PARTITIONS`)；fail=查不存在表/语法错（作业自身错，非缺引擎）
- [ ] T012 [US1] Hive 驱动隔离：下载与 HS2 4.0.0 匹配的 `hive-jdbc-<ver>-standalone.jar`，经 `POST /driver-jars`(typeCode=HIVE) 上传拿 `driver_jar_id`；脚本化进 `scripts/verify-hive.sh` 前置（ClickHouse/StarRocks/Doris 免上传）
- [ ] T013 [P] [US1] `scripts/verify-clickhouse.sh`：起 olap→登记 CLICKHOUSE 源(8123,免驱动)→`dw run` success/fail + `--test`→`capture.sh`；断言结果集渲染(SC-004)真现于日志
- [ ] T014 [P] [US1] `scripts/verify-starrocks.sh` + `verify-doris.sh`：登记 STARROCKS/DORIS 源(9030,mysql 内置驱动)→真跑 success/fail + `--test`→capture；断言日志契约与 ClickHouse 一致
- [ ] T015 [US1] `scripts/verify-hive.sh`：绑上传驱动的 HIVE 源(10000)→多语句 HQL(分区)真跑 success/fail + `--test`→capture；断言分区语义正确 + 结果集渲染 + 退出码透传
- [ ] T016 [US1] SKIPPED 对照：停 `olap`/`hive` profile（或清源）跑同一 success 夹具，确认「已跳过：<原因>」不阻塞下游；证据落 `evidence/<engine>/skipped.log`
- [ ] T017 [US1] 若真跑暴露 `SqlTaskExecutor`/`HiveTaskExecutor` 缺陷（连接/方言/结果集渲染/退出码/分区切分）→ 外科式修复 + 补/更新单测（`SqlTaskExecutorTest`/`HiveTaskExecutorTest`）+ 重跑取证；记 `defects` 进台账
- [ ] T018 [US1] 写 US1 台账 4~5 行（STARROCKS/DORIS/CLICKHOUSE/HIVE：status/version/success+fail evidence/resultset_rendered/dw_run_vs_server）→ `evidence/LEDGER.md` + `ledger.json`

**Checkpoint**: US1 = MVP。4 个 OLAP/Hive 引擎真跑成功+真失败+SKIPPED 三态齐、结果集渲染证实。

---

## Phase 4: User Story 2 - 数据集成真跑（DataX + SeaTunnel）(P2) · 工作流 B · 外部 Agent 1

**Goal**: DataX/SeaTunnel 以子进程提交**真实安装引擎**、真搬数据、真透引擎原生日志与退出码，SKIPPED 三态可辨。

**Independent Test**: 装 `DATAX_HOME`/`SEATUNNEL_HOME` → 建 source→sink 任务 → `dw run` + `--test` success/fail → evidence 显示引擎原生同步统计日志 + 退出码；清 `*_HOME` 跑同夹具 → 已跳过。

- [ ] T019 [US2] 建 worktree `git worktree add ../dw-061-b -b 061-task-type-verification-b`
- [ ] T020 [P] [US2] `clients/install-datax.sh`：下载 DataX tarball 装宿主机 `DATAX_HOME=/opt/datax`，自检 `python bin/datax.py`（JDK25+Python3；不行则社区镜像/Python2 旁路，仍须真 DataX）；版本记台账
- [ ] T021 [P] [US2] `clients/install-seatunnel.sh`：装 SeaTunnel `SEATUNNEL_HOME=/opt/seatunnel`（Zeta local）+ 内置 `connector-fake`/`connector-console`（jdbc 进阶再加 `connector-jdbc`+驱动）
- [ ] T022 [P] [US2] `compose.integration.yml`（profile `integration`）：**连既有 backend `dataweave-mysql`(localhost:3307) 作源，不重定义同名容器**（避免容器名冲突）+ 目标汇（复用 A 的数仓或新增独立 mysql sink，端口/容器名不与既有冲突）；建 source→sink 所需表种子
- [ ] T023 [P] [US2] DataX 夹具 `jobs/datax.success.json`（`streamreader→streamwriter` 底线，免外部依赖）+ 进阶 `jobs/datax.mysql2sink.json`；`jobs/datax.fail.json`（reader 指向不存在表）
- [ ] T024 [P] [US2] SeaTunnel 夹具 `jobs/seatunnel.success.conf`（`FakeSource→Console` 底线 HOCON）+ 进阶 `jdbc→jdbc`；`jobs/seatunnel.fail.conf`（源表不存在/配置非法）
- [ ] T025 [US2] `scripts/verify-datax.sh`：设 `DATAX_HOME`→建 DATAX 任务→`dw run` success/fail + `--test`→capture；断言 DataX 原生统计日志逐行透出 + 退出码透传
- [ ] T026 [US2] `scripts/verify-seatunnel.sh`：设 `SEATUNNEL_HOME`→建 SEATUNNEL 任务→`dw run` success/fail + `--test`→capture；断言引擎原生日志透出 + 退出码
- [ ] T027 [US2] SKIPPED 对照：清 `DATAX_HOME`/`SEATUNNEL_HOME` 跑同 success 夹具→「已跳过：<原因>」不阻塞下游；证据落 skipped.log
- [ ] T028 [US2] 若暴露 `DataXTaskExecutor`/`SeaTunnelTaskExecutor` 缺陷（`*_HOME` 探测/原生日志吞并/退出码）→ 修复 + 补测（`DataXTaskExecutorTest`/`SeaTunnelTaskExecutorTest`）+ 重跑取证；记台账 defects
- [ ] T029 [US2] 写 US2 台账 2 行（DATAX/SEATUNNEL）→ `LEDGER.md` + `ledger.json`

**Checkpoint**: US2 = DataX/SeaTunnel 真跑成功+真失败+SKIPPED 三态齐、引擎原生日志证实。

---

## Phase 5: User Story 3 - 计算与流式真跑（Spark + Flink，含 reattach）(P2) · 工作流 C · 外部 Agent 2

**Goal**: Spark 真提交真集群跑通；Flink 有界任务真提交 + `long_running` detached 提交拿真实 JobID + `external_job_handle` 回写 + reattach 对真 JobManager 轮询到真终态（SC-005）。

**Independent Test**: 起 `compute` profile + 装 `SPARK_HOME`/`FLINK_HOME` → Spark 任务真跑 + Flink 有界任务真跑 + Flink 流式任务 detached→JobID→reattach→终态；`dw run` 与 `--test` 一致。

- [ ] T030 [US3] 建 worktree `git worktree add ../dw-061-c -b 061-task-type-verification-c`
- [ ] T031 [P] [US3] `compose.compute.yml`（profile `compute`）：`apache/spark`(standalone master 7077 + worker) + `flink:1.20`(jobmanager REST 8081 + taskmanager)；healthcheck；tag 记台账
- [ ] T032 [P] [US3] `clients/install-spark.sh`（宿主机 `SPARK_HOME`，`local[*]` 或指向 7077）+ `clients/install-flink.sh`（宿主机 `FLINK_HOME`，与集群同版本，`bin/flink`）
- [ ] T033 [P] [US3] Spark 夹具 `jobs/spark.success.py`（pyspark `spark.range(100).count()`+`df.show()`）+ `jobs/spark.fail.py`（抛异常/spark-sql 语法错）
- [ ] T034 [P] [US3] Flink 夹具：`jobs/flink.bounded.success.sql`（batch datagen→print，有界）+ `jobs/flink.bounded.fail.sql`（语法错/不存在 jar）+ `jobs/flink.streaming.sql`（无界 datagen→blackhole，`long_running=true`）
- [ ] T035 [US3] `scripts/verify-spark.sh`：设 `SPARK_HOME`+master→建 SPARK 任务→`dw run` success/fail + `--test`→capture；断言 spark-submit 原生日志 + 退出码 + 本地↔服务端一致(SC-006)
- [ ] T036 [US3] `scripts/verify-flink-bounded.sh`：设 `FLINK_HOME`+REST 8081→有界 FLINK 任务真跑 success/fail + `--test`→capture；断言原生日志 + 退出码透传
- [ ] T037 [US3] `scripts/verify-flink-longrunning.sh`（SC-005 核心）：流式 FLINK 任务 `long_running` → `flink run -d` 拿**真实 JobID** → 断言 `external_job_handle` 真回写 → 触发 reattach（重启 worker/带句柄）→ `FlinkJobStatusFetcher.http()` 对真 8081 轮询到 RUNNING/终态；`flink cancel` 造终态收尾；证据含 JobID+句柄+轮询轨迹
- [ ] T038 [US3] 核实 `FlinkTaskExecutor.executeLongRunning` 源码「桩实现」注释(≈line164) 与 `FlinkJobStatusFetcher.http()` 去桩实现是否一致：若真跑证明链路成立→修订过期注释；若暴露真缺陷→修复 + 补 `FlinkTaskExecutorTest` 单测 + 重跑取证；记台账 defects
- [ ] T039 [US3] SKIPPED 对照：停 `compute` profile/清 `*_HOME` 跑同夹具→Spark/Flink「已跳过」不阻塞下游；证据落 skipped.log
- [ ] T040 [US3] 写 US3 台账（SPARK/FLINK；FLINK 行含 `flink_reattach.{job_id,handle_written,reattach_polled_terminal}`）→ `LEDGER.md` + `ledger.json`

**Checkpoint**: US3 = Spark/Flink 真跑成功+真失败+SKIPPED 三态齐；Flink long_running reattach 对真 JobManager 证实。

---

## Phase 6: Polish & 收口（跨 Story）

**Purpose**: 合并三工作流、汇总台账、保真回归、文档收口。

- [ ] T041 [P] PYTHON/SHELL 回归确认：任一 profile 期间真跑一个 Python + Shell 任务确认无回归；台账补 2 行 status=PASS
- [ ] T042 按顺序合并三 worktree 回 main（对齐共享只读面：任务类型枚举/dw 用法/net.yml/台账格式；遵多 Agent 硬规则不覆盖他人产出）；`git worktree remove` 清理
- [ ] T043 保真回归复核：合并后 `setsid` 全量跑 `./mvnw -pl dataweave-worker,dataweave-api test`（脱离），对比 T005 基线零退化；任何缺陷修复的新单测确认在内
- [ ] T044 汇总台账：`evidence/LEDGER.md` + `ledger.json` 置 `overall`——10 引擎全 `PASS`→`PASS`；任一 `BLOCKED`/`PENDING`→`INCOMPLETE`（附原因，不冒充）
- [ ] T045 [P] 更新 `CLAUDE.md` Knowledge Map 增 061 行（真实引擎验证台账位置 + 硬门结论）；`quickstart.md` 走一遍验证其可复现
- [ ] T046 收口评审：逐条核对 spec SC-001~SC-009 达成情况写入 `evidence/LEDGER.md` 尾部；BLOCKED 项如实列原因与已尝试手段

---

## Dependencies & Execution Order

### Phase 依赖
- **Setup(P1)**：无依赖，立即开始。
- **Foundational(P2)**：依赖 Setup；**阻塞所有 User Story**。
- **US1/US2/US3(P3~5)**：均依赖 Foundational 完成后，**可三工作流并行**（各自 worktree + 错峰共享本机 Docker）。
- **Polish(P6)**：依赖三 Story 完成（T042 合并依赖 US1/US2/US3；T041/T045 可提前）。

### Story 独立性
- US1(P1)/US2(P2)/US3(P3) 互不依赖，各自可独立真跑与取证；仅共享只读面（枚举/CLI/net.yml/台账格式）在 T042 合并前对齐。
- 资源约束：三 profile 不必同起——错峰 `up.sh <profile>` / `down.sh <profile>`。

### Story 内顺序
- worktree → compose + clients → jobs 夹具 → verify 脚本（success/fail）→ SKIPPED 对照 → 缺陷修复+补测 → 写台账。

### 并行机会
- Setup 的 T002/T003/T004 [P] 并行。
- Foundational 的 T007 [P] 与 T005/T006 并行。
- **三 User Story 整体并行**（分派本人 + Agent1 + Agent2）。
- 各 Story 内 compose/clients/jobs 的 [P] 任务并行（不同文件）。

---

## Parallel Example: 三工作流分派

```bash
# Foundational 完成后，三工作流并行（各自 worktree）：
# 本人   → US1：verification/061-task-types/{compose.olap.yml,compose.hive.yml} + verify-{clickhouse,starrocks,doris,hive}.sh
# Agent1 → US2：clients/install-{datax,seatunnel}.sh + jobs/{datax,seatunnel}.* + verify-{datax,seatunnel}.sh
# Agent2 → US3：compose.compute.yml + clients/install-{spark,flink}.sh + verify-{spark,flink-bounded,flink-longrunning}.sh
```

---

## Implementation Strategy

### MVP First（US1 Only）
1. Setup(P1) → Foundational(P2) → US1(P3：OLAP×3 + Hive 真跑三态齐)。
2. **STOP & VALIDATE**：US1 台账 4~5 行全 PASS + 结果集渲染证实 → 平台最主流数据开发场景已证真能干活。

### Incremental Delivery
1. Setup+Foundational → 基线就绪。
2. US1（本人）→ 独立取证 → MVP。
3. US2（Agent1）/ US3（Agent2）并行 → 各自独立取证。
4. Polish：合并 + 汇总台账 + 保真回归 + 文档收口。

### Parallel Team Strategy（本特性核心）
1. 本人先做 Setup+Foundational（共享基建，一次到位）。
2. 完成后派活：本人 US1、外部 Agent1 US2、外部 Agent2 US3；各 worktree 隔离、错峰 Docker。
3. 三 Story 独立完成 → 本人合并 + 收口台账（硬门：全 PASS 才整体达标）。

---

## Notes
- [P] = 不同文件、无依赖。[Story] 映射工作流便于分派与追溯。
- 三态硬门每引擎必过：SUCCESS + 真实 FAILURE + SKIPPED 对照。
- 起不来须解决（加资源/换镜像/换接入），穷尽不可行记 `BLOCKED`（未达标，绝不冒充/假替身）。
- WSL2 长命令必 `setsid` 脱离（T005/T043）。
- 写操作（建源/建任务）过既有 push 闸门 + 审计，无旁路。
- 缺陷修复留在既有执行器语义内 + 补测；触及 schema 须 bump `schema_version` 三处相等。
- 每任务或逻辑组后提交；每 checkpoint 可停下独立验证。
