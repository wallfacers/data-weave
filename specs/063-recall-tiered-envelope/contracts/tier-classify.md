# Contract: `tier_classify.classify_tiers`（纯函数，无 torch/GPU）

**位置**：`ml/lineage-extractor/realeval/tier_classify.py`
**性质**：确定性纯函数，可无 GPU 单测（复用 channel_router 健壮性补丁）。

## 签名

```python
def classify_tiers(model_pred: dict, content: str, thr: float = 0.95) -> dict:
    """把模型预测 ∪ SQL-AST 通道分层为自动采纳 / 复核候选。

    model_pred: {"reads": [{table, columns}...], "writes": [...]}
                （已过语义 grounding + dir_fix 的模型输出）
    content:    脚本原文（用于 SQL-AST 通道 + 方向锚定）
    thr:        自动采纳累计校准 precision 阈（默认 0.95）

    返回:
      {
        "auto":   {"reads": [TableIo...], "writes": [TableIo...]},
        "review": {"reads": [TableIo...], "writes": [TableIo...]},
        "tiered": bool,   # 是否有 ≥1 表进复核层
      }
    每个 TableIo = {table, columns, tier, confidence}
    """
```

## 行为契约

1. **候选来源（FR-002）**：`S = channel_router.extract_sql_lineage(content, exec_gated=True)` 表集；`M` = model_pred 表集；`_canonical_edges(S, M)` 合并为 canon 互斥候选边并打 tier。
2. **分级（FR-003）**：每候选按 tier 取**冻结校准常量**（`_FROZEN_PRECISION[tier]`，来自 `calibrate_tiers.py` 固化）为 `confidence`。
3. **切分（FR-004）**：按冻结**校准序**累计，采纳「前 k 级」使累计 precision ≥ `thr` 者 → `auto`；其余 → `review`。`thr=0` → 全进 auto。
4. **排序（FR-006）**：`review` 内按 `confidence` 降序。
5. **方向（FR-010）**：沿用 dir_fix 结果；agree 层方向冲突取 SQL-AST 的 AST target 锚定。
6. **确定性（FR-008）**：无随机、无 GPU、无金标重算——只读冻结常量。

## 断言点（供测试）

| 场景 | 期望 |
|---|---|
| 模型漏抽、SQL-AST 可解析的真表 | 出现在 `review`（或 `auto` 若其 tier 达阈），不被静默丢 |
| `sql_bare`（CTE/碎片）候选 | 归 `review`（低 confidence），不进 `auto` |
| 同表 `t`（模型）+ `db.t`（SQL） | canon 合并为一条 `agree`，不重复计数 |
| 空 model_pred + 无 SQL 命中 | `auto`/`review` 均空，`tiered=False` |
| `thr=0` | 全并集进 `auto`，`review` 空 |
| `thr=0.95` 默认 | 仅累计 ≥0.95 的前缀进 `auto` |

## 依赖

- `realeval.channel_router.extract_sql_lineage`
- `realeval.confidence_calibration._canonical_edges`（tier 归类，canon 去重）
- `realeval.tier_classify._FROZEN_PRECISION`（冻结常量，`calibrate_tiers.py` 产出）
