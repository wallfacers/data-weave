# Contract: `build_silver` 列保留（单 teacher）

## 改动点

`realeval/build_silver.py`：`(writes if ... else reads).append({"table": t, "columns": None})`
两处（read/write）改为携带单 teacher 列：

```python
cols = canon_cols(col_map_m1.get(t))          # 单 teacher m1 该表列(弃权→None)
(writes if direction == "w" else reads).append({"table": t, "columns": cols})
```
- `--keep-columns` 开关：默认 False→行为不变（既有表级银标零回归）；True→保列。
- 单 teacher（m1）：SFT 容噪，无列标 item 保 `null`（模型学弃权）。
- 表级入选/方向/provenance 逻辑**完全不动**。

## 训练目标（`sft_qlora.py` 零改动）

- SFT prompt schema 已含 `{"table":str,"columns":[str] or null}`（`sft_qlora.py:29`）。
- 仅训练数据从"列恒 null"变为"携带 m1 列"→ 模型学会联合表+列输出。
- 超参沿用 059 固化配置（`--lr/--warmup/--max-grad-norm`）。

## CLI

```
# 银标(单 teacher m1)
PYTHONPATH=. python3 realeval/build_silver.py \
    --teacher-labels realeval/teacher_labels-silver --teacher m1 \
    --keep-columns --out data/silver-col.jsonl
# 联合重训(3B 先行)
python3 train/sft_qlora.py --data data/silver-col.jsonl \
    --base Qwen/Qwen2.5-Coder-3B-Instruct --out out/run-col-3b \
    --lr <059固化> --warmup <059固化> --max-grad-norm <059固化>
```

## 测试
- `--keep-columns` False → 输出与既有逐字段相等（零回归）。
- True → item 带 m1 列；m1 弃权表 → `columns:null`。
