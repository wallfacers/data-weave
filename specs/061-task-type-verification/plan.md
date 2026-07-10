# Implementation Plan: 大数据任务类型真实引擎验证（Docker 环境实跑证明）

**Branch**: `061-task-type-verification` | **Date**: 2026-07-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/061-task-type-verification/spec.md`

## Summary

059 已把 7 类引擎家族的执行器（`SqlTaskExecutor`/`HiveTaskExecutor`/`SparkTaskExecutor`/`PythonTaskExecutor`/`FlinkTaskExecutor`/`DataXTaskExecutor`/`SeaTunnelTaskExecutor`）合入 main，但证据只覆盖「命令构造纯函数 + 缺引擎→SKIPPED」两条路径，**真实 SUCCESS 路径从未证明**。本特性交付：**为每类引擎家族建可复现的真实 Docker 环境（按引擎家族分 profile 按需起）+ 对每类任务类型真跑取证（SUCCESS + 真实 FAILURE，保 SKIPPED 三态可辨）+ 修复真跑暴露的执行器缺陷 + 汇总验证台账**。硬门：7 类无一例外须真跑成功；起不来是阻塞项须解决而非跳过，穷尽手段仍不可行者如实记为「未完成」，绝不冒充。

**技术路线**：后端以 all-in-one（`spring-boot:run`，PG profile）跑在宿主机，`dw run` 亦在宿主机——保持原则 III 保真（真执行器子进程）。引擎分两类接入：① **JDBC 引擎**（StarRocks/Doris/ClickHouse/Hive）→ Docker 起服务、worker 经 JDBC 连宿主发布端口；② **子进程引擎**（Spark/Flink/DataX/SeaTunnel）→ 引擎服务端优先 Docker，客户端二进制（`*_HOME`）装在宿主机供 worker 子进程调用。验证 harness（compose/脚本/作业夹具）落 `verification/061-task-types/`，证据台账落 `specs/061-task-type-verification/evidence/`。

## Technical Context

**Language/Version**: 后端 Java 25 / Spring Boot 4（既有，不新增语言）；验证 harness = bash + docker compose；作业夹具 = SQL/HQL、DataX job JSON、SeaTunnel HOCON、pyspark/Flink SQL。

**Primary Dependencies**（真跑接入面，全部既有，无新增后端依赖）：
- JDBC：worker 已打包 `mysql-connector-j`（StarRocks/Doris/MySQL）、`clickhouse-jdbc`、`mssql-jdbc`、`mariadb`、`ojdbc11`；**Hive JDBC 未打包 → 经 `POST /driver-jars` 上传 `hive-jdbc` standalone jar 走驱动隔离**（`IsolatedDriverLoader`）。
- 子进程：`EngineSubmitRef`（`engineHome`/`mode`/`jarPath`/`mainClass`/`configPath`/`longRunning`/`externalJobHandle`）+ `SparkSubmitRef`（`sparkHome`/`master`/…）从任务配置/环境变量解析；探测 `*_HOME` + 二进制存在性决定 SKIPPED vs 提交。
- Flink long_running：`flink run -d` → 解析 JobID → `external_job_handle` 回写（`ExternalJobHandleWriter`）→ `FlinkJobStatusFetcher.http()` 轮询 REST（8081）；reattach 走句柄重连。

**Storage**: 元数据 PG（`docker compose up -d postgres redis`，既有）；引擎数据在各引擎容器内；证据文件落 `specs/061-.../evidence/`（脱敏）。无 schema 变更（除非真跑暴露缺陷需改，届时 bump `schema_version`）。

**Testing**:
- 保真回归：059 既有单测 + SKIPPED 闭环（H2/无外部依赖）MUST 持续全绿。
- 真跑验证：**可复现脚本 + 证据台账**（非常驻 CI 集成测试）——每引擎一键真跑脚本产出证据；重引擎不进 CI。
- 缺陷修复：任何执行器改动补/更新对应单测（JUnit 5 + AssertJ）。

**Target Platform**: 本机 WSL2 + Docker；后端 all-in-one 宿主机运行。

**Project Type**: 验证/加固型特性（backend 复用 + 运维 harness），非新产品面。

**Performance Goals**: N/A（验证特性，不设吞吐目标；仅要求真跑在合理时长内出证据）。

**Constraints**: 单台 Docker 资源受限 → 按 profile 错峰起（olap/hive/integration/compute 分批、跑完即 down），MUST NOT 要求全部引擎同时常驻。凭据（弱口令）仅限本地隔离环境，不入仓库/日志。

**Scale/Scope**: 7 类引擎家族 × {真跑成功证据 + 真实失败证据}；3 条并行工作流（本人 + 2 外部 Agent）；PYTHON/SHELL 纳回归确认（视为已真跑）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|---|---|---|
| I. Files-First | ✅ PASS | 不改定义文件格式；验证作业夹具即普通文本文件（SQL/JSON/HOCON），可 diff/review。 |
| II. Server is Source of Truth | ✅ PASS | 验证任务经既有 push 闸门 + 审计创建；不引入旁路、不做双向合并。 |
| III. Two-Legged Debugging（不可让渡） | ✅ PASS·核心对齐 | 061 正是对「`dw run` ↔ 服务端执行器保真」在**真实引擎**下取证（FR-010/SC-006）；复用真执行器子进程，不分叉第二引擎。 |
| IV. AI in Local Agent（不可让渡） | ✅ PASS | 不引入服务端 AI；不损伤观测/调度内核。 |
| V. Reuse the Kernel | ✅ PASS | 复用执行器/驱动隔离/调度/闸门，零重写；缺陷修复留在既有执行器语义内，写操作过闸门留痕。 |
| 测试门（no test = not done） | ✅ PASS | 缺陷修复带单测；059 单测持续绿；真跑证据台账即验证特性的「done」凭据。 |
| Worktree 隔离 | ✅ PASS | 3 工作流各自独立 worktree；共享面拆分（见下）避免互相覆盖。 |

**无违规** → Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/061-task-type-verification/
├── plan.md              # 本文件
├── research.md          # Phase 0：各引擎 Docker 镜像/版本/接入/最小作业/失败作业 决策
├── data-model.md        # Phase 1：验证环境/真跑证据/工作流/台账 实体
├── quickstart.md        # Phase 1：起一个 profile → 真跑一类任务 → 取证 的最短路径
├── contracts/
│   ├── reused-endpoints.md   # 复用的既有 API 触点（driver-jars/datasource/push/dw run/ops logs）
│   └── evidence-ledger.schema.json  # 证据台账条目结构
├── evidence/            # 真跑证据落盘（脱敏日志片段 + LEDGER.md 台账）
└── tasks.md             # /speckit-tasks 产出（本命令不建）
```

