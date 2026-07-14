# Contract: `canon_col`（eval/metrics.py 纯函数）

```python
def canon_col(name: str) -> str | None:
    """列名规范化。返回归一后的列名,或 None 表示"弃权信号"(不参与集合运算)。"""
```

## 行为契约

| 输入 | 输出 | 说明 |
|---|---|---|
| `"Amount"` | `"amount"` | 小写 |
| `"  amount "` | `"amount"` | 去空白 |
| `"orders.amount"` | `"amount"` | 剥表限定前缀(单点) |
| `"db.orders.amount"` | `"amount"` | 剥多级前缀(取尾段) |
| `"o.amount"` | `"amount"` | 别名前缀同剥 |
| `"*"` | `None` | 通配→弃权信号 |
| `"orders.*"` | `None` | 通配尾段→弃权 |
| `""` / `None` | `None` | 空→弃权 |

## 列集归一（辅助）

```python
def canon_cols(cols: list[str] | None) -> set[str] | None:
    """None/[]→None(弃权);含通配→None(弃权);否则→归一后非空列名集合。"""
```
- `None` 或 `[]` → `None`（三态：弃权）。
- 任一元素归一为 `None`（通配）→ 整表列弃权 `None`（有 `*` 即不确定具体列）。
- 否则 → `{canon_col(c) for c in cols if canon_col(c)}`。

## 测试要点
- 每行行为契约一条断言。
- 通配传染：`["amount","*"]` → `None`（整集弃权）。
- 纯函数、无副作用、无 torch 依赖。
