# Quickstart: 一类任务类型的真跑取证最短路径

**Feature**: 061-task-type-verification | **Date**: 2026-07-10

以 **ClickHouse SQL**（最省事的 JDBC 引擎，worker 已内置驱动）为例，演示「起 profile → 建源 → 建任务 → 真跑 → 取证」端到端。其余引擎同构，差异见 `research.md` + 各 `verify-<engine>.sh`。

## 前置

```bash
# 元数据依赖（既有）
cd backend && docker compose up -d postgres redis
# 后端 all-in-one（宿主机，PG profile）
./mvnw -pl dataweave-api spring-boot:run    # :8000
# CLI
cd ../cli && ./build.sh                       # 产出 dw；export DW_API=http://localhost:8000 DW_TOKEN=<token>
```

## 1. 起引擎 profile（跑完即停，错峰）

```bash
cd verification/061-task-types
./scripts/up.sh olap          # docker network create dw061net(幂等) + docker compose -f compose.olap.yml up -d（含 clickhouse）
# 就绪判据：curl http://localhost:8123/ping → Ok
```

## 2. 登记数据源（既有 API）

- 类型 `CLICKHOUSE`（已 seed，id=9，driver 内置，无需上传 jar）。
- host=`localhost` port=`8123` database=`default` user/pass=本地弱口令。
- 先过连接自检（`JdbcConnectionTester` `SELECT 1`）确认真在位。

## 3. 建任务并真跑（过闸门 + 保真）

```bash
# 用 weft-task-authoring 金路径：pull → 编辑 SQL 任务(绑 CLICKHOUSE 源) → dw run
dw run ch-smoke            # 本地真跑：连真 CH，逐条执行 + SELECT 结果集渲染 + 退出码
dw run ch-smoke --test     # 服务端 TEST：日志流回；比对本地↔服务端一致(SC-006)
```

success 夹具（`jobs/clickhouse.success.sql`）：建表→插入→`SELECT`/`SHOW TABLES`（验证结果集渲染 FR-008/SC-004）。
fail 夹具（`jobs/clickhouse.fail.sql`）：查不存在的表 → 真失败 + 退出码透传（≠ SKIPPED）。

## 4. 取证并写台账

```bash
./scripts/verify-clickhouse.sh    # 封装 2~3：建源+success+fail+capture，产出 evidence/clickhouse/*.log
./scripts/capture.sh              # 汇总 banner/退出码/版本 → evidence/LEDGER.md + evidence/ledger.json
```

台账一行 = `CLICKHOUSE | A | PASS | <image:tag> | success.log | fail.log | resultset_rendered=true | dw_run_vs_server=consistent`。

## 5. 停引擎

```bash
./scripts/down.sh olap
```

## 三态自检清单（每引擎必过）

- [ ] **SUCCESS**：真引擎在位 + success 夹具 → 退出码 0 + 结果证据（结果集/影响行数 或 引擎原生 stdout）。
- [ ] **FAILURE**：真引擎在位 + fail 夹具（作业自身错）→ 非 0 退出码 + 引擎原生错误行。
- [ ] **SKIPPED 对照**：不装引擎（停 profile / 清 `*_HOME`）跑同一 success 夹具 → 「已跳过：<原因>」，不阻塞下游。
- [ ] **保真**：`dw run` 与 `dw run --test` 结果分类一致。
- [ ] **回归**：059 单测 + SKIPPED 闭环仍全绿（`./mvnw -pl dataweave-worker test`）。

## 引擎差异速查

| 引擎 | profile | 上传驱动 jar | 提交方式 | 特殊 |
|---|---|---|---|---|
| ClickHouse | olap | 否（内置） | JDBC 8123 | 结果集渲染 |
| StarRocks/Doris | olap | 否（mysql 内置） | JDBC 9030 | MySQL 协议 |
| Hive | hive | **是（hive-jdbc standalone）** | JDBC 10000 | 分区 HQL + 结果集 |
| DataX | integration | — | 子进程 datax.py | streamreader→writer 底线 |
| SeaTunnel | integration | — | 子进程 seatunnel.sh -e local | fake→console 底线 |
| Spark | compute | — | spark-submit local[*]/standalone | pyspark 取一形态 |
| Flink | compute | — | flink run [-d] REST 8081 | **long_running reattach(SC-005)** |
