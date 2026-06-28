# Tasks: Weft 子特性 D —— CLI 同步命令 + 本地轻量 runtime

**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Branch**: `009-weft-cli-runtime`

复用约束(硬):B(filecontract)/C(ProjectSyncService 端点)**零修改**;本地 runner 复用 `dataweave-worker` 执行器,**仅依赖 worker、不耦合 master**;Go CLI 沿用 `cli/main.go` switch 分发 + `DW_API`/`DW_TOKEN`。测试禁 `_skipped`/注释 `@Test`;后端真跑 `-Dmaven.build.cache.enabled=false`。

## Phase 1: Setup

- [ ] T001 在 `cli/` 下建 `sync/`、`run/` 子包目录,确认 `cli/go.mod` module path,`cli/main.go` 现有 `task`/`logs` case 不动
- [ ] T002 [P] 在工作副本约定根写 `.gitignore` 模板片段(`/.weft/`),并定义 `.weft/state.json`、`.weft/datasources.local.yaml` 的常量路径于 `cli/sync/workcopy.go`
- [ ] T003 [P] 确认 `dataweave-worker` 测试基座(JUnit5+AssertJ)可加 `localrun`/`infrastructure` 新测试,无需新依赖

## Phase 2: Foundational（阻塞所有 US)

- [ ] T004 在 `cli/sync/workcopy.go` 实现 `.weft/state.json` 读写(projectId/baseline/fileCount/apiBase)+ 文件树 ↔ `files{path→content}` 互转(pull 落地 / push 收集)
- [ ] T005 在 `cli/` 共享 HTTP 客户端封装(复用 `DW_API`/`DW_TOKEN`,`ApiResponse<T>` 信封解析,错误码透传,区分用法错误 vs 服务端/网络错误 → 退出码)— FR-013
- [ ] T006 [P] `cli/run/datasource.go`:解析 `.weft/datasources.local.yaml`(逻辑名 → typeCode/jdbcUrl/username/password),缺失逻辑名返回可定位错误 — FR-008

## Phase 3: User Story 1 — 本地工作副本往返同步 (P1)

**Independent Test**: `dw pull` 到空目录 → 改脚本 → `dw diff` 见 modified → `dw push` → 再 pull 到干净目录语义等价。

- [ ] T007 [US1] `cli/sync/pull.go`:`dw pull <project>` → `POST /api/projects/{id}/pull`,落地文件树 + 写 state;目标目录非空默认拒绝,`--force`/`--clean` 才覆盖 — FR-001
- [ ] T008 [US1] `cli/sync/diff.go`:`dw diff` → `POST /api/projects/{id}/diff`,呈现 added/modified/removed,`stale` 提示;对服务器零写入 — FR-003
- [ ] T009 [US1] `cli/sync/push.go`:`dw push [--force]` → `POST /api/projects/{id}/push`(files+baseline+force+expectedFileCount),报告 created/updated/deleted,把 newBaseline 写回 state — FR-002/004
- [ ] T010 [US1] 在 `cli/main.go` switch 注册 `pull`/`push`/`diff` case,分发到上述 handler,统一退出码
- [ ] T011 [P] [US1] `cli/sync/pull_test.go` + `push_test.go` + `diff_test.go`:文件树 I/O、baseline 写回、目标非空策略、基线过期拒绝/force、越权非0 退出
- [ ] T012 [US1] 集成验证:对真实项目 pull→改→push→再 pull 到另一干净目录,断言两次文件树语义等价(SC-001)

## Phase 4: User Story 2 — 本地轻量 runtime 真跑 (P1)

**Independent Test**: 本地 SHELL/SQL/PYTHON 任务 `dw run` 与服务器执行器退出码/输出一致;失败任务非0 退出。

