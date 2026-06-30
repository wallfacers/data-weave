# Quickstart: 运行态同步行数采集验证

**Feature**: 025-lineage-synced-rows

前置：neo4j（`docker compose up -d`）+ 一个 SQL 任务（写表）。详见 [data-model.md](./data-model.md) 与 [contracts/](./contracts/)。

## 场景 1 — SQL 任务跑完 syncSummary 有数据（US1）

1. 建一个 SQL 任务（写单表）：`script` = `INSERT INTO orders_clean(total) SELECT amount FROM orders`。
2. 触发执行（调度跑，或 `dw run --test` 提交服务器 TEST，使其经 reportFinished）。
3. 断言：neo4j `(:TaskRun{instanceId})-[:SYNCED{rowCount:<affected>,bizDate}]->(:Table{orders_clean})`。
4. `GET /api/lineage/sync-summary` 当日返回 `<affected>`（不再 null/「估算中」）。
5. 对照：失败/跳过的任务 → 无 `:SYNCED`。

## 场景 2 — 多写表（US3）

SQL 含两条 INSERT 写两表（A 100 行、B 50 行）→ 两表各 `:SYNCED`，syncSummary `SUM=150`。
单 statement 写多表（INSERT ALL）→ 每表共享该 statement updateCount（近似）。

## 自动化测试

- **单测**（无 neo4j）：`SqlTaskExecutor` 收 statementMetrics（多 statement / SELECT 跳过 / DDL）；`reportFinished` 逐 statement 解析写表 → recordSynced 调用（mock LineageStore）；statementMetrics null/empty 跳过；UPDATE/DELETE WARN。
- **集成**（testcontainers-neo4j）：端到端 SQL 任务 → `:SYNCED` + syncSummary SUM；多表 SUM；失败/非 SQL 不写。

## 降级 / 兼容验证

- 旧 worker（无 statementMetrics）+ 新 master → 跳过 recordSynced，不崩。
- 新 worker + 旧 master → `@JsonIgnoreProperties` 忽略多余字段，不崩。
- neo4j 不可达 → reportFinished try-catch，任务仍 SUCCESS。
- UPDATE/DELETE statement（updateCount>0 但无写表）→ WARN，不静默丢。
