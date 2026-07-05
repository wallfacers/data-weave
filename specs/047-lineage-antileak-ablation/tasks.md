---
description: "Task list for 047 抗泄漏消融（041-R 方案 B）"
---

# Tasks: 抗泄漏消融（041-R 方案 B）

**Input**: `specs/047-lineage-antileak-ablation/`（plan.md · spec.md · research.md · data-model.md · contracts/ · quickstart.md）

**Tests**: 含测试任务（CLAUDE.md 硬规则"新特性必须有测试"；spec SC-005/SC-003 要求可复现 + 消融纯净可核验）。**无 GPU 单测先行、先失败后实现**；GPU 训练/真实评测为实验运行（非单测）。

**Organization**: 按 user story 分相。所有路径相对 `ml/lineage-extractor/`（除论文/spec）。权重落 worktree 外 `weft-lineage-weights/`。

## Format: `[ID] [P?] [Story] Description`
- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1=B1 真实名增广 · US2=B2 弃权训练 · US3=消融综合
- **[GPU]**: 需 12G 单卡的实验运行（非单测；数小时级）

---

## Phase 1: Setup

- [ ] T001 确认 `ml/lineage-extractor/` 环境（transformers/peft/trl/torch/datasets 已装；`WEFT_WEIGHTS_DIR` 经脚本 `parents[K]` 解析到 sibling `weft-lineage-weights/`），并核对基线资产就位：`data/out/heldout.jsonl`、`realeval/gold/real.jsonl`、`weft-lineage-weights/run{1,3}`。
- [ ] T002 [P] 在 `ml/lineage-extractor/.gitignore` 补 `data/out-b1/`、`data/out-b2/`（含 `pool.json`）；确认 `weft-lineage-weights/` 及 `run-b1/`、`run-b2/` 已被忽略、不入库（沿用 041 既有约定）。

---

## Phase 2: Foundational（阻塞 US1+US2 的共享前提）

**⚠️ 完成前任何 story 不能开工。** 这里建两支共用的数据构造脚手架与诚实泄漏度量。

- [ ] T003 泛化 `realeval/leak_analysis.py`：新增 `--train-pool <pool.json>`，按 contract `leak-analysis-pool.md` 输出 `verbatim_own_*`（自有池）+ 保留 `verbatim_synth_*`（原合成池对照）两列；**不传时行为逐字不变**（回放 `synth_table_names(SEED,400)`）。
- [ ] T004 [P] 无 GPU 单测 `tests/test_leak_pool.py`：假 predictor + 假 `pool.json` 断言 `verbatim_own_*` 计数正确、缺省路径与旧输出 byte 一致、自有池 ⊇ 变体标签表名。**先写、先失败。**
- [ ] T005 建 `data/antileak.py` 共享脚手架：argparse（`--variant/--out/--train-size/--heldout-size` + B1/B2 旋钮）、复用 `synth_pipeline.{build_pools,render,synth_table_names,harvest_hf_names}` 与 `templates`，实现 `pool.json` 写出（`train_table_names` + `synth_generated_subset`，键序确定）。**此文件 US1/US2 共享——先落骨架，变体分支分别在各 story 加。**

**Checkpoint**: 泄漏度量可按自有池评测 + 变体构造脚手架就绪。

---

## Phase 3: User Story 1 - B1 真实表名增广 (Priority: P1) 🎯 MVP

**Goal**: 造真实名主导的训练变体、重训 1.5B、用同一真实金标测，量化逐字泄漏 22.4%→? 与合成 held-out trade-off。

**Independent Test**: 仅跑 B1 一支即得可并入论文泄漏表的一行（自有池泄漏率 + 原合成池对照 + 真实 prec/方向 + 合成 held-out），不依赖 US2。

### Tests（先写、先失败）
- [ ] T006 [P] [US1] 无 GPU 单测 `tests/test_antileak_data.py`（B1 用例）：同 SEED byte-identical；训练表名中合成生成名占比 ≤15%；行 schema 与基线 `train.jsonl` 逐键一致；train/heldout 名池不相交；`pool.json.synth_generated_subset ⊆ synth_table_names`。

