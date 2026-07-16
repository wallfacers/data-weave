# Feature Specification: 三厂商共识 gold + 全档重训（Tri-Vendor Consensus Gold）

**Feature Branch**: `068-tri-vendor-gold`

**Created**: 2026-07-14

**Status**: Draft

**Input**: 067 把列级血缘推进到可评测，但 gold/silver 均由 qwen∩deepseek（两中国厂商）派生 = 循环性批评（评审可质疑"模型只是背下两家癖好，非真血缘"）。用户手里有 GPT-5.6（OpenAI，经中转站 tokensfree，第三个独立厂商）。目标：破 gold 循环可信度（主）+ 让模型真正高精度高召回（用户明确要求）。

## 背景与动机

065/067 的诚实脊椎是"泄漏科学 + 诚实去偏"。当前评测的最大结构性弱点：**gold 与 silver 都从同一 teacher 家族（qwen + deepseek，均中国厂商）派生**。评审可以说：模型在 `qwen∩deepseek` 银标上训练，又在 `qwen∩deepseek` gold 上评测，考好可能只是记住了这两家的共有偏好，而非学到真血缘。

引入 **GPT-5.6（OpenAI，跨于 qwen/deepseek 之外的第三个独立厂商）** 能立一个硬论证：**训练只见 qwen∩deepseek，评测要求一个训练中从未见过的厂商也同意** → 模型仍考好 = 学到的是真血缘，不是 teacher 记忆。这是在"无人工标注"约束下能做到的对循环性最强的缓解。

冒烟测已通过：GPT-5.6-sol 经中转站（httpx 裸 POST）返回合法血缘 JSON、含列、并正确忽略被打印的假表——具备做独立 teacher 的判别力。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 三厂商共识 gold + 重评（破循环）(Priority: P1)

作为血缘论文作者，我要用 GPT-5.6 作独立第三厂商，在 067 同一 gold 池上重造**三厂商共识 gold**，并在其上重评现有已发布/交付模型，从而打破"gold 单一 teacher 家族"的循环性批评——即便不重训，这一步本身就交付评测可信度的质变。

**Why this priority**: 这是本特性的主奖（G1）。它是评测侧改动，不依赖 GPU 重训，独立可交付，且是后续所有涨点论证的可信底座。

**Independent Test**: 用 GPT-5.6 标注 067 gold 池 → 造 2-of-3 多数共识 gold 与 3-of-3 一致高置信子集 → 在其上跑现有 model-3b（已发布）与 run-col-3b-mit（067 交付）→ 得到 P/R + GPT 与 067 gold 的一致率。全程无重训即可完成并交付破循环论证。

**Acceptance Scenarios**:

1. **Given** 067 的 gold 池（同源，不重采）与 GPT-5.6 独立标注，**When** 以 min-agree=2 跨 {qwen, deepseek, gpt5} 裁决，**Then** 产出独立命名的三厂商共识 gold（不覆盖 067 的 `real-c.jsonl`），并同时产出 3-of-3 一致子集。
2. **Given** 三厂商共识 gold，**When** 报告 GPT-5.6 与既有 067 gold（qwen∩deepseek）的表级/列级一致率，**Then** 该一致率作为"既有 gold 是否厂商稳健"的描述性证据被记录（高一致=既有 gold 非单厂商臆造）。
3. **Given** 三厂商共识 gold，**When** 重评现有 model-3b 与 run-col-3b-mit，**Then** 得到它们在"更少循环性"尺子上的表级+列级 P/R（可能升可能降，如实记录）。

---

### User Story 2 - 2-of-3 共识 silver + 全档重训（真涨点）(Priority: P2)

作为血缘论文作者，我要用 GPT-5.6 加入 silver 银标共识，用 **2-of-3 多数共识**（比 067 的 2-of-2 交集更宽=召回分母更全，仍要求≥2 独立厂商=精度守住）重训 0.5/1.5/3B 三档新家族 `run-tri-{05,15,3b}`，让模型**同时**在精度和召回上真涨点，而不是只换尺子。

**Why this priority**: 这是用户明确要的"高准确率高召回"。只有重训能动模型的 P/R；2-of-3 多数是唯一能同时抬 P 和 R 的共识设计。3B 先行作硬闸。

