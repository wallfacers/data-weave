# Contract: `data/antileak.py` — B1/B2 训练集变体构造器

**新增脚本**。确定性生成抗泄漏消融的两个训练数据变体，复用 `synth_pipeline` 的 pool/render 与 `templates` 的模板，最小增量。

## CLI

```
python data/antileak.py --variant {b1|b2} --out data/out-b1 \
    [--train-size 10000] [--heldout-size 600] \
    [--b1-real-min 2000] [--b1-synth-keep 40] [--b2-negative-frac 0.20]
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--variant` | 必填 | `b1`=真实名主导；`b2`=掺弃权负样本 |
| `--out` | 必填 | 变体输出目录（`data/out-b1` / `data/out-b2`） |
| `--train-size` / `--heldout-size` | 10000 / 600 | 与基线同量（消融纯净） |
| `--b1-real-min` | 2000 | B1 真实名去重后目标下限（HF harvest 上调至可达） |
| `--b1-synth-keep` | 40 | B1 保留的合成生成名数（招牌指标可测所需） |
| `--b2-negative-frac` | 0.20 | B2 空标签负样本占训练集比例 |

## 行为契约

1. **确定性**：固定 `SEED=20260703`；同参数两次运行输出 byte-identical（含 `pool.json` 键序）。
2. **输出**：`{out}/train.jsonl`、`{out}/heldout.jsonl`（schema 与 `synth_pipeline` 逐字同构）、`{out}/pool.json`（见 data-model §3）。
3. **B1**：表名池 = `synth_table_names(SEED, b1_synth_keep)` ∪ `harvest_hf_names(大 limit)`，真实名主导；模板/列名/split 隔离复用基线逻辑不变。`pool.json.synth_generated_subset` = 保留的合成名。
4. **B2**：正样本 80% 复用基线 `render`（与基线逐行一致）；负样本 20% 由新增弃权模板家族生成，`labels={"reads":[],"writes":[]}`，且其 content 在约定 A 下确应为空。
5. **消融纯净**：除表名分布（B1）/ 负样本掺入（B2）外，不引入任何其它与基线的差异。
6. **降级**：HF 不可达时 `harvest_hf_names` 既有兜底（打 warn，退纯合成名）——B1 退化为"合成名扩量"，脚本仍可复现（须在 `pool.json` 标 `hf_degraded: true`，评测时披露）。

## 退出码
`0` 成功；`≠0` 参数非法/写失败。

## 复用（不改）
`synth_pipeline.harvest_hf_names / synth_table_names / build_pools / render`、`templates.TRAIN_TEMPLATES / Ctx / Template`。