### Implementation
- [ ] T007 [US1] 在 `data/antileak.py` 实现 `--variant b1` 分支：表名池 = `synth_table_names(SEED, b1_synth_keep=40)` ∪ `harvest_hf_names(大 limit≥20000, 目标真实名≥2000)`，真实名主导；HF 降级时打 warn 且 `pool.json.hf_degraded=true`（contract `antileak-data.md`）。
- [ ] T008 [US1] 生成 B1 变体数据：`PYTHONPATH=. python data/antileak.py --variant b1 --out data/out-b1`，产出 `data/out-b1/{train,heldout}.jsonl + pool.json`。
- [ ] T009 [GPU] [US1] 训 B1：`train/sft_qlora.py --data data/out-b1/train.jsonl --out $WEFT_WEIGHTS_DIR/run-b1 --base-model Qwen/Qwen2.5-Coder-1.5B-Instruct`（其余配方逐字同基线）。
- [ ] T010 [GPU] [US1] 评 B1 三产物：`eval_real.py --model run-b1/merged`→`out/eval-real-b1.*`；`eval/evaluate.py --model run-b1/merged --data data/out/heldout.jsonl`→`out/eval-report-b1.*`；`leak_analysis.py --model run-b1/merged --train-pool data/out-b1/pool.json`→`out/leak-report-b1.*`。
- [ ] T011 [US1] 记录 B1 判读：逐字泄漏(自有池) 相对 22.4% 的方向与幅度 + "是否改背真实训练名" + 合成 held-out 是否退化（trade-off 显式，禁单边）。

**Checkpoint**: B1 一支端到端完成、可独立并入论文。

---

## Phase 4: User Story 2 - B2 开放域弃权训练 (Priority: P2)

**Goal**: 掺 20% 空标签弃权负样本、重训、同口径评测，量化 precision↑/recall↓ 权衡与幻觉/泄漏变化。

**Independent Test**: 仅跑 B2 即得可并列的一行（prec/幻觉/recall/方向 + 泄漏），不依赖 US1（仅与 US1 共享 `antileak.py` 文件，故 T012 在 T005 之后顺序改同一文件）。

### Tests（先写、先失败）
- [ ] T012 [P] [US2] 无 GPU 单测 `tests/test_antileak_data.py`（B2 用例）：同 SEED byte-identical；负样本占比 20%±1% 且 labels 全空；负样本 content 在约定 A 下确应为空（自洽）；正样本部分与基线逐行一致（消融纯净）。

### Implementation
- [ ] T013 [US2] 在 `data/antileak.py` 加 `--variant b2` 分支 + 弃权模板家族（三型：纯计算/日志、注释或打印内 SQL、动态拼接名），`labels={"reads":[],"writes":[]}`；正样本 80% 复用基线 `render`。
- [ ] T014 [US2] 生成 B2 变体数据：`python data/antileak.py --variant b2 --out data/out-b2`。
- [ ] T015 [GPU] [US2] 训 B2：`sft_qlora.py --data data/out-b2/train.jsonl --out $WEFT_WEIGHTS_DIR/run-b2 --base-model Qwen/Qwen2.5-Coder-1.5B-Instruct`。
- [ ] T016 [GPU] [US2] 评 B2 三产物（同 T010 口径）→ `out/{eval-real-b2,eval-report-b2,leak-report-b2}.*`。
- [ ] T017 [US2] 记录 B2 判读：precision↑ 与 recall↓ 权衡量级；识别"恒弃权"退化（recall→~0）并判该补救不可用（spec Edge Case / SC-006）。

**Checkpoint**: B1、B2 两支各自独立可测。

---

## Phase 5: User Story 3 - 消融综合裁决 (Priority: P3)

**Goal**: 汇 基线/B1/B2/3B 同口径消融表 + 诚实结论，并入论文。

