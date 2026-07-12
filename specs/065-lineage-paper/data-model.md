# Phase 1 Data Model: 证据实体

本特性无数据库；"实体"是评测证据的内存/文件结构。稳定接缝=`eval/metrics.py` 已产的逐脚本 counts。

## PerScriptCounts（逐脚本计数）— 稳定接缝

单个脚本样本在某模型/基线预测下的评分计数，由既有 `eval/metrics.py:score_row` 产出。**本特性只消费、不重定义。**

| 字段 | 含义 |
|---|---|
| `script_id` | 样本唯一标识（对齐 gold 的 repo@commit:path 或行号） |
| `tp/fp/fn` | 表名集合口径的真阳/假阳/假阴（reads∪writes） |
| `direction_correct` | 方向（读/写角色）是否正确 |
| `exact_match` | reads∪writes 集合是否与 gold 精确匹配（供 McNemar 的二元判据） |
| `subset` | `sql` \| `script`（纯 SQL / 命令式脚本，供分层） |

## MetricCI（指标置信区间）— W1

| 字段 | 含义 |
|---|---|
| `metric` | precision \| recall \| f1 \| direction_acc |
| `point` | 点估计（既有 `aggregate` 结果） |
| `lo95/hi95` | 脚本级 percentile bootstrap 95% 区间 |
| `n_scripts` | 参与样本数（非空口径） |
| `n_resamples/seed` | 重采样次数与种子（复现锚点） |

**校验**：`lo95 ≤ point ≤ hi95`；固定 seed 下重跑逐位一致。

## PairedDiff（配对差值）— W1

| 字段 | 含义 |
|---|---|
| `model_a/model_b` | 对比双方（如 3B vs qwen-max） |
| `metric` | 比较的指标 |
| `diff` | a−b 点差 |
| `lo95/hi95` | 配对 bootstrap 差值 95% 区间 |
| `significant` | 区间是否不含 0 |

## McNemarResult（配对精确检验）— W1

| 字段 | 含义 |
|---|---|
| `model_a/model_b` | 对比双方 |
| `b/c` | discordant 计数（a对b错 / a错b对），并列不计入 |
| `p_value` | `binomtest(min(b,c), b+c, 0.5)` 精确 p |
| `significant` | p<0.05 |

## BaselineComparisonRow（工具对照行）— W2

| 字段 | 含义 |
|---|---|
| `predictor` | regex \| sqllineage \| model-0.5b \| 1.5b \| 3b \| teacher-m1 |
| `subset` | all \| sql \| script |
| `precision/recall/f1` | 该 predictor 在该子集的指标（各带 MetricCI） |

**校验（SC-003）**：`sqllineage` 在 `sql` 子集 recall 与既有报告可比；在 `script` 子集 recall ≤0.10；`model-3b` 在 `script` 子集 recall 显著高于工具（PairedDiff.significant 或 McNemar）。

## LabelRecord + SourcePointer（发布标签+指针）— W3

| 字段 | 含义 |
|---|---|
| `repo/commit/path` | 源文件的不可变指针（**不含源码正文**） |
| `reads/writes` | 表级标签（`{table, columns:null}`，列级 out-of-scope） |
| `arbitrated` | 是否经 pro 仲裁翻空 |
| `subset` | sql \| script |

## BenchmarkManifest（发布清单）— W3

| 字段 | 含义 |
|---|---|
| `records[]` | LabelRecord + SourcePointer 列表 |
| `eval_scripts[]` | 复现所需脚本清单 |
| `disclosure` | 公开边界声明：不含源码本体、不含合成训练集 |
| `credential_free` | 声明核心复现无需私有凭据 |

**校验（FR-007）**：manifest **MUST NOT** 含源码正文字段；**MUST NOT** 引用任何合成训练集文件；抓取脚本仅依赖公开端点。

## ExpansionBatch（扩容批，可选）— W4

| 字段 | 含义 |
|---|---|
| `source` | the-stack |
| `consensus` | m1∧m2 一致（deepseek 缺，非三方） |
| `dedup_against` | 已在 gold C 的内容 sha256 集（防污染） |
| `robustness_only` | 恒 true：标注"teacher 派生、非独立真值" |
