# Feature Specification: 自训小模型血缘抽取达到生产可用（真实语料 teacher 蒸馏）

**Feature Branch**: `052-lineage-distillation`

**Created**: 2026-07-07

**Status**: Draft

**Input**: User description: "自训小模型血缘抽取达到生产可用：用大模型 teacher 在真实开源 ETL 语料上蒸馏，消除合成训练的记忆泄漏与 domain shift，服务态纯自托管小模型追平大模型基线"

## 背景与问题陈述

041-R/047/050 已用证伪式真跑证明：**纯合成数据训练**的自托管小模型（0.5B/1.5B/3B）在真实 ETL 脚本上不可用——合成 held-out 近满分（0.99）但真实集崩塌（precision 0.27 / 方向 0.50 ≈ 抛硬币），根因是 **domain shift** 与 **记忆泄漏**（在陌生真实脚本上背诵合成训练表名，逐字泄漏 py/sh 22.4% / JVM 40.8%）。所有补救（真实名增广=假修、弃权训练=微效、加硬弃权=回退、架构 dir_fix=唯一小幅奏效）都在**合成训练框架内**打转，未治本。

所有失败实验有一个共同的、可被打破的自我约束：**训练数据全部为合成**。本特性放开该约束，改用大模型 teacher（m1-qwen-max、m2-anthropic）在**真实开源 ETL 语料**上打的高精银标做训练分布，从根上消除 domain shift 与泄漏，让服务态**纯自托管**小模型追平大模型 m2 基线。大模型仅在**训练时**当 teacher（离线、一次性、语料为公开 GitHub 脚本），**服务态零外部依赖**。

## Clarifications

### Session 2026-07-07

- Q: 「生产可用」达标判据有多严？ → A: 严格全过——SC-001~004 四项在测试集 B 同时满足才判可用；分阶段人工兜底仅为未达标降级选项，不作判据。
- Q: held-out 测试集 B 的规模与证伪终裁者？ → A: 新采扩到非空金标 ≥100，我逐条证伪初裁 + 维护者抽查终审。
- Q: 银标一致性口径？ → A: 交集为主 + 分歧经字面门救回（m1∩m2 一致直收；分歧表名若脚本内字面出现且方向可 AST 定则救回）。
- Q: 达标交付的模型规模？ → A: 3B 达标为主（验收门只卡 3B）；1.5B 同配方重训作消融对照，不设硬门。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 真实语料蒸馏出达标的自托管模型 (Priority: P1)

作为血缘平台的维护者，我要一个在真实 ETL 脚本上表现追平大模型 m2 的自托管小模型，且**服务态不依赖任何外部大模型 API**，以便在数据私有/离线/成本约束下把脚本血缘抽取做到可信。

**Why this priority**: 这是整个特性的核心与 MVP——没有达标的模型，后面的服务集成与发布都无意义。它独立交付"一个被证明可用的模型权重 + 达标报告"。

**Independent Test**: 采集真实语料 → 双 teacher 打标 → 构建高精银标 → 重训小模型 → 在**从未参与训练的 held-out 真实测试集**上跑既有四方评测 harness（`eval_real.py` / `jvm_slice_eval.py`），核对是否全部越过验收门（见 Success Criteria SC-001~SC-005）。产出=权重 + 四方对比 + 泄漏/污染审计报告。

**Acceptance Scenarios**:

1. **Given** 采集到的真实 ETL 语料已剔除全部评测金标脚本（content-hash 零重叠），**When** 用 m1∩m2 一致 + 字面子串硬门 + AST 方向优先构建银标，**Then** 银标中不含任何合成表名，且 teacher 幻觉名被字面门滤除。
2. **Given** 从 base（Qwen2.5-Coder-3B-Instruct）在真实银标上重训得到的模型，**When** 在 held-out 真实测试集 B 上评测，**Then** recall(非空)≥0.80、方向(非空)≥0.73、幻觉率(全)≤0.15、precision(全)≥0.50 同时成立。
3. **Given** 训练后的模型，**When** 用 `leak_analysis.py --train-pool <真实银标名池>` 在测试集上测逐字背诵率，**Then** 逐字泄漏 ≈ 0（构造性保证的实测确认）。
4. **Given** 合成 held-out 集，**When** 评测，**Then** 合成分数**不作为**可用性判据（延续 041-R 诚实底线：只认真实 held-out）。

---

