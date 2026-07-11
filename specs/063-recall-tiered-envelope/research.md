# Phase 0 Research: 召回回收 · 置信度分层复核信封

## R1. 冻结校准集：测试集 A 已删 → 用 pool-c-held 替身（决定性）

**问题**：clarify Q1=A 定「分级校准常量冻结于独立集**测试集 A**，gold C 纯 held-out」。但盘查发现 **`realeval/gold/real.jsonl`（052/054 的测试集 A 金标）已随 052/054 worktree 删除、全盘不存在**，无法复用。

**Decision**：用 **`realeval/pool-c-held`（162 条 the-stack held-out 脚本）+ `realeval/teacher_labels-c-held`（m1/m3/m_flash teacher 标签）** 作为冻结校准集替身。流程：
1. 由 teacher 标签构建 pool-c-held 的银标（沿用 `build_silver.py` 的一致口径，如 m_flash∩m1 共识）；
2. 对 pool-c-held **dump 模型预测**（GPU 推理，无 teacher $，模型**未在此切片上训练**——无泄漏）；
3. 在 pool-c-held 上跑 `confidence_calibration.calibrate` + `conf_calibration_cv`（CV 去偏）→ **固化每级 held-out precision 常量表**；
4. 常量写死进 `tier_classify.py`，部署期不重算；
5. gold C 作为**纯 held-out** 评测（`rescore_tiered.py`），不参与定级。

**Rationale**：pool-c-held 满足 Q1=A 的**全部实质意图**——① 与 gold C **源隔离**（the-stack vs GitHub-fresh，059 前提）；② **模型未训练**于此切片（held-out，无记忆泄漏，避免自动层 precision 虚高）；③ teacher 标签**已存在**（零新花费）；④ gold C 保持纯 held-out，消除「同集既定级又评测」的循环。它比字面的「测试集 A」更干净（A 是 gold 但会被模型见过与否不明；pool-c-held 明确 held-out）。

**Alternatives considered**：
- **重建测试集 A**：重跑 collect_stack + teacher_label 生成新独立金标——**拒绝**（teacher API $ 花费，违反「无花费」约束）。
- **仅 gold C 嵌套 CV**（k-1 折定级、留 1 折评测轮转）：给无偏 held-out 估计，但**部署期固化常量仍需来自非 gold C 的集**（否则 gold C 泄进部署常量）——不满足「独立冻结集」诉求，且 gold C 非空仅 61 条再切更弱。**拒绝**为主方案，保留为 gold C 上的辅助印证。
- **训练池 pool-c-train 定级**：模型在其上训练过→模型-tier precision 记忆泄漏虚高——**拒绝**。

> ⚠️ 这是对 clarify Q1=A **字面**答案的一处受迫替换（A 数据已不存在），但**保全其意图**。已在完成报告显式披露，供用户否决。

## R2. 银标构建口径（pool-c-held）

**Decision**：复用 `build_silver.py` 的跨厂商一致口径构建 pool-c-held 银标——teacher 一致（如 m_flash∩m1，或该脚本既有的 bulk 口径），与 059 训练银标同源逻辑，保证校准所测的「候选正确性」与训练目标同尺子。

**Rationale**：分级 precision = 候选边命中银标的比例；用与训练目标一致的银标定级，再在 gold C（真 held-out）验证转移性——若银标定级的前沿在 gold C 上 precision 仍 ≥ 阈，则信封成立；不成立则 held-out 评测如实暴露（诚实）。

**Alternatives**：用单一最强 teacher（m3/deepseek-pro）当银标——备选，`build_silver` 一致口径更稳，主用一致口径。

## R3. 分级 taxonomy 与校准序（复用，冻结于 pool-c-held）

**Decision**：沿用 `confidence_calibration.TIERS` 五级 `{agree, sql_qual, sql_bare, model_qual, model_bare}`（通道归属 × 名字限定性），`_canonical_edges` 在 canon 下合并 model∪SQL 为互斥候选边。校准序由 pool-c-held 经验 precision 降序 + CV 去偏产出并**冻结**（不用 gold C 的样本内序）。

**Rationale**：052/054 已证「限定性是跨通道主信号、先验『SQL 恒优』被证伪」；本特性复用该机制，只把定级基准从「样本内 gold」换成「held-out pool-c-held」。gold C 上跑出的样本内序（sql_qual>model_bare>agree>model_qual>sql_bare）仅作对照，不作部署序。

**Alternatives**：新设分级信号（logprob/自一致）——非目标（纯确定性、复用内核）。

## R4. 治理阈与自动/复核切分

**Decision**：自动采纳阈 = **累计校准 precision ≥ thr**（`best_frontier` 语义），thr 由 env `LINEAGE_AUTOACCEPT_MIN_PRECISION` 提供，默认 `"0.95"`。累计前沿（校准序采纳前 k 级）precision ≥ thr 的最大召回点即自动层边界；其余候选进复核层。thr=0 → 全并集进自动层；`LINEAGE_TIERING=0` → 完全关分层退回旧单一输出。

**Rationale**：与 clarify 定的 ≥0.95 治理严格一致；env 可配满足 US3；累计（非单级）门与 `confidence_calibration.best_frontier` 既有语义一致，直接复用。文档标注 0.90 为统计稳定膝点（gold C 实测自动层召回 0.047→0.453）。

## R5. confidence 值语义

**Decision**：每候选的 `confidence` = 其**所属 tier 的 held-out（pool-c-held CV）经验 precision 常量**（非累计）。复核队列按该值降序。

**Rationale**：per-tier precision 是「这条候选对的先验概率」的最直接可解释值，用于复核者「从最可能对的先看、按阈早停」（FR-006）。累计 precision 是层边界判据（R4），二者分工清晰。

## R6. columns 处理

**Decision**：分层为**表级**操作；候选的 `columns` 字段**保留来源值**（模型给出的列，059 表级抽取多为 null），SQL-AST 通道补回的表 columns 取通道解析值或 null。分层不新增/丢弃列信息。

**Rationale**：059 是表级血缘，列多为 null；保留来源列零风险、向后兼容，避免引入列级新逻辑（YAGNI）。

## R7. 管线顺序与既有后处理关系

**Decision**：postprocess 链 = `模型 JSON 解析 → 语义 grounding（剔非表 FP）→ dir_fix（AST 方向）→ 【新】分层`。分层的 model 表集 M = grounding+dir_fix 后的模型输出；SQL 表集 S = `channel_router.extract_sql_lineage(content, exec_gated=True)`。agree 层方向冲突以 **SQL-AST 的 AST target 锚定为准**（clarify Q2=A）。

**Rationale**：grounding 在前保证进并集的模型候选已剔 grounded-but-wrong FP（不把 FP 当召回回收）；SQL 通道候选源自 AST 解析、本身 grounded。方向冲突取 AST 锚定与 dir_fix 既有偏好一致。

**Alternatives**：把 grounding-dropped 表回收进复核层——**拒绝**（那些是 FP、零召回价值，回收伤精度）。

## R8. CV 去偏方法

**Decision**：复用 `conf_calibration_cv.py`（留一/k折）对 pool-c-held 的每级 precision 做去偏估计，取 held-out 均值作固化常量；披露边界抖动（样本小）。

**Rationale**：052/054 已用同法处理样本内乐观偏置；直接复用，保持方法一致与可复核。
