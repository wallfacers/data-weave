# Quickstart: 召回回收 · 置信度分层复核信封

**worktree**：`dw-063-recall-tiered-envelope` · **改动范围**：`ml/lineage-extractor/`

## 前置：gitignored 数据落位

代码在 main（063 已有）；gold/preds/labels 为 gitignored，住在 `dw-059-lineage-corpus-expansion` worktree。实现前把校准/评测所需数据软链或复制进 063（**均 gitignored，不入 commit**）：

```bash
D59=/home/wallfacers/project/dw-059-lineage-corpus-expansion/ml/lineage-extractor
D63=/home/wallfacers/project/dw-063-recall-tiered-envelope/ml/lineage-extractor
ln -s $D59/realeval/gold/real-c-arbitrated.jsonl   $D63/realeval/gold/   # gold C（held-out 评测）
ln -s $D59/realeval/pool-c-held                    $D63/realeval/         # 冻结校准集（the-stack held-out）
ln -s $D59/realeval/teacher_labels-c-held          $D63/realeval/         # 该集 teacher 标签
ln -s $D59/out/preds-c-run-059-runc.jsonl          $D63/out/              # 3B 在 gold C 的预测（评测用）
# teacher 在 gold C 的预测（三方对照用）
ln -s $D59/out/preds-c-teacher-deepseek-pro.jsonl  $D63/out/
ln -s $D59/out/preds-c-teacher-qwen-max.jsonl      $D63/out/
```

> 测试集 A（`real.jsonl`）已删、不可用 → 用 `pool-c-held` 作冻结校准替身（见 research.md R1）。

## 步骤 1：冻结校准常量（pool-c-held）

```bash
cd $D63
# 1a. 对 pool-c-held dump 模型预测（GPU 推理，无 teacher $；模型未训练此切片）
MODEL_DIR=<3b-merged> PYTHONPATH=. python3 realeval/dump_preds.py \
    --pool realeval/pool-c-held --out out/preds-poolheld-runc.jsonl
# 1b. 由 teacher 标签构建 pool-c-held 银标（build_silver 一致口径）
PYTHONPATH=. python3 realeval/build_silver.py \
    --labels realeval/teacher_labels-c-held --out realeval/gold/pool-c-held-silver.jsonl
# 1c. 跑校准 + CV 去偏 → 固化每级 held-out precision 常量表 + 报告
PYTHONPATH=. python3 realeval/calibrate_tiers.py \
    --gold realeval/gold/pool-c-held-silver.jsonl --model out/preds-poolheld-runc.jsonl \
    --report out/calibrate-tiers.md --emit-constants realeval/tier_classify_constants.py
```

## 步骤 2：离线证明（gold C 纯 held-out，三方对照）

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
| SC-002 自动层 held-out P ≥ 阈 | 步骤 2 gold C 纯 held-out precision（常量冻结于 pool-c-held） |
| SC-003 复核排序/负载 | 步骤 2 报告候选/脚本 + confidence 降序 |
| SC-004 阈可调/回滚 | 步骤 3 改 env（0.95→0.90）+ `LINEAGE_TIERING=0` 等价 |
| SC-005 三方对照落盘 | 步骤 2 报告三方 + 诚实披露 ≥0.95 代价 |
