# Feature Specification: 列级血缘（联合表+列重训 · 保表级曲线 · 诚实可发表）

**Feature Branch**: `067-column-lineage`

**Created**: 2026-07-12

**Status**: Draft

**Input**: User description: "列级血缘:联合表+列重训小模型让其真学吐列,建列级 teacher 银标 gold+条件列 metric,保住表级逐规模单调曲线不退化,产出列级 scale 曲线"

## 背景与动机

血缘抽取小模型（041→059→063）此前把列级血缘**显式 defer 为 future work**：模型 JSON schema 有 `columns` 字段但训练银标列恒 `null`（模型从没见过列监督）、`eval/metrics.py` 只按表名算 P/R/F1、`build_gold_b`/`build_silver` 在造 gold/银标时把 teacher 免费吐出的列**主动抹成 `null`**。用户现在明确「列级我也想要」。

关键事实（探明）：teacher 系统提示词已要求 `{"table": str, "columns": [str] or null}`，`_parse_lineage_json` 原样保留 —— **列数据一直在 teacher 输出里流动，只在两个卡点（`build_gold_b.py:75`、`build_silver.py:96`）被丢弃**。因此造列级 gold/银标的边际成本 ≈ 一次普通表级重标（列在同一次 API 调用里白送）。

本特性把列级从空壳推进到**模型级、可评测、诚实可发表**：联合表+列重训新模型家族，建列级 teacher 银标 gold 与条件列 metric，**硬约束是既有表级逐规模单调曲线不退化**（3B 表 precision 0.743 / recall 0.832 是红线），并产出列级逐规模 scale 曲线作为论文第二根脊椎。

**硬约束（贯穿全篇）**：
- teacher 列标预算 ≤ ¥100（成本约束已由用户放宽到此上限）。
- 重训只用本机现有 RTX 5070（059/063 同款，非新 GPU、零花费，仅耗墙钟）。
- 无人工列标注 —— 列 gold 只能是 teacher 共识银标（列级循环性须如实声明）。
- 列增强模型是**新训练家族**（`run-col-*`），**绝不覆盖**已发布的表级模型；已发布表级曲线是只读基线。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 列级评测基建 + 表级正交性红线（Priority: P1）

作为血缘论文作者，我要能对任意模型/teacher 的预测**诚实评测列级 P/R/F1**，且这套列打分**在数学上碰不到既有表级指标**——这样列级工作绝不可能污染已辛苦拿到的表级结果。

**Why this priority**：这是 MVP 与红线地基。没有列 gold + 条件列 metric + 正交性证明，后续重训无从评测、也无法保证表级不被扰动。即使不重训，本 story 独立交付「诚实测现有 best-effort 列输出到底多弱」的能力。

**Independent Test**：用带列的 teacher 银标 gold 给冻结的既有 3B 模型 preds 打分，产出列级 P/R/F1；同时运行正交性单测证明开列打分前后表级 counts 逐字节相等。

**Acceptance Scenarios**：

1. **Given** 双 teacher 已对同一表点名各自列集，**When** 跑列级一致裁决（min-agree=2 交集），**Then** gold 该表 `columns` = 两方列交集；交集空或任一方弃权 → `columns=null`（弃权，不硬判）。
2. **Given** 一批 (gold, pred, content)，**When** 分别在「带列 gold」与「列全抹 None」下调用 `score_row`，**Then** 表级 `tp/fp/fn/halluc/dir_correct/dir_total` 逐字节相等（门① 单测）。
3. **Given** pred 对某表列弃权（null）而 gold 有列，**When** 评列，**Then** 全记 `col_fn`（漏，不算幻觉）；反之 pred 给列而 gold 弃权 → 该表列跳过不评。

---

### User Story 2 - 列增强联合重训 3B + 表级曲线复现门（Priority: P2）

作为血缘论文作者，我要联合表+列重训 3B 模型让它**真学会吐列**，达到列级 precision ≥ 0.70 / recall ≥ 0.55，**同时**新模型必须复现表级逐规模曲线（3B 表 p ≥ 0.72、r ≥ 0.80、script 救回 Δr 显著），否则如实报「表列权衡」负结果而不覆盖既有曲线。

**Why this priority**：这是核心能力交付与门② 经验校验。3B 先行作为红线闸——先烧一次 GPU 验证方向，过了才扩两端。

**Independent Test**：重训 `run-col-3b`，在带列 gold 上评列级 P/R/F1 达标，且复用既有 significance harness 证明 3B 表级指标落既有 CI 带内（McNemar 不显著退化）。

**Acceptance Scenarios**：

