# Phase 1 Data Model: 创作上下文服务

**纯读 + 无状态**：无新数据库表。以下为**契约/领域对象**（服务返回、CLI/MCP 序列化），非持久化实体。所有对象在 `TenantContext` 租户+项目边界内产出。

## AuthoringContext（创作上下文包）

某任务/工作副本草稿的意图接地视图。

| 字段 | 说明 |
|---|---|
| `taskRef` | 任务标识（已 push 任务 id 或草稿的逻辑名） |
| `reads` | `TableFact[]` —— 读表及每表的上游生产者 |
| `writes` | `TableFact[]` —— 写表及每表的下游消费者 |
| `columnLineage` | `ColumnEdgeFact[]` —— 读写表相关的列级血缘 |
| `datasourceSchema` | 绑定数据源解析出的真实列 schema（按表） |
| `depthUsed` | 实际遍历深度（调用方自决，回显） |
| `truncated` | `TruncationNote[]` —— 哪些节点因广度超阈值被截断（FR-018） |
| `partial` | `MissingNote[]` —— 哪些事实源不可用致部分缺失（FR-005） |

### TableFact
| 字段 | 说明 |
|---|---|
| `table` | 限定表名 + 数据源坐标 |
| `direction` | READS / WRITES |
| `neighbors` | `NodeRef[]` —— 上游生产者或下游消费者（任务/表），带跳距 |
| `groundingState` | PRESENT / INFERRED / UNGROUNDED（源自 `CatalogGroundingService`，FR-003） |
| `source` | 事实来源通道（Calcite/脚本/规则/图；未接地不虚构上游） |

## TaskDependencyView（任务依赖视图）

合并**声明**与**推导**两来源的依赖（FR-006）。

| 字段 | 说明 |
|---|---|
| `taskRef` | 目标任务 |
| `upstream` | `DependencyEdge[]` |
| `downstream` | `DependencyEdge[]` |

### DependencyEdge
| 字段 | 说明 |
|---|---|
| `peerTaskRef` | 对端任务 |
| `origin` | DECLARED（`WorkflowEdge` DAG）/ DERIVED（数据血缘）/ BOTH |
| `viaTables` | 承载该依赖的中间表（DERIVED 时） |

## ReuseCandidate（复用候选，P2）

| 字段 | 说明 |
|---|---|
| `candidateRef` | 已有任务/表定义标识 |
| `producedTargets` | 该候选写出的表/列 |
| `overlap` | 与当前草稿写表目标的重叠表/列集 |
| `score` | 确定性分（重叠度为主 + 名称相似加权，FR-008） |

## ConsistencyDiagnostic（一致性诊断，P3）

| 字段 | 说明 |
|---|---|
| `category` | DANGLING_UPSTREAM / DOWNSTREAM_COLUMN_CONTRACT_BREAK / DUPLICATE_DEFINITION / DEP_DIVERGENCE_MISSING / DEP_DIVERGENCE_STALE |
| `severity` | ERROR / WARN / INFO（建议性，FR-012） |
| `entities` | 涉及的表/列/任务 |
| `suggestion` | 可执行修正建议（FR-011） |

## DraftLineage（草稿抽取归一，内部）

工作副本草稿经**既有 extractor**（`ScriptLineageService`/Calcite）抽取的归一产物（D3 硬不变量：不实现第二套抽取）。

| 字段 | 说明 |
|---|---|
| `taskRef` / `type` / `datasourceRef` | 草稿元数据 |
| `reads` / `writes` | 抽取出的读写表 |
| `columnEdges` | 抽取出的列边 |
| `hints` | 解析降级/未定位留痕（防幻觉，FR-003） |

## 关系图（概念）

```
AuthoringContext ──contains──▶ TableFact ──neighbors──▶ NodeRef(任务/表)
        │                          └─groundingState◀── CatalogGroundingService
        ├──derives──▶ TaskDependencyView ──edges(origin)──▶ WorkflowEdge(DECLARED) / 血缘(DERIVED)
        ├──feeds────▶ ReuseCandidate (P2, 写表目标重叠)
        └──feeds────▶ ConsistencyDiagnostic (P3, 声明 vs 推导背离等)
DraftLineage ◀──extract(既有 extractor)── 工作副本草稿(无状态)
```

## 校验规则（源自 FR）

- 未接地表 MUST NOT 生成虚构上游（FR-003 / SC-005）。
- 工作副本分析 MUST 无持久化副作用（FR-004）。
- 深度=调用方参数；广度截断 MUST 显式标注（FR-018）。
- 草稿覆盖同名已 push 定义（FR-019）。
- 一切输出限定在请求身份租户/项目内（FR-016）。
