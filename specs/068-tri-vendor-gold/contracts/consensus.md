# Contract: 三厂商共识裁决（gold + silver）

## Gold（`build_gold_b.py` 扩展）

**输入**：`--teachers m1,m3,m_gpt --min-agree 2`（+ 现有 `--columns`）。

**表级裁决**：表 T ∈ gold ⟺ ≥2 个 teacher 的 reads/writes（同侧）含 T（canon 规范化后）。

**列级裁决**（仅在共识表 T 上，延续 067）：
- 三家对 T 都给具体列集 → gold 列 = 多数一致列（≥2 家共有的列）。
- 任一家对 T 弃权（`null`）/ 交集空 / 含通配 `*` → gold 列 = `null`（弃权）。
- `canon_col` 剥表限定前缀；`[]`→弃权。

**两把尺产出**：
- `real-c-tri.jsonl`：min-agree=2（主尺）。
- `real-c-tri-unan.jsonl`：额外 filter `consensus.agree.table==3`（3-of-3 子集，主尺的严格子集）。

**契约测试**（`test_tri_consensus.py`）：
1. 三家全同 → 表入 gold，`agree=3`。
2. 两家同、一家异 → 表入 2-of-3、不入 3-of-3。
3. 仅一家 → 表不入 gold。
4. 一家表级弃权 → 列裁决对该表弃权（`null`）。
5. 三家列集 {a,b}/{a,b}/{a,c} → gold 列=多数 {a,b}；{a}/{b}/{c} 无多数 → 弃权。
6. 含 `*` → 列弃权。

## Silver（`build_silver.py` 扩展）

**输入**：`--pair m1,m_flash,m_gpt`（三 teacher）`--min-agree 2 --keep-columns --exclude-gold real-c-tri`。

**表级裁决**：**2-of-3 多数**（≥2 家给该表则入 silver）——比 067 的 2-of-2 交集多边（召回）。

**列级**：共识表上多数/交集（延续 067），列取一致、弃权优先。

**防泄漏**：`--exclude-gold` 排除 real-c-tri 的 chash。

**契约测试**：2-of-3 多数比 2-of-2 交集边数 ≥（同池上）；exclude-gold 后与 gold chash 交集为空。

## 门② 相对判据（`significance_report.py` 复用）

- run-tri-3b vs 067 published（model-3b）在**同一** real-c-tri 上表级 McNemar → 不显著退化（SC-005）。
- run-tri-3b vs run-col-3b-mit 在同一 gold → 对照。
- bootstrap CI（表/列 P/R）。
