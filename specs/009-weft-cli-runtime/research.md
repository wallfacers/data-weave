# Research: Weft 子特性 D —— CLI + 本地 runtime

**Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

所有决策落在真实代码上,标注复用点与 worker 当前实况。

## D1 — 本地真跑的执行载体(FR-005/006,US2 核心)

**Decision**: 新增一个**独立可运行的 Java 本地 runner 入口**(脱离 master/scheduler),复用 `dataweave-worker` 现有执行器;Go `dw` CLI 以**子进程**方式调起它(`java -cp … LocalRunMain`),逐行管道直出终端、透传退出码。本期不写 Go 原生执行器。

**Rationale**:
- worker 的 `TaskExecutor.execute(ExecutionContext ctx, Consumer<String> onLine) → ExecutionResult(success, exitCode, stdout, stderr, truncated, timedOut, message)` 正是需要的复用面:`ExecutionContext` 已携带 content/datasource(`DataSourceRef` 含 jdbcUrl/user/password/driver)/`shellEnvVars`/`pythonConfigPath`/timeout,`onLine` 逐行回调 = 实时管道,`exitCode` = 忠实退出码。
- 复用真实执行器 = **代码级零漂移**,直接满足宪法 v1.1.0 原则 III 的「phased implementation」(已追认,见 [constitution v1.1.0](../../.specify/memory/constitution.md))。
- 执行器各自起 `ProcessBuilder("bash","-c",…)`(Shell)/连 JDBC(SQL),本就**不依赖 master 调度**,可独立运行。

**Alternatives rejected**:
- Go 原生执行器 + 黄金契约测试钉一致性 —— 本期难度高,延后(宪法已定为 future target)。
- 把整个 worker SpringBootApplication 跑起来 —— 太重,本地 run 不需要心跳/认领/调度,只需执行器单元。

## D2 — Java runner 与 Go CLI 的解析边界(复用 B 不重发明格式)

**Decision**: **Go CLI 轻读任务元数据**(type、datasource 逻辑名、脚本体文件引用 —— 仅几个文档化字段),把 `(type, content, 已解析数据源连接, timeout)` 经 args/stdin 传给 Java runner;**Java runner 只负责执行**(构造 `ExecutionContext` → 选执行器 → `execute`),不依赖 B filecontract。

**Rationale**:
- 模块边界干净:Java runner 只依赖 `dataweave-worker`(执行器),不引入对 `dataweave-master`(filecontract 所在)的耦合。
- **不违反「复用 B 不重发明格式」**:同步(pull/push)全程**只搬运原始字节**(C 端做 serialize/deserialize),CLI 不解析;仅 `dw run` 轻读几个元数据键,非重写 B 的双向契约/合成 id/round-trip 逻辑。
- 轻读解析失败只影响"本地能否跑这个任务"(低爆炸半径),且 round-trip 完整性永不依赖 Go 解析。

**Alternatives rejected**:
- Java runner 复用 B filecontract 解析 → 需 runner 依赖 master 全模块,重且破坏分层。
- 抽 filecontract 成独立模块供 runner 复用 → 触碰"filecontract 包零修改"红线,风险高,延后评估。

## D3 — Python 执行器缺口(FR-007 MVP 含 PYTHON)

**Decision**: 在 `dataweave-worker` **新增 `PythonTaskExecutor`**(`type()=="PYTHON"`),与 Shell/Sql 同构(`ProcessBuilder("python3", …)` + `pythonConfigPath` 数据源 JSON 注入);**服务器与本地 runner 共享**。

**Rationale**:
- 现状只有 `ShellTaskExecutor`/`SqlTaskExecutor`/`EchoTaskExecutor`,**无 Python 执行器**;但 `ExecutionContext` 已预留 `pythonConfigPath` 字段 → 设计已留口。
- 新增到 worker 而非只在本地:让"一致性"有意义(本地与服务器跑 PYTHON 同一执行器),且服务器侧也获得 PYTHON 能力。
- 这是 worker 的**增量新增**(不改既有执行器),零回归风险。

**Alternatives rejected**: 仅本地 runner 内置 python 执行 —— 本地/服务器语义会分叉,违原则 III 初衷。

## D4 — `dw run <X>` 任务定位(FR-005)

