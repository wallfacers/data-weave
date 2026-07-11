# Contract: `/extract` 分层响应（serving sidecar）

**端点**：`POST /extract`（`ml/lineage-extractor/serve/app.py`，独立进程 sidecar）
**变化性质**：向后兼容扩展——旧字段 `reads/writes/dirFixed/grounded` 语义保留，新增分层字段有默认值。

## 请求（不变）

```json
{ "taskType": "PYTHON", "content": "<script text>" }
```

## 响应（扩展）

```json
{
  "modelVersion": "wallfacers/weft-lineage-extractor-3b@v1",
  "reads":  [ { "table": "ods.orders", "columns": null, "tier": "sql_qual",  "confidence": 0.97 } ],
  "writes": [ { "table": "dwd.clean",  "columns": null, "tier": "agree",     "confidence": 0.95 } ],
  "reviewReads":  [ { "table": "stg_tmp", "columns": null, "tier": "model_bare", "confidence": 0.83 } ],
  "reviewWrites": [ ],
  "dirFixed": true,
  "grounded": false,
  "tiered": true
}
```

### 字段契约

| 字段 | 类型 | 语义 | 契约 |
|---|---|---|---|
| `reads` / `writes` | TableIo[] | **自动采纳层**（累计校准 precision ≥ 阈，治理安全，可直接入库） | 天真消费者只读此二字段 → 只会自动入库高置信表（FR-001/US2） |
| `reviewReads` / `reviewWrites` | TableIo[] | **复核候选层**（并集剩余，进人工队列，**不自动入库**） | 按 `confidence` 降序（FR-006）；`auto ∪ review` = 全并集（FR/US3-AS3） |
| `dirFixed` | bool | AST 是否修正过方向（不变） | 向后兼容 |
| `grounded` | bool | 语义 grounding 是否剔过表（059 ③，不变） | 向后兼容 |
| `tiered` | bool | 是否发生分层（有 ≥1 表被分到复核层） | 空脚本/无候选 → false |

### TableIo（每项）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `table` | str | — | 表名 |
| `columns` | str[] \| null | null | 列（表级多为 null） |
| `tier` | str | "" | `agree`/`sql_qual`/`sql_bare`/`model_qual`/`model_bare` |
| `confidence` | float | 0.0 | 所属 tier 的 held-out 校准 precision |

## 配置

| env | 默认 | 作用 |
|---|---|---|
| `LINEAGE_AUTOACCEPT_MIN_PRECISION` | `"0.95"` | 自动采纳累计 precision 阈；调 0.90 = 稳定膝点；调 0 = 全并集进 `reads/writes` |
| `LINEAGE_TIERING` | `"1"` | 置 `0` = 完全关分层，退回旧单一 `reads/writes`（`reviewReads/Writes` 空，`tiered=false`），逐字节等价旧行为 |
| `LINEAGE_SEMANTIC_GROUNDING` | `"1"` | 059 ③ 语义 grounding（不变） |

## 不变量

- **确定性**：同 `modelVersion` + 同 `content` + 同阈 → 同响应（FR-008）。
- **向后兼容**：`LINEAGE_TIERING=0` 时响应与 059 现状逐字节等价；开启时旧字段语义不变，仅 `reads/writes` 收窄为自动层（治理更严，US2/US3 已确认代价）。
- **召回天花板恒定**：`reviewReads/Writes ∪ reads/writes` 覆盖的表不随阈变化。