1. **Given** 列增强联合银标（表+列同一 SFT 样本，弃权表仍 `columns:null`），**When** 重训 `run-col-3b`，**Then** 列级（条件表命中）precision ≥ 0.70、recall ≥ 0.55、F1 ≥ 0.60，带 bootstrap 95% CI。
2. **Given** `run-col-3b` 的 preds，**When** 跑表级评测，**Then** 表 p ≥ 0.72 且 r ≥ 0.80（落既有 3B CI 带内，vs 既有 3B McNemar 不显著退化）且 script 救回 Δr vs SQLLineage 仍显著。
3. **Given** 3B 表级显著退化，**When** 判门②，**Then** 停止扩训、如实记「表列权衡」负结果、既有表级曲线与模型保持不动。

---

### User Story 3 - 扩训 0.5/1.5B + 列级 scale 曲线（Priority: P3）

作为血缘论文作者，我要在 3B 过门后扩训 0.5B/1.5B 列增强模型，产出**列级逐规模 scale 曲线**，理想单调升（规模越大列越准）作为论文第二根脊椎。

**Why this priority**：第二根脊椎是论文增量叙事，但依赖 US2 过门；单档 3B 已能交付核心 P/R 目标。

**Independent Test**：重训 `run-col-05`/`run-col-15`，在同一列 gold 上评测，与 3B 并列出列级 scale 曲线，同时确认三档表级曲线仍单调。

**Acceptance Scenarios**：

1. **Given** `run-col-{05,15,3b}` 三档 preds，**When** 出列级 scale 报告，**Then** 列级 F1 逐规模呈现（理想 0.5B<1.5B<3B 单调；非单调如实报为中性/负结果）。
2. **Given** 三档新模型，**When** 复跑表级 scale 对照，**Then** 表级单调性保住（既有曲线复现）。

---

### User Story 4 - 确定性列基线对照（Priority: P4，可选 stretch）

作为血缘论文作者，我想在 SQL 子集上用确定性工具（SQLLineage 列级）做**列级基线**，佐证「工具在脚本上列级也失效、模型救回」的招牌叙事。

**Why this priority**：强化叙事但非主路径；工具列级抽取跨进程/解析代价高，默认可砍。

**Independent Test**：SQLLineage 列级抽取在 SQL 子集出列 preds，与模型列 P/R 并列对照。

**Acceptance Scenarios**：

1. **Given** SQL 子集脚本，**When** 跑 SQLLineage 列级抽取，**Then** 产出列 preds 并入列级 baseline 报告与模型列对照。

---

### Edge Cases

- teacher 对某表列输出 `SELECT *` / 通配 / 动态构造 → 归为弃权信号（`columns=null`），不当具体列。
- 列名带表限定前缀（`orders.amount` / `o.amount`）→ `canon_col` 剥前缀归一，消解「限定 vs 裸名」假错配。
- gold 列极稀疏（只在双 teacher 点名同列时存在）→ 列级评测 n 小、CI 宽、McNemar 功效弱 → 如实报，不夸大。
- 表级未命中（非 TP）的表 → 列不评（条件式），避免在错表上谈列。
- 重训后表级轻微退化但列级达标 → 触发门②「表列权衡」负结果路径，不覆盖既有模型/曲线。
- teacher 列输出为空 `[]` → 无法区分「确定无列」与「不知道」→ 保守归弃权（同 `null`）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 在 `build_gold_b` 增加列级一致裁决模式：仅在双 teacher 已一致的表上裁列，两方都给列集时 gold 列 = 交集（min-agree=2 列粒度），交集空或任一方弃权 → `columns=null`。
- **FR-002**: 系统 MUST 提供纯函数 `canon_col`（小写、去空白、剥表限定前缀、`*`/通配→弃权信号），供 gold 裁决与评测共用。
- **FR-003**: 系统 MUST 在 `metrics.py` 加**条件列打分**：只对表级已命中（TP）的表评列，用独立 key `col_tp/col_fp/col_fn/col_halluc/col_pred_total/col_eval_tables`；gold 该表弃权则跳过，pred 弃权而 gold 有列则记 `col_fn`。
- **FR-004**: 系统 MUST 保证列打分对表级返回值**逐字节零扰动**：`score_row` 的 `tp/fp/fn/halluc/dir_correct/dir_total` 不变，并有断言此不变量的单测（门①）。
- **FR-005**: 系统 MUST 在 `build_silver` 停止抹列，产出列增强训练银标（弃权表仍 `columns:null`，让模型学到「不确定就吐 null」）。
- **FR-006**: 系统 MUST 联合表+列监督重训 3B（`run-col-3b`，同一 SFT 样本携带表结构+共识列，超参沿用 059 已固化配置）。
- **FR-007**: 系统 MUST 复用既有 significance harness 校验门②：`run-col-3b` 表级 p/r 落既有 3B CI 带内、vs 既有 3B McNemar 不显著退化、script 救回 Δr 仍显著。
- **FR-008**: 系统 MUST 以 3B 先行为闸：门② 未过则停止扩训、不覆盖既有模型/曲线、如实记负结果。
- **FR-009**: 系统 MUST 在 3B 过门后扩训 `run-col-05`/`run-col-15`，产出列级逐规模 scale 报告并复核表级单调性保住。
- **FR-010**: 系统 MUST 在 `significance_report`/`eval_baselines_c` 报告加列级指标行（表级行原样保留，自动发现 `out/preds/*.jsonl`）。
- **FR-011**: 系统 MUST 记录诚实边界溯源（列级循环性、gold 列稀疏/小 n/宽 CI、新模型 ≠ 已发布权重的「近似复现」性质），并入证据表（呼应 065 `PAPER-EVIDENCE.md` 的无裸数字约定）。
- **FR-012**: 系统 MUST 把列增强模型作为新家族 `run-col-*` 落盘，**不覆盖**既有 0.5/1.5/3B 表级模型；已发布表级曲线为只读基线。
- **FR-013**: 系统 SHOULD（US4 可选）在 SQL 子集提供确定性列基线（SQLLineage 列级）与模型列对照。
- **FR-014**: 系统 MUST 保证既有 `metrics.py` 与评测相关单测零回归（表级返回值不变的直接推论）。
- **FR-015**: 系统 MUST 把 teacher 列标累计花费控制在 ≤ ¥100，并记录真实 token 用量/成本。

