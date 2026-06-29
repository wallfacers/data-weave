---
name: weft-task-authoring
description: >
  Author, edit, run, and push Weft tasks/task-flows. Use when the developer wants to
  create or modify a Weft task or flow, run a task locally, diff, or push to the server.
allowed-tools: Bash, Read, Write, Edit, Grep
---

# Weft Task Authoring

Weft 是 **Tasks-as-Code** 平台。你用 AI agent 在本地仓库里像写代码一样开发数据任务和任务流，
通过 `dw` CLI 在本地真跑验证，最后 push 到服务器治理与调度运行。

## Golden Path Dev-Loop

标准创作闭环（单一真相，按顺序执行）：

```
dw pull <project>          # 1. 拉取项目为本地文件树
[写/改任务文件]              # 2. 创作任务定义 + 脚本体
dw run <task>              # 3. 本机真跑验证
dw diff                    # 4. 差异预览（对服务器零写入）
dw push [--force]          # 5. 推回服务器（幂等覆盖 + 版本快照）
dw run --test <task>       # 6. TEST 模式提交服务器验证
```

每步详细说明见下文各节。快速开始参考 `specs/015-agent-authoring-skill/quickstart.md`。

## 前置条件

1. 后端运行中（H2 profile 零外部依赖）：
   ```bash
   cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
   ```
2. `dw` CLI 已构建：`cd cli && ./build.sh`
3. 环境变量：
   - `DW_API`（默认 `http://localhost:8000`）
   - `DW_TOKEN`（统一 Bearer 凭据，所有命令共用）
4. `dw run` 额外需要：
   - **JVM**（`java` 在 PATH）
   - **worker classpath**：设 `DW_WORKER_CP` 或执行 `cd backend && ./mvnw -pl dataweave-worker -am package -DskipTests` 生成 fat jar（自动探测）
   - **python3**（跑 PYTHON 任务时需要）
   - **`.weft/datasources.local.yaml`**（SQL/PYTHON 任务的数据源连接凭据）

## Step 1: Pull — 拉取项目为本地文件树

```bash
dw pull <project> [--force|--clean] [--dir DIR]
```

- `<project>`：项目 id（数字）或 code（按 code 精确解析）
- `--force`：目标目录非空时覆盖同名文件（不清除多余文件）
- `--clean`：先清空目标目录所有内容再拉取
- `--dir DIR`：工作副本根目录（默认当前目录）

产出：`<DIR>/文件树` + `<DIR>/.weft/state.json`（基线令牌，push 时用于乐观并发控制）。

## Step 2: 创作任务 — 写任务定义 + 脚本体

文件位于 pull 下来的工作副本目录中。Weft 文件契约核心规则：

### 目录结构

```
<project-dir>/
├── project.yaml              # 项目元数据（project code/name）
├── _folder.yaml              # 根类目标记（空类目靠它存在）
├── catalog/                  # 类目目录 = catalog 树
│   ├── _folder.yaml          # 类目标记（sortOrder 等元数据）
│   ├── etl/                  # 子类目
│   │   ├── _folder.yaml
│   │   ├── my-task.task.yaml # 任务定义
│   │   └── my-task.sql       # 脚本体（独立文件，原生语言）
│   └── flows/
│       ├── _folder.yaml
│       └── daily-flow.flow.yaml  # 任务流定义
└── .weft/
    ├── state.json            # 基线状态（自动管理，勿手改）
    └── datasources.local.yaml # 本地数据源凭据（git-ignored）
```

### 任务定义文件 (`*.task.yaml`)

```yaml
# my-task.task.yaml
name: 我的每日ETL任务        # 显示名（可含中文/空格，不要求唯一）
type: SQL                    # 开放字符串：SQL/SHELL/PYTHON/DATA_SYNC/ECHO …
script: my-task.sql          # 脚本体文件名（同目录下的独立文件）
datasource: warehouse_pg     # 数据源逻辑名（从 datasources.local.yaml 查）
timeoutSec: 600              # 超时秒数（可选）
retryCount: 2                # 重试次数（可选）
description: 每日同步仓库数据  # 描述（可选）
params:                      # 参数（可选）
  - name: bizdate
    type: DATE
    defaultValue: "{{bizdate}}"
enabled: true                # 冻结标志（可选，默认 true）
```

**身份 = 文件名 slug**（`my-task`），显示名 `name` 是独立字段。详情见 `file-contract.md`。

### 脚本体文件

SQL/SHELL/PYTHON 脚本以原生语言纯文本独立存放，不被转义或包裹。支持 `{{placeholder}}` 占位符语法：

```sql
-- my-task.sql
SELECT * FROM orders WHERE dt = '{{bizdate}}';
```

占位符 `{{...}}` 在运行时由参数值替换。参数名匹配 `params[].name`。

### 任务流定义文件 (`*.flow.yaml`)

