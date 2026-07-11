# Contract: 工具基线预测接口（`eval/baselines/*.py`）

所有工具基线共用同一签名，与既有 `regex_baseline.predict` 一致，供评测入口统一调度。

## `predict(row: dict) -> dict`
- **In**: `row` 至少含 `row["content"]`（脚本正文字符串）；可含 `row["type"]`（SQL/PYTHON/SHELL/SPARK）。
- **Out**: `{"reads": [{"table": str, "columns": None}], "writes": [{"table": str, "columns": None}]}`。
  - `columns` 恒 `None`（列级 out-of-scope，与 gold 口径一致）。
  - 写表从 reads 去重（沿用 regex_baseline 语义）。
- **不变量**: 纯函数、无副作用、**永不抛异常**（内部 catch，失败返回 `{"reads":[],"writes":[]}`）。

## `sqllineage_baseline.predict`（新增）
- 用 `sqllineage.runner.LineageRunner(content).source_tables()/target_tables()` 抽表。
- **非 SQL / 解析失败**：catch 后返回空 → 脚本子集召回 0（坐实结构性失效，SC-003）。
- **不变量**: 纯 SQL 有效解析时 source/target 正确分列；对任意输入不抛（含二进制/超长/非 SQL）。

## 评测入口 `realeval/eval_baselines_c.py`
- 对 gold C 每样本调 `regex.predict` 与 `sqllineage.predict`，经 `metrics.score_row` 得 counts，按 `subset ∈ {sql, script}` 分层，产 `BaselineComparisonRow` 表 + 各行 MetricCI（调 significance），落 `out/baselines-c.md`。
- **验收锚点（SC-003）**：`sqllineage@sql.recall` 可比、`{regex,sqllineage}@script.recall ≤0.10`、`model-3b@script.recall` 显著高于工具。
