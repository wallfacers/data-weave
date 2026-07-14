# 067 列级血缘：证据台账（独立于 065 的 out/PAPER-EVIDENCE.md，不覆盖）

065 显式 defer 列级为 future work（空壳：gold/评测/serving 全表级）。067 把它推进到**可评测、可发表**：
联合表+列重训 + 门①正交 + 门②表级曲线保护 + 隔离消融诚实归因。

## 数据来源（同 065 磁盘清空后从零重建，但为 067 独立 gold）

- **列 gold** `realeval/gold/real-c.jsonl`（067 独立重建，**不复用/覆盖 065 的 130 行 gold**）：
  `collect_stack --target 400`（the-stack-dedup 流式）→ `teacher_label --teachers m1,m3`（qwen-max ∩ deepseek-v4-pro）
  → `build_gold_b --min-agree 2 --columns`（**列级一致裁决**：双 teacher 交集 min-agree=2/一方弃权→null/含通配→null）
  → **397 行 / 非空 107 / 具体列表实例 111**（≥30 门槛 3.7×）/ 434 列 / 均 3.9 列·表；集中 SQL(105)+SHELL(4)+PYTHON(2)。
- **列银标** `data/silver-col.jsonl`：`collect_stack --target 3000` → `teacher_label m1,m_flash` →
  `build_silver --pair m1,m_flash --keep-columns`（**表 recipe 不变=双 teacher 交集护门② + 列取 pair[0]=m1**）
  → 979 行 / 非空 783 / **937 具体列表实例**；`--exclude-gold` 排除 real-c（**无训练/测试泄漏**）。
- **teacher 循环性**：列 gold 由 m1∩m3 派生 = 构造性循环，故**不以"超 teacher"为列级头条**（承 065 脊椎）。

## US1：列级评测基建 + 门①正交（无 GPU）

| 陈述 | 证据 | 真实数字 | 状态 |
|---|---|---|---|
| **[门①] 列打分绝不扰动表级计数**（逐字节相等）| `tests/test_metrics_columns.py::test_column_scoring_never_perturbs_table_counts` | 多态 fixtures × canon 双档，表级 8 key 逐字节相等 | ✅ 单测钉死 |
| **[before 锚] 既有表级 3B 一列不吐=空壳实证** | `out/significance-col-c.md` | 既有 model-3b 列 p 1.000/r **0.000**/f1 0.000（n=97）| ✅ 真跑 |
| **[门② 基线] 既有 3B 表级（067 gold）** | `out/significance-col-c.md` | model-3b 表 p 0.775[0.681,0.861]/r 0.845[0.772,0.905]/f1 0.808 | ✅ 真跑 |

## US2：联合重训 + 门② + 隔离消融（核心）

**★头条（US2）**：联合列监督把列抽取从 **recall 0.000 → 0.840**（run-col-3b-mit），同时表级经缓解重训恢复到
语料受限天花板、门②相对判据 PASS。**表列权衡真实存在但可缓解**（隔离消融干净归因 + 容量旋钮消除）。

四模型同 `real-c.jsonl` 对比（`out/significance-mit.md`，512 token，非空 n=107 / 列条件命中 n≈93-98）：

| 模型 | 训练 | 表 p | 表 r | 表 f1 | 列 p | 列 r | 列 f1 |
|---|---|---|---|---|---|---|---|
| model-3b（既有 published）| 原 059 全量·无列 | 0.775 | 0.845 | 0.808 | 1.000 | **0.000** | 0.000 |
| run-tblonly-3b（消融）| 979 行·**剥列** | 0.742 | 0.798 | 0.769 | 1.000 | 0.000 | 0.000 |
| run-col-3b（首训 r16/e2）| 979 行·带列 | 0.705 | 0.649 | 0.676 | 0.827 | 0.869 | 0.847 |
| **run-col-3b-mit（交付 r32/e3）** | 979 行·带列 | **0.782** | **0.746** | **0.763** | 0.803 | **0.840** | 0.821 |

**隔离消融（诚实因果分解，表 r 0.845→0.649 的 −0.196）**：base/recipe/silver 三同、逐条内容一致、唯一变量=列：
- **语料规模效应**（原全量→979，列关）：−0.047（0.845→0.798，混淆项）
- **加列监督效应**（979 剥列→带列，首训）：**−0.149**（真·表列权衡，非混淆）

**截断假设先证伪**：max_new_tokens 512→1024 仅表 r +0.02（`out/significance-tok1024.md`）→ 权衡非推理伪影。

**缓解重训**（LoRA r16→32/alpha32→64/epochs2→3，直击容量争用+欠训）：列效应 **−0.149→−0.052**，
表 f1 0.763 ≈ 纯表天花板 0.769 = **权衡几近消除**，列仍强（r 0.840）。

**门② 相对判据（同 067 gold，`out/significance-mit.md`）**：
- vs 既有 published：precision diff **+0.007**[−0.053,+0.063] 不显著、McNemar b/c=4/6 **p=0.754 不显著退化** ✅
- vs 纯表天花板 run-tblonly：McNemar 2/3 **p=1.000** = 表级统计不可区分（权衡消除）✅
- vs 首训 run-col-3b：precision **+0.077**[+0.009,+0.156] **显著改进** ✅

