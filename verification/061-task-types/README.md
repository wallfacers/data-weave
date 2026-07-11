# 061 大数据任务类型真实引擎验证 — Harness

对 059 新增/暴露的任务类型执行器做**真实引擎**端到端真跑取证。单测只覆盖了「命令构造 + 缺引擎→SKIPPED」两条路径，本 harness 证明真实 SUCCESS 路径。

规格：`specs/061-task-type-verification/`（spec/plan/tasks/research/data-model/contracts/quickstart）。

## 硬门（贯穿每引擎）

三态必过，缺一不算通过：
- **SUCCESS**：真引擎在位 + success 夹具 → 退出码 0 + 结果证据（结果集/影响行数 或 引擎原生 stdout/stderr）。
- **FAILURE**：真引擎在位 + fail 夹具（**作业自身错**，非缺引擎）→ 非 0 退出码 + 引擎原生错误行。
- **SKIPPED 对照**：停引擎/清 `*_HOME` 跑同一 success 夹具 → 「已跳过：<原因>」，不阻塞下游。

**起不来 = 阻塞项须解决**（加内存 / 换镜像 / 换接入方式）；穷尽手段仍不可行者在台账记 `BLOCKED`（计未达标），**绝不以假替身脚本 / 标注冒充 PASS**。

## 目录

```
verification/061-task-types/
├── net.yml                 # 共享 docker network dw061net（各 compose external 引用）
├── compose.olap.yml        # 工作流A：starrocks/doris/clickhouse（profile olap）
├── compose.hive.yml        # 工作流A：hiveserver2+metastore（profile hive）
├── compose.integration.yml # 工作流B：datax/seatunnel 的 source/sink（profile integration）
├── compose.compute.yml     # 工作流C：spark standalone + flink jm/tm（profile compute）
├── clients/                # 子进程引擎宿主机客户端安装脚本（*_HOME）
├── jobs/                   # 每引擎最小作业夹具 <engine>.success.* / <engine>.fail.*
└── scripts/                # up.sh/down.sh/capture.sh/verify-<engine>.sh
```

## 错峰使用约定（单台 WSL2 防 OOM）

- **禁全量常驻**。每条工作流只起自己那批 profile，跑完立刻停：
  ```bash
  ./scripts/up.sh olap      # 起 OLAP 数仓
  #  ...真跑取证...
  ./scripts/down.sh olap    # 立即停
  ```
- profile：`olap` / `hive` / `integration` / `compute`。三工作流时间上错峰共享本机 Docker。

## 前置（后端 all-in-one + CLI，跑在宿主机保原则 III 保真）

```bash
cd backend && docker compose up -d postgres redis
./mvnw -pl dataweave-api spring-boot:run          # :8000  健康 GET /api/health
cd ../cli && ./build.sh                            # 产出 dw
export DW_API=http://localhost:8000 DW_TOKEN=<token>
```

## 证据与台账

- 每次真跑经 `scripts/capture.sh` 抓取 → `specs/061-task-type-verification/evidence/<engine>/<kind>.log`（脱敏）+ upsert 一行进 `evidence/ledger.json`（结构见 `contracts/evidence-ledger.schema.json`）。
- 汇总台账 `evidence/LEDGER.md`；整体达标 ⟺ 10 引擎行全 `PASS`。

## 多 Agent 协作（3 工作流并行）

- A（本人）OLAP+Hive / B（外部 Agent1）DataX+SeaTunnel / C（外部 Agent2）Spark+Flink。
- 各自 worktree `../dw-061-{a,b,c}`；**按 family 拆 compose 文件 → 各改各的、零覆盖**；共享只读面（`net.yml`/台账格式/任务类型枚举/`dw` 用法）合并前对齐，遵仓库多 Agent 硬规则不覆盖他人产出。
