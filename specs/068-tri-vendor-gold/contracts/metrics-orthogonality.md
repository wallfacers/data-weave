# Contract: 门① 列打分正交（回归，不改逻辑）

## 不变量

`metrics.py` 的列级打分**绝不扰动表级计数**。三厂商特性**只喂新 gold，不改 `metrics.py` 打分逻辑**。

- **表级 8 key**（`score_row` 产出）：逐字节相等——列打分开关（有列/无列/canon 双档）不改变任何表级 count。
- **列级独立 key**：`col_tp/col_fp/col_fn/col_halluc`——与表级物理隔离。

## 回归测试（`test_metrics_columns.py`，067 已存，必绿）

- `test_column_scoring_never_perturbs_table_counts`：多态 fixtures × canon 双档，断言表级 8 key 逐字节相等（数学证明列碰不到表）。
- 三厂商 gold 引入后**重跑此测试必绿**（零回归）——若红=违反门①，阻断。

## 治理路由（`governance_routing.py`，限制②缓解）

- 输入：real-c-tri（含 `consensus.agree.table`）+ 模型 preds。
- 分层：`agree==3`→auto、`agree==2`→review。
- 产出：auto 层模型表级精度（SC-011）+ review 层占比 + 接 063 分层信封（分歧=复核层）。
- 契约测试：分层计数正确；auto∪review=全集；精度只在 auto 层子集上算。
