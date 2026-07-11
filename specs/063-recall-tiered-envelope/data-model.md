# Phase 1 Data Model: 召回回收 · 置信度分层复核信封

数据实体均为**内存/文件级**（无 DB）。核心是候选边 + 分层 + 冻结校准表。

## 实体

### Candidate（候选血缘边）

抽取管线在 canon 去重后产出的单个 (表, 方向) 单元。

| 字段 | 类型 | 说明 |
|---|---|---|
| `table` | str | 表名（限定或裸名，保留来源串） |
| `columns` | list[str] \| null | 列（表级抽取多为 null，保留来源值，R6） |
| `direction` | "read" \| "write" | 读/写；agree 层冲突以 SQL-AST AST target 为准（FR-010） |
| `tier` | enum | 置信级，见 TierTaxonomy |
| `confidence` | float | 所属 tier 的 held-out（pool-c-held CV）经验 precision 常量（R5） |

**来源合并规则（FR-002）**：由 `confidence_calibration._canonical_edges(S, M)` 在 canon 下把 SQL 表集 S 与模型表集 M 合并为**互斥**候选——`t` 与 `db.t` 视同一表只占一条，避免限定/裸名重复计数。

### TierTaxonomy（置信级枚举）

`confidence_calibration.TIERS` 五级（通道归属 × 名字限定性）：

| tier | 含义 | 部署序由 pool-c-held 校准冻结 |
|---|---|---|
| `agree` | SQL∩模型（canon 互认） | held-out precision 定 |
| `sql_qual` | SQL-only · 限定名 | held-out precision 定 |
| `sql_bare` | SQL-only · 裸名 | 通常最低（CTE/临时/碎片） |
| `model_qual` | 模型-only · 限定名 | held-out precision 定 |
| `model_bare` | 模型-only · 裸名 | held-out precision 定 |

> 部署**校准序**（按 held-out precision 降序）冻结自 pool-c-held，非 gold C 样本内序。

### AutoAcceptTier（自动采纳层）

累计校准 precision ≥ 治理阈（默认 0.95）的候选集合。

- **判据**：`best_frontier(cal, thr)` 语义——校准序下采纳「前 k 级」使累计 precision ≥ thr 的最大召回边界。
- **不变量**：held-out（gold C）precision ≥ thr（SC-002）；治理安全，可直接入血缘库。
- 映射到 `/extract` 响应的 `reads` / `writes`。

### ReviewTier（复核候选层）

并集中未进自动层的候选集合。

- **不变量**：`AutoAcceptTier ∪ ReviewTier` = 全并集（召回天花板 0.764，不随阈变化，FR/US3-AS3）。
- **排序**：按 `confidence` 降序（FR-006）。
- 映射到 `/extract` 响应的 `reviewReads` / `reviewWrites`。

### CalibrationTable（冻结校准常量表）

每个 tier 的 held-out 经验 precision 常量 + 冻结出的校准序，由 `calibrate_tiers.py` 在 pool-c-held 上产出、写死进 `tier_classify.py`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `tier` | enum | 五级之一 |
| `heldout_precision` | float | pool-c-held CV 去偏 precision |
| `n` | int | 该级样本数（披露样本规模/抖动） |
| `calibrated_rank` | int | 冻结校准序位次 |

**生命周期**：一次冻结→写死→部署期只读。变更 = 重跑 `calibrate_tiers.py` 覆写常量（记录来源集与日期）。

## 状态流转

无持久状态机。抽取为无状态请求：`content → 模型 → grounding → dir_fix → 分层（读冻结常量 + 当前 thr）→ {auto, review}`，确定性（FR-008）。

## 校验规则（来自 Requirements）

- FR-002：候选 canon 互斥去重。
- FR-004：自动层累计校准 precision ≥ thr，否则降级复核。
- FR-005：校准常量冻结于 pool-c-held（源隔离、模型未训练），gold C 不参与定级。
- FR-006：复核层 confidence 降序。
- FR-008：确定性（无随机/GPU 依赖于分层段）。
- FR-010：方向沿用 AST 锚定；agree 冲突取 SQL-AST。
