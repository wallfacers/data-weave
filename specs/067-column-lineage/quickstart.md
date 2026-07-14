# Quickstart: 列级血缘端到端

前置：`cd ml/lineage-extractor`；`.env` 含 DASHSCOPE(m1)/DEEPSEEK_ANTHROPIC(m3)/HF_TOKEN（[[weft-lineage-eval-credentials]]）；`PYTHONPATH=.`。数据/权重全 gitignored。

## Phase A（US1，无 GPU）—— 评测基建 + 门①

```bash
# 1. 纯函数单测(门① 正交 + 列打分 + 列裁决)先绿
PYTHONPATH=. python -m pytest tests/test_metrics_columns.py tests/test_build_gold_columns.py -q

# 2. 重建带列 gold(双 teacher,~400 候选 ≈¥6-7)
HF_TOKEN=$HF_TOKEN PYTHONPATH=. python3 realeval/collect_stack.py \
    --langs python,shell,sql --target 400 --out realeval/pool-c
PYTHONPATH=. python3 realeval/teacher_label.py \
    --pool realeval/pool-c --teachers m1,m3 --workers 12 --out realeval/teacher_labels-c
PYTHONPATH=. python3 realeval/build_gold_b.py \
    --teacher-labels realeval/teacher_labels-c --min-agree 2 --columns \
    --out realeval/gold/real-c.jsonl

# 3. US1 冻结基线:既有 3B 列输出 ≈0(before 锚)
MODEL=weights/weft-lineage-extractor-3b PYTHONPATH=. python3 realeval/dump_model_preds.py \
    --gold realeval/gold/real-c.jsonl --out out/preds/model-3b.jsonl
PYTHONPATH=. python3 realeval/significance_report.py   # 表列两套指标
```

## Phase B（US2，GPU）—— 联合重训 3B + 门②

```bash
# 4. 再生列增强银标(单 teacher m1,~2-3k ≈¥25-40)
PYTHONPATH=. python3 realeval/collect_stack.py --langs python,shell,sql --target 3000 --out realeval/pool-silver
PYTHONPATH=. python3 realeval/teacher_label.py --pool realeval/pool-silver --teachers m1 --workers 12 --out realeval/teacher_labels-silver
PYTHONPATH=. python3 realeval/build_silver.py --teacher-labels realeval/teacher_labels-silver --teacher m1 --keep-columns --out data/silver-col.jsonl

# 5. 联合重训 3B(WSL2 脱离规则真跑)
setsid bash -c 'python3 train/sft_qlora.py --data data/silver-col.jsonl \
    --base Qwen/Qwen2.5-Coder-3B-Instruct --out out/run-col-3b \
    >train-3b.log 2>&1; echo $? >train-3b.exit' </dev/null >/dev/null 2>&1 & disown

# 6. 门② 同集校验:run-col-3b vs 既有 3B 表级 + 列级达标
MODEL=out/run-col-3b/merged PYTHONPATH=. python3 realeval/dump_model_preds.py \
    --gold realeval/gold/real-c.jsonl --out out/preds/run-col-3b.jsonl
PYTHONPATH=. python3 realeval/significance_report.py    # 表级不显著退化? 列 p≥0.70/r≥0.55?
PYTHONPATH=. python3 realeval/eval_baselines_c.py        # Δr 三档显著?
```
**门② PASS → Phase C；FAIL → 记表列权衡负结果,停,不覆盖既有模型。**

## Phase C（US3，GPU）—— 扩训 + 列 scale 曲线

```bash
for s in 05 15; do
  setsid bash -c "python3 train/sft_qlora.py --data data/silver-col.jsonl \
     --base Qwen/Qwen2.5-Coder-${s/05/0.5}${s/15/1.5}B-Instruct --out out/run-col-$s \
     >train-$s.log 2>&1; echo \$? >train-$s.exit" </dev/null >/dev/null 2>&1 & disown
done
# 三档 dump→significance_report 出列级 scale 曲线 + 表级单调复核
```

## Phase D（US4,可选）—— SQLLineage 列基线

```bash
PYTHONPATH=. python3 realeval/eval_baselines_c.py --with-columns   # SQL 子集列对照
```

## 验收对照
- SC-001/002/003：`out/significance-c.md` 列级 p≥0.70/r≥0.55/f1≥0.60,n≥30。
- SC-004：pytest 门① 正交单测绿。
- SC-005：`run-col-3b` 表 p≥0.72/r≥0.80 + McNemar 不显著 + Δr 三档显著 + 单调。
- SC-006：列级 scale 单调(理想)。
- SC-007：`teacher_label` usage 累计 ≤¥100。
- SC-008：`pytest` 全绿零回归。
