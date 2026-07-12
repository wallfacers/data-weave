# Contract: 条件列打分 + 门① 正交隔离（eval/metrics.py）

## 签名（扩展既有，不改表级返回值）

```python
def score_row(gold: dict, pred: dict, content: str, canon: bool = False) -> dict:
    # ① 既有表级逻辑 100% 不动 → 产出 tp/fp/fn/halluc/dir_* (逐字节不变)
    # ② 新增独立块：条件列打分 → 追加 col_* key
```

## 门① 正交实现（硬约束）

- **独立代码块**：列打分**不复用**表计数路径；表级 8 个 key 的赋值代码一字不改。
- 列打分从 `gold`/`pred` 的 **item 字典**（含 columns）**重新推导**表对齐对 `(gold_table, pred_table)`（用与表级相同的 `canon_match` + 同 `canon` 设置，独立算一遍），再取双方 columns 比对。冗余对齐换取零扰动。

## 条件列打分逻辑

对每个 role（reads / writes）分别：
1. 求表对齐命中对 `(g_t, p_t)`（表级 TP）。
2. 对每个命中对：
   - `gcols = canon_cols(gold_item.columns)`；`pcols = canon_cols(pred_item.columns)`。
   - `gcols is None`（gold 弃权）→ **跳过**（不评，不计 col_eval_tables）。
   - 否则 `col_eval_tables += 1`：
     - `pcols is None`（pred 弃权而 gold 有列）→ `col_fn += len(gcols)`。
     - 否则 `col_tp += len(gcols & pcols)`；`col_fp += len(pcols - gcols)`；`col_fn += len(gcols - pcols)`。
   - 列幻觉：`pcols` 中 `canon_col` 后字面不在 `content.lower()` 的 → `col_halluc += 1`；`col_pred_total += len(pcols or [])`。

## `aggregate` 扩展

```python
col_precision = col_tp / (col_tp + col_fp)  if (col_tp+col_fp) else 1.0
col_recall    = col_tp / (col_tp + col_fn)  if (col_tp+col_fn) else 1.0
col_f1        = 2pr/(p+r) if (p+r) else 0.0
col_hallucination = col_halluc / col_pred_total if col_pred_total else 0.0
```
表级 `precision/recall/f1/direction_acc/invalid` 返回值不变。

## 门① 单测（钉死）

```python
def test_column_scoring_never_perturbs_table_counts():
    for row in FIXTURES:                      # 含具体列/弃权/空多态样本
        base    = score_row(strip_columns(gold), pred, content, canon=True)
        withcol = score_row(gold,               pred, content, canon=True)
        for k in ("tp","fp","fn","halluc","pred_total","dir_total","dir_correct","invalid"):
            assert base[k] == withcol[k]      # 逐字节相等
```
+ 条件列打分正确性单测（TP/FP/FN、弃权跳过、pred 弃权记 fn、列幻觉、按 role）。
+ 既有 `metrics.py` 单测零回归（表级返回值不变的直接推论）。