- [ ] T013 [US2] `dataweave-worker` 新增 `infrastructure/PythonTaskExecutor.java`(`type()=="PYTHON"`,`ProcessBuilder("python3",…)` + `pythonConfigPath` 数据源注入),与 Shell/Sql 同构 — FR-007
- [ ] T014 [P] [US2] `PythonTaskExecutorTest`:成功/失败退出码、stdout-stderr 分流、超时中止、数据源 JSON 注入
- [ ] T015 [US2] `dataweave-worker` 新增 `localrun/LocalRunArgs.java`(解析 `--type/--timeout/--ds-json` + stdin content)
- [ ] T016 [US2] `dataweave-worker` 新增 `localrun/LocalRunMain.java`:构造 `ExecutionContext` → 按 type 选执行器(Shell/Sql/Python)→ `execute(ctx, line→stdout)` → `System.exit(result.exitCode())`;脱离 master/scheduler 独立运行
- [ ] T017 [US2] `cli/run/local.go`:`dw run <task>` 轻读任务元数据(type/datasource/content/timeout)→ SQL/PYTHON 查 datasource.go 解析连接 → 调起 `java … LocalRunMain` 子进程,管道 stdout/stderr,**透传退出码** — FR-005/006
- [ ] T018 [US2] 任务定位:相对文件路径优先 + 任务名别名匹配;多义/中文 hash 退化 → 报错提示用路径 — FR-005(D4)
- [ ] T019 [US2] 在 `cli/main.go` 注册 `run` case(无 `--test` 走本地);缺 JVM/python3/数据源 → 可定位环境错误,非0 退出
- [ ] T020 [P] [US2] `cli/run/local_test.go` + `datasource_test.go`:任务定位、数据源解析、退出码透传、失败任务非0(SC-004)
- [ ] T021 [US2] `localrun/LocalRunMainParityTest`:同一 (type,content) 经 LocalRunMain vs 服务器执行器,exitCode/stdout-stderr/超时**逐项相等**(SC-002 黄金对照)

## Phase 5: User Story 3 — TEST 模式提交服务器 (P2)

**Independent Test**: `dw run --test` 建 TEST 实例 + 日志流回本地至终态;受闸门+隔离。

- [ ] T022 [US3] `cli/run/testrun.go`:解析 server task id(项目内按 name 查)→ `POST /api/tasks/{id}/run`(bizDate 可选,gated TEST_RUN)→ 取实例 id — FR-009
- [ ] T023 [US3] 消费 `GET /api/ops/instances/{id}/logs/stream`(`text/event-stream`)流式直出至终态;流断开给明确状态提示;退出码反映实例成败 — FR-009
- [ ] T024 [US3] `cli/main.go` `run` case 识别 `--test` 分流到 testrun;越权/闸门拒 → 非0 退出 — FR-010
- [ ] T025 [P] [US3] `cli/run/testrun_test.go`:id 解析、TEST 提交经闸门、日志流消费、流断开处理、越权拒

## Phase 6: Polish & Cross-Cutting

- [ ] T026 [P] `dw` 帮助文案 + 错误信息 i18n 一致性(用法错误 vs 服务端错误可区分);更新 `cli/` README/用法
- [ ] T027 [P] 在 CLAUDE.md「Knowledge Base Navigation」补一行 D 导航(dw CLI + LocalRunMain)— 由架构(我)统一改,避免与 E worker 抢 CLAUDE.md
- [ ] T028 全量验证:`cd cli && go test ./...` 绿;后端 `./mvnw -q -pl dataweave-worker test -Dmaven.build.cache.enabled=false` 绿;quickstart.md 闭环手测(pull→run→diff→push→run --test)

## Dependencies & 并行

- Setup(T001-003)→ Foundational(T004-006)→ 各 US。
- **US1(T007-012)与 US2(T013-021)可并行**(US2 仅依赖 B/worker,不依赖 C)。US3(T022-025)依赖 US1 的 HTTP 基座 + C。
- `[P]` = 不同文件可并行。T021/T014 是 SC 黄金对照,优先级高。
- **MVP = US1 + US2**(同步往返 + 本地真跑);US3 为贴近生产验证补充。

## 交叉面(与 E 对账)

- D 的 `dw push` 与 E 的 `project_push` 共用 C 同一 `ProjectSyncService` + 同一写闸门语义 —— D 走 HTTP 端点、E 走 MCP+执行器,底层一致,集成由架构对账。
- T027 改 CLAUDE.md 与 E 的同类任务都由架构(我)串行改,避免冲突。
