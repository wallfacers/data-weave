# Reused API / CLI Touchpoints（本特性不新增端点，全部复用既有面）

**Feature**: 061-task-type-verification | **Date**: 2026-07-10

真跑验证是「用既有平台面把真任务跑到真引擎上」，不引入新 API。以下是每次真跑会触达的既有触点。

## 1. 上传驱动 jar（仅 HIVE 需要）

- `POST /driver-jars`（multipart/form-data；`typeCode=HIVE`，`file=hive-jdbc-<ver>-standalone.jar`）→ `DriverJarController` → `DriverJarService.upload`。
- 返回 `driver_jar_id`；数据源创建时绑定该 id → `IsolatedDriverLoader` 隔离加载。
- ClickHouse/StarRocks/Doris **不需要**（worker 已内置 clickhouse-jdbc / mysql-connector-j）。

## 2. 登记数据源（JDBC 引擎）

- 数据源类型已 seed（`datasource_types`：HIVE=7 / CLICKHOUSE=9 / STARROCKS=10 / DORIS=11）。
- 创建数据源：既有数据源管理 API（host/port/database/username/password[/driver_jar_id]）。
- 连接自检：`JdbcConnectionTester`（`SELECT 1`）——真跑前先过连接测试确认引擎在位。

## 3. 创建/发布任务（过闸门 + 审计）

- 任务定义经既有 push 发布闸门（`GatedActionService.submit` → `PolicyEngine`）+ `agent_action` 审计；无旁路。
- 任务 `type` ∈ `{SQL, HIVE, SPARK, PYTHON, FLINK, DATAX, SEATUNNEL}`；SQL 类绑定对应数据源。
- 子进程引擎的 `*_HOME`/master/REST 经任务配置或 worker 环境变量提供（`EngineSubmitRef`/`SparkSubmitRef`）。

## 4. 本地真跑（原则 III 保真）

- `dw run <task>`：宿主机复用 worker 执行器子进程（`DW_WORKER_CP` 或 auto-detect fat jar），连本机引擎，输出流到终端、退出码忠实透传。
- `dw run --test`：提交服务端 TEST run，日志流回本地——用于 SC-006 本地↔服务端一致性对照。

## 5. 观测真跑日志

- 实时日志 SSE：`GET /api/ops/instances/{id}/logs/stream`。
- DAG 状态 SSE：`GET /api/ops/workflow-instances/{id}/events/stream`。
- 证据抓取从上述日志 + `dw run` 终端输出 + 退出码汇总。

## 6. Flink long_running 回写（SC-005）

- 执行器内部：`flink run -d` → 解析 JobID → `ExternalJobHandleWriter`（`HttpExternalJobHandleWriter` 生产）回写 `external_job_handle` 到 master → `FlinkJobStatusFetcher.http()` 轮询 REST `http://localhost:8081`。
- reattach：实例 `external_job_handle` 非空 → 直接轮询（不再 `flink run`）。真跑对真 8081 验证此链路。

## 不变量

- 所有写操作（含验证任务创建）过闸门 + 审计，不因来源是验证而豁免（原则 V / CLAUDE.md 红线）。
- 无 schema 变更；若缺陷修复触及 schema，须 bump `schema_version` 且文件/DB/项目版本三处相等。