### Source Code / Harness (repository)

```text
verification/061-task-types/          # 验证 harness（git 跟踪；evidence 大日志 gitignore）
├── compose.olap.yml                  # 工作流 A：starrocks / doris / clickhouse（profile: olap）
├── compose.hive.yml                  # 工作流 A：hive metastore + hiveserver2（profile: hive）
├── compose.integration.yml           # 工作流 B：datax/seatunnel 的 source/sink（mysql→数仓，profile: integration）
├── compose.compute.yml               # 工作流 C：spark standalone + flink jm/tm（profile: compute）
├── net.yml                           # 共享 docker network（各 compose external 引用，避免同改一文件）
├── clients/                          # 子进程引擎宿主机客户端安装脚本（*_HOME）
│   ├── install-spark.sh  install-flink.sh  install-datax.sh  install-seatunnel.sh
├── jobs/                             # 每引擎最小作业夹具：<engine>.success.* / <engine>.fail.*
├── scripts/
│   ├── up.sh <profile>   down.sh <profile>          # 起/停某 profile
│   ├── verify-<engine>.sh                            # 一键：建源→建任务→dw run+服务端→抓证据
│   └── capture.sh                                    # 统一证据抓取（banner/退出码/版本→evidence）
└── README.md                          # harness 总说明 + 错峰使用约定

backend/dataweave-worker/src/main/java/com/dataweave/worker/infrastructure/*TaskExecutor.java
                                       # 仅当真跑暴露缺陷时外科式修复（+ 单测）
```

**Structure Decision**：验证特性——后端零结构改动（仅缺陷修复），新增独立 `verification/061-task-types/` harness 目录。**共享面拆分是关键设计**：不使用单一 `docker-compose.yml`，而按引擎家族拆成 `compose.<family>.yml` + 共享 `net.yml`，使 A/B/C 三工作流各改各的文件、零覆盖冲突（遵循多 Agent 硬规则）；任务类型枚举/i18n 等真·共享面只读复用，不改。

## 并行工作流切分与 Agent 分工

| 工作流 | 覆盖 | 引擎（Docker 优先） | 特有风险/要点 | 归属 |
|---|---|---|---|---|
| **A**（US1，P1） | OLAP SQL×3 + Hive HQL | starrocks allin1 / doris allin1 / clickhouse-server / apache-hive（HMS+HS2） | Hive 需上传 `hive-jdbc` 驱动 jar；结果集渲染契约（FR-008/SC-004）；分区 HQL | 本人（P1 风险最高，亲自守） |
| **B**（US2，P2） | DataX + SeaTunnel | datax 客户端(宿主) + mysql→starrocks 源汇；seatunnel(zeta local) | `*_HOME` 探测切 SUCCESS；引擎原生日志透传；真实失败（源表不存在） | 外部 Agent 1 |
| **C**（US3，P2） | Spark + Flink（含 long_running reattach） | spark standalone / flink jm+tm（REST 8081） | Flink reattach 对真 JobManager（SC-005）；Spark 三形态取一；`dw run`↔服务端一致 | 外部 Agent 2 |

- 每工作流独立 `git worktree add ../dw-061-<a|b|c> -b 061-task-type-verification-<a|b|c>`。
- 合并前对齐共享只读面（任务类型枚举、`dw` CLI 用法、evidence 台账格式、net.yml）；错峰使用本机 Docker（跑完即 `down.sh`）。
- 每工作流交付：`compose.<family>.yml` + `jobs/` 夹具 + `verify-<engine>.sh` + evidence 证据 + 台账行；暴露缺陷则修执行器 + 补单测。

## Complexity Tracking

> 无 Constitution 违规，本表留空。
