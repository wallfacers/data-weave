# Phase 1 Data Model: 血缘 AI Agent 通道 + 数据源 Schema 解析

## 1. 关系型新表（schema.sql，PG/H2 兼容 DDL；schema_version 0.10.0 → 0.11.0）

### 1.1 `lineage_agent_config` — AI Agent 抽取配置

按租户/项目隔离，默认关闭。凭据加密存储。

| 列 | 类型 | 约束 / 说明 |
|----|------|-------------|
| `id` | BIGINT IDENTITY PK | |
| `tenant_id` | BIGINT NOT NULL | 隔离 |
| `project_id` | BIGINT NOT NULL | 隔离；(tenant_id, project_id) 唯一——每项目一条配置 |
| `protocol` | VARCHAR(16) NOT NULL | `ANTHROPIC` \| `OPENAI` |
| `base_url` | VARCHAR(512) NOT NULL | 兼容端点根 URL |
| `model` | VARCHAR(128) NOT NULL | 模型名 |
| `api_key_enc` | VARCHAR(1024) | 经 `DatasourceEncryptor` 加密的密钥；可空（部分自建网关免鉴权）|
| `enabled` | SMALLINT DEFAULT 0 | **默认 0=关闭**（FR-019）|
| `timeout_ms` | INTEGER DEFAULT 30000 | 单次外呼预算（FR-022）|
| `rate_limit_per_min` | INTEGER DEFAULT 60 | 每分钟外呼上限（FR-023）|
| `max_columns` | INTEGER DEFAULT 2000 | schema 抓取列数上限（FR-014）|
| `created_by` / `updated_by` | BIGINT | |
| `created_at` / `updated_at` | TIMESTAMP | |
| `deleted` | SMALLINT DEFAULT 0 | 软删 |
| `version` | INTEGER DEFAULT 0 | 乐观锁 |

唯一约束：`UNIQUE(tenant_id, project_id, deleted)`（软删后可重建）。

**校验规则**：`protocol ∈ {ANTHROPIC, OPENAI}`；`base_url` 非空且 http(s)；`enabled=1` 时 `base_url`+`model` 必填。

### 1.2 `lineage_agent_call` — AI 外呼审计（FR-021）

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGINT IDENTITY PK | |
| `tenant_id` / `project_id` | BIGINT NOT NULL | 隔离 |
| `config_id` | BIGINT NOT NULL | → lineage_agent_config.id |
| `protocol` | VARCHAR(16) | 快照 |
| `task_def_id` | BIGINT | 关联任务 |
| `latency_ms` | INTEGER | 外呼耗时 |
| `status` | VARCHAR(16) | `SUCCESS` \| `DEGRADED` \| `REJECTED`（防幻觉拒收）|
| `edges_emitted` | INTEGER DEFAULT 0 | 产出边数 |
| `note` | VARCHAR(512) | 降级/拒收原因摘要（脱敏）|
| `created_at` | TIMESTAMP | |

索引：`idx_agent_call_task (tenant_id, project_id, task_def_id)`。**不含明文密钥/脚本内容**。

> `api_key` 明文绝不入任一表/日志；只 `api_key_enc` 密文。

## 2. neo4j 富化（唯一血缘存储，复用既有节点/边）

- **`:Column` 富化**：`DatasourceSchemaResolver` 取回的列经 `Neo4jColumnBackfillWriter` MERGE 进 `(:Table)-[:HAS_COLUMN]->(:Column)`，写入既有预留位 `c.dataType` / `c.ordinal`（原 v1 透传 null，本特性首次真正填充）。`Neo4jColumnLineageCatalog` 读路径无需改动即命中回填结果。
- **`:IoEdge` / `DERIVES_FROM` 新来源**：AI 通道产出的读写边 `source=SCRIPT_AGENT`、`confidence=UNVERIFIED`（人工确认后升级），复用 `recordTaskIo` 写入，与既有 041 边同结构；仅 `Source` 枚举扩值。
- **列 schema 失效**：重 push 时对候选表 `HAS_COLUMN` 不删（列元数据是共享节点、跨任务），仅刷新 `dataType/ordinal`（结构变更覆盖）；进程内 TTL 缓存做真正的失效层。

## 3. 领域枚举 / 结构变更

### 3.1 `Source` 枚举（domain/lineage/Source.java）

```
AGENT, SQL_PARSED, FORM, SCRIPT_SQL, SCRIPT_INFERRED, SCRIPT_MODEL,
SCRIPT_AGENT   // 新增：云 AI Agent 推断（041→053）
```

### 3.2 通道优先序（ScriptLineageService.CHANNEL_PRIORITY，FR-004a）

```
SCRIPT_SQL  >  SCRIPT_INFERRED  >  SCRIPT_AGENT  >  SCRIPT_MODEL
(内嵌SQL/确定)  (规则)            (AI 推断)         (本地小模型)
```

对齐 spec 全局优先级「内嵌 SQL/Calcite > 规则 > AI > 小模型」；Calcite 表级/列级来源（`SQL_PARSED`）在 SQL 任务路径天然最高。

## 4. 应用层数据结构（内存传递，非持久）

- **`AgentExtraction`**（协议归一产物）：`reads: List<String>`、`writes: List<String>`、`columnEdges: List<ColumnEdge>`、`confidence: double`、`modelVersion: String`（= 配置 model 名快照）。
- **`AgentLineageConfig`**（配置领域对象 + 脱敏 DTO）：DTO 的 `apiKeyMasked` = `sk-…` + 末 4 位；实体持 `apiKeyEnc`，解密仅在 `LlmAgentClient` 内即用即弃。
- **`TableSchema` / `ColumnMeta`**（既有 `application/lineage`）：`DatasourceSchemaResolver` 复用其作为返回类型，`ColumnMeta` 的 `dataType/ordinal` 从 `DatabaseMetaData` 真实填充。
- **`LineageAgentEnrichmentRequested`**（EventBus 事件）：`tenantId, projectId, taskDefId, taskType, calciteParsed`（后者供 D7 判定 SQL 是否走 AI）。

## 5. 状态流转

- **配置**：`enabled=0`（默认）↔ `enabled=1`；删除=软删（`deleted=1`）。`enabled=0` → enricher 旁路、零外呼。
- **AI 血缘边**：`UNVERIFIED`（AI 首产）→ 人工 `CONFIRMED`（升级）/ `REMOVED`（剔除，重 push 不复现——复用 `ScriptLineageCorrectionGate` 裁决重放）。
- **schema 缓存条目**：`MISS` → 实时抓取 `RESOLVED`（写缓存+neo4j）→ TTL 过期 / 重 push evict → `MISS`。
