# Contract: `realeval/leak_analysis.py` — `--train-pool` 泛化

**修改既有脚本**（向后兼容）。让泄漏检测能按"被测模型自有训练池"评测，防 B1 换名字假性归零（研究决策 3）。

## CLI 变更

```
# 既有（不变，默认回放合成池）
PYTHONPATH=. python realeval/leak_analysis.py --gold realeval/gold/real.jsonl

# 新增（按变体自有池 + 保留原合成池对照列）
PYTHONPATH=. python realeval/leak_analysis.py --model $W/run-b1/merged \
    --gold realeval/gold/real.jsonl --train-pool data/out-b1/pool.json \
    --report out/leak-report-b1.md
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--train-pool <file>` | 无（None） | 变体 `pool.json`。传则泄漏逐字判据用其 `train_table_names`；**同时**保留一列"逐字命中原合成池"（`train_table_pool()` 回放）作对照 |

## 行为契约

1. **向后兼容（硬约束）**：不传 `--train-pool` 时行为**逐字不变**——回放 `synth_table_names(SEED,400)`，基线/3B/JVM 的既有 `leak-report*.json` 复跑 byte 级一致。
2. **传 `--train-pool` 时**报告新增列：
   - `verbatim_own_pool` / `verbatim_own_rate` = 幻觉逐字命中变体自有 `train_table_names` 的数/率；
   - `verbatim_synth` / `verbatim_synth_rate` = 逐字命中原合成池（与基线同源对照）；
   - `synthetic_shaped` / `shaped_rate` 判据不变（`_synthetic_shaped`）。
3. **gold-无关不变**：幻觉判据仍 = 预测名 ∉ 金标 ∧ ∉ 脚本文本；池只用于分类幻觉来源。
4. 报告 md/json 均含新列；json 结构增量向后兼容（老字段保留）。

## 校验（单测 `test_leak_pool.py`，无 GPU）
- 构造假 predictor + 假 `pool.json`，断言 `verbatim_own_*` 正确计数、`--train-pool` 缺省路径与旧输出一致。
- 断言自有池 ⊇ 变体标签表名（与 data-model §3 校验一致）。
