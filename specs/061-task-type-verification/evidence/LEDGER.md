# 061 真跑验证台账

**整体状态**: `INCOMPLETE`（10 引擎行全 `PASS` 方为 `PASS`；任一 `BLOCKED`/`PENDING` = 未达标，绝不冒充）

机读版：`ledger.json`（结构见 `../contracts/evidence-ledger.schema.json`）。证据文件：`evidence/<engine>/{success,fail,skipped}.log`（脱敏）。

| 引擎 | 工作流 | status | 引擎版本 | success | fail | 结果集渲染 | dw_run↔server | 备注/BLOCKED原因 |
|---|---|---|---|---|---|---|---|---|
| StarRocks | A | PENDING | — | — | — | — | — | |
| Doris | A | PENDING | — | — | — | — | — | 宿主端口 9031（与 StarRocks 9030 错开）|
| ClickHouse | A | **PASS** | clickhouse-server:24.3 (24.3.18.7) | ✅ success.log | ✅ fail.log | ✅ | consistent | 本地dw run+服务端--test双SUCCESS；三态齐(SUCCESS exit0/FAIL exit6 UNKNOWN_TABLE/SKIPPED exit0连接失败)；SHOW TABLES+SELECT结果集表头+数据行真现于日志 |
| Hive | A | PENDING | — | — | — | — | — | 需上传 hive-jdbc standalone jar |
| DataX | B | **PASS** | datax_v202309 | ✅ success.log | ✅ fail.log | n/a | consistent | streamreader→writer 底线：5记录/0错误/原生统计透出；fail=mysqlreader→不存在表(DataX原生报错)；SKIPPED=确认真·引擎在位vs缺引擎三态可辨 |
| SeaTunnel | B | PENDING | — | — | — | n/a | — | FakeSource→Console 底线 |
| Spark | C | PENDING | — | — | — | n/a | — | pyspark 取一形态 |
| Flink | C | PENDING | — | — | — | n/a | — | long_running reattach(SC-005) |
| Python | A | PENDING | — | — | — | n/a | — | 回归确认 |
| Shell | A | PENDING | — | — | — | n/a | — | 回归确认 |

## 真跑暴露的缺陷（FR-011）

- **D1（009 CLI sync 路径，非 061 执行器）**：`dw push` 的完整性校验用 pull 基线文件数当 `expectedFileCount`（`cli/sync/push.go:39` 发 `state.FileCount`），任何**新增/删除文件**都会撞 `project.sync.incomplete`（服务端 `ProjectSyncService.java:635` `files.size() != expectedFileCount`）→ 正常「加任务再 push」被拒。正确修法：该行改 `len(files)`（push.go:63 成功后本就写 `state.FileCount=len(files)`）。**当前 workaround**：push 前把 `.weft/state.json.fileCount` 对齐为实际文件数。属 009 sibling 缺陷，是否并入 061 或另开修复待定，不阻塞 ClickHouse 真跑取证（本地 dw run 无需 push）。

## 三态判据

- **SUCCESS**：真引擎在位 + 退出码 0 + 结果证据（结果集/影响行数 或 引擎原生 stdout/stderr）。
- **FAILURE**：真引擎在位 + 作业自身错 → 非 0 退出码 + 引擎原生错误行（≠ SKIPPED）。
- **SKIPPED**：真引擎缺失 → 「已跳过：<原因>」，不阻塞下游、不新增状态。

## SC 达成核对（收口 T046 填写）

- [ ] SC-001 6 家族均有可复现 Docker 环境 + 记版本
- [ ] SC-002 每引擎真跑成功（硬门：全 PASS）
- [ ] SC-003 每引擎真实失败 + 三态可辨
- [ ] SC-004 SQL/HQL 结果集真现日志
- [ ] SC-005 Flink long_running reattach 对真 JobManager
- [ ] SC-006 dw run ↔ 服务端一致 100%
- [ ] SC-007 059 SKIPPED + 单测仍全绿；缺陷修复重跑取证
- [ ] SC-008 台账第三者可判真伪
- [ ] SC-009 3 工作流并行交付
