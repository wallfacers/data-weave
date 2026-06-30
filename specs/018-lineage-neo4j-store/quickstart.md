# Quickstart: 血缘图底座（neo4j）

**Feature**: 018-lineage-neo4j-store | **Date**: 2026-06-30

本文给实现者/评审者一条最短验证路径：起 neo4j → 跑 Testcontainers 测试 → 验证血缘真入图。

---

## 1. 本地起 neo4j（开发态可视化验证）

neo4j 已作为硬依赖加入 `docker-compose.yml`（与 PG/Redis/MinIO 同级）：

```bash
cd /home/wallfacers/project/dw-018-lineage-neo4j-store
docker compose up -d neo4j        # 仅起 neo4j（或 docker compose up -d 起全部）
# Bolt: bolt://localhost:7687  ·  Browser UI: http://localhost:7474
# 凭据见 compose（如 neo4j/dataweave）
```

后端连接配置键（`application.yml` / 环境变量，由 `Neo4jConfig` 读取）：

```yaml
lineage:
  neo4j:
    uri: bolt://localhost:7687
    username: neo4j
    password: dataweave
```

启动后端后，`Neo4jSchemaInitializer` 建约束/索引，`Neo4jLineageSeeder` 幂等播种演示血缘（5 表/7 边/3 任务/1 指标）。

---

## 2. 跑 Testcontainers 集成测试（CI 真相）

测试用 Testcontainers neo4j（真容器，不依赖上面的常驻 neo4j）；遵循 WSL2 长命令脱离硬规则：

```bash
cd /home/wallfacers/project/dw-018-lineage-neo4j-store/backend
# 先快速本地构建装 ~/.m2
./dev-install.sh -pl dataweave-master -am

# 脱离会话跑血缘 IT（必须 setsid 脱离，否则 surefire/容器子进程持 pipe 卡到超时）
setsid bash -c './mvnw -pl dataweave-master test -Dtest="Neo4jLineageStoreIT,PushLineageIT,SchemaVersionConsistencyTest" >/tmp/build.log 2>&1; echo $? >/tmp/build.exit' </dev/null >/dev/null 2>&1 & disown

# 单次秒回轮询（禁前台 sleep 循环）
[ -f /tmp/build.exit ] && echo "DONE exit=$(cat /tmp/build.exit)" || { echo running; tail -1 /tmp/build.log; }
```

> Docker daemon 必须可用（Testcontainers 拉 neo4j 镜像）。首次拉镜像较慢。

---

## 3. 验证血缘真入图（三条核心断言）

### US1 建任务即入图
建一个 `INSERT INTO dwd_order SELECT * FROM ods_order` 任务（`createAndOnline`），在 neo4j Browser 跑：

```cypher
MATCH (t:Table) WHERE t.qualifiedName IN ['ods_order','dwd_order'] RETURN t;          // 两个 :Table
MATCH (:Task {taskDefId:$id})-[r:READS|WRITES]->(:Table) RETURN type(r), r;            // READS ods_order / WRITES dwd_order
MATCH (a:Table {qualifiedName:'ods_order'})-[:FLOWS_TO]->(b:Table {qualifiedName:'dwd_order'}) RETURN a,b;  // 表级流边
```
同时确认 PG **不再写** `data_table`/`task_table_io`（表已删除）。

### US3 数据源去重
用两个连接配置（同 `ip/port/database`、不同 username）各建一个引用同表的任务：

```cypher
MATCH (d:Datasource) WHERE d.ip='10.0.0.1' AND d.port=5432 AND d.database='warehouse' RETURN count(d);  // 期望 1
MATCH (:Datasource {database:'warehouse'})-[:HAS_TABLE]->(t:Table {qualifiedName:'...'}) RETURN count(t); // 目标表唯一
```
同 ip/port 不同 database → 两个不同 `:Datasource`（断言 count=2）。

### replace-per-task 幂等
对同一任务重复 `recordTaskIo` 两次：

```cypher
MATCH (:Task {taskDefId:$id})-[r:READS|WRITES]->() RETURN count(r);  // 与单次记录完全一致，无翻倍
```

### US2 push 也建血缘
对含 SQL 任务的项目 `dw push` 后，跑 US1 同款断言 —— 该任务血缘边应出现，语义与 `createAndOnline` 一致。

---

## 4. 韧性验证（neo4j 不可达不阻断）

停掉 neo4j（`docker compose stop neo4j`）后建任务 / `dw push` —— **主链路仍 100% 成功**（任务建成、push 成功），仅日志记血缘写入失败（SC-004 / FR-007）。恢复 neo4j 后新任务血缘正常入图。

---

## 5. schema 收口验证

```bash
# 四张血缘表已从 schema.sql 删除
grep -E "data_table|task_table_io|task_run_table_io|metric_lineage" backend/dataweave-api/src/main/resources/schema.sql   # 期望无 CREATE TABLE 命中

# schema_version 三处恒等：库内单行 / 文件头注释 / 项目版本
# 由 SchemaVersionConsistencyTest 自动校验（版本号较 0.0.1 已递增）
```

---

## 完成判据（对齐 Success Criteria）

- [ ] 建任务 / `dw push` 后库/表/任务读写/表级流血缘 100% 入 neo4j，PG 血缘四表不再被写（SC-001）。
- [ ] 同 `(ip,port,database)` 的 `:Datasource` 节点数恒为 1（SC-002）。
- [ ] 同任务重复记录边集合一致、无残留陈边（SC-003）。
- [ ] neo4j 不可达时建任务/push 成功率不受影响（SC-004）。
- [ ] Testcontainers neo4j 测试全绿；`schema_version` 三处恒等（SC-005）。
