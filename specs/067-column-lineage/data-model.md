# Phase 1 Data Model: 列级血缘

数据实体 = jsonl 记录格式与内存计数结构（无关系型 DB）。

## E1. 列级 gold 行（`realeval/gold/real-c.jsonl` 重建）

```jsonc
{
  "chash": "sha256...",              // content-hash 主键
  "content": "…脚本正文…",           // gitignored,走 HF
  "task_type": "PYTHON|SHELL|SQL",
  "reads":  [ {"table": "db.orders", "columns": ["amount","user_id"]} ],
  "writes": [ {"table": "db.summary", "columns": null} ],   // null=弃权
  "is_empty": false,
  "provenance": "intersection|disagreement_rescued|empty",
  "n_agree_edges": 2
}
```
- **columns 三态**：`null`=弃权 / `[...]`=双 teacher 交集具体列 / `[]`→归弃权。
- **约束**：columns 仅在双 teacher **表已一致**且**列集交集非空**时为具体值；否则 `null`。
- **重建来源**：`collect_stack`→双 teacher→`build_gold_b --min-agree 2 --columns`。

## E2. 列增强训练银标（`data/silver-col.jsonl`）

同 E1 结构，但：
- **来源**：`collect_stack` 再生语料 + **单 teacher m1** → `build_silver --keep-columns`。
- **columns**：单 teacher 直接给的列（SFT 容噪）；无则 `null`。
- **规模**：~2-3k 脚本；无列标 item 保 `null`（模型学弃权）。
- **用途**：`sft_qlora` 联合表+列监督（SFT 目标 JSON 携带 columns）。

## E3. 列级 metric counts（`score_row` 返回值扩展）

```python
{  # 表级(既有,逐字节不变) ────────────────
   "tp","fp","fn","halluc","pred_total","dir_total","dir_correct","invalid",
   # 列级(新增,独立 key) ────────────────────
   "col_tp","col_fp","col_fn",           # 条件于表 TP 的列集运算
   "col_halluc","col_pred_total",        # 列幻觉(pred 列不在脚本文本)
   "col_eval_tables" }                   # 实际评了列的表数(诊断 n,对应 SC n≥30)
```
- **不变量（门①）**：表级 8 key 与"列全抹 None"输入下**逐字节相等**。
- **聚合**：`aggregate` 加 `col_precision/col_recall/col_f1/col_hallucination`；列 P/R 分母 = `col_eval_tables` 相关（弃权表不进）。

## E4. `run-col-*` 模型家族（merged 权重目录）

- `out/run-col-3b/merged` · `out/run-col-15/merged` · `out/run-col-05/merged`（gitignored,走 HF）。
- **性质**：列增强新权重，双维度（表+列）评测；**不覆盖**既有 `weft-lineage-extractor-{05,15,3b}`。
- **状态流转**：3B 训完 → 门② 判定 → PASS 则扩训 15/05；FAIL 则冻结、记负结果。

## E5. teacher 列输出（`realeval/teacher_labels-c/{m1,m3}.jsonl`）

- 既有格式，item 已含 `columns`（`teacher_label` 零改动，本就免费吐）。
- gold 用 m1+m3（双）；银标用 m1（单）。
- 带 `usage`（真实 token 用量）供 ≤¥100 成本记账（FR-015）。

## E6. 报告行（`out/significance-c.md` / `out/baselines-c.md`）

- 既有表级行**原样保留**；追加列级指标行（`col_precision/recall/f1` + CI + n）。
- 自动发现 `out/preds/*.jsonl`（含 `run-col-*` preds）→ 表列两套曲线同表并列。
