# Data Model: 自训小模型血缘蒸馏（Phase 1）

管线数据流：**候选采集 → 双 teacher 打标 → 银标构建 → 训练 → 权重 → 服务/评测**。所有落盘为 JSONL（除权重）。

## 实体

### 1. CorpusCandidate（语料候选）
一条采自公开 GitHub 的 ETL 脚本。产出：`realeval/pool/*`（gitignore）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | str | 稳定标识（源仓库+路径派生） |
| `content` | str | 脚本全文 |
| `lang` | enum | `PYTHON`/`SHELL`/`SCALA`/`JAVA`/`SQL`/`CONFIG` |
| `content_hash` | str | 去重与污染护栏键（sha256） |
| `is_job` | bool | "looks like job" 粗筛结果 |
| `engine_hint` | str? | seatunnel/datax/flink/spark/hive/… 粗标 |

**规则**：`content_hash` ∈ 测试集 A/B 的候选 MUST 被剔除（FR-002）。

### 2. TeacherLabel（teacher 标注）
某 teacher 对一条候选给出的血缘。产出：`realeval/teacher_labels/{m1,m2}.jsonl`（缓存，gitignore）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `cand_id` | str | → CorpusCandidate.id |
| `teacher` | enum | `m1`(qwen-max) / `m2`(anthropic) |
| `reads` | [TableRef] | 读表 |
| `writes` | [TableRef] | 写表 |
| `error` | str? | 调用/解析失败标记（`_error`） |
| `cached_at` | str | 打标时间戳（幂等/续跑用） |

`TableRef` = `{ "table": str, "columns": [str]|null }`（沿用 `llm/clients.py` 契约）。

### 3. SilverLabel（银标）
经一致性+字面门+AST 方向构建的训练标签。产出：`data/silver.jsonl`（gitignore）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `cand_id` | str | → CorpusCandidate |
| `content` | str | 脚本全文（训练输入） |
| `task_type` | str | 语言/引擎（prompt 用） |
| `reads` / `writes` | [TableRef] | 银标血缘 |
| `is_empty` | bool | 空样本（弃权训练用） |
| `provenance` | enum | `intersection` / `disagreement_rescued`（来源口径，可审计） |
| `dir_source` | enum | `ast` / `teacher`（方向来源） |

**规则**（FR-004/005）：
- 表名 MUST 在 `content` 内字面出现（字面子串硬门）。
- `intersection` = m1∩m2 一致；`disagreement_rescued` = 分歧但字面出现且方向可 AST 定。
- MUST NOT 含任何合成表名（构造性反泄漏）。
- 空/非空按 **~20%** 空样本配比混合。

### 4. Gold（测试金标）
人工证伪裁决的真实标签，训练不可见。产出：`realeval/gold/real.jsonl`(A) + `realeval/gold/real-b.jsonl`(B)。

| 字段 | 类型 | 说明 |
|---|---|---|
| `content` | str | 脚本全文（键） |
| `reads` / `writes` | [TableRef] | 金标血缘 |
| `set` | enum | `A`（现有 139+141）/ `B`（新采 ≥100 非空） |
| `adjudicated_by` | str | `agent`（初裁）；`maintainer_sampled`（抽查终审标记） |

**规则**（FR-007/SC-006/SC-009）：B 非空 ≥100；A/B 与银标 content_hash 零重叠。

### 5. ModelArtifact（模型权重）
蒸馏 LoRA-merged 权重。产出：sibling `weft-lineage-weights/run-distill-{3b,1.5b}/merged/`（gitignore）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `base_model` | str | `Qwen/Qwen2.5-Coder-3B-Instruct`(主) / `-1.5B-`(对照) |
| `train_set` | str | silver.jsonl 版本/hash |
| `is_gate_target` | bool | 3B=true（卡门）/ 1.5B=false（对照） |
| `trainer_state` | json | loss/step/token_acc（权威训练指标） |

### 6. EvalReport（评测报告）
四方对比 + 泄漏审计 + 污染审计 + 达标判定。产出：`out/eval-distill-{b,a}.md`、`out/leak-distill.md`、`out/contamination-audit.md`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `test_set` | enum | A / B（判定以 B 为准） |
| `system_scores` | metrics | 模型+dir_fix（系统数） |
| `model_only_scores` | metrics | 模型独跑（不藏拐杖，FR-017） |
| `four_way` | table | distilled/m1/m2/regex |
| `verbatim_leak` | float | `--train-pool` 自有池逐字率 |
| `contamination_overlap` | int | train∩test content-hash 重叠数（须=0） |
| `gate_pass` | bool | SC-001~004 是否**同时**过（严格全过） |
| `teacher_family_disclosure` | str | teacher=Qwen 系披露（FR-017） |

`metrics` = `{ recall_nonempty, direction_nonempty, hallucination_full, precision_full, f1_nonempty }`（沿用 `eval/metrics.py` canon 口径）。

## 状态流转

```
Candidate(采集,去污染) → TeacherLabel×2(缓存,可续) → SilverLabel(构建,配比)
   → 训练(base→LoRA→merged) → ModelArtifact
   → 评测(A+B, 系统数/模型数/泄漏/污染) → EvalReport
   → gate_pass? ── 是 ──▶ swap MODEL_DIR + 改 HF 卡（加性发布）
              └─ 否 ──▶ 升级路径（难例挖掘→7B→dir_fix 调参）重训重评
```

## 验证规则汇总（映射 FR/SC）

- 字面子串硬门（FR-004）→ SilverLabel.表名 ⊆ content 字面。
- 零合成名（FR-005）→ SilverLabel 名池 ∩ 合成池 = ∅。
- 污染护栏（FR-002/SC-006）→ Candidate/Silver ∩ Gold(A∪B) content_hash = ∅。
- 达标（FR-016/SC-001~004）→ EvalReport(B).gate_pass = 四门同时真。
- 泄漏（SC-005）→ EvalReport.verbatim_leak ≈ 0。
- 纯自托管（SC-007）→ 服务路径 teacher 调用数 = 0。
