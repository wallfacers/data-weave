# Quickstart: 复现头条证据

前置：`cd ml/lineage-extractor && pip install -r requirements.txt`（新增 scipy + sqllineage）。W1–W3 离线复算，**无需 GPU**（复用落盘 preds）；gold/preds 走 HF 拉取（见 benchmark/README）。

## W1 — 统计诚实层（每个数字带 CI）
```bash
python -m realeval.significance_report --gold gold/real-c-arbitrated.jsonl \
  --preds out/preds/ --seed 20260712 --resamples 10000
# → out/significance-c.md：每指标 point+95%CI；3B-vs-teacher 的 diff-CI + McNemar p + "是否显著"判定
```
验收：`test_significance.py` 绿（bootstrap 确定性、CI 含点估计、McNemar 已知例）；报告如实标注 n≈49 下 teacher 对比是否显著。

## W2 — 工具基线（工具≈0/模型救回）
```bash
python -m realeval.eval_baselines_c --gold gold/real-c-arbitrated.jsonl --preds out/preds/
# → out/baselines-c.md：regex/sqllineage/model-* × {all,sql,script} 对照表 + 各行 CI
```
验收（SC-003）：`sqllineage@sql.recall` 可比；`{regex,sqllineage}@script.recall ≤0.10`；`model-3b@script.recall` 显著高于工具（diff-CI 不含 0 或 McNemar p<0.05）。

## W3 — 可复现 benchmark（无凭据第三方复算）
```bash
python -m benchmark.build_manifest --gold gold/real-c-arbitrated.jsonl --out dist/benchmark/
python -m benchmark.fetch --manifest dist/benchmark/manifest.json --out /tmp/eval-src/   # 无凭据抓公开源
python -m realeval.eval_model_c --gold dist/benchmark/labels.jsonl --src /tmp/eval-src/    # 复算头条表
```
验收：第三方在无项目私有凭据环境按 benchmark/README 复算出的头条指标落在 W1 的 CI 内；`test_benchmark_manifest.py` 断言清单不含源码正文、不引合成集、抓取仅用公开端点。

## W4 — auto-gold 扩容（可选/稳健性，默认不跑）
```bash
python -m realeval.expand_gold_c --target 100 --dedup-against gold/real-c-arbitrated.jsonl
# 复用 collect_stack + teacher_label(m1+m2) + build_gold_b；产物标注"teacher 派生、非独立真值"
```
验收（SC-006）：扩容后至少一项关键指标 CI 宽度相对收窄；论文标注为稳健性证据。

## 招牌图（W5 写作复用既有产物）
- 泄漏曲线（0.5/1.5/3B 逐字泄漏 37→22→11%）+ 合成 vs 真实塌缩：源自既有 `leak_analysis.py` / `out/leak-curve.md`。
- 工具 vs 模型脚本对照：源自 W2 `out/baselines-c.md`。
