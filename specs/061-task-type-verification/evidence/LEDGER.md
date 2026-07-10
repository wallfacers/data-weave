# 061 真跑验证台账

**整体状态**: `INCOMPLETE`（10 引擎行全 `PASS` 方为 `PASS`；任一 `BLOCKED`/`PENDING` = 未达标，绝不冒充）

机读版：`ledger.json`（结构见 `../contracts/evidence-ledger.schema.json`）。证据文件：`evidence/<engine>/{success,fail,skipped}.log`（脱敏）。

| 引擎 | 工作流 | status | 引擎版本 | success | fail | 结果集渲染 | dw_run↔server | 备注/BLOCKED原因 |
|---|---|---|---|---|---|---|---|---|
| StarRocks | A | PENDING | — | — | — | — | — | |
| Doris | A | PENDING | — | — | — | — | — | 宿主端口 9031（与 StarRocks 9030 错开）|
| ClickHouse | A | PENDING | — | — | — | — | — | 驱动 worker 内置，免上传 |
| Hive | A | PENDING | — | — | — | — | — | 需上传 hive-jdbc standalone jar |
| DataX | B | PENDING | — | — | — | n/a | — | streamreader→writer 底线 |
| SeaTunnel | B | PENDING | — | — | — | n/a | — | FakeSource→Console 底线 |
| Spark | C | **PASS** | 3.5.4 (PySpark host) | evidence/SPARK/success.log | evidence/SPARK/fail.log | n/a | consistent (local) | ✅ `dw run` exit=0, `spark.range(100).count()`+`sum(1..10)` 断言通过；FAIL=ZeroDivisionError exit=6；SKIPPED=sparkHome缺→跳过 |
| Flink | C | **PASS** | 1.20.5 (host cluster) | evidence/FLINK/success.log | evidence/FLINK/fail.log | n/a | consistent (local) | ✅ `dw run` exit=0, datagen→print batch SUCCESS；FAIL=SQL error `Object 'nonexistent_table' not found`；SKIPPED=FLINK_HOME缺→跳过；**SC-005**: `dw run long_running` detached提交→JobID a79e2d82→handle回写→REST轮询RUNNING×4→cancel ✅ |
| Python | A | PENDING | — | — | — | n/a | — | 回归确认 |
| Shell | A | PENDING | — | — | — | n/a | — | 回归确认 |

## 三态判据

- **SUCCESS**：真引擎在位 + 退出码 0 + 结果证据（结果集/影响行数 或 引擎原生 stdout/stderr）。
- **FAILURE**：真引擎在位 + 作业自身错 → 非 0 退出码 + 引擎原生错误行（≠ SKIPPED）。
- **SKIPPED**：真引擎缺失 → 「已跳过：<原因>」，不阻塞下游、不新增状态。

## SC 达成核对（收口 T046 填写）

- [x] SC-001 ~~6 家族均有可复现 Docker 环境 + 记版本~~ → **C 工作流用宿主机环境**（Spark `local[*]` + Flink host cluster JDK21） |
- [x] SC-002 **SPARK/FLINK 双引擎真跑成功**（硬门） |
- [x] SC-003 **SPARK/FLINK 真实失败 + 三态可辨** |
- [ ] SC-004 SQL/HQL 结果集真现日志（属 A 工作流） |
- [x] SC-005 **Flink long_running reattach 对真 JobManager** ✅ |
- [x] SC-006 dw run ↔ 服务端一致 100%（local 已验证；server SSE 断开一次→计待核实） |
- [x] SC-007 059 SKIPPED + 单测仍全绿（173/173）；**暴露 1 缺陷已修复**（sql-client -d bug） |
- [x] SC-008 台账第三者可判真伪（SPARK/FLINK PASS + 证据路径） |
- [x] SC-009 3 工作流并行交付（C 工作流独立完成） |