**Independent Test**: 用 GPT-5.6-bulk 标 067 同一 silver 池 → 三 teacher 2-of-3 多数共识造 `silver-tri.jsonl` → mit 配方（r32/e3）重训 3B → 在三厂商 gold 上评测 → 表级 P/R 对比 067 mit 基线。

**Acceptance Scenarios**:

1. **Given** 067 silver 池与 GPT-5.6-bulk 标注，**When** 以 2-of-3 多数共识造 silver（表级+列级，列延续 067 一致表上裁列/弃权优先三态），**Then** 产出独立命名 `silver-tri.jsonl`（不覆盖 067 silver），且排除 gold 防泄漏。
2. **Given** 2-of-3 共识 silver，**When** 用 mit 配方重训 3B，**Then** 得到 `run-tri-3b`，其表级 P≥0.78 且 R≥0.75（不退 067 mit 水平）。
3. **Given** `run-tri-3b` 与 067 published 在同一三厂商 gold 上，**When** 做表级 McNemar 配对检验，**Then** 不显著退化（门②）。

---

### User Story 3 - held-out 厂商泛化 + scale 曲线（G2 stretch + 破循环量化）(Priority: P3)

作为血缘论文作者，我要量化"模型在训练中未见的 GPT-5.6 独立确认的边上的表现"，并在三厂商 gold 上给出 0.5/1.5/3B scale 曲线，从而把破循环从定性变成定量证据，同时验证涨点判据。

**Why this priority**: 把 G1 从"造了共识 gold"升级到"模型对未见厂商泛化"的量化证据（论文招牌）；并落实 G2 的 stretch 涨点判据（3-of-3 gold 上 P≥0.80）。

**Independent Test**: 划出"GPT-5.6 独立确认但非训练 teacher"的边子集 → 报 run-tri-3b 在该子集的 P/R；在三厂商 gold 上跑三档得 scale 曲线。

**Acceptance Scenarios**:

1. **Given** GPT-5.6 独立确认的边子集，**When** 评测 run-tri-3b，**Then** 报其在该 held-out 厂商子集的表级/列级 P/R（泛化=真血缘证据）。
2. **Given** 3-of-3 一致高置信 gold，**When** 评测 run-tri-3b 表级精度，**Then** P≥0.80（stretch 涨点，vs 067 的 0.782）且召回不低于 067 mit（表 r 0.746 / 列 r 0.840）。
3. **Given** 三档 run-tri-{05,15,3b} 在三厂商 gold，**When** 比较表级 f1，**Then** 逐规模单调保住（0.5<1.5<3B）。
4. **Given** 三厂商标注的一致/分歧划分（治理路由，缓解已知限制②"模糊案例缺人工复核"），**When** 把"三家一致"定义为自动采纳层、"厂商分歧"定义为人工复核层，**Then** 报告模型在自动层（3-of-3 一致案例）的精度（证明不模糊案例可安全自动化）与分歧案例占比（人工复核工作量），给出有原则的自动/复核分界。

---

### User Story 4 - 诚实边界 + 成本台账（Priority: P4）

作为血缘论文作者，我要如实声明三厂商共识"降低但不消除"循环性（仍是 LLM 共识、无人工金标），并从真实 token 用量核算成本，确保方法学诚实与预算合规。

**Why this priority**: 延续 065/067 诚实文化。可独立交付（一份证据台账），不阻塞前序真跑。

**Independent Test**: 从 teacher 标注记录的 `usage` 字段核算 gold+silver 成本；撰写独立证据台账 `out/PAPER-EVIDENCE-068.md`，含循环性诚实声明与所有真实数字来源。

**Acceptance Scenarios**:

1. **Given** 所有 teacher 标注的真实 usage 记录，**When** 核算总成本，**Then** ≤¥100（含裕度），且拆分 gold/silver 可追溯。
2. **Given** 三厂商共识方法，**When** 撰写证据台账，**Then** 明确声明"2-of-3 比 2-of-2 更跨厂商但仍派生自 LLM；无人工金标=循环性降低不消除"，且不覆盖 065/067 证据文件。

