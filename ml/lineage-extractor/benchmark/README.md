# Weft 血缘 Benchmark（可复现评测发布物）

标签+指针+脚本形态的可复现评测集：**无需项目私有凭据**即可复算论文头条表与招牌图。

## 发布内容

| 含 | 不含 |
|---|---|
| `manifest.json` / `labels.jsonl`：表级标签 + 源文件 `repo@commit:path` 指针 | ❌ 源文件正文（重分发许可考量，改指针+抓取） |
| 评测脚本（`eval/`、`realeval/eval_baselines_c.py`、`significance.py`、基线） | ❌ 合成训练集（防止训练数据泄漏使真实集评测失效） |
| `fetch.py`：无凭据抓公开源 | ❌ 模型权重（走 HuggingFace，见下） |

`labels.jsonl` 每条：`{repo, commit, path, reads:[{table,columns:null}], writes:[...], subset}`。列级 `columns` 恒 `null`——**列级血缘是 out-of-scope / future work**。

## 复现步骤（无私有凭据）

```bash
pip install -r requirements.txt          # numpy/pandas/sqlglot/sqllineage（无需 scipy）
python -m benchmark.fetch --manifest manifest.json --out src/    # 公开 raw 抓源
# 工具基线对照（CPU，招牌图：工具脚本≈0/模型救回）
PYTHONPATH=. python3 realeval/eval_baselines_c.py --gold labels.jsonl --report out/baselines-c.md
# 统计诚实层（每数字带 95% CI + McNemar；需先落盘 model/teacher preds）
PYTHONPATH=. python3 realeval/significance_report.py --gold labels.jsonl --preds-dir out/preds
```

第三方复算的头条指标应落在论文报告的 95% 置信区间内（SC-004）。

## 可复现边界（诚实声明）

- **可复现**：真实 gold C 上的 precision/recall/F1 + 95% CI、工具 vs 模型分层对照、真实 vs 合成 eval 数字。
- **不可从本发布物复现**：**逐字记忆泄漏**的重算需要**原始训练语料**做比对，而训练/合成语料**不在发布范围**（FR-007）。泄漏曲线（37→22→11%）以**预算好的泄漏分析产物**形式在论文/附录给出，非从本 benchmark 现算。（消解 065-analyze 的 C1）
- **凭据门控分支**（deepseek/m3 teacher 三方对比）需私有 API key，**与核心可复现路径解耦**：核心结论的复算不依赖任何受限凭据（FR-008）。缺 key 时该分支跳过，不影响头条。

## 数据/权重获取

- gold C（仲裁后标签）：随本 benchmark 发布（`labels.jsonl`）；原始池/银标/合成集走 **HuggingFace** `wallfacers/weft-lineage-*`，**不入 git**（复现性由固定 seed + 本清单保证）。
- 模型权重（0.5/1.5/3B merged）：HuggingFace `wallfacers/weft-lineage-extractor-*`。

## 尺子

打分口径 = `eval/metrics.py`（表级 tp/fp/fn，`canon_match` 处理限定名尾段）；统计层 = `eval/significance.py`（脚本级 bootstrap CI + McNemar 精确二项）。**列级不参与打分**（out-of-scope）。