**Decision**: **相对文件路径优先 + 任务名别名**。`dw run etl/daily/foo.task.yaml` 直接定位;`dw run foo`(或中文名)在工作副本内按 task 元数据 name 匹配,命中多个或中文名退化 hash 时报错并提示改用路径。

**Rationale**: 文件树里路径最自然、可 shell tab 补全、天然规避 B 的中文名 hash 退化;名字作便捷别名兼顾直觉。已在 clarify 裁定。

## D5 — 数据源本地连接配置(FR-008)

**Decision**: 本地 **git-ignored 配置文件** `.weft/datasources.local.yaml`(工作副本根),按 datasource 逻辑名映射连接串:`<逻辑名>: { typeCode, jdbcUrl, username, password }`。`dw run` SQL 任务时按任务声明的逻辑名查表 → 构造 `DataSourceRef` 传 runner。凭据仅此本地文件,`.weft/` 全量进 `.gitignore`,**绝不随 push 上行**(push 只搬 B 契约文件,数据源定义本就不入契约)。

**Rationale**: 凭据本地持有、可复用、git 忽略,契合 FR-008 与原则 II(凭据不入文件/git)。已在 clarify 裁定。缺失逻辑名时可定位报错(FR-007)。

## D6 — TEST 提交与日志回传(FR-009,US3)

**Decision**: **零新服务端端点**。`dw run --test <task>`:① 在工作副本所属 project 内按 name 解析出**服务器 task id**(复用任务列表查询);② `POST /api/tasks/{id}/run`(既有端点,`toolName=test_run`/`actionType=TEST_RUN`,经写闸门,run_mode=TEST 跑草稿);③ 取实例 id,`GET /api/ops/instances/{id}/logs/stream`(既有 SSE,`text/event-stream`)流式直出;④ 退出码反映实例终态。

**Rationale**:
- TEST 提交端点已存在(`TaskController` `/{id}/run` → `TestRunCommand` 编码 → gated TEST_RUN),日志流端点已存在 → D 纯客户端复用。
- TEST = "贴近生产验证",语义上要求定义已在服务器(有 id),故"先 push / 已存在再 run --test"是合理前置;MVP 范围限单任务 TEST(workflow TEST 留后续)。

**Alternatives rejected**: 新增"按名 TEST 提交"端点 —— 不必要,按名解析 id 在 CLI 侧即可。

## D7 — 同步命令与本地工作副本状态

**Decision**: `dw pull/push/diff` 直接打 C 的 `POST /api/projects/{id}/pull|push|diff`(已落地)。pull 落地文件树 + 写隐藏元数据 `.weft/state.json`(projectId + 上次 pull 的 baseline 令牌);push 读 `.weft/state.json` 的 baseline 做乐观并发(C 已支持 baseline/force);diff 只读。pull 目标目录非空且冲突 → 默认拒绝,除非 `--force`/`--clean`(安全默认)。

**Rationale**: C 的 `PullResult{bundle,baseline,fileCount}` / `PushCommand{files,baseline,force,expectedFileCount,remark}` / `DiffPreview{added,modified,removed,stale}` 直接映射 CLI 行为;baseline 复用 C 的 SHA256 摘要,CLI 不自造并发模型。

## 复用点汇总(零修改)

| 复用 | 来源 | D 的用法 |
|---|---|---|
| `ProjectSyncService` pull/push/diff 端点 | C(008) | US1 同步 |
| `TaskExecutor`/`ExecutionContext`/各执行器 | worker | US2 本地 runner 复用执行 |
| `POST /api/tasks/{id}/run`(TEST_RUN,gated) | 既有 | US3 TEST 提交 |
| `GET /api/ops/instances/{id}/logs/stream` SSE | 既有 | US3 日志回传 |
| B filecontract | B(007) | 仅服务器侧 serialize/deserialize(CLI 不直接调) |

## 新增点(本期)

- `dw` Go CLI:`pull`/`push`/`diff`/`run`/`run --test` 子命令(`cli/`)。
- Java 本地 runner 入口:`LocalRunMain`(复用 worker 执行器,脱离 master 独立运行)。
- `PythonTaskExecutor`(worker,服务器+本地共享)。
- 本地配置:`.weft/state.json`、`.weft/datasources.local.yaml`(git-ignored)。