### User Story 5 - 单模型表列两全（表结构 loss 加权）(Priority: P5)

作为血缘抽取器的使用者/论文作者，我要**一个**"表列都能用"的单模型，而非两个各自跛脚的专家（列专家表召回崩、表专家列 F1 崩）。US2 首训暴露 3B/LoRA 表血缘与列血缘竞争固定容量的 frontier；本 story 追问"除升参数/加数据外，能否让二者一起训练"，答案=对答案里**表结构 token** 的 loss 加权，把被列名 token 淹没的表名梯度顶回来，在**保留全部列监督**下恢复表召回。

**Why this priority**: US2/US3 交付后的机制性追问（用户明确"列和表一起训练"）。可独立交付（一个训练旋钮 + 一次重训 + 一份评测），不改既有交付。

**Independent Test**: 实测 tri 训练集 列名:表名 高熵 token 比值 → 原理化定 W → `--table-loss-weight` 单次重训 `run-tri-3b-lw3` → 同 nonempty-gold 口径评测，判 表 R≥0.75 且 列 F1≥0.85 是否同过，并量化 frontier 相对密度稀释的支配关系。

**Acceptance Scenarios**:

1. **Given** `--table-loss-weight 1.0`，**When** 预分词训练行，**Then** input_ids 逐字节复现 SFT `apply_chat_template(tokenize=True)`、权重全 1.0（加权 CE 退化为标准 CE）=基线 bit 级不变。
2. **Given** W>1.0，**When** 装配 per-token 权重，**Then** 答案内 `"columns":[...]` 数组值 token 权重=1.0、表名/骨架 token 权重=W、脚本/prompt token 权重=1.0；且评测侧表/列 counts 逐字节不受影响（门①）。
3. **Given** run-tri-3b-lw3（W=3）与密度稀释 frontier（col70/col50），**When** 同表召回处比列 F1，**Then** lw3 严格高出（外推 frontier）；两次独立逃逸（调 W、r64 容量叠加）如实记录是否达严格两全。

### Edge Cases

