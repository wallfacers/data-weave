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
| Spark | C | PENDING | — | — | — | n/a | — | pyspark 取一形态 |
| Flink | C | PENDING | — | — | — | n/a | — | long_running reattach(SC-005) |
| Python | A | PENDING | — | — | — | n/a | — | 回归确认 |
| Shell | A | PENDING | — | — | — | n/a | — | 回归确认 |

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
