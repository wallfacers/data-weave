# Quickstart: 抗泄漏消融（041-R 方案 B）端到端验证

在 `ml/lineage-extractor/` 下运行。权重落 worktree 外 `weft-lineage-weights/`（`WEFT_WEIGHTS_DIR` 已由脚本 `parents[K]` 解析，无需手设）。GPU = 单张 12G。

## 前置
- Python 环境已装 `requirements.txt`（transformers/peft/trl/torch/datasets）。
- 基线资产就位：`data/out/heldout.jsonl`、`realeval/gold/real.jsonl`、`weft-lineage-weights/run{1,3}`、3B 冻结数字（论文 §5）。
- `.env`（gitignore）配 `DASHSCOPE_API_KEY`（真实四方里 m1/m2 列需要；缺则该列优雅跳过，不阻断 B 主指标）。

## 无 GPU 先行验证（数据变体 + 泄漏池泛化，单测）
```bash
cd ml/lineage-extractor
pytest tests/test_antileak_data.py tests/test_leak_pool.py -q
```
**期望**：变体确定性（同 SEED byte-identical）、B1 合成名占比 ≤15%、B2 负样本占比 20%±1% 且全空标签、`--train-pool` 缺省与旧输出一致——全绿。

## B1 支（真实表名增广，P1）
```bash
# 1) 造变体（确定性）
PYTHONPATH=. python data/antileak.py --variant b1 --out data/out-b1
# 2) 训（只改 --data，其余配方逐字同基线）
PYTHONPATH=. python train/sft_qlora.py --data data/out-b1/train.jsonl \
    --out "$WEFT_WEIGHTS_DIR/run-b1" --base-model Qwen/Qwen2.5-Coder-1.5B-Instruct
# 3) 真实四方 + 合成 held-out + 泄漏（自有池）
PYTHONPATH=. python realeval/eval_real.py --model "$WEFT_WEIGHTS_DIR/run-b1/merged" \
    --gold realeval/gold/real.jsonl --report out/eval-real-b1.md
PYTHONPATH=. python eval/evaluate.py --model "$WEFT_WEIGHTS_DIR/run-b1/merged" \
    --data data/out/heldout.jsonl --report out/eval-report-b1.md
PYTHONPATH=. python realeval/leak_analysis.py --model "$WEFT_WEIGHTS_DIR/run-b1/merged" \
    --gold realeval/gold/real.jsonl --train-pool data/out-b1/pool.json --report out/leak-report-b1.md
```
**期望产物**：`out/eval-real-b1.*`、`out/eval-report-b1.*`、`out/leak-report-b1.*`（后者含 `verbatim_own_rate` 与 `verbatim_synth_rate` 两列）。**判读**：逐字泄漏(自有池) 相对基线 22.4% 的方向与幅度 = B1 是否修得动；合成 held-out 是否退化 = trade-off。

## B2 支（弃权训练，P2）
```bash
PYTHONPATH=. python data/antileak.py --variant b2 --out data/out-b2
PYTHONPATH=. python train/sft_qlora.py --data data/out-b2/train.jsonl \
    --out "$WEFT_WEIGHTS_DIR/run-b2" --base-model Qwen/Qwen2.5-Coder-1.5B-Instruct
PYTHONPATH=. python realeval/eval_real.py --model "$WEFT_WEIGHTS_DIR/run-b2/merged" \
    --gold realeval/gold/real.jsonl --report out/eval-real-b2.md
PYTHONPATH=. python eval/evaluate.py --model "$WEFT_WEIGHTS_DIR/run-b2/merged" \
    --data data/out/heldout.jsonl --report out/eval-report-b2.md
PYTHONPATH=. python realeval/leak_analysis.py --model "$WEFT_WEIGHTS_DIR/run-b2/merged" \
    --gold realeval/gold/real.jsonl --train-pool data/out-b2/pool.json --report out/leak-report-b2.md
```
**判读**：precision↑ 与 recall↓ 的权衡量级；若 recall 崩到近 0 = 恒弃权退化，判该补救不可用。

## 汇总（P3）
```bash
PYTHONPATH=. python realeval/ablation_table.py \
    --b1-eval out/eval-real-b1.json --b1-leak out/leak-report-b1.json --b1-synth out/eval-report-b1.json \
    --b2-eval out/eval-real-b2.json --b2-leak out/leak-report-b2.json --b2-synth out/eval-report-b2.json \
    --report out/ablation-antileak.md
```
**期望**：`out/ablation-antileak.*`（基线/B1/B2/3B 同口径表 + 结论骨架）。人工审读后 append 到 `paper-negative-result-findings.md` §B。

## 完成判据（对齐 spec SC）
- [ ] 无 GPU 单测全绿（SC-005 可复现 / SC-003 消融纯净可核验）。
- [ ] B1、B2 至少一支产出真实四方 + 合成 held-out + 泄漏三产物（SC-001/SC-002）。
- [ ] 消融表生成、含真实与合成两侧、trade-off/恶化被显式标注（SC-002/SC-004/SC-006）。
- [ ] 结论并入论文，与数据一致，保留方向病/domain-shift 披露（SC-004）。