### User Story 2 - 蒸馏模型接进后端三通道并全自动服务 (Priority: P2)

作为平台运行时，我要蒸馏模型带 AST 方向修正接进后端 `ScriptLineageService` 的 MODEL 通道，在既有路由（SQL > RULE > MODEL）与 2s 预算内**全自动**产出方向可靠的血缘，无人工闭环。

**Why this priority**: 模型达标后必须真正被产品消费才产生价值。它独立交付"后端提交真实脚本 → 得到方向正确的血缘边"这一端到端能力。

**Independent Test**: 把 sidecar 的 `MODEL_DIR` 指向蒸馏权重、`dir_fix` 下沉进 sidecar，通过 `ScriptLineageService` 提交一批真实脚本，核对返回边的方向正确率与延迟符合预算。

**Acceptance Scenarios**:

1. **Given** sidecar 加载蒸馏权重并内置 sqlglot 方向修正，**When** 提交一条含嵌入 SQL 的自由脚本，**Then** 返回的血缘边表集由模型定、方向由 AST 校正（dir_fix 策略，非 override）。
2. **Given** 一条无任何字面表引用的脚本（弃权样本形态），**When** 提交，**Then** 模型诚实输出空、不凭空抽表。
3. **Given** 畸形/超大脚本片段（远处分号致大块、Jinja/`$CONDITIONS` 模板 SQL），**When** sidecar 处理，**Then** 不发生 sqlglot 灾难性回溯/爆内存（片段窗封顶、跳模板标记、逐片段限时兜底），在预算内返回。
4. **Given** 确定性通道（SQL/规则）能覆盖的脚本，**When** 路由，**Then** 由确定性通道处理，模型只接自由残差（训练分布=服务分布）。

---

### User Story 3 - 诚实验收与加性发布 (Priority: P3)

作为对结论负责的作者，我要一套诚实的验收与发布流程：达标才宣布"生产可用"并改写 HF 卡 / swap 后端权重；没达标就诚实迭代，且全程加性零破坏、现有已发布产物不动到证明通过为止。

**Why this priority**: 延续 041-R 诚实底线，防止把未达标的模型吹成可用；独立交付"可信的达标判定 + 无假象的对外叙事"。

**Independent Test**: 生成四方对比、逐字泄漏审计、train∩test 污染审计（content-hash 证明为空），据此做达标/未达标判定；仅在测试集 B 全过时才触发权重 swap 与卡片改写。

**Acceptance Scenarios**:

1. **Given** 评测报告，**When** 呈现，**Then** 同时报"系统数（模型+dir_fix）"与"模型独跑数"，不隐藏 AST 拐杖；并披露 teacher 为 Qwen 系（追平 m2=追平同源 teacher）。
2. **Given** 测试集 B 未全过门，**When** 判定，**Then** 判"未达标"，触发预定义升级路径（难例挖掘第二轮 → 7B QLoRA → dir_fix 策略调参），**不**改写 HF 卡、**不** swap 后端权重。
3. **Given** 测试集 B 全过门，**When** 判定，**Then** 判"生产可用"，改写 HF 卡为生产卡+真实数字、swap 后端 `MODEL_DIR`。
4. **Given** 整个过程，**When** 任何时点，**Then** 现有已发布 HF 模型与后端 sidecar 保持原样，直到 swap；新权重落 sibling `weft-lineage-weights/`。

---

### Edge Cases

- **teacher 分歧高的脚本**：m1∩m2 不一致 → 不进银标（一致性过滤天然处理，宁缺毋滥）。
- **teacher 打标中断/限速**：打标须可续跑 + 本地缓存，重跑不重复烧配额、不丢已标结果。
- **真实语料稀疏**：非空脚本占比低（现有约 1/3~1/6）→ 采集候选量需数倍于目标银标量；空脚本本身是有价值的弃权样本，按 ~20% 配比保留。
- **3B 显存逼近上限（11.9/12G）**：OOM 风险 → 回退 batch1 或 1.5B；7B 需 QLoRA 4bit。
- **学生略逊 teacher**：蒸馏常态；若落到 recall~0.75（低于门但远高于现状 0.62）→ 判**未达标**，触发预定义升级路径（难例挖掘第二轮 → 7B QLoRA → dir_fix 调参）。严格全过前不改写 HF 卡、不 swap 后端；"分阶段人工兜底"仅为降级备选，不作达标判据。
- **服务延迟超 2s 预算**：3B 单脚本推理可能 1–3s → 调优（MODEL 通道单独放宽预算 / 量化提速 / 异步）。

