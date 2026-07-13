# Phase 1 Data Model: 三厂商共识 gold + 全档重训

数据实体均为 JSONL 文件（gitignored 走 HF）。复用 067 schema，三厂商只扩裁决方，字段不变。

## 实体 1：Teacher 标注（teacher label）

一行 = 一个 teacher 对一条脚本的血缘判断。**复用 067 schema，新增 m_gpt teacher。**

```json
{
  "chash": "内容哈希(池内唯一键)",
  "teacher": "m1|m3|m_gpt|m_flash",
  "task_type": "SQL|PYTHON|SHELL",
  "reads":  [{"table": "str", "columns": ["str"] | null}],
  "writes": [{"table": "str", "columns": ["str"] | null}],
  "error":  "str | null",
  "usage":  {"in": int, "out": int}
}
```

- **文件**：`realeval/teacher_labels-c/{m1,m3,m_gpt}.jsonl`（gold）· `teacher_labels-silver/{m1,m_flash,m_gpt}.jsonl`（silver）。
- **复用**：m1/m3/m_flash 已存（067），仅新增 m_gpt（gold=sol，silver=luna）。
- **验证**：`usage` 必填（SC-009 成本核算）；解析失败 → `reads/writes=[]` + `error`（该 teacher 对该条弃权，不阻断共识）。

## 实体 2：三厂商共识 gold（评测尺子）

一行 = 一条脚本的共识金标。**表级 + 列级三态。**

```json
{
  "chash": "str",
  "task_type": "str",
  "content": "脚本原文",
  "labels": {
    "reads":  [{"table": "str", "columns": ["str"] | null}],
    "writes": [{"table": "str", "columns": ["str"] | null}]
  },
  "is_empty": bool,
  "consensus": {"agree": {"table": int, "column": "int|null"}, "vendors": ["m1","m3","m_gpt"]},
  "provenance": "tri-vendor min-agree=2 | unanimous-3"
}
```

- **文件**：`realeval/gold/real-c-tri.jsonl`（2-of-3 主尺）· `real-c-tri-unan.jsonl`（3-of-3 子集，`real-c-tri` 的严格子集）。**独立命名，不覆盖 067 `real-c.jsonl`。**
- **裁决**：表——min-agree=2 跨 {m1,m3,m_gpt}；列——仅在共识表上，多数/交集，弃权优先（`null`/`[]`/`*`→弃权）。
- **状态转移**：teacher 标注 →（min-agree=2）→ 2-of-3 gold →（filter agree=3）→ 3-of-3 子集。

## 实体 3：2-of-3 共识 silver（训练标签）

结构同 067 silver（`{chash, task_type, content, labels:{reads,writes}}`），裁决方从 2-of-2 交集换成 **2-of-3 多数**。

- **文件**：`data/silver-tri.jsonl`。**独立命名，不覆盖 067 `silver-col.jsonl`。**
- **裁决**：表——2-of-3 多数（≥2 家给该表则入）；列——共识表上多数/交集（延续 067，列取一致，弃权优先）。
- **防泄漏**：`--exclude-gold` 排除 real-c-tri 的 chash（无训练/测试泄漏）。

## 实体 4：run-tri 模型家族

- **run-tri-{05,15,3b}**：原始 Qwen2.5-Coder-{0.5,1.5,3}B-Instruct base + LoRA（r32/alpha64/e3）on silver-tri。
- **文件**：`out/run-tri-{05,15,3b}/`（adapter + merged，gitignored 走 HF）。
- **只读对照**：`out/run-col-*`（067）· `weights/weft-lineage-extractor-*`（059 published）。

## 实体 5：治理路由记录（限制②缓解）

一行 = 一条脚本的治理分层。

```json
{
  "chash": "str",
  "tier": "auto|review",
  "vendor_agree": int,
  "model_correct": bool | null
}
```

- **裁决**：`vendor_agree==3` → `auto`（自动采纳层）；`vendor_agree==2`（有一家分歧）→ `review`（人工复核层）。
- **汇总产出**（`out/governance-routing-068.md`）：自动层模型精度（SC-011）+ 分歧案例占比（人工工作量）+ 接 063 分层信封语义。

## 打分键（门①正交，只读回归）

`metrics.py` 现有 counts key：
- **表级 8 key**（逐字节不变，`test_metrics_columns.py` 钉死）：`tp/fp/fn/halluc/dir_*` 等。
- **列级独立 key**：`col_tp/col_fp/col_fn/col_halluc`（与表级物理隔离，门①）。
- 三厂商特性**不改 metrics.py 打分逻辑**——只喂新 gold；门① 回归单测必绿。
