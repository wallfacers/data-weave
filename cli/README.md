# dw —— Weft 运维 CLI

Weft 的运维 / 同步 CLI，单一 Go 二进制（薄壳调后端 REST，权限由平台 PolicyEngine 统一裁决）。

## 构建

```bash
cd cli && ./build.sh                 # 产物 cli/dw（不入 git）
./build.sh linux amd64               # 交叉编译 → cli/dw-linux-amd64
```

## 命令

### 任务运维（既有）

```
dw task list / show <id> / instances <taskId> / rerun <instanceId>
dw logs cat <instanceId>
```

### 本地工作副本往返 + 本地真跑（子特性 D）

| 命令 | 动作 | 复用 |
|---|---|---|
| `dw pull <project>` | 拉取项目为本地文件树 + 写 `.weft/state.json` | C `/api/projects/{id}/pull` |
| `dw push [--force] [--remark R]` | 推回服务器（幂等覆盖 + 版本快照），写回新基线 | C `/api/projects/{id}/push` |
| `dw diff` | 只读差异预览（added/modified/removed），对服务器零写入 | C `/api/projects/{id}/diff` |
| `dw run <task>` | 本机真跑任务脚本体（SHELL/SQL/PYTHON/SPARK/DATAX/SEATUNNEL），透传退出码 | Java `LocalRunMain`（复用 worker 执行器） |
| `dw run --test <task>` | TEST 模式提交服务器 + 流式日志回传 | `/api/tasks/{id}/run` + 日志 SSE |

`<project>` = 项目 id（数字）或 code（按 code 精确解析）。`<task>` = 相对文件路径（优先）或任务名别名。

## 环境变量

| 变量 | 用途 |
|---|---|
| `DW_API` | 后端地址（默认 `http://localhost:8000`） |
| `DW_TOKEN` | 统一 Bearer 凭据，所有命令共用（`Authorization: Bearer`） |
| `DW_WORKER_CP` | `dw run` 的 Java runtime classpath 或 worker fat jar 路径（缺省从 cwd 向上探测 `backend/dataweave-worker/target/*-exec.jar`） |

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 2 | 用法错误（本地参数 / 文件 / 缺配置） |
| 3 | 鉴权 / 越权（HTTP 401 或 `access_denied`） |
| 4 | 服务端业务错误（基线过期 / 校验失败 / not_found …） |
| 5 | 网络不可达 / HTTP 5xx |
| 6 | `dw run` 任务执行失败（透传 runner 退出码） |
| 7 | 环境/前置错误（缺 JVM / worker classpath） |

## 本地真跑前置（`dw run`）

`dw run` 复用平台 Java 执行器（代码级语义一致，宪法原则 III phased），需本机：

1. **JVM**（`java` 在 PATH）。
2. **worker classpath**：设 `DW_WORKER_CP`，或在仓库执行 `cd backend && ./mvnw -pl dataweave-worker -am package -DskipTests` 生成 fat jar（自动探测）。
3. **python3**（跑 PYTHON 任务）。
4. **`.weft/datasources.local.yaml`**（SQL/PYTHON 任务的数据源连接，凭据本地持有、`.weft/` 全量 git-ignored、绝不随 push 上行）。

```yaml
# .weft/datasources.local.yaml（逻辑名 → 本地连接）
warehouse_pg:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/warehouse
  username: dev
  password: devpass
```

## 完整闭环

见 [specs/009-weft-cli-runtime/quickstart.md](../specs/009-weft-cli-runtime/quickstart.md)（pull → run → diff → push → run --test）。
