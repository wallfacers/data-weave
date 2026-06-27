# Phase 1 Data Model: 文件化定义契约(007-weft-file-contract)

定义四类实体的磁盘文件形状(DTO)、字段、与服务端领域模型的映射、确定性与往返规则。这是契约本身。

## 0. 项目目录布局(总览)

```
<project-root>/
├── project.yaml              # 项目级元数据(formatVersion / code / name)
├── tags.yaml                 # 标签调色板(定义 name+color);关联内联在各实体
├── _folder.yaml              # 根目录的类目标记(根本身视为一个隐式类目容器)
├── <catalog-slug>/           # 类目节点 = 目录
│   ├── _folder.yaml          # 类目标记(name 显示名 / sortOrder)
│   ├── <task-slug>.task.yaml # 任务元数据
│   ├── <task-slug>.sql       # 任务脚本体(扩展名由 type 映射)
│   ├── <wf-slug>.flow.yaml   # 任务流元数据 + DAG
│   └── <sub-catalog-slug>/   # 子类目(递归)
│       └── _folder.yaml
└── <未分类任务/任务流直接落在 project-root>
```

**命名(FR-007a)**:所有目录名、`*.task.yaml`/`*.flow.yaml` 的 slug 前缀 MUST 为可移植字符集 `[a-z0-9_-]+`;同一目录内禁止仅大小写不同的重名。`_folder.yaml`/`project.yaml`/`tags.yaml` 为固定保留文件名。

**身份(FR-007)**:类目身份 = 相对 project-root 的目录路径;任务/任务流身份 = 所属目录 + slug;无独立 key 字段。

## 1. project.yaml —— ProjectDoc

| 文件字段 | 类型 | 必填 | 领域来源(Project) | 说明 |
|---|---|---|---|---|
| formatVersion | int | 是 | —(常量 1) | 格式版本,前向兼容标记(FR-016) |
| code | string | 是 | `Project.code` | 项目身份(租户内唯一);push 时服务器据此定位/建项目 |
| name | string | 是 | `Project.name` | 显示名(可中文) |

**不进文件**:id、tenantId、ownerId、status、审计字段(D5)。租户归属由服务器治理。

## 2. tags.yaml —— TagsDoc

| 文件字段 | 类型 | 必填 | 领域来源(Tag) | 说明 |
|---|---|---|---|---|
| formatVersion | int | 是 | —(常量 1) | 同上 |
| tags[] | list | 是(可空表) | `Tag` 列表 | 标签**定义**(调色板),按 `name` 升序 |
| tags[].name | string | 是 | `Tag.name` | 项目内唯一,关联引用键 |
| tags[].color | string | 否 | `Tag.color` | 原始色串(如 `#RRGGBB`),省略=null |

**关联不在此**:任务/任务流与标签的关联以**内联 `tags: [name,...]`** 存在各实体文件(EntityTag 多态),保证 diff 局部性(FR-013)。tags.yaml 只存定义。

## 3. _folder.yaml —— FolderDoc(每个类目目录一个)

| 文件字段 | 类型 | 必填 | 领域来源(CatalogNode) | 说明 |
|---|---|---|---|---|
| name | string | 是 | `CatalogNode.name` | 类目显示名(可中文);目录名是 slug 身份,name 是显示(同 task 的 slug/name 分离) |
| sortOrder | int | 否 | `CatalogNode.sortOrder` | 同级排序;省略=null(视为 0) |

**不进文件**:id、parentId(由目录嵌套表达)、path(派生,反序列化重算)、tenantId/projectId、审计字段。空类目仅含 `_folder.yaml`,因此可被 git 跟踪并 round-trip(FR-003)。

## 4. \<slug>.task.yaml —— TaskDoc + 脚本体文件

| 文件字段 | 类型 | 必填 | 领域来源(TaskDef) | 说明 |
|---|---|---|---|---|
| formatVersion | int | 是 | —(常量 1) | |
| name | string | 是 | `TaskDef.name` | 显示名(可中文/空格/重名);**身份是文件名 slug,非此字段** |
| type | string | 是 | `TaskDef.type` | **开放字符串**(SQL/SHELL/PYTHON/DATA_SYNC/ECHO 等,非穷举) |
| description | string | 否 | `TaskDef.description` | |
| priority | int | 否 | `TaskDef.priority` | 0=最高..9=最低;省略=null |
| timeoutSec | int | 否 | `TaskDef.timeoutSec` | 省略=null |
| retryMax | int | 否 | `TaskDef.retryMax` | 省略=null(视为 0) |
| frozen | bool | 否 | `TaskDef.frozen`(0/1) | 文件用 bool;省略=false |
| datasource | string | 否 | `TaskDef.datasourceId` → **逻辑 code** | 连接解析归环境(FR-009) |
| targetDatasource | string | 否 | `TaskDef.targetDatasourceId` → 逻辑 code | 同上 |
| params | map | 否 | `TaskDef.paramsJson`(JSON 串) | 展开为有序 map;读回规范 JSON 串(D6);省略=null |
| tags | list\<string> | 否 | `EntityTag`(entityType=TASK) | 引用 tags.yaml 的 name,升序;省略=无标签 |
| (脚本体) | 独立文件 | 否 | `TaskDef.content` | `<slug>.<ext>`,扩展名由 type 映射(D7);null=无文件 |

**不进文件**:id、status、currentVersionNo、hasDraftChange、catalogNodeId(=目录)、ownerId、version、审计字段、数字 datasourceId(已转 code)。

**脚本扩展名映射(D7)**:SQL→`.sql` / SHELL→`.sh` / PYTHON→`.py` / DATA_SYNC→`.json` / ECHO→`.txt` / 未知→`.txt`。