## Requirements *(mandatory)*

### Functional Requirements

**数据管线（US1）**

- **FR-001**: 系统 MUST 采集真实开源 ETL 脚本语料（多引擎：py/sh 命令式、嵌入 SQL、JVM Scala/Java、config-driven），去重并粗筛为"作业"脚本。
- **FR-002**: 系统 MUST 按 content-hash 从训练语料中剔除全部现有评测金标脚本（real.jsonl 139 + real-jvm.jsonl 141），保证 train∩test=∅。
- **FR-003**: 系统 MUST 用 m1(qwen-max) 与 m2(anthropic) 双 teacher 对每条候选打标，并对打标结果本地缓存、支持中断续跑。
- **FR-004**: 系统 MUST 构建银标，口径=**交集为主 + 分歧经字面门救回**：m1∩m2 一致的表直接收；两 teacher 分歧的表名，仅当在脚本文本中**字面出现**且方向可由 AST 判定时才救回。所有入选表名 MUST 通过字面子串硬门（滤 teacher 幻觉名）；方向优先取 AST 解析结果，不可解析时取 teacher 方向（方向分歧不可 AST 定则弃该边）。
- **FR-005**: 银标 MUST NOT 含任何合成表名（构造性反泄漏）；MUST 按 ~20% 空样本（弃权）配比混合非空/空脚本。
- **FR-006**: 训练语料 MUST 裁剪为**服务态残差分布**——确定性通道（纯 SQL、config-driven）能完整覆盖的脚本不进模型训练集，模型只训自由命令式/程序化表引用脚本。
- **FR-007**: 系统 MUST 保留独立测试集：测试集 A（现有 139+141 金标）+ 测试集 B（新采、teacher 从未参与训练打标的新鲜集，**非空金标 ≥100**，由本地 agent 逐条证伪初裁 + 维护者抽查终审）。达标判定以测试集 B 为准。

**训练（US1）**

- **FR-008**: 系统 MUST 从干净 base（Qwen2.5-Coder-3B-Instruct 主力；1.5B 对照）起训，MUST NOT 从合成 checkpoint 续训。
- **FR-009**: 训练 MUST 复用既有配方（`train/sft_qlora.py`：固定 SEED、r16/α32、2ep、max2048、4 语言 SYSTEM_PROMPT），仅替换数据为真实银标。
- **FR-010**: 若单轮 3B 未达验收门，系统 MUST 走预定义升级路径（难例挖掘第二轮 → 7B QLoRA 4bit → dir_fix 策略调参），每步重跑同一验收门。

**服务集成（US2）**

- **FR-011**: 服务态 MUST 为纯自托管小模型，MUST NOT 在推理路径调用任何外部大模型 API。
- **FR-012**: sidecar MUST 在模型推理后就地执行 dir_fix 方向修正（表集由模型定、SQL 可识别表的方向由 sqlglot AST 校正），返回方向已修正的边；MUST NOT 用 override（SQL 整条覆盖）策略。
- **FR-013**: sidecar MUST 移植 050 健壮性补丁（片段窗封顶、跳模板标记、逐片段 SIGALRM 限时），对畸形/超大片段不回溯爆内存。
- **FR-014**: 接入 MUST 保持后端 `ScriptLineageService` 既有三通道路由（SQL>RULE>MODEL）与 Java 侧接口不变，仅切换 `MODEL_DIR` 与 sidecar 逻辑；模型只接确定性通道未覆盖的残差。
- **FR-015**: 服务 MUST 全自动、无人工复核闭环（不接 043 监督队列）；无字面表时诚实输出空。

**验收与发布（US3）**

- **FR-016**: 系统 MUST 以测试集 B **同时**通过 SC-001~004 全部验收门为唯一"生产可用"判据（严格全过，任一项不达即未达标）；合成分数不作判据。验收门只卡 **3B 交付主体**；1.5B 作消融对照不设硬门。
- **FR-017**: 评测报告 MUST 同时给出系统数（模型+dir_fix）与模型独跑数，并披露 teacher 同源（Qwen 系）。
- **FR-018**: 系统 MUST 提供逐字泄漏审计（`leak_analysis.py --train-pool`）与 train∩test 污染审计（content-hash）。
- **FR-019**: 仅当判定"生产可用"时，系统 MAY 改写 HF 模型卡为生产卡+真实数字并 swap 后端 `MODEL_DIR`；未达标则保持现有已发布产物不动、诚实迭代。
- **FR-020**: 全过程 MUST 加性零破坏——现有已发布 HF 模型与后端 sidecar 在证明通过前保持原样，新权重落 sibling `weft-lineage-weights/`。

