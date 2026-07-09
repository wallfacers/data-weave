# Quickstart / 验证指南：大数据开发任务类型补全

端到端验证每种新任务类型。**核心不变量**：无引擎环境 → SKIPPED（不 fail、不阻塞）；有引擎 → 真跑、退出码忠实透传；本地 `dw run` 与服务端语义一致。

## 前置

```bash
# 后端（零外部依赖跑 SKIPPED 闭环用 h2）
cd backend && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000
# 前端
cd frontend && pnpm install && pnpm dev                                  # :4000
# CLI
cd cli && ./build.sh
```

## 场景 1 — OLAP SQL（US1，已就绪，主要验证）

数据源类型 STARROCKS/DORIS/CLICKHOUSE 已 seed。

1. 前端「数据源」创建一个 ClickHouse/StarRocks 数据源（若无真库，可验证 SKIPPED 分支）。
2. 创建 `SQL` 任务绑定该数据源，写分析 SQL（含 `SHOW TABLES;` 与一条 `SELECT`），试跑。
3. **有库**：见「连接→逐条执行→**`SHOW TABLES` 结果表头+表名行**/`SELECT` 数据行（超 200 行标注已截断）→影响/返回行数→完成」日志，`success`（FR-011a）。
4. **无库/无驱动**：见「已跳过：数据源连接失败/未配置」，不阻塞下游。

参见 [research.md D1/D10](./research.md)。验证点：ClickHouse 逐条 execute 不误报行数；StarRocks/Doris affected-rows 正确回填；`SHOW TABLES` 结果集渲染进日志、密码不回显。

## 场景 2 — Hive HQL（US1，新）

```bash
# 本地：缺 Hive 连接 → SKIPPED
dw run <hive-task>        # 期望：stderr 打印「已跳过：…」，exit 0（跳过约定）
```

1. 创建 `HIVE` 任务，写 HQL（含 `SET` 会话指令 + 分区写入）。
2. 绑定 HIVE 数据源（HiveServer2 JDBC）。
3. **有 HiveServer2**：逐条执行，分区写入不误报行数，退出码透传。
4. **无连接**：SKIPPED。

## 场景 3 — DataX（US2，新）

```bash
export DATAX_HOME=/opt/datax     # 有引擎时
dw run <datax-task>              # job JSON 作为内容
# 无 DATAX_HOME → 期望 SKIPPED（stderr「已跳过：DATAX_HOME 未配置」）
```

1. 创建 `DATAX` 任务，粘贴最小 reader→writer job JSON。
2. **有 DataX**：`datax.py` 子进程逐行同步日志，退出码透传。
3. **无 DataX**：SKIPPED，不阻塞下游。

## 场景 4 — SeaTunnel（US2，新）

```bash
export SEATUNNEL_HOME=/opt/seatunnel
dw run <seatunnel-task>          # 无 HOME → SKIPPED
```

1. 创建 `SEATUNNEL` 任务，写 source→sink 配置。
2. **有引擎**：`seatunnel.sh --config` 子进程日志；**无引擎**：SKIPPED。

## 场景 5 — Flink（US3，新）+ 入口全类型暴露

```bash
export FLINK_HOME=/opt/flink
dw run --flink-mode sql <flink-sql-task>     # 无 FLINK_HOME → SKIPPED
```

1. **前端入口验证（浏览器门）**：打开创建任务对话框，任务类型下拉可见并可选 `SQL/SHELL/PYTHON/SPARK/HIVE/FLINK/DATAX/SEATUNNEL`；各类型编辑器语言高亮正确。
2. 创建 `FLINK` 任务（sql 形态），试跑：**有 Flink** → `flink`/`sql-client` 子进程日志、退出码透传；**无 Flink** → SKIPPED。
3. 从入口创建 `SPARK`/`PYTHON` 任务并试跑，行为与既有执行器一致。

## 场景 6 — 保真 parity（Constitution III）

后端测试断言本地↔服务端逐项相等：

```bash
cd backend
setsid bash -c './mvnw -pl dataweave-worker test -Dtest="*TaskExecutorTest,LocalRunMain*Test" >/tmp/build.log 2>&1; echo $? >/tmp/build.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f /tmp/build.exit ] && echo done=$(cat /tmp/build.exit) || tail -1 /tmp/build.log
```

期望：每个新执行器的 buildCommand/SKIPPED/退出码透传单测 + parity 断言全绿。

## 场景 7 — round-trip（Constitution II）

```bash
dw push <flink-task> && dw pull <project> /tmp/clean && dw diff   # 期望：无差异（type/content/params 无丢失）
```

## 完成判据

- [ ] 7 类引擎家族均可在平台内创建 + 运行（有引擎真跑 / 无引擎 SKIPPED）
- [ ] 创建入口可选类型 ≥8 种，编辑器语言高亮正确
- [ ] 缺引擎环境 100% SKIPPED，不阻塞下游、不中断调度
- [ ] 本地 `dw run` 与服务端结果分类一致（parity 测试绿）
- [ ] 每个新执行器 CI 无外部依赖跑通 SKIPPED 闭环
- [ ] push→pull round-trip 无字段丢失
- [ ] 日志规范（SC-007）：各类型运行日志含起止 banner + 执行过程；SQL/HQL `SHOW TABLES` 结果集在日志可见；引擎类原生 stdout/stderr 逐行透出；经 SSE 实时可见、凭据脱敏
- [ ] `content` 列上限已裁决（T002a）：足够则维持，超限则已扩列 + bump schema_version
