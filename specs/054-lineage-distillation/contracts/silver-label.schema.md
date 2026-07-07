# Contract: 银标 JSONL Schema（build_silver.py 输出）

`data/silver.jsonl`——每行一条训练样本。消费者：`data/`（组装训练集）+ `train/sft_qlora.py`。

## 每行结构

```json
{
  "cand_id": "gh:owner/repo@sha:path/to/job.py",
  "content": "<脚本全文>",
  "task_type": "PYTHON",
  "reads":  [{"table": "ods.orders", "columns": null}],
  "writes": [{"table": "dwd.orders_clean", "columns": null}],
  "is_empty": false,
  "provenance": "intersection",
  "dir_source": "ast"
}
```

## 字段约束

| 字段 | 约束 |
|---|---|
| `content` | 非空；训练输入原文 |
| `task_type` | ∈ {PYTHON,SHELL,SCALA,JAVA,SQL,CONFIG}（config/纯SQL 一般不入模型训练集，见 R5） |
| `reads[].table` / `writes[].table` | **MUST 在 content 内字面出现**（字面子串硬门）；**MUST NOT ∈ 合成表名池** |
| `is_empty` | true ⟺ reads=[] 且 writes=[]；空样本占比 ≈20% |
| `provenance` | `intersection`（m1∩m2 一致）\| `disagreement_rescued`（分歧+字面+AST 方向） |
| `dir_source` | `ast`（sqlglot 可解析取 AST 方向）\| `teacher`（不可解析取 teacher 方向） |

## 不变量（单测 `test_build_silver.py` 覆盖）

1. **字面门**：∀ 表名 ∈ 样本 → 表名 ⊆ content（大小写/`:`→`.`归一后）。
2. **零合成名**：银标全体表名 ∩ `data/templates.py` 合成池 = ∅。
3. **污染护栏**：样本 content_hash ∉ Gold(A∪B) content_hash 集。
4. **配比**：`is_empty=true` 占比落在 [0.15, 0.25]。
5. **方向一致性**：`dir_source=ast` 的边方向 = sqlglot 对该语句的判定；分歧且不可 AST 定的边被丢弃（不产出）。
