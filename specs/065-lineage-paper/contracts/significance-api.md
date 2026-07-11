# Contract: 统计诚实层接口（`eval/significance.py`）

纯函数模块，只消费逐脚本 counts，**不 import 也不修改 `metrics.py` 的打分逻辑**。全部确定性（固定 seed）。

## `bootstrap_metric_ci(counts, metric, *, n_resamples=10000, seed) -> MetricCI`
- **In**: `counts: list[PerScriptCounts]`；`metric ∈ {precision,recall,f1,direction_acc}`。
- **Out**: `{point, lo95, hi95, n_scripts, n_resamples, seed}`。
- **语义**: 以脚本为单位有放回重采样 `n_resamples` 次，每次调既有 `aggregate` 重算 `metric`，取 2.5/97.5 分位。
- **不变量**: 同 `(counts, seed, n_resamples)` 逐位可复现；`lo95 ≤ point ≤ hi95`；空/单样本返回退化区间并标注。

## `paired_bootstrap_diff(counts_a, counts_b, metric, *, n_resamples=10000, seed) -> PairedDiff`
- **In**: 两模型在**同一脚本集合**上的 counts（等长、同序）。
- **Out**: `{diff, lo95, hi95, significant}`；`significant = not (lo95 <= 0 <= hi95)`。
- **语义**: 每次重采样抽同一批脚本索引，同步作用于 a、b，算 `metric(a)-metric(b)`。

## `mcnemar_exact(correct_a, correct_b) -> McNemarResult`
- **In**: 两个等长 `list[bool]`（各脚本精确匹配对错）。
- **Out**: `{b, c, p_value, significant}`；`p_value = scipy.stats.binomtest(min(b,c), b+c, 0.5, alternative="two-sided")`。
- **语义**: 并列（都对/都错）不计入 b/c；`b+c==0` 时 p=1.0（无差异证据）。

## 汇总编排 `realeval/significance_report.py`
- 读既有落盘 preds（3B/teacher/baselines @ gold C、gold A），经 `metrics.score_row` 得 counts，调上述三函数，输出 `out/significance-c.md`（每指标 point+CI、3B-vs-teacher 的 diff-CI 与 McNemar p、诚实"是否显著"判定）。
- **MUST**：如实呈现宽区间；`b+c` 小时明确标注"样本量受限、不显著"。