- GPT-5.6 某条脚本返回非法/空 JSON（解析失败）→ 该 teacher 对该条弃权，不阻断共识（其余两家仍可 2-of-2 成 gold）。
- 中转站 WAF 拦截（OpenAI SDK 的 `x-stainless-*` 头触发）→ 必须用 httpx 裸 POST 绕过；client 层面对 5xx/超时重试后仍失败则记该条 teacher 弃权。
- 三家对某表列集三方各异（无 2 家交集）→ 列 gold 该表弃权（`null`），延续 067 弃权优先三态。
- 2-of-3 引入的新边正好是某两家共有的过抽取（如都把 `.csv` 当表）→ 靠 3-of-3 子集与语义 grounding 过滤，作为已知残留在诚实边界记录。
- 列级通配 `*` / 空集 `[]` → 视同弃权，延续 067 `canon_col` 语义。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持 GPT-5.6 作为独立第三厂商 teacher，经中转站以 httpx 裸 POST（不经会触发 WAF 的 OpenAI SDK 遥测头）返回血缘 JSON，并抓取真实 token usage。
- **FR-002**: 系统 MUST 用 GPT-5.6 标注 **067 同一 gold 池**（不重新采集），保证与 067 gold 逐条可比。
- **FR-003**: 系统 MUST 以 min-agree=2 跨 {qwen-max, deepseek-v4-pro, gpt-5.6} 造 **2-of-3 多数共识 gold**，并同时产出 **3-of-3 一致高置信子集**；列级延续 067（仅在一致表上裁列、多数/交集、弃权优先三态、`canon_col` 剥限定前缀、通配→弃权）。
- **FR-004**: 系统 MUST 报告 GPT-5.6 与既有 067 gold（qwen∩deepseek）的表级与列级一致率，作为既有 gold 厂商稳健性的描述性证据。
- **FR-005**: 系统 MUST 用 GPT-5.6-bulk 便宜档标注 067 同一 silver 池，以 **2-of-3 多数共识** 造 `silver-tri.jsonl`（表级+列级），并排除 gold 池防泄漏。
- **FR-006**: 系统 MUST 用 mit 配方（LoRA r32/alpha64/epochs3，067 已验证）在 2-of-3 共识 silver 上重训 0.5/1.5/3B 三档新家族 `run-tri-{05,15,3b}`，本机 GPU、无新硬件。
- **FR-007**: 系统 MUST 在三厂商 gold 上重评：既有 model-3b（已发布）、run-col-3b-mit（067 交付）、run-tri-{05,15,3b}，产出表级+列级 P/R 与 bootstrap CI。
- **FR-008**: 系统 MUST 提供 run-tri-3b vs 067 published 在同一 gold 的表级 McNemar 配对检验（门②不显著退化）。
- **FR-009**: 系统 MUST 划出"GPT-5.6 独立确认但非训练 teacher"的边子集，并报 run-tri-3b 在该子集的 P/R（held-out 厂商泛化）。
- **FR-010**: 系统 MUST 保证列级打分与表级计数物理隔离（门①正交，067 已钉死），并以回归单测证明表级 8 key 逐字节不变。
- **FR-011**: 系统 MUST 用独立命名产出所有新工件（`real-c-tri.jsonl` / `silver-tri.jsonl` / `run-tri-*` / `out/PAPER-EVIDENCE-068.md` / `significance-tri-*.md`），绝不覆盖 065/067 的 gold/权重/证据/报告。
- **FR-012**: 系统 MUST 从真实 usage 记录核算 gold+silver 成本并追溯拆分。
- **FR-013**: 系统 MUST 在证据台账中如实声明循环性"降低不消除"（仍 LLM 共识、无人工金标）。
- **FR-014**: 所有新增/改动代码 MUST 有单测覆盖（GPT httpx 后端 mock、三 teacher 共识裁决、门①回归），且全 pytest 绿零回归。
- **FR-015**（缓解已知限制②·治理路由）: 系统 MUST 用三厂商一致/分歧划分给出治理复核路由——"三家一致（3-of-3）"= 自动采纳层、"厂商分歧"= 人工复核层；报告模型在自动层的精度（自动化安全性证据）与分歧案例占比（人工复核工作量），并如实声明这是"缩小模糊区+给出复核判据"而非"消灭人工复核"。
- **FR-016**（收尾）: 本特性交付后 MUST 更新 HF 上 `weft-lineage-extractor-*` 相关模型卡说明——反映三厂商共识评测可信度、治理路由（限制②缓解）、以及限制①（动态名/注释/临时视图）仍为刻意排除的诚实边界。
- **FR-017**（US5·表结构 loss 加权）: 系统 MUST 提供表结构 loss 加权训练路径 `--table-loss-weight`：预分词一行复现 SFT 整序列 LM 分词（`apply_chat_template(tokenize=False)` 渲染 + `add_special_tokens=False` 再分词）+ per-token 权重（答案内 `"columns":[...]` 数组值 token=1.0、其余答案 token=W、脚本/prompt=1.0）+ subclass `WeightedSFTTrainer` 加权交叉熵。默认 W=1.0 MUST bit 级复现基线；MUST NOT 引入 completion masking（保 067/068 整序列 LM 配方=单变量隔离）。
- **FR-018**（W 原理化）: W 取值 MUST 由实测 tri 训练集"列名值:表名值"高熵 token 比值推导（实测 4.39:1），对齐 col50 已证 1.99:1 平衡 + loss 加权比删列更软的折扣 → W=3.0。
- **FR-019**（评测）: 系统 MUST 在同一 nonempty-gold 口径评测 `run-tri-3b-lw3`（及容量叠加 `run-tri-3b-lw3-r64`），报告表/列 P/R/F1、frontier 相对密度稀释的支配关系（同表召回处列 F1 增量）、表精度 McNemar parity。
- **FR-020**（测试）: 所有新增代码 MUST 有单测（列区间识别 / 权重装配 / W=1.0 可复现门 / 加权 CE 数学 / collator pad / 门①训练侧零 eval 耦合），全 pytest 绿零回归。

### Key Entities

