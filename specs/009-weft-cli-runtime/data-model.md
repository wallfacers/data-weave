# Data Model: Weft 子特性 D —— CLI + 本地 runtime

**Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

D 不新增 DB 实体。以下为本地文件态结构 + 子进程接口契约。

## 1. 本地工作副本状态 `.weft/state.json`（git-ignored）

pull 时写、push 读 baseline。一个工作副本根一份。

```json
{
  "apiBase": "http://localhost:8000",
  "projectId": 12,
  "projectCode": "demo",
  "baseline": "a1b2c3d4e5f60718",
  "pulledAt": "2026-06-27T10:00:00Z",
  "fileCount": 37
}
```

| 字段 | 来源 | 用途 |
|---|---|---|
| projectId | pull 入参 | push/diff/run --test 目标 |
| baseline | C `PullResult.baseline`(SHA256 前 16 hex) | push 乐观并发基线;过期且无 `--force` 拒绝 |
| fileCount | C `PullResult.fileCount` | push `expectedFileCount` 校验 |

## 2. 本地数据源配置 `.weft/datasources.local.yaml`（git-ignored，凭据本地持有）

按 datasource 逻辑名映射本地连接;`dw run` SQL 任务时查表。**绝不上行**。

```yaml
# 逻辑名 → 本地连接（凭据仅此文件，.weft/ 全量 .gitignore）
warehouse_pg:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/warehouse
  username: dev
  password: devpass
ods_mysql:
  typeCode: MYSQL
  jdbcUrl: jdbc:mysql://localhost:3306/ods
  username: root
  password: ""
```

映射到 worker `ExecutionContext.DataSourceRef(name, typeCode, jdbcUrl, username, password, …)`。缺失逻辑名 → `dw run` 可定位报错(FR-007)。

## 3. Java 本地 runner 子进程契约（Go CLI ↔ `LocalRunMain`）

Go 轻读任务元数据后,以参数 + stdin(脚本体)调起 Java runner;runner 复用 worker 执行器执行,逐行 stdout 直出,`System.exit(exitCode)`。

**调用**:`java -cp <worker-runtime-cp> com.dataweave.worker.localrun.LocalRunMain --type SQL --timeout 600 [--ds-json <file>] < script-body`

| 入参 | 说明 | 映射 |
|---|---|---|
| `--type` | SHELL/SQL/PYTHON | 选执行器 `TaskExecutor.type()` |
| `--timeout` | 秒,≤0 不限 | `ExecutionContext.timeoutSeconds` |
| `--ds-json` | 已解析数据源连接 JSON(SQL/PYTHON)| 构造 `DataSourceRef` / `pythonConfigPath` |
| stdin | 脚本体(content) | `ExecutionContext.content` |

**输出契约**:执行器 `onLine` 逐行 → runner 直写 stdout;结束 `System.exit(result.exitCode())`(成功 0、失败非 0、超时按 ExecutionResult.timedOut → 非 0)。Go CLI 透传该退出码为 `dw` 退出码。

**不变量(SC-002)**:同一 (type, content, ds) 经 runner 与经服务器执行器执行,exitCode / stdout-stderr 分流 / 超时中止行为**逐项相等**(同一 `AbstractTaskExecutor` 实现 → 代码级一致)。`LocalRunMainParityTest` 断言之。

## 4. 任务元数据轻读（Go，仅 `dw run` 用）

Go 从 `<task>.task.yaml` 读取最小字段(不重写 B 双向契约):

| 键 | 用途 |
|---|---|
| `type` | 选执行器 |
| `datasource`(逻辑名) | 查 `.weft/datasources.local.yaml` |
| 脚本体引用(同名 `.sql/.sh/.py`) | 读 content 经 stdin 传 runner |
| `name` | `dw run <名字>` 别名匹配 |
| `timeoutSeconds`(若有) | runner timeout |

解析失败仅影响本地 run(低爆炸半径);同步 round-trip 永不依赖此解析。

## 5. CLI 命令面（详见 [contracts/dw-cli.md](./contracts/dw-cli.md)）

| 命令 | 动作 | 复用 |
|---|---|---|
| `dw pull <project>` | 拉取为文件树 + 写 state | C `/pull` |
| `dw push [--force]` | 文件树推回 + 快照 | C `/push` |
| `dw diff` | 只读差异 | C `/diff` |
| `dw run <task>` | 本机真跑 | Java runner(worker 执行器) |
| `dw run --test <task>` | TEST 提交 + 日志流 | `/api/tasks/{id}/run` + 日志 SSE |

## 6. 状态转移

- **pull**:(server 项目) → 本地文件树 + `.weft/state.json{baseline}`。
- **edit**:本地文件改动(AI/编辑器),`.weft/state.json` 不变 → diff 显示偏离。
- **push**:本地文件树 + state.baseline → C(baseline 匹配则覆盖+快照+新 baseline;不匹配且无 force 则拒);成功后**应刷新本地 baseline**(建议 push 返回 newBaseline 写回 state)。
- **run**:本地文件 → runner 子进程 → 退出码(无服务器副作用)。
- **run --test**:本地任务名 → 解析 server task id → gated TEST_RUN → 实例 → 日志流 → 退出码。