```yaml
# daily-flow.flow.yaml
name: 每日数据流水线
schedule:
  type: CRON
  cron: "0 6 * * *"
nodes:
  - key: extract
    task: ../etl/extract-task.task.yaml  # 相对路径引用任务
    type: TASK
  - key: transform
    task: ../etl/transform-task.task.yaml
    type: TASK
edges:
  - from: extract
    to: transform
    strength: STRONG  # STRONG（默认）或 WEAK
```

**一致性规则**：edges 的 `from`/`to` MUST 指向 nodes 中真实存在的 `key`。引用可跨目录（相对路径）。

### 数据源逻辑名

可用数据源逻辑名从 **本地配置** 查得，不臆造名字：

```bash
# 查看项目配置的数据源逻辑名
cat .weft/datasources.local.yaml
```

示例 `.weft/datasources.local.yaml`（凭据本地持有，绝不随 push 上行）：
```yaml
warehouse_pg:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/warehouse
  username: dev
  password: devpass
```

## Step 3: dw run — 本机真跑验证

```bash
dw run <task> [--dir DIR] [--timeout N]
```

- `<task>`：任务相对路径（如 `catalog/etl/my-task.task.yaml`）或任务名别名
- `--timeout N`：覆盖超时秒数

在本机用 Java runtime（复用平台真实执行器）执行脚本，stdout/stderr 直出终端，退出码忠实反映执行结果。

**退出码语义**：
| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 2 | 用法错误（参数/文件/配置问题） |
| 3 | 鉴权/越权 |
| 4 | 服务端业务错误 |
| 5 | 网络不可达 |
| 6 | 任务执行失败（runner 返回非零码） |
| 7 | 环境/前置错误（缺 JVM/worker classpath） |

**环境错误 vs 任务失败**可以编程区分：退出码 7 = 环境问题（检查 JVM/classpath），退出码 6 = 任务本身执行失败（检查脚本逻辑）。

## Step 4: dw diff — 差异预览（零写入）

```bash
dw diff [--dir DIR]
```

列出 added/modified/removed 文件。对服务器**零写入**，可安全反复运行。
若基线过期（他人已 push），提示先 `dw pull` 更新本地。

## Step 5: dw push — 推回服务器

```bash
dw push [--force] [--remark R] [--dir DIR]
```

- `--force`：基线过期时强制覆盖（慎用，会覆盖他人变更）
- `--remark R`：快照备注（默认 "dw push"）

读取 `.weft/state.json` 基线做乐观并发控制；成功后写回新基线。
若遇 **基线过期**（`project.sync.stale`），提示执行 `dw pull` 更新或 `dw push --force` 强制覆盖。

### GateResult 三态：理解 push 的权限闸门反馈

push 返回的 outcome 有三态，**务必区分**：

| outcome | 含义 | agent 应如何反应 |
|---------|------|-----------------|
| `EXECUTED` | 已执行 | 成功，继续下一步 |
| `PENDING_APPROVAL` | 挂起待审批 | **不是失败**，等待管理员审批后执行 |
| `REJECTED` | 已拒绝 | 失败，检查是否触发了平台安全策略 |

**关键规则**：含删除或高危操作的 push 可能被挂起（PENDING_APPROVAL），agent **不应**把挂起当作失败而错误重试或回滚。

## Step 6: dw run --test — TEST 模式提交服务器

```bash
dw run --test <task> [--dir DIR] [--biz-date DATE]
```

- `<task>`：需已存在于服务器（通常先 `dw push`）
- `--biz-date DATE`：业务日期（可选）

按任务名解析服务器 task id → gated TEST_RUN 提交 → 流式日志回传至终态，退出码反映实例成败。

## 命令速查

| 命令 | 说明 |
|------|------|
| `dw pull <project>` | 拉取项目为本地文件树 |
| `dw push [--force]` | 推回服务器（幂等覆盖 + 快照） |
| `dw diff` | 只读差异预览 |
| `dw run <task>` | 本机真跑 |
| `dw run --test <task>` | TEST 模式提交服务器 |
| `dw task list` | 列出任务定义 |
| `dw task show <id>` | 查看任务详情 |
| `dw task instances <taskId>` | 查看运行实例 |
| `dw task rerun <instanceId>` | 重跑实例（需写权限） |
| `dw logs cat <instanceId>` | 查看实例日志 |

全部支持 `--json` 结构化输出。

## 支持文件

- **`file-contract.md`**：文件契约一页速查（字段结构、必填/可选、数据术语）
- **`examples/`**：可仿写最小模板（任务 + SQL、任务流、datasources 样例）

## 数据术语约定

以下术语在文件与交流中**保持英文**，不被翻译：

- **cron**：调度表达式
- **DAG**：有向无环图（任务流拓扑）
- **SLA**：服务等级协议
- **lineage**：数据血缘
- **datasource**：数据源逻辑引用
- **slug**：文件名身份（稳定、可移植）