**Independent Test**: 存在 `out/ablation-antileak.*`（同口径表 + trade-off/未解病标注）并 append 进论文 §B。依赖 US1/US2 至少之一。

### Tests（先写、先失败）
- [ ] T018 [P] [US3] 无 GPU 单测 `tests/test_ablation_table.py`：缺"合成 held-out 列"时报错（防单边）；recall 相对基线跌 >20% 或合成 prec 跌 >5pt 时自动插告警行；基线/3B 冻结常量与论文 §5 一致。

### Implementation
- [ ] T019 [US3] 实现 `realeval/ablation_table.py`（contract `ablation-table.md`）：读 B1/B2 三类 json（可选缺一支）、内嵌基线/3B 冻结常量（注来源 §5）、生成 `out/ablation-antileak.md/.json` + 结论骨架（含 trade-off/未解病占位与自动告警）。
- [ ] T020 [US3] 运行汇编：`realeval/ablation_table.py --b1-* ... --b2-* ... --report out/ablation-antileak.md`。
- [ ] T021 [US3] 人工审读后 append 消融表 + 结论到 `specs/041-script-lineage-extraction/paper-negative-result-findings.md` §B（更新"加强实验状态"B 行为已完成）；若任一支翻转主结论，如实上修 thesis（spec FR-010/SC-004，不自动改论文、审读后写）。

**Checkpoint**: 全部 story 落地，论文 §B 补齐。

---

## Phase 6: Polish & Cross-Cutting

- [ ] T022 [P] 更新 memory `weft-041-script-lineage.md`：B 消融完成 + worktree 047 + weft-lineage-weights sibling 约定。
- [ ] T023 跑 quickstart.md 完成判据：无 GPU 单测全绿 + 至少一支端到端产物齐全 + 消融表含真实与合成两侧且恶化被标注。

---

## Dependencies & Execution Order

- **Phase 1 Setup** → 无依赖，先行。
- **Phase 2 Foundational（T003–T005）** → 阻塞 US1/US2。T003→T004（测其泛化）；T005 独立可 [P] 于 T003。
- **US1（T006–T011）** → 依赖 Phase 2。T006(测)先于 T007(实现)；T007→T008→T009→T010→T011 线性（数据→训→评→判）。
- **US2（T012–T017）** → 依赖 Phase 2；与 US1 逻辑独立，但 T013 与 T007 同改 `antileak.py`，故 T013 排在 T007 之后（同文件顺序）。T012(测)先于 T013。
- **US3（T018–T021）** → 依赖 US1 和/或 US2 的评测产物。T018(测)先于 T019。
- **Polish（T022–T023）** → 依赖所需 story 完成。

### 并行机会
- T002 ∥ T003；T004 ∥ T005；单测 T006/T012/T018 各自 [P]。
- **GPU 串行**：T009/T015 单卡串行（12G 一次一训）；两支训练是本特性主要 wall-clock。

---

## Implementation Strategy

### MVP（仅 US1 = B1）
Phase 1 → Phase 2 → Phase 3，**STOP & VALIDATE**：B1 一行进论文即已回答审稿人"换个增广不就好了"。

### 增量交付
Setup+Foundational → B1（MVP）→ B2 → 综合表。每支独立加值、不破坏前者。若时间/算力受限，**做完 B1 即可交付一个决定性结论**；B2、JVM 复评为增厚。

### 诚实性红线（贯穿全程）
- 每支必同时报真实 + 合成 held-out（禁单边）。
- 泄漏按自有池测（防 B1 假性归零）。
- 恒弃权/合成崩塌/thesis 翻转 → 显式披露，不藏。
- 消融纯净：每支与基线仅差一维，其余超参逐字一致。

---

## Notes
- 成功 = 决定性且诚实的测量，**非补救必须奏效**（spec 背景 + SC 引言）。
- [GPU] 任务是"数小时真实验"本体；无 GPU 单测保证数据/度量层可复现且消融纯净可核验。
- 每任务或逻辑组后提交；GPU 产物落 `out/`、权重落 sibling，均不与另一 agent 冲突。
