# Phase 0 Research: 召回回收 · 置信度分层复核信封

## R1. 校准集：无独立非泄漏带标集 → gold C 嵌套 CV 去偏（决定性，经实测证伪修正）

**问题**：clarify Q1=A 定「分级校准常量冻结于独立集**测试集 A**」。实测盘查三连否定：
1. **测试集 A**（`realeval/gold/real.jsonl`）已随 052/054 worktree 删除、全盘不存在。
2. **pool-c-held**（162 条）**经 chash 核对：153 条 ∈ gold C**（`real-c-arbitrated.jsonl` 恰 153 行），即 **pool-c-held ⊇ gold C**（就是 gold C 的候选池，仅多 9 条）——**非独立**，用它校准再评 gold C = 循环。
3. **pool-c-train**（3000 条）与 gold C ∩=0（源隔离 ✓），但**模型在其上训练**（Run C 用其 1774 条）→ model-only 层 precision 记忆泄漏虚高，冻结阈到 gold C held-out 恐不达标，砸 SC-002。

→ **没有既独立于 gold C、又未被模型见过的带标集**（数据现实，非可绕过）。

**Decision**：退回 **gold C 嵌套 CV 去偏**（clarify 当时的选项 B）。复用 `conf_calibration_cv.py`（k 折/留一，文档明确「级定义固定确定性，CV 只对拟合量=级序+前沿切点做留出，口径干净」）：
1. 对 gold C 的模型预测**先过语义 grounding**（部署管线一致），键为行 idx（gold C 与 preds 同序 153 行，无 idx 字段则按行号对齐）；
2. `conf_calibration_cv --gold real-c-arbitrated.jsonl --model <grounded gold C preds> --k 5 --thr 0.95` → **留一/k 折 held-out** 前沿 precision/recall（无偏泛化估计，作 SC-002 报告口径）；
3. `confidence_calibration.calibrate` 全 gold C 点估计 → **部署固化常量**（写死进 `tier_classify_constants.py`，部署期不重算）；
4. 无需 dump pool-c-held 预测、无需 build_silver（用既有 gold C 预测，反而更简）。

**Rationale**：无独立集时，CV 去偏是 054 已确立并验证的诚实法（054 记「CV 确认前沿泛化 held-out P 0.93-1.00」）。级定义确定性、CV 只留出拟合量，消除样本内乐观偏置。部署常量取全 gold C 点估计不可避（gold C 是唯一干净带标集），CV 诚实估计其泛化。

**Alternatives considered**：
- **pool-c-train 冻结**：与 gold C 源隔离但模型训练过 → model 层泄漏虚高——**拒绝**（SC-002 风险）。
- **重建测试集 A′**：collect 新 GitHub 源 + teacher 标注——**拒绝**（teacher $ 花费，违反「无花费」；用户已确认走 CV）。

> ⚠️ 这是对 clarify Q1=A 的一次**数据证伪修正**（A 不存在、pool-c-held=gold C、pool-c-train 泄漏），经用户确认退回 CV 去偏。诚实边界：CV 只校同分布偏置、不覆盖分布漂移，真·独立 fresh 集仍待凭据/预算。

## R2.（已并入 R1）银标/独立集构建——本方案不需要

CV 去偏直接在 gold C 金标 + 既有模型预测上做，**不需**构建 pool-c-held 银标（build_silver）或 dump 新预测。R2 取消。

## R3. 分级 taxonomy 与校准序（复用，全 gold C 点估计 + CV 校验）

**Decision**：沿用 `confidence_calibration.TIERS` 五级 `{agree, sql_qual, sql_bare, model_qual, model_bare}`（通道归属 × 名字限定性），`_canonical_edges` 在 canon 下合并 model∪SQL 为互斥候选边。校准序 = 全 gold C 经验 precision 降序（部署点估计），其**泛化由 CV 去偏（R1/R8）验证**。

**Rationale**：052/054 已证「限定性是跨通道主信号、先验『SQL 恒优』被证伪」；本特性复用该机制。gold C 样本内序（实测 sql_qual>model_bare>agree>model_qual>sql_bare）作部署常量，CV held-out 序/前沿作诚实校验——若 CV 前沿 precision ≥ 阈则信封成立。

**Alternatives**：新设分级信号（logprob/自一致）——非目标（纯确定性、复用内核）。

## R4. 治理阈与自动/复核切分

**Decision**：自动采纳阈 = **累计校准 precision ≥ thr**（`best_frontier` 语义），thr 由 env `LINEAGE_AUTOACCEPT_MIN_PRECISION` 提供，默认 `"0.95"`。累计前沿（校准序采纳前 k 级）precision ≥ thr 的最大召回点即自动层边界；其余候选进复核层。thr=0 → 全并集进自动层；`LINEAGE_TIERING=0` → 完全关分层退回旧单一输出。

**Rationale**：与 clarify 定的 ≥0.95 治理严格一致；env 可配满足 US3；累计（非单级）门与 `confidence_calibration.best_frontier` 既有语义一致，直接复用。文档标注 0.90 为统计稳定膝点（gold C 实测自动层召回 0.047→0.453）。

## R5. confidence 值语义

**Decision**：每候选的 `confidence` = 其**所属 tier 的经验 precision 常量**（全 gold C 点估计，CV 去偏校验；非累计）。复核队列按该值降序。

**Rationale**：per-tier precision 是「这条候选对的先验概率」的最直接可解释值，用于复核者「从最可能对的先看、按阈早停」（FR-006）。累计 precision 是层边界判据（R4），二者分工清晰。

## R6. columns 处理

**Decision**：分层为**表级**操作；候选的 `columns` 字段**保留来源值**（模型给出的列，059 表级抽取多为 null），SQL-AST 通道补回的表 columns 取通道解析值或 null。分层不新增/丢弃列信息。

**Rationale**：059 是表级血缘，列多为 null；保留来源列零风险、向后兼容，避免引入列级新逻辑（YAGNI）。

## R7. 管线顺序与既有后处理关系

**Decision**：postprocess 链 = `模型 JSON 解析 → 语义 grounding（剔非表 FP）→ dir_fix（AST 方向）→ 【新】分层`。分层的 model 表集 M = grounding+dir_fix 后的模型输出；SQL 表集 S = `channel_router.extract_sql_lineage(content, exec_gated=True)`。agree 层方向冲突以 **SQL-AST 的 AST target 锚定为准**（clarify Q2=A）。

**Rationale**：grounding 在前保证进并集的模型候选已剔 grounded-but-wrong FP（不把 FP 当召回回收）；SQL 通道候选源自 AST 解析、本身 grounded。方向冲突取 AST 锚定与 dir_fix 既有偏好一致。

**Alternatives**：把 grounding-dropped 表回收进复核层——**拒绝**（那些是 FP、零召回价值，回收伤精度）。

## R8. CV 去偏方法

**Decision**：复用 `conf_calibration_cv.py`（留一/k折）对 **gold C** 的每级 precision + 前沿做去偏估计（held-out）；部署固化常量取全 gold C 点估计，CV held-out 数作 SC-002 报告口径；披露边界抖动（样本小）。

**Rationale**：052/054 已用同法处理样本内乐观偏置（054：CV 确认前沿泛化 held-out P 0.93-1.00）；直接复用，保持方法一致与可复核。