### Key Entities *(include if feature involves data)*

- **列级 gold（`real-c` 加列）**：teacher 共识银标；每 reads/writes item 的 `columns` 为三态（`null` 弃权 / 具体列集），仅在双 teacher 一致表上有具体列。
- **列增强训练银标**：`build_silver` 保列产物，供联合监督重训。
- **`run-col-*` 模型家族**：0.5/1.5/3B 列增强新权重，本机 5070 QLoRA 训出，双维度（表+列）评测。
- **列级 metric counts**：`col_tp/col_fp/col_fn/col_halluc/col_pred_total/col_eval_tables`，与表级 counts 物理隔离。
- **teacher 列输出**：`teacher_labels/{m1,m3}.jsonl` 中 item 已含 `columns`（零改动，本就免费吐）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `run-col-3b` 列级（条件表命中）**precision ≥ 0.70**，带 bootstrap 95% CI。
- **SC-002**: `run-col-3b` 列级 **recall ≥ 0.55**，带 CI。
- **SC-003**: `run-col-3b` 列级 **F1 ≥ 0.60**。
- **SC-004**: 门① 正交性单测通过——列 gold 有/无两种输入下 `score_row` 表级 counts 逐字节相等。
- **SC-005**: 门② 表级曲线复现——`run-col-3b` 表 p ≥ 0.72、r ≥ 0.80，vs 既有 3B McNemar 不显著退化，三档表级 F1 单调保住，script 救回 Δr vs SQLLineage 三档全显著。
- **SC-006**（stretch）: 列级逐规模 F1 单调（0.5B < 1.5B < 3B）；非单调时如实报为中性/负结果。
- **SC-007**: teacher 列标累计花费 ≤ ¥100（真实 token 用量记录佐证）。
- **SC-008**: 既有 `metrics.py`/评测单测零回归（全绿）。

## Assumptions

- teacher（qwen-max=m1 / deepseek-v4-pro=m3）在同一次调用里已按 schema 吐 `columns`，无需改提示词；列质量比表噪，作诚实边界处理而非阻塞。
- 语料复用既有 the-stack ETL 候选池（≤¥100 内可微扩，但不强求扩池）。
- 重训超参沿用 059 已固化的 bf16 稳定性配置（lr/warmup/max-grad-norm），以最小化与表基线的训练差异。
- 列 gold 采「交集/弃权优先」保守裁决——高精度、诚实、n 偏小；召回价值主要靠模型学习而非扩 gold。
- 本特性只在 ml 侧（抽取器 + 评测 + serving 透传），不改后端平台 Calcite 消费面；US4 若做也仅取 SQLLineage 列级作离线基线。
- 数据/权重/preds/gold 全 gitignored 走 HF，不入代码仓（沿用既有约定）。

## Out of Scope

- 平台侧（后端 Calcite `getColumnOrigins`）列级消费链路改造——成熟但无研究新颖度，不在本特性。
- 人工列级标注 / 独立非泄漏列级带标集——预算与约束不允许，列 gold 恒为 teacher 共识银标。
- 7B 及以上规模列训——12G 显存受限（既有 defer 不变）。
- 列级血缘的下游血缘图库（neo4j）落库——仅抽取+评测，不落图。