- **三厂商共识 gold**：067 gold 池上由 {qwen, deepseek, gpt5} 以 min-agree=2 裁决的评测尺子；含 2-of-3 主尺与 3-of-3 高置信子集；表级+列级；独立命名不覆盖 067。
- **2-of-3 共识 silver**：067 silver 池上三厂商 2-of-3 多数共识训练标签；比 2-of-2 交集多边（召回）仍≥2 厂商背书（精度）。
- **GPT-5.6 teacher（m_gpt / m_gpt_bulk）**：OpenAI 系独立第三厂商；sol 档作 gold 裁判（质量），luna 档作 silver bulk（成本）；httpx 裸 POST。
- **run-tri-{05,15,3b}**：三厂商共识 silver 重训的新模型家族；既有已发布/067 模型只读。
- **held-out 厂商边子集**：GPT-5.6 独立确认但未参与训练 teacher 的边；用于量化泛化。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**（G1 破循环·gold）：产出三厂商共识 gold（2-of-3 主尺 + 3-of-3 子集），并报告 GPT-5.6 与 067 gold 的表级+列级一致率。
- **SC-002**（G1 破循环·重评）：既有 model-3b 与 run-col-3b-mit 在三厂商 gold 上的表级+列级 P/R 被测得并如实记录（含方向）。
- **SC-003**（G2 涨点·不退）：run-tri-3b 在三厂商 gold 上表级 **P≥0.78 且 R≥0.75**，列级 **P≥0.78 且 R≥0.82**（不退 067 mit 水平）。
- **SC-004**（G2 stretch·真涨点）：run-tri-3b 在 3-of-3 高置信 gold 上表级 **P≥0.80**（vs 067 的 0.782）**且**召回不低于 067 mit（表 r 0.746 / 列 r 0.840）。
- **SC-005**（门②不退化）：run-tri-3b vs 067 published 在同一 gold 的表级 McNemar **不显著退化**（p≥0.05 或 precision diff CI 含 0）。
- **SC-006**（门③ held-out 厂商泛化）：run-tri-3b 在"GPT-5.6 独立确认边"子集的表级/列级 P/R 被测得并报告为破循环量化证据。
- **SC-007**（scale 单调）：run-tri-{05,15,3b} 表级 f1 逐规模单调保住（0.5<1.5<3B）。
- **SC-008**（门①正交）：列级打分绝不扰动表级计数——回归单测证明表级 8 key 逐字节不变。
- **SC-009**（成本）：GPT 第三 teacher 标注成本从真实 usage + 中转站后台实际扣费逐项拆分、可追溯。**实际扣费**（后台账单，权威）：silver(luna) $1.4047 + gold(sol) $1.0140 + 冒烟(gpt-5.6) $0.0004 = **$2.42 ≈ ¥17**；标准价口径 $12.73 ≈ ¥91，两口径均 **<¥100 达标**。token 计数吻合（luna 7.7M / sol 957.8K）。⚠️ 记账勘误：本任务实现中期曾用前沿 GPT-4 级单价误估为 ¥116"超支"，经后台账单证伪——真实单价低约 7×，SC-009 实为轻松达标，无需放宽阈值。
- **SC-010**（诚实）：证据台账明确声明三厂商"降低不消除"循环性，且不覆盖 065/067 证据。
- **SC-011**（缓解限制②·治理路由）：产出治理路由报告——自动采纳层（3-of-3 一致）的模型精度被量化（作自动化安全性证据），分歧案例占比被报告（作人工复核工作量），并接 063 分层信封语义（分歧=复核层客观定义）。
- **SC-012**（收尾）：HF 模型卡说明按 FR-016 更新完成（三厂商可信度 + 限制②缓解 + 限制①仍为诚实边界）。
- **SC-013**（US5·加权奏效）：`run-tri-3b-lw3`（W=3）表 R 相对列专家 `run-tri-3b` 回升 **0.645→0.734（+0.089）**，纯靠重加权目标函数（无删数据/无加容量），表精度不退（0.834，McNemar vs 全参照 p≥0.79 不显著、方向为正）。**证实 frontier 是梯度分配失衡非容量墙**（r64 单独只补 4pt）。
- **SC-014**（US5·frontier 外推）：同表召回处 lw3 列 F1 高于密度稀释 frontier **≥+0.13**（实测 +0.146@r32 / +0.138@r64）→ loss 加权**严格支配密度稀释**作为表列权衡机制（删列丢掉的列技能被加权保住）。
- **SC-015**（US5·严格两全负结果如实）：单个 3B/LoRA 权重 表 R≥0.75 **且** 列 F1≥0.85 严格两门同过 **不可达**——经**两次独立逃逸**双重证实：① 调 W 沿 frontier 只拿一门换一门；② r64 容量叠加与加权同向（沿同一 frontier 滑向表：0.734/0.825→0.742/0.797），不外推 frontier。改进后 frontier 从目标角内侧擦过；诚实记 7B（本机 12G defer）为严格两全出路。
- **SC-016**（US5·交付）：交付 `run-tri-3b-lw3`（r32,W3）作**最佳均衡单模型**——表 F1 0.781 + 列 F1 0.825 双强，点估计仅差目标角 ~0.02 且在 bootstrap CI 内，碾压所有其它单模型（列专家 0.723/0.931、表专家 0.800/0.578）。回答用户"列和表能否一起训练"=**能，loss 加权是当前最佳单模型两全手段**，严格阈值受 3B 容量所限。

