# Phase 1 Data Model: 抗泄漏消融（041-R 方案 B）

实体 = 训练数据变体、变体训练池、权重、评测产物、消融表。均为文件态（jsonl / json / safetensors / md），无数据库。

---

## 1. B1 训练集变体 `data/out-b1/{train,heldout}.jsonl` + `pool.json`

由 `data/antileak.py --variant b1` 确定性生成（SEED=20260703）。

**行 schema（与既有 synth_pipeline 输出逐字同构，评测器零改动可读）**：
```json
{
  "task_type": "PYTHON|SHELL|SCALA|JAVA",
  "content": "<脚本文本>",
  "labels": {"reads": [{"table": "...", "columns": [...]|null}], "writes": [...]},
  "meta": {"template_id": "...", "rule_covered": bool, "form_family": "...",
           "source_dataset": "synth-b1-realname+gretelai+b-mc2", "split_group": "train|heldout"}
}
```

**与基线唯一差异维度**：表名池来源。基线 = `synth_table_names(400) ∪ HF`；B1 = `synth_table_names(~40) ∪ HF(≥2000)`（真实名主导 ~90%）。模板/渲染/列名池/split 隔离逻辑不变。

**校验规则**（单测 `test_antileak_data.py`）：
- 确定性：同 SEED 两次生成 byte-identical。
- 配比：训练表名中合成生成名（`synth_table_names` 可重放集）占比 ≤ 15%。
- 行 schema 与基线 train.jsonl 逐键一致（评测器兼容）。
- train/heldout 名池不相交（沿用基线 split 隔离，防内部泄漏）。

---

## 2. B2 训练集变体 `data/out-b2/{train,heldout}.jsonl` + `pool.json`

由 `data/antileak.py --variant b2` 生成。

**与基线唯一差异维度**：掺入 20% 空标签弃权负样本。正样本 80% = 基线同款；负样本 20% 来自新增"弃权模板家族"，`labels = {"reads": [], "writes": []}`。

**弃权负样本三型**（覆盖约定 A 范围外）：
| 型 | 特征 | 期望模型行为 |
|---|---|---|
| 纯计算/日志 | pandas/print/logging/数值聚合，无 SQL/无落表 | 输出空 |
| 注释/打印内 SQL | 表名仅在 `#`/`//` 注释或被 `print()` 的字符串里 | 输出空（不抽注释/打印名） |
| 动态拼接名 | `f"{schema}.{tbl}"` 变量驱动，无字面表名 | 输出空（不抽动态名） |

**校验规则**（单测）：
- 确定性（同 SEED byte-identical）。
- 负样本占比 = 20% ± 1%，且负样本 labels 全空。
- 负样本 content 内**无**可被约定 A 判为字面读写的表（负样本自洽：金标口径下确实应为空）。
- 正样本部分与基线逐行一致（消融纯净）。

---

## 3. 变体训练池 `pool.json`（诚实泄漏度量的锚）

每个变体落盘一份，供泛化后的 `leak_analysis.py --train-pool` 读取。
```json
{"variant": "b1", "seed": 20260703,
 "train_table_names": ["real_schema.real_tbl", "...", "ods.ods_orders_di"],
 "synth_generated_subset": ["ods.ods_orders_di", "..."]}
```
- `train_table_names` = 该变体训练集**实际出现的全部表名**（模型"见过"的名字全集）——泄漏逐字判据用它。
- `synth_generated_subset` = 其中属确定性合成生成器可重放的子集——供 B1"是否仍背合成名"那一列。

**校验**：`train_table_names` ⊇ 该变体 train.jsonl 中所有 labels 表名；`synth_generated_subset ⊆ synth_table_names(SEED, N)`。

---

## 4. 变体权重 `weft-lineage-weights/run-b1|run-b2/{adapter,merged}`

- merged = 可部署/可评测的合并权重（1.5B bf16）。
- 落 **worktree 外** sibling（`WEFT_WEIGHTS_DIR` 解析），gitignore，不进版本库。
- 由 `train/sft_qlora.py --data data/out-b{1,2}/train.jsonl --out $WEFT_WEIGHTS_DIR/run-b{1,2} --base-model Qwen/Qwen2.5-Coder-1.5B-Instruct` 产出。

---

## 5. 评测产物（复用既有评测器，`out/` 下）

| 产物 | 生成器 | 内容 |
|---|---|---|
| `out/eval-real-b1.md/.json` | `eval_real.py --model run-b1/merged` | B1 真实四方（prec/幻觉/recall/方向） |
| `out/eval-real-b2.md/.json` | `eval_real.py --model run-b2/merged` | B2 真实四方 |
| `out/eval-report-b1.md/.json` | `eval/evaluate.py --model run-b1/merged` | B1 合成 held-out（暴露 trade-off） |
| `out/eval-report-b2.md/.json` | `eval/evaluate.py --model run-b2/merged` | B2 合成 held-out |
| `out/leak-report-b1.md/.json` | `leak_analysis.py --model run-b1/merged --train-pool out-b1/pool.json` | B1 泄漏（自有池 + 原合成池两列） |
| `out/leak-report-b2.md/.json` | `leak_analysis.py --model run-b2/merged --train-pool out-b2/pool.json` | B2 泄漏 |

---

## 6. 抗泄漏消融表 `out/ablation-antileak.md/.json`（+并入论文）

由 `realeval/ablation_table.py` 汇编。

**行**：基线 1.5B（冻结）/ B1 / B2 / 3B（冻结）。
**列**：真实 precision · 真实幻觉 · 真实 recall(非空) · 真实方向(非空) · 逐字泄漏(自有池) · 合成形态率 · 合成 held-out precision(参照 trade-off)。

**校验/一致性规则**：
- 每格数字来自单一评测器单一金标（`real.jsonl` / `heldout.jsonl`）。
- 基线/3B 行取 `paper-negative-result-findings.md` 冻结值（脚本内以常量嵌入并注明来源），不重跑。
- 结论段须同时呈现真实指标与合成 held-out（禁单边），并显式标注任一恶化（recall 崩塌 / 合成退化 / thesis 翻转）。

---

## 实体关系

```
antileak.py --variant b1 ──▶ out-b1/{train,heldout}.jsonl + pool.json
antileak.py --variant b2 ──▶ out-b2/{train,heldout}.jsonl + pool.json
        │                              │
        ▼ (sft_qlora --data)           ▼
   run-b1/merged                  run-b2/merged
        │                              │
        ├─ eval_real ─▶ eval-real-b*   （真实四方）
        ├─ evaluate  ─▶ eval-report-b* （合成 held-out）
        └─ leak_analysis --train-pool pool.json ─▶ leak-report-b*
                                       │
        基线/3B 冻结值 ────────────────┼──▶ ablation_table.py ─▶ ablation-antileak.md
                                       │        │
                                       ▼        ▼
                        paper-negative-result-findings.md（§B 补 + 结论上修）
```
