# Weft File Contract Quick Reference

一页速查：Weft 文件契约结构与字段语义。术语保持英文（cron/DAG/SLA/lineage/datasource/slug）。

## 目录布局

```
<project>/
├── project.yaml              # 项目元数据
├── _folder.yaml              # 根类目标记（空类目靠它存在）
├── {catalog}/                # 类目目录 = catalog 树
│   ├── _folder.yaml          # 类目标记：sortOrder 同级排序
│   ├── {task-slug}.task.yaml # 任务定义
│   ├── {task-slug}.sql       # 脚本体（独立文件，原生语言）
│   └── {flow-slug}.flow.yaml # 任务流定义
├── tags.yaml                 # 标签定义（可选）
└── .weft/
    ├── state.json            # 基线（自动管理，勿手改）
    └── datasources.local.yaml # 本地数据源凭据（git-ignored）
```

**身份 = 文件名 slug**（小写 ASCII + 数字 + `-`/`_`），显示名 `name` 是独立字段。文件所在目录 = 类目归属。

## project.yaml

```yaml
code: my-project      # 项目 code（唯一标识）
name: 我的项目         # 显示名
description: ...      # 描述（可选）
```

## _folder.yaml（类目标记）

```yaml
name: 数据ETL          # 类目显示名
sortOrder: 1          # 同级排序（可选，默认 0）
```

每个类目目录放一个，使空类目可被 git 跟踪与 round-trip。

## *.task.yaml（任务定义）

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `name` | 是 | string | 显示名（可含中文/空格，不唯一） |
| `type` | 是 | string | 开放字符串：SQL/SHELL/PYTHON/DATA_SYNC/ECHO/SPARK… |
| `script` | 否 | string | 脚本体文件名（同目录下）；无脚本任务可省略 |
| `datasource` | 否 | string | 数据源逻辑名，从 datasources.local.yaml 查 |
| `timeoutSec` | 否 | int | 超时秒数 |
| `retryCount` | 否 | int | 重试次数（默认 0） |
| `description` | 否 | string | 描述 |
| `enabled` | 否 | bool | 冻结标志（默认 true） |
| `priority` | 否 | int | 优先级（默认 0） |
| `params` | 否 | array | 参数列表 |
| `params[].name` | 是 | string | 参数名（slug） |
| `params[].type` | 否 | string | DATE/NUMBER/STRING（默认 STRING） |
| `params[].defaultValue` | 否 | string | 默认值，支持 `{{placeholder}}` |

### SPARK 类型额外字段

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `sparkMode` | 是 | string | `pyspark` / `spark-sql` / `jar`（内容形态判别） |
| `jarPath` | 否 | string | jar 形态本地文件路径（本地 dw run 用） |
| `jarRef` | 否 | string | jar 形态资产 storageKey（服务端用，复用 driver_jars 上传链路） |
| `mainClass` | 否 | string | jar 形态 `--class` 主类全名 |

**脚本扩展名**（round-trip 保真）：
- `sparkMode=pyspark` → `.py`
- `sparkMode=spark-sql` → `.sql`
- `sparkMode=jar` → 无脚本体（`script` 字段省略） |

## *.flow.yaml（任务流定义）

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `name` | 是 | string | 显示名 |
| `schedule.type` | 否 | string | MANUAL/CRON/DEPENDENCY |
| `schedule.cron` | 否 | string | cron 表达式 |
| `nodes` | 是 | array | 节点列表 |
| `nodes[].key` | 是 | string | 节点唯一标识（slug） |
| `nodes[].task` | 否 | string | 引用的任务文件相对路径（TASK 节点必填） |
| `nodes[].type` | 否 | string | TASK（默认）/ VIRTUAL |
| `edges` | 是 | array | 依赖边列表 |
| `edges[].from` | 是 | string | 源节点 key（MUST 存在于 nodes） |
| `edges[].to` | 是 | string | 目标节点 key（MUST 存在于 nodes） |
| `edges[].strength` | 否 | string | STRONG（默认）/ WEAK |

**一致性规则**：edges 的 `from`/`to` MUST 指向 nodes 中真实存在的 `key`。

## params 与占位符 `{{...}}`

- 参数定义于 `params[]`，执行时由平台注入。
- 脚本体中使用 `{{paramName}}` 引用参数值，运行时替换。
- 内置占位符：`{{bizdate}}`（业务日期，T-1 兜底）。
- 占位符名大小写敏感，与 `params[].name` 精确匹配。

## datasource 逻辑名

- 在任务定义中引用逻辑名（`datasource` 字段）。
- 本地真跑时从 `.weft/datasources.local.yaml` 解析连接信息。
- 可用逻辑名通过查看该文件获取，**不臆造名字**。

## GateResult 三态（push/写操作反馈）

| outcome | 含义 | agent 应对 |
|---------|------|-----------|
| `EXECUTED` | 已执行 | 继续下一步 |
| `PENDING_APPROVAL` | 挂起待审批 | **非失败**，等待审批 |
| `REJECTED` | 已拒绝 | 检查操作是否触发安全策略 |

**关键**：含删除/高危操作的 push 可能被挂起（PENDING_APPROVAL），**不等于失败**。

## tags.yaml（可选）

```yaml
- name: 核心任务
  color: "#ff0000"
  tasks:
    - catalog/etl/my-task.task.yaml
  flows:
    - catalog/flows/daily.flow.yaml
```

## .weft/datasources.local.yaml（本地凭据）

```yaml
{逻辑名}:
  typeCode: POSTGRESQL|MYSQL|...
  jdbcUrl: jdbc:...
  username: ...
  password: ...
  driver: ...（可选）
```

**SPARK 数据源**（`typeCode: SPARK`）：

```yaml
spark_cluster:
  typeCode: SPARK
  master: local[*]              # local[*] | yarn | spark://...
  sparkHome: /opt/spark         # SPARK_HOME；缺失 → SKIPPED
  deployMode: client            # client | cluster（可选）
  queue: etl                    # yarn 队列（可选）
  conf:                         # 附加 spark.* 配置（可选）
    spark.executor.memory: 2g
```

SPARK 数据源不需 `jdbcUrl`/`username`/`password`。同一逻辑名本地 `local[*]` / 服务端 `yarn` 分别配置，`.task` 文件不感知环境。

此文件 git-ignored，凭据绝不随 push 上行。