## 5. \<slug>.flow.yaml —— WorkflowDoc

| 文件字段 | 类型 | 必填 | 领域来源 | 说明 |
|---|---|---|---|---|
| formatVersion | int | 是 | —(常量 1) | |
| name | string | 是 | `WorkflowDef.name` | 显示名;身份=文件名 slug |
| description | string | 否 | `WorkflowDef.description` | |
| schedule | map | 是 | `WorkflowDef` 调度字段 | 见下 |
| schedule.type | string | 是 | `scheduleType` | MANUAL/CRON/DEPENDENCY |
| schedule.cron | string | 否 | `cron` | type=CRON 时非空 |
| schedule.start | datetime | 否 | `scheduleStart` | ISO-8601;省略=null |
| schedule.end | datetime | 否 | `scheduleEnd` | 省略=null |
| schedule.intervalMs | long | 否 | `scheduleIntervalMs` | 周期毫秒;省略=null |
| priority | int | 否 | `WorkflowDef.priority` | |
| preemptible | bool | 否 | `WorkflowDef.preemptible`(0/1) | 省略=false |
| timeoutSec | int | 否 | `WorkflowDef.timeoutSec` | |
| tags | list\<string> | 否 | `EntityTag`(entityType=WORKFLOW) | 升序 |
| nodes[] | list | 是 | `WorkflowNode` | 按 `key` 升序 |
| edges[] | list | 是(可空) | `WorkflowEdge` | 按 `(from,to,strength)` 升序 |

**node 子结构(WorkflowNode)**:

| 字段 | 类型 | 必填 | 领域来源 | 说明 |
|---|---|---|---|---|
| key | string | 是 | `nodeKey` | 工作流内稳定标识(非服务端自增 id) |
| type | string | 是 | `nodeType` | TASK / VIRTUAL |
| task | string | TASK 必填 | `taskId` → **任务相对路径/slug** | project-root 相对、无扩展名(如 `orders/orders_etl`);VIRTUAL 省略 |
| name | string | 否 | `WorkflowNode.name` | |
| pos | [int,int] | 否 | `posX,posY` | 画布坐标;省略=null,null,保 round-trip |

**edge 子结构(WorkflowEdge)**:

| 字段 | 类型 | 必填 | 领域来源 | 说明 |
|---|---|---|---|---|
| from | string | 是 | `fromNodeId` → 源 nodeKey | 引用 node.key |
| to | string | 是 | `toNodeId` → 目标 nodeKey | 引用 node.key |
| strength | string | 否 | `WorkflowEdge.strength` | WEAK 时写出;**STRONG(默认)省略**(D3),读回缺失=STRONG |

**不进文件**:id、status、currentVersionNo、hasDraftChange、catalogNodeId(=目录)、lastFireTime/nextTriggerTime、版本快照(dagSnapshotJson)、taskVersionNo(钉死属服务器发布,FR-014)、审计字段、节点/边的数字 id(用 nodeKey/相对路径)。

## 6. 校验规则(FR-015,反序列化时)

- **必填缺失** → 报 `<file>: 缺字段 <field>`。
- **类型不符**(如 priority 给了字符串)→ 报 `<file>: <field> 期望 <type>`。
- **命名违规**(FR-007a:目录/slug 非 `[a-z0-9_-]+`,或同级仅大小写不同重名)→ 报明确错误。
- **节点引用悬挂**:edge.from/to 不存在于 nodes,或 node.task 指向项目内不存在的任务路径 → 报错并指明该边/节点。
- **类目与目录矛盾**:不会发生(类目唯一由目录定,FR-008);若任务 yaml 出现已废弃的类目字段 → 视未知字段忽略(FR-016)。
- **脚本体超长**:文件→模型时若 content 超出服务端承载上限 → 报错,不静默截断(Edge Case)。
- **未知字段** → 忽略不报错(FR-016)。
- **TASK 节点缺 task / VIRTUAL 节点带 task** → 报错。

## 7. 往返不变量(FR-010/011/013,测试断言)

- **R1 序列化幂等**:`serialize(m)` 多次调用逐字节相同。
- **R2 模型→文件→模型语义等价**:`deserialize(serialize(m)) ≈ m`(语义比较,忽略服务端治理字段与 params 文本差异)。
- **R3 文件→模型→文件字节稳定**:`serialize(deserialize(b)) == b`(对规范化后的合法 bundle)。
- **R4 顺序无关**:打乱模型内集合顺序(tags/nodes/edges/params),序列化结果与排序后一致。
- **R5 diff 局部性**:改单一语义字段,bundle 中变更文件数=1、变更行 minimal。
- **R6 脚本保真**:脚本体逐字符(含换行/缩进)round-trip 不变。

## 8. DTO ↔ 领域 映射要点

- 领域类(TaskDef/WorkflowDef/WorkflowNode/WorkflowEdge/CatalogNode/Tag)均为可 new 的 POJO(无参构造+getter/setter),mapper 可在纯单测直接构造,无需 Spring/DB。
- `EntityTag` 关联在文件侧内联到实体的 `tags`;mapper 出域时按 (entityType, entitySlug) 还原 EntityTag 列表(entityId 由服务器 ingest 时定)。
- `datasourceId`/`targetDatasourceId`(Long)↔ `datasource`/`targetDatasource`(code):B 仅做 code 字段的 round-trip 保真;Long↔code 的解析表由 C/环境提供,B 以 code 为权威。
- `catalogNodeId`(Long)不映射字段:出域时由实体所在目录路径推导类目;入域时由目录树重建 CatalogNode 并回填归属。