### Key Entities *(include if feature involves data)*

- **真实语料候选（corpus candidate）**：一条采自公开 GitHub 的 ETL 脚本；属性=内容、语言/引擎、content-hash、是否作业。
- **teacher 标注（teacher label）**：某 teacher 对一条候选给出的表集 + 方向；两 teacher 各一份。
- **银标（silver label）**：经一致性 + 字面门 + AST 方向构建的高精训练标签；属性=表集、方向、是否空（弃权）、来源候选 hash。
- **测试金标（gold）**：人工证伪裁决的真实标签；测试集 A（既有 139+141）与测试集 B（新鲜、训练未见）。
- **模型权重（model artifact）**：从 base 蒸馏得到的 LoRA-merged 权重（3B 主力 / 1.5B 对照 / 7B 备选），落 sibling `weft-lineage-weights/`。
- **评测报告（eval report）**：四方对比 + 泄漏审计 + 污染审计 + 达标判定。

## Success Criteria *(mandatory)*

### Measurable Outcomes

验收门锚定大模型 m2 基线（real.jsonl：recall 0.806 / 方向 0.730 / 幻觉 0.134 / precision 0.542），针对 **3B 交付主体**在 **held-out 真实测试集 B** 上全自动评测，SC-001~004 **须同时满足**（严格全过）：

- **SC-001**: 非空子集 recall ≥ 0.80（现状 sft 0.62 → 门 0.80）。
- **SC-002**: 非空子集方向准确率 ≥ 0.73（现状 sft 0.50 ≈ 抛硬币 → 门 0.73）。
- **SC-003**: 全集幻觉率 ≤ 0.15（现状 sft py/sh 0.153、JVM 0.347 → 门 0.15）。
- **SC-004**: 全集 precision ≥ 0.50（现状 sft 0.27 → 门 0.50）。
- **SC-005**: 逐字泄漏率 ≈ 0（现状 py/sh 22.4% / JVM 40.8% → 门 ≈0），以模型自有真实银标名池实测。
- **SC-006**: train∩test（测试集 A 与 B）content-hash 重叠 = 0。
- **SC-007**: 服务态推理路径对外部大模型 API 的调用次数 = 0（纯自托管）。
- **SC-008**: 服务态单脚本端到端在既定预算内返回（默认 2s，MODEL 通道预算可单独调优后固定）。
- **SC-009**: 测试集 B 非空金标样本数 ≥ 100（缩窄置信区间，回应 small-n 质疑），经本地 agent 证伪初裁 + 维护者抽查终审。

## Assumptions

- teacher 端点（DASHSCOPE=m1-qwen-max、ALI_ANTHROPIC=m2）在训练打标期可用且配额够打上千条脚本；凭据存 gitignore 的 `.env`，绝不入 git。
- GPU（RTX 5070 12G）空闲可跑训练（1.5B ~48min / 3B ~61min bf16 LoRA；7B 需 QLoRA 4bit）。
- GITHUB_TOKEN 可用于 `collect.py` 扩采真实语料。
- 训练语料为**公开** GitHub 脚本，不涉及客户私有数据隐私；服务态在客户私有脚本上只跑自托管小模型。
- teacher 为 Qwen 系，"追平 m2"=追平同源 teacher，作为已知天花板如实披露；学生追平不保证，届时按升级路径与维护者拍板。
- 本特性在 worktree `dw-052-lineage-distillation`（分支同名，从 main d0aeae0 起）隔离开发；gitignore 数据资产（`.env` / `realeval/pool` / `realeval/gold`）从 `dw-041` worktree 复制，权重复用 sibling `weft-lineage-weights/`。
- 复用既有管线：`collect.py`（采集）、`adjudicate_aid.py`（字面门/裁决）、`train/sft_qlora.py`（训练）、`eval_real.py`/`jvm_slice_eval.py`/`leak_analysis.py`（评测），新增打标/银标构建/dir_fix 产品化。
- 后端 `ScriptLineageService` 三通道路由与 Java 接口不变，改动集中在 Python sidecar 与配置。
