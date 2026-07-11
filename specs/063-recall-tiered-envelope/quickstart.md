# Quickstart: 召回回收 · 置信度分层复核信封

**worktree**：`dw-063-recall-tiered-envelope` · **改动范围**：`ml/lineage-extractor/`

## 前置：gitignored 数据落位

代码在 main（063 已有）；gold/preds/labels 为 gitignored，住在 `dw-059-lineage-corpus-expansion` worktree。实现前把校准/评测所需数据软链或复制进 063（**均 gitignored，不入 commit**）：

```bash
D59=/home/wallfacers/project/dw-059-lineage-corpus-expansion/ml/lineage-extractor
D63=/home/wallfacers/project/dw-063-recall-tiered-envelope/ml/lineage-extractor
ln -sfn $D59/realeval/gold/real-c-arbitrated.jsonl  $D63/realeval/gold/   # gold C（校准+CV+评测）
ln -sfn $D59/out/preds-c-run-059-runc.jsonl         $D63/out/            # 3B 在 gold C 的预测
ln -sfn $D59/out/preds-c-teacher-deepseek-pro.jsonl $D63/out/            # 三方对照
ln -sfn $D59/out/preds-c-teacher-qwen-max.jsonl     $D63/out/
```

> **research R1**：无独立非泄漏带标集（测试集 A `real.jsonl` 已删；pool-c-held 162 条中 153 条⊇gold C，非独立；pool-c-train 模型训练过、泄漏）→ 退回 **gold C 嵌套 CV 去偏**。CV 只需 gold C 金标 + 既有预测，无需 pool-c-held/银标/新 dump。

## 步骤 1：冻结校准常量（gold C 嵌套 CV 去偏）

```bash
cd $D63/ml/lineage-extractor
# 1a. 对既有 gold C 预测先过语义 grounding（部署管线一致），按行 idx 对齐
PYTHONPATH=. python3 realeval/calibrate_tiers.py \
    --gold realeval/gold/real-c-arbitrated.jsonl \
    --model out/preds-c-run-059-runc.jsonl \
    --k 5 --thr 0.95 \
    --report out/calibrate-tiers.md \
    --emit-constants realeval/tier_classify_constants.py
# 内部：confidence_calibration.calibrate（全 gold C 点估计→部署常量）
#      + conf_calibration_cv（k折/留一→CV held-out 报告口径）
```

## 步骤 2：离线证明（gold C，三方对照；自动层精度取 CV held-out 口径）

```bash
PYTHONPATH=. python3 realeval/rescore_tiered.py \
    --gold realeval/gold/real-c-arbitrated.jsonl \
    --preds 3b:out/preds-c-run-059-runc.jsonl \
    --preds deepseek:out/preds-c-teacher-deepseek-pro.jsonl \
    --preds qwen:out/preds-c-teacher-qwen-max.jsonl \
    --thr 0.95 --report out/rescore-tiered.md
# 期望（SC）：自动层∪复核层召回 ≥0.76；自动层 held-out precision ≥0.95；披露 ≥0.95 自动层召回~0.05 代价 + 0.90 膝点
```

## 步骤 3：serving 分层（部署落地）

```bash
MODEL_DIR=<3b-merged> LINEAGE_AUTOACCEPT_MIN_PRECISION=0.95 \
    uvicorn serve.app:app --host 0.0.0.0 --port 8500
curl -s localhost:8500/extract -H 'content-type: application/json' \
    -d '{"taskType":"PYTHON","content":"spark.sql(\"INSERT OVERWRITE TABLE dwd.clean SELECT * FROM ods.orders\")"}' | jq
# 期望：reads/writes=自动层（高置信可入库）；reviewReads/Writes=复核候选；tiered=true
```

回滚：`LINEAGE_TIERING=0` → 逐字节等价 059 现状。

## 步骤 4：测试

```bash
cd $D63/ml/lineage-extractor
PYTHONPATH=. python3 -m pytest tests/test_tier_classify.py tests/test_calibrate_tiers.py tests/test_dir_fix_serve.py -q
PYTHONPATH=. python3 -m pytest -q   # 全量不回归
```

## 成功判据映射

| SC | 验证 |
|---|---|
| SC-001 召回 ≥0.76 | 步骤 2 `rescore-tiered.md` 自动∪复核召回 |
| SC-002 自动层 held-out P ≥ 阈 | 步骤 1 CV held-out precision（`conf_calibration_cv` 留出级序/前沿） |
| SC-003 复核排序/负载 | 步骤 2 报告候选/脚本 + confidence 降序 |
| SC-004 阈可调/回滚 | 步骤 3 改 env（0.95→0.90）+ `LINEAGE_TIERING=0` 等价 |
| SC-005 三方对照落盘 | 步骤 2 报告三方 + 诚实披露 ≥0.95 代价 |
