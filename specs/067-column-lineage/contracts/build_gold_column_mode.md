# Contract: `build_gold_b` 列级一致裁决模式

## 改动点

`realeval/build_gold_b.py`：
- `_role_map(rec)` 旁增 `_col_map(rec) -> dict[str, set[str]|None]`：表名(lower) → 该 teacher 给该表的 `canon_cols` 结果（弃权→None）。
- `decide_tables(content, teacher_recs, min_agree, columns=False)` 加 `columns` 开关：
  - `columns=False`（默认）→ 行为**完全不变**（既有表级 gold 零回归）。
  - `columns=True` → 对每个入 gold 的表，追加列级裁决。

## 列级裁决（`columns=True`，仅对已入 gold 的表）

```
cmaps = [_col_map(r) for r in voters]          # 该表的投票 teacher 各自列集
present = [cm[t] for cm in cmaps if cm.get(t) is not None]   # 非弃权的列集
if len(present) < 2:            gold_cols = None   # 不足双方具体列 → 弃权
else:                          inter = set.intersection(*present)
                               gold_cols = sorted(inter) if inter else None  # 交集空→弃权
item = {"table": t, "columns": gold_cols}
```
- 交集/弃权优先（R2）；`min-agree=2` 列粒度。
- 表级方向/入选逻辑**完全不动**——列只是给已定表附加 columns。

## CLI

```
PYTHONPATH=. python3 realeval/build_gold_b.py \
    --teacher-labels realeval/teacher_labels-c \
    --min-agree 2 --columns \
    --out realeval/gold/real-c.jsonl
```

## 测试（`tests/test_build_gold_columns.py`）
- 双方都给 `{amount,uid}` → gold `["amount","uid"]`。
- 双方 `{amount,uid}` vs `{amount}` → 交集 `["amount"]`。
- 一方弃权(null) → gold `null`。
- 交集空(`{a}` vs `{b}`) → gold `null`。
- 含 `*` → 该方弃权 → gold `null`。
- `columns=False` 下输出与既有逐字段相等（零回归)。
