# Data Model: 041-script-lineage-extraction

## 1. PG 新表（schema_version 0.6.2 → 0.7.0）

### lineage_edge_correction — 人工修正记录（FR-007）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| tenant_id | BIGINT | NOT NULL | 租户隔离 |
| project_id | BIGINT | NOT NULL | 项目隔离（036 约定） |
| task_def_id | BIGINT | NOT NULL | 语义键·任务 |
| direction | VARCHAR(8) | NOT NULL, CHECK IN ('READ','WRITE') | 语义键·方向 |
| table_key | VARCHAR(512) | NOT NULL | 语义键·表坐标（与 neo4j tableKey 同构：`dsKey\|norm(table)`） |
| column_key | VARCHAR(640) | NULL | 语义键·字段（NULL=表级边） |
| status | VARCHAR(16) | NOT NULL, CHECK IN ('CONFIRMED','REMOVED') | 确认 / 剔除 |
| operator | VARCHAR(128) | NOT NULL | 操作人（来自 TenantContext） |
| created_at | TIMESTAMP | NOT NULL DEFAULT now | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT now | 撤销=物理删除行，无软删列 |

- **唯一约束**: `UNIQUE(tenant_id, project_id, task_def_id, direction, table_key, COALESCE(column_key,''))`（同一语义键仅一条有效裁决；再次操作 = UPSERT 覆盖 status）。H2 兼容写法：column_key 用 `NOT NULL DEFAULT ''` 替代 COALESCE 索引。
- **语义**: REMOVED → 写入时抑制（该键的边不进图）；CONFIRMED → 该键推断边 confidence 升级 CONFIRMED。撤销（REVOKE）= 删行，下次 push 恢复自然推断结果。
- 审计明细不在此表（由 agent_action 经门禁自动留痕），此表只存**当前生效裁决**。

### lineage_unresolved_hint — 未解析提示（FR-006）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGSERIAL | PK | |
| tenant_id / project_id | BIGINT | NOT NULL | 隔离 |
| task_def_id | BIGINT | NOT NULL | 归属任务 |
| version_no | INT | NULL | 产生时的任务版本 |
| kind | VARCHAR(24) | NOT NULL, CHECK IN ('DYNAMIC_TABLE','DYNAMIC_SQL','TIMEOUT','PARSE_FAIL') | 疑似形态 |
| script_hint | VARCHAR(512) | NOT NULL | 行号+截断片段（如 `L42: df.to_sql(table_name, …)`） |
| created_at | TIMESTAMP | NOT NULL DEFAULT now | |

- **替换语义**: 每次抽取按 `(tenant_id, project_id, task_def_id)` 先 DELETE 后 INSERT（与 recordTaskIo replace-per-task 一致，FR-008）。
- 索引: `(tenant_id, project_id, task_def_id)`。

## 2. neo4j 图属性扩展（无新节点/边类型）

| 边 | 现有属性 | 变更 |
|---|---|---|
| READS / WRITES | source, confidence, version, taskDefId | `source` 新增取值 `SCRIPT_SQL` / `SCRIPT_INFERRED` / `SCRIPT_MODEL`（现有取值不动）；confidence 取值沿用 CONFIRMED/UNVERIFIED；SCRIPT_MODEL 边额外带 `modelVersion` 属性（FR-015 可回溯） |
| READS_COL / WRITES_COL | （同上族） | 同上 |
| FLOWS_TO | taskDefId | **补写** `source`、`confidence`（取该任务两端读写边中较弱置信度）——修既有读侧落空缝，SQL 任务同步受益 |
| DERIVES_FROM | taskDefId, transform, confidence | source 补齐（SCRIPT_SQL/SCRIPT_INFERRED/原值） |

- 置信度映射：SCRIPT_SQL → `CONFIRMED`；SCRIPT_INFERRED / SCRIPT_MODEL → `UNVERIFIED`；被 correction 确认 → `CONFIRMED`。
- 冲突消解优先序（同 (方向, tableKey) 键）：SCRIPT_SQL > SCRIPT_INFERRED > SCRIPT_MODEL（FR-012）。
- 节点（Datasource/Table/Column）、tableKey/columnKey 构造完全沿用 `DatasourceCoord.dsKey()` 与 `norm()`（FR-011 同源要求）。

## 3. 应用层值对象（application/lineage/script/）

```
ScriptSource      = { taskDefId, taskType, content, datasourceId, targetDatasourceId }
ScriptExtraction  = { reads: Set<String>, writes: Set<String>,
                      columnEdges: List<ColumnEdge>,        // 复用既有类型
                      hints: List<Hint{kind, line, snippet}>,
                      channel: SCRIPT_SQL | SCRIPT_INFERRED | SCRIPT_MODEL,
                      modelVersion: String? }               // 仅 SCRIPT_MODEL 通道
ScriptLineageExtractor（接口，FR-010 可插拔点）
  boolean supports(String taskType)           // ModelExtractor：endpoint 未配置/探活失败 → false（整体旁路）
  ScriptExtraction extract(ScriptSource src)
ScriptLineageService（编排）
  ScriptLineageResult extract(ScriptSource)   // 聚合→冲突消解(SQL>RULE>MODEL)→correction 抑制/升级→时间预算
ModelExtractor 配置键：lineage.model.endpoint（未设=旁路）、lineage.model.timeout（默认 2s）
```

### 模型通道 ml 侧数据契约（ml/lineage-extractor/）

```
训练/评估样本（JSONL 一行）：
{ "task_type": "PYTHON" | "SHELL",
  "content": "<脚本源码>",
  "labels": { "reads": [{"table": "ods.orders", "columns": ["id","amount"]?}],
              "writes": [{"table": "dw.orders", "columns": null}] },
  "meta": { "template_id": "py-saveastable-01", "source_dataset": "gretelai/synthetic_text_to_sql",
            "split_group": "train" | "heldout" } }   // split_group 按模板形态隔离，防泄漏
模型输出（受约束 JSON，温度 0）＝ labels 同构；服务端双重校验：schema 合法 + 每个 table 子串可在 content 中定位
```

## 4. 状态与生命周期

```
推断边生命周期：
  抽取产出(UNVERIFIED) ──人工确认──> CONFIRMED（correction 行 status=CONFIRMED）
        │                              │撤销（删 correction 行）→ 回 UNVERIFIED
        └──人工剔除──> 不入图（correction 行 status=REMOVED）
                             │撤销 → 下次 push 恢复推断
重复 push：recordTaskIo 删旧边重建 + hint 表整体替换；correction 表不动 → 裁决自动重放（FR-007/008）
```

## 5. DTO 变更（读侧）

- `FlowEdgeView`：新增可选字段 `source: String`、`humanState: NONE|CONFIRMED|REMOVED?`（REMOVED 边不出图，故实际只需 NONE/CONFIRMED；@JsonInclude(NON_NULL) 向后兼容）。
- 新增 `LineageCorrectionView`、`UnresolvedHintView`（见 contracts/lineage-script-api.md）。