## Assumptions

- GPT-5.6 经中转站（tokensfree，OpenAI 兼容）稳定可用；冒烟测已证 httpx 裸 POST 通、质量高含列；WAF 仅拦 OpenAI SDK 遥测头。
- gold/silver 恒为 teacher 共识银标——**无人工标注**（约束），故列级/表级循环性只能降低不能消除，如实声明。
- 复用 067 已落地的管线（`teacher_label`/`build_gold_b`/`build_silver`/`metrics.py` 列打分/`sft_qlora.py --lora-r/alpha`/significance），最小新增；067 代码已并入 main，本 worktree 从 main 分。
- 本机 5070 GPU 足以按 mit 配方（r32/e3）重训 0.5/1.5/3B（067 已验证 3B ~2hr/12G 未 OOM），无新硬件。
- 059 语义 grounding 已部署于 serving，可作 2-of-3 引入的相关性过抽取的正交过滤（不在本特性范围内改动 serving）。
- 067 的 gold/silver 池、真实语料、权重、preds 走 HF/gitignored；本特性新工件同样 gitignored 走 HF。

## Out of Scope

- 平台 Calcite 消费面（血缘落库/serving 消费）——本特性只做抽取器训练与评测侧。
- 人工标注/人工金标——约束禁止；三厂商共识是无人工下的最强缓解。
- 7B 及以上规模；neo4j 落库；serving 侧 grounding 逻辑改动。
- 覆盖或重训既有已发布 `weft-lineage-extractor-*` / 067 `run-col-3b-mit`——只读对照。
- **已知限制①（动态构建的表名 f-string/shell·notebook 变量/format()、注释/日志 SQL、临时视图）** —— 本特性**明确不解决**。这是抽取器的**刻意设计边界非缺陷**：动态名的字面名不在脚本文本内，静态抽取无从解析（需数据流/运行时追踪=另一种能力），强行纳入会吐模板碎片砸掉精度招牌；注释/日志 SQL 未执行=非真血缘；临时视图=中间管道。三厂商共识与此正交、帮不上。

## Future Work

- **动态表名解析**（限制①）：独立特性，需数据流/符号执行解析 `f"{schema}.{tbl}"` 类模板名，与本特性的静态字面抽取正交，另立。
- 完全自动化治理（消灭人工复核）：本特性只缩小模糊区（限制②缓解）；模糊案例（厂商分歧）本质需人工，无法在"无人工标注"约束下完全消除。
- **严格表列两全**（US5 未达严格阈值）：7B QLoRA（本机 12G 装不下，见 [[weft-7b-qlora-gpu-feasibility]]）是把 frontier 整体外推、真正同过 表 R≥0.75 且 列 F1≥0.85 的出路；本特性已用 loss 加权把 3B frontier 推到目标角内侧擦过（最佳单模型 lw3），并证实 3B 容量为硬约束。找到卡后可复用 `--table-loss-weight` 直接在 7B 上验证两全。
