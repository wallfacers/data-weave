# 067 US4：SQLLineage 列级基线 vs 模型列（子集=sql）

- gold: realeval/gold/real-c.jsonl · model: run-col-3b-mit.jsonl

| 抽取器 | col_eval_n | col_precision | col_recall | col_f1 |
| --- | --- | --- | --- | --- |
| SQLLineage(get_column_lineage) | 25 | 1.000 | 0.143 | 0.250 |
| run-col-3b-mit（模型）| 89 | 0.830 | 0.839 | 0.834 |

> **招牌对比（列级）**：确定性 SQL 工具在 SQL 子集列级 recall=0.143，模型列级 recall=0.839（Δ=+0.696）。承 065「工具结构性弱、模型救回」到列级。 col_eval_n 差异源于两者表命中集不同（列评条件于表命中），如实呈现。
