# Quickstart: 068 三厂商共识 gold + 全档重训（真跑手册）

工作目录：`/home/wallfacers/project/dw-068-tri-vendor-gold/ml/lineage-extractor`
解释器：`python3`。长命令必 `setsid` 脱离 + 单次秒回轮询；Bash 工具设 `timeout` 参数。

## 前置（已就绪）

- `.env` 含 `GPT_API_KEY`/`GPT_BASE_URL`(带 /v1)/`GPT_MODEL=gpt-5.6-sol`（gitignored）。
- 复用 067 迁入资产：`realeval/pool-{c,silver}/`、`teacher_labels-c/{m1,m3}`、`teacher_labels-silver/{m1,m_flash}`、`realeval/gold/real-c.jsonl`、`data/silver-col.jsonl`、`out/run-col-*`、`weights/weft-lineage-extractor-*`。

## 步骤（US1 破循环先出第一个数字，无需重训）

```bash
# 0) 单测先绿（TDD）
python3 -m pytest tests/test_gpt_backend.py tests/test_tri_consensus.py tests/test_metrics_columns.py -q

# 1) GPT 标 gold 池（sol，~400 条，setsid 脱离）
python3 -m realeval.teacher_label --pool realeval/pool-c --teachers m_gpt \
  --out realeval/teacher_labels-c/m_gpt.jsonl

# 2) 三厂商共识 gold（2-of-3 主尺 + 3-of-3 子集）
python3 -m realeval.build_gold_b --teachers m1,m3,m_gpt --min-agree 2 --columns \
  --out realeval/gold/real-c-tri.jsonl   # 附产 real-c-tri-unan.jsonl

# 3) GPT vs 067 gold 一致率（FR-004）
python3 -m realeval.agreement_report --tri realeval/gold/real-c-tri.jsonl \
  --base realeval/gold/real-c.jsonl > out/agreement-068.md

# 4) US1 重评现有模型（model-3b / run-col-3b-mit）在三厂商 gold
python3 -m realeval.dump_model_preds --model out/run-col-3b-mit/merged --gold realeval/gold/real-c-tri.jsonl ...
python3 -m eval.significance_report ... > out/significance-tri-c.md   # 第一个破循环数字
```

## US2 全档重训（真涨点）

```bash
# 5) GPT 标 silver 池（luna 便宜档，~3000 条，setsid 脱离，最慢一步）
python3 -m realeval.teacher_label --pool realeval/pool-silver --teachers m_gpt_bulk \
  --out realeval/teacher_labels-silver/m_gpt.jsonl

# 6) 2-of-3 共识 silver（表+列，防泄漏）
python3 -m realeval.build_silver --pair m1,m_flash,m_gpt --min-agree 2 --keep-columns \
  --exclude-gold realeval/gold/real-c-tri.jsonl --out data/silver-tri.jsonl

# 7) 全档重训（fresh Qwen base + mit 配方 r32/e3，setsid，3B ~2hr）
for sz in 0.5 1.5 3; do
  python3 -m train.sft_qlora --data data/silver-tri.jsonl \
    --base-model Qwen/Qwen2.5-Coder-${sz}B-Instruct \
    --lora-r 32 --lora-alpha 64 --epochs 3 --out out/run-tri-${sz/./}b
done
```

## US3/US4 评测 + 治理路由 + 台账

```bash
# 8) 三厂商 gold 重评三档 + 门② McNemar + scale
python3 -m eval.significance_report ... > out/significance-tri-scale.md

# 9) held-out 厂商泛化（GPT 独立确认边子集，FR-009/门③）
python3 -m realeval.heldout_vendor_eval --model out/run-tri-3b/merged ...

# 10) 治理路由（限制②缓解，FR-015）
python3 -m realeval.governance_routing --tri realeval/gold/real-c-tri.jsonl \
  --preds out/preds/run-tri-3b.jsonl > out/governance-routing-068.md

# 11) 成本台账（真实 usage）+ 独立证据台账
#     out/PAPER-EVIDENCE-068.md（不碰 065/067）
```

## 收尾（FR-016/SC-012）

- 更新 HF `wallfacers/weft-lineage-extractor-*` 模型卡：三厂商可信度 + 限制②缓解（治理路由）+ 限制①仍为诚实边界。
- 全 pytest 绿零回归；合 main（守并发多 Agent 硬规则）。

## 验证判据（SC 速查）

- SC-001/002：real-c-tri + unan 产出、一致率报告、现有模型重评数字。
- SC-003：run-tri-3b 表 P≥0.78/R≥0.75、列 P≥0.78/R≥0.82。
- SC-004：3-of-3 gold 表 P≥0.80 且召回不降。
- SC-005：vs 067 published McNemar 不显著退化。
- SC-006：held-out 厂商子集 P/R。
- SC-007：表 f1 单调 0.5<1.5<3B。
- SC-008：`test_metrics_columns.py` 绿（门①）。
- SC-009：成本 ≤¥100 真实 usage。
- SC-011：治理路由报告（auto 层精度 + 分歧占比）。