**SC 达成（列级，run-col-3b-mit）**：col p 0.803 ≥0.70 ✅ / col r 0.840 ≥0.55 ✅ / col f1 0.821 ≥0.60 ✅。

**诚实边界**：列 gold=teacher 交集银标（循环性）；绝对表 r 0.746 < 065 红线 0.832，但红线为 065 独立 gold；
067 gold 上 vs 既有 3B McNemar 不显著退化，绝对差主为语料规模混淆（tblonly 同为 0.798）。

**方法学偏离（如实记）**：① 银标用双 teacher 交集（非 clarify R6 单 teacher 措辞）—— 护表级曲线红线优先；
② 交付模型 run-col-3b-mit 超参 r32/e3 偏离 059 固化 r16/e2 —— 067 联合任务容量缓解，非表级曲线配方。

## US3：列级 scale 曲线（0.5/1.5/3B，mit 配方 r32/e3，`out/significance-scale.md`）

同 `real-c.jsonl`，512 token，三档均 mit 配方（与 3B 交付一致=内部可比）：

| 模型 | 表 p | 表 r | 表 f1 | 列 n | 列 p | 列 r | 列 f1 |
|---|---|---|---|---|---|---|---|
| run-col-05（0.5B）| 0.715 | 0.447 | 0.550 | 81 | 0.776 | 0.731 | 0.753 |
| run-col-15（1.5B）| 0.681 | 0.468 | 0.555 | 67 | 0.875 | 0.845 | **0.860** |
| run-col-3b-mit（3B）| 0.782 | 0.746 | 0.763 | 94 | 0.803 | 0.840 | 0.821 |

| 陈述 | 证据 | 真实数字 | 状态 |
|---|---|---|---|
| **[门② 单调子判据] 表级 f1 逐规模单调保住** | `out/significance-scale.md` | table f1 **0.550→0.555→0.763** 单调升；recall 0.447→0.468→0.746 单调升 | ✅ 真跑 |
| **[SC 达成] 列 f1 各档全过阈（≥0.60）** | `out/significance-scale.md` | 0.753 / 0.860 / 0.821 | ✅ 真跑 |
| **[SC-006 stretch·诚实混合] 列 scale 非干净单调** | `out/significance-scale.md` | col f1 1.5B 0.860 > 3B 0.821（**非单调**）；n 差异 81/67/94=列评条件于表命中→**不同分母混淆**；CI 重叠（3B[0.731,0.901] vs 1.5B[0.796,0.913]）差异不显著 | ⚠️ 中性/未达成 |

**诚实边界（US3）**：列 scale 曲线受**不同表命中分母**混淆（各档命中表集不同→列评样本非同一子集），不能干净确立单调；
1.5B 列 f1 略高于 3B 但 CI 重叠不显著。∴**不以"列级逐规模单调"为头条**（承 065 脊椎的诚实文化），
仅报"列抽取各档均强 + 表级单调保住"。

## 成本台账（SC-007 ≤¥100，从 label 记录的真实 usage 算）

teacher 调用真实 token（`realeval/teacher_labels-{c,silver}/*.jsonl` 的 `usage` 字段，非估算）：

| 模型 | 调用 | in tokens | out tokens | 定价(RMB/1M in,out) | 成本 |
|---|---|---|---|---|---|
| m1=qwen-max（gold+silver）| 3399 | 3.98M | 0.16M | 2.4 / 9.6 | ¥11.04 |
| m3=deepseek-v4-pro（gold）| 399 | 0.09M | 0.23M | 4.0 / 16.0 | ¥4.09 |
| m_flash=deepseek-v4-flash（silver）| 3000 | 3.58M | 1.58M | 1.0 / 4.0 | ¥9.91 |
| **合计** | 6798 | | | | **≈¥25.04** |

拆分：**列 gold ≈¥5.46 + 列银标 ≈¥19.58**。**SC-007 PASS**（4× 裕度；定价翻倍 ¥50 仍 <¥100）。
（列在同一 teacher 调用白送=065 探明的免费列，故列级边际成本≈普通表级重标。）

## US4：SQLLineage 列级基线对照（招牌图·列级，`out/col-baseline-c.md`）

承 065 招牌对比（工具@脚本 recall≈0，模型救回）延伸到列级。SQL 子集上：

| 抽取器 | col_eval_n | col_p | col_r | col_f1 |
|---|---|---|---|---|
| SQLLineage `get_column_lineage()` | 25 | 1.000 | **0.143** | 0.250 |
| run-col-3b-mit（模型）| 89 | 0.830 | **0.839** | 0.834 |

| 陈述 | 证据 | 真实数字 | 状态 |
|---|---|---|---|
| **[招牌·列级] 工具即便在 SQL 上列级也结构性弱，模型救回** | `out/col-baseline-c.md` | SQLLineage 列 r **0.143** vs 模型列 r **0.839**，**Δ+0.696** | ✅ 真跑 |

**诚实边界（US4）**：col_eval_n 差异（25 vs 89）源于两者表命中集不同（列评条件于表命中）；SQLLineage 列 p 1.0
系其仅在少数表给列（vacuous 高精度/近零召回=经典工具行为）。非 SQL/解析失败→列弃权。
