# Contract: `dw` CLI 命令面（子特性 D）

沿用 `cli/main.go` 现有 switch 分发与 `DW_API`(默认 `:8000`)/`DW_TOKEN` 约定。所有退出码:0=成功,非0=失败(区分用法错误 vs 服务端/网络错误,FR-013)。

## `dw pull <project>` （US1,FR-001）

- **解析**:`<project>` = project id 或 code。
- **动作**:`POST {DW_API}/api/projects/{id}/pull`(Bearer/令牌)→ `ApiResponse<PullResult{projectId,bundle{files},baseline,fileCount}>`;把 `files{path→content}` 落地为本地文件树;写 `.weft/state.json`。
- **目标目录非空**:默认拒绝并提示;`--force`/`--clean` 才覆盖(D7 安全默认)。
- **错误**:越权/无效令牌 → 透传 C 错误码,非0 退出。

## `dw push [--force]` （US1,FR-002/004）

- **动作**:读本地文件树 + `.weft/state.json.baseline` → `POST /api/projects/{id}/push` body `PushCommand{files,baseline,force,expectedFileCount,remark}` → `PushResult{created,updated,deleted,snapshots,newBaseline}`。
- **基线过期**(C 返回 stale/冲突)且无 `--force` → 拒绝,非0 退出,提示先 pull/diff;`--force` 覆盖。
- **成功**:打印 created/updated/deleted 统计;把 `newBaseline` 写回 `.weft/state.json`。
- **校验失败**(无效/不完整定义、未知数据源、删除 ONLINE 引用)→ 透传 C 可定位错误码,不部分落库,非0 退出。

## `dw diff` （US1,FR-003,只读）

- **动作**:`POST /api/projects/{id}/diff` body `PushCommand{files,baseline,…}` → `DiffPreview{added,modified,removed,stale}`;终端表格呈现。
- **不变量**:对服务器**零写入**;`stale=true` 时提示 baseline 过期。

## `dw run <task>` （US2,FR-005/006/007/008）

- **定位**:`<task>` = 相对文件路径(优先)| 任务名别名(D4);多义/中文 hash 退化 → 报错提示用路径。
- **动作**:轻读任务元数据(type/datasource/content/timeout)→ SQL/PYTHON 时查 `.weft/datasources.local.yaml` 解析连接 → 调起 `java … LocalRunMain --type … --timeout … [--ds-json …] < content`;逐行 stdout/stderr 直出;**透传 runner 退出码**。
- **脱机**:不需服务器、不需 master/worker 调度进程(本期需本机 JVM)。
- **错误**:缺数据源逻辑名 / 缺 JVM / 缺 python3 → 可定位环境错误(FR-007),非0 退出。

## `dw run --test <task>` （US3,FR-009/010）

- **动作**:① 解析 server task id(项目内按 name 查;需任务已存在于服务器,通常先 push);② `POST /api/tasks/{id}/run`(body 可含 bizDate;`actionType=TEST_RUN`,经写闸门,run_mode=TEST 跑草稿)→ `GateResult`;③ 取实例 id → `GET /api/ops/instances/{id}/logs/stream`(`text/event-stream`)消费直至终态;④ 退出码反映实例成败。
- **闸门**:TEST_RUN 经 PolicyEngine + 审计;越权被拒(FR-010),非0 退出。
- **流断开**:明确状态提示,不静默挂死(Edge Case)。

## 复用的服务端端点（均已落地,D 零新增）

| 端点 | 来源 | 用途 |
|---|---|---|
| `POST /api/projects/{id}/pull\|push\|diff` | C(008) ProjectSyncController | 同步 |
| `POST /api/tasks/{id}/run`(TEST_RUN gated) | 既有 TaskController | TEST 提交 |
| `GET /api/ops/instances/{id}/logs/stream` | 既有 OpsController SSE | 日志回传 |
