# Tasks: 列级血缘（联合表+列重训 · 保表级曲线）

**Input**: Design documents from `specs/067-column-lineage/`
**Prerequisites**: plan.md · spec.md · research.md · data-model.md · contracts/ · quickstart.md
**Tests**: 已在 spec 明确要求（门① 正交单测 + 列裁决/列打分单测；CLAUDE.md「无测试=未完成」）→ 含测试任务。

**路径约定**：全部相对 `ml/lineage-extractor/`。数据/权重/preds 全 gitignored 走 HF。

**执行策略（MVP 优先）**：Phase 3（US1）即 MVP——**无 GPU 训练**即交付「诚实评列 + 门① 表级零扰动证明」（冻结基线为推理档，可 CPU 回退）。US2 3B 先行为门② 闸，过门才 US3 扩训。US4 可选。

---

## Phase 1: Setup（共享基础）

- [X] T001 在 `.gitignore` 追加 067 运行态产物忽略：`ml/lineage-extractor/out/run-col-*/`、`ml/lineage-extractor/out/preds/run-col-*.jsonl`、`ml/lineage-extractor/realeval/pool-c*/`、`ml/lineage-extractor/realeval/teacher_labels-c*/`、`ml/lineage-extractor/realeval/teacher_labels-silver/`、`ml/lineage-extractor/realeval/pool-silver/`、`ml/lineage-extractor/data/silver-col.jsonl`（gold/weights/preds 既有规则已覆盖）
- [X] T002 核实 `.env` 凭据活性（DASHSCOPE=m1 / DEEPSEEK_ANTHROPIC=m3 / HF_TOKEN），`cd ml/lineage-extractor && PYTHONPATH=. python -c "from llm.clients import load_clients; print(list(load_clients()))"` 应含 m1+m3 —— ✅ m1/m2/m3/m_flash 全加载，m1+m3 真调通且**均免费吐 columns 字段**
- [X] T003 下载既有 HF 权重到 `weights/weft-lineage-extractor-{05,15,3b}`（`snapshot_download` 三档 merged 模型；门②/冻结基线/表级单调复核的真实前置，被 T015/T020/T024 依赖）—— ✅ 三档落地 954M/2.9G/5.8G，各 1 safetensors+config

---

## Phase 2: Foundational（阻塞所有 US 的共享纯函数层）

**Purpose**: `canon_col` 被 gold 列裁决（US1）与列打分（US1）共用，是所有列逻辑的地基。

- [X] T004 [P] 写 `tests/test_canon_col.py`：按 `contracts/canon_col.md` 行为表逐条断言（小写/去空白/剥单多级前缀/别名/`*`→None/空→None/通配传染 `["amount","*"]`→None）
- [X] T005 在 `eval/metrics.py` 实现纯函数 `canon_col(name)->str|None` 与 `canon_cols(cols)->set[str]|None`（三态：None/[]→None、含通配→None、否则归一非空集），使 T004 全绿；无 torch 依赖

**Checkpoint**: `PYTHONPATH=. python -m pytest tests/test_canon_col.py -q` 绿 → 可进任意 US。

---

## Phase 3: US1 — 列级评测基建 + 表级正交红线（Priority: P1）🎯 MVP

**Goal**: 能对任意 preds 诚实评列级 P/R/F1，且代码级证明表级 counts 逐字节零扰动。
**Independent Test**: 带列 gold 给冻结既有 3B preds 打分出列级指标 + 门① 正交单测绿。
**GPU**: 无 GPU 训练（T015 冻结基线为推理档，`dump_model_preds` cuda 有 CPU 回退）。

### 测试先行（TDD）

- [X] T006 [P] [US1] 写 `tests/test_metrics_columns.py::test_column_scoring_never_perturbs_table_counts`：对多态 fixtures（具体列/弃权/空），断言 `score_row` 带列 vs 列抹 None 下表级 `tp/fp/fn/halluc/pred_total/dir_total/dir_correct/invalid` 逐字节相等（门①）
- [X] T007 [P] [US1] 在 `tests/test_metrics_columns.py` 加条件列打分正确性：TP/FP/FN 集合运算、gold 弃权跳过、pred 弃权记 `col_fn`、列幻觉、按 role（reads/writes）分算、`col_eval_tables` 计数
- [X] T008 [P] [US1] 写 `tests/test_build_gold_columns.py`：按 `contracts/build_gold_column_mode.md` 断言列级裁决（双方交集/交集空→null/一方弃权→null/含 `*`→null/`columns=False` 零回归）

### 实现

- [X] T009 [US1] 在 `eval/metrics.py` `score_row` 加**独立列打分块**（不改表级 8 key 赋值代码）：按 `contracts/metrics_column_scoring.md` 独立重算表对齐对 → 条件列 col_* 计数；使 T006/T007 绿
- [X] T010 [US1] 在 `eval/metrics.py` `aggregate` 加 `col_precision/col_recall/col_f1/col_hallucination`（表级返回值不变）；补/跑既有 `metrics.py` 单测确认零回归（SC-008）
- [X] T011 [US1] 在 `realeval/build_gold_b.py` 加 `_col_map` + `decide_tables(..., columns=False)` 开关与列级交集裁决（表级逻辑不动），使 T008 绿；`build_gold_b.py` main 加 `--columns` flag
- [X] T012 [US1] 在 `realeval/significance_report.py`（与 `eval_baselines_c.py`）报告追加列级指标行（表级行原样保留，自动发现 `out/preds/*.jsonl`）

### 数据 + 基线（teacher API + 推理，无 GPU 训练）

- [X] T013 [US1] 重建带列 gold：`collect_stack --target 400 --out realeval/pool-c` → `teacher_label --teachers m1,m3 --out realeval/teacher_labels-c` → `build_gold_b --min-agree 2 --columns --out realeval/gold/real-c.jsonl` —— ✅ pool 399/非空 107/空 290/397 行；collect_stack 终结阶段 GIL race（数据已落盘）从 teacher_label 续跑；teacher 798 调用（2 error）
- [X] T014 [US1] 校验列 gold 具体列表实例数 ≥30（SC/FR-016）—— ✅ **111 个具体列表实例**（≥30 的 3.7×）/434 列均 3.9 列/表，集中 SQL(105)+SHELL(4)+PYTHON(2)；弃权表实例 231
- [X] T015 [US1] US1 冻结基线：`dump_model_preds MODEL=weights/weft-lineage-extractor-3b --gold real-c.jsonl` → `significance_report` —— ✅ **列级 before 锚**：既有表级 3B = col_p 1.000/col_r **0.000**/col_f1 0.000（n=97，模型今天一列不吐=空壳实证）；**门② 表级基线**：表 p **0.775**[0.681,0.861]/r **0.845**[0.772,0.905]/f1 0.808（备份 `out/preds/model-3b-baseline-tablecurve.jsonl` 防覆盖）

**Checkpoint**: 门① 单测绿 + 列 gold（n≥30）+ 冻结基线列级 before 数已出 → US1 交付，可独立评任何 preds。

---

## Phase 4: US2 — 列增强联合重训 3B + 表级曲线复现门（Priority: P2）

**Goal**: `run-col-3b` 列级 p≥0.70/r≥0.55/F1≥0.60，且表级同集不显著退化（门②）。
**Independent Test**: 重训 3B → 列级达标 + 门② harness 证表级相对既有 3B 不显著退化。
**Depends on**: US1（列 gold + 列 metric + 门①）+ T003（既有权重）。

### 测试先行

- [X] T016 [P] [US2] 写 `tests/test_build_silver_columns.py`：按 `contracts/build_silver_column_preserve.md` 断言 `--keep-columns` False 零回归 / True 携带 m1 列 / m1 弃权→null

### 实现 + 数据（teacher API）

- [X] T017 [US2] 在 `realeval/build_silver.py` 两处 `columns:None` 改为单 teacher m1 `canon_cols`，加 `--keep-columns`/`--teacher` flag（表级逻辑不动），使 T016 绿
- [X] T018 [US2] 再生列增强银标：`collect_stack --target 3000` → `teacher_label m1,m_flash` → `build_silver --pair m1,m_flash --keep-columns` → `build_train_distill` —— ✅ silver 979 行/非空 783/**937 具体列表实例**；`--exclude-gold` 排除 real-c（无泄漏）。**偏离 clarify R6 单 teacher 措辞**：改用**双 teacher 交集**（m1∩m_flash）护表级曲线红线 + 列取 m1(qwen-max)=proven 配方，m_flash 廉价压成本（evidence 记偏离）；teacher 6000 调用

### 重训 + 门②（GPU，WSL2 脱离规则）

- [X] T019 [US2] `setsid` 脱离真跑联合重训 `run-col-3b`（`sft_qlora --data data/out/train-col.jsonl --base Qwen2.5-Coder-3B --out out/run-col-3b`）—— ✅ train_loss 0.703/token_acc 0.87/**无 NaN**，merged 6.17GB 落 `out/run-col-3b/merged`（不覆盖既有 3b），57 分钟
- [X] T020 [US2] dump `run-col-3b` preds → `significance_report` —— ✅ **列级达标全过**：col p **0.827**/r **0.869**/f1 **0.847**（n=93，从既有 3B 的 col r 0.000 抬起）；**门② 相对判据 PASS**：vs 既有 3B 表级 McNemar b/c=6/7 **p=1.000 不显著退化**、precision diff CI 含 0。⚠️ 但表 recall 0.845→0.649，须隔离消融归因（语料规模 vs 加列）→ 见 T020b
- [X] T020b [US2] **隔离消融（诚实归因）** —— ✅ 逐条内容一致的受控对比（base/recipe/silver 三同，唯一变量=列）。**run-tblonly-3b（979 剥列）表 r=0.798**，run-col-3b（979 带列）表 r=0.649 → 分解 0.845→0.649：**语料规模 −0.047**（既有全量→979）+ **加列监督 −0.149**（真·表列权衡，非混淆）。col r 消融证 0.000（剥列训练确不吐列）。门② McNemar 全 p=1.0 不显著（n=107 功效弱，掩盖边级 recall 掉），但绝对表 r 0.649 < 红线 0.832
- [X] T021 [US2] 门② 判定 —— **缓解重训后干净 PASS**。原 run-col-3b（r16/e2）表列权衡真实（列效应 −0.149）→ 缓解重训 `run-col-3b-mit`（LoRA r16→32/alpha32→64/epochs 2→3，直击容量争用+欠训）：表 p **0.782**/r **0.746**/f1 **0.763**（≈ 纯表天花板 0.769=权衡几近消除，列效应 −0.149→−0.052）、列 p 0.803/r **0.840**/f1 0.821（全过 SC）。**门② 相对判据**：vs published McNemar **p=0.754 不显著退化**、precision diff +0.007；vs 纯表 run-tblonly McNemar p=1.0 表级不可区分；vs 原 run-col precision +0.077 **显著改进**。截断假设先证伪（512→1024 仅 +0.02）。**run-col-3b-mit = US2 交付模型**（超参偏离 059 固化=067 联合任务缓解，evidence 记录）

**Checkpoint**: `run-col-3b` 列级达标 + 门② PASS（或诚实负结果落档）。

---

## Phase 5: US3 — 扩训 0.5/1.5B + 列级 scale 曲线（Priority: P3）

**Goal**: 列级逐规模 scale 曲线（理想单调）= 论文第二脊椎，且表级单调保住。
**Independent Test**: 三档 preds 出列级 scale 报告 + 表级单调复核（对照 T003 既有 0.5/1.5B）。
**Depends on**: US2 门② PASS + T003（既有 0.5/1.5B 权重）。

- [X] T022 [P] [US3] 扩训 `run-col-15`（Qwen2.5-Coder-1.5B，**mit 配方 r32/e3** 与 3B 交付一致）—— ✅ 表 f1 0.555/列 f1 0.860
- [X] T023 [P] [US3] 扩训 `run-col-05`（Qwen2.5-Coder-0.5B，mit 配方）—— ✅ 表 f1 0.550/列 f1 0.753；逐档串行无 NaN
- [X] T024 [US3] scale 曲线（`out/significance-scale.md`）—— ✅ **表级单调保住**：table f1 0.550→0.555→0.763 单调升、recall 0.447→0.468→0.746 单调升（门② 单调子判据 ✅）。**列级 SC-006 stretch 未干净达成（诚实混合）**：col f1 0.753→**0.860**→0.821 非单调（1.5B 峰），且 n 差异 81/67/94（列评条件于表命中=不同分母混淆）+ CI 重叠差异不显著。三档列 f1 全过 SC 阈
- [X] T025 [US3] scale 结果写 `out/PAPER-EVIDENCE-067.md`（独立于 065）——列各档均强但 scale 非干净单调（分母混淆），如实报中性；表级单调保住可作支撑

**Checkpoint**: 列级 scale 曲线 + 表级单调复核出档。

---

## Phase 6: US4 — 确定性列基线对照（Priority: P4，可选 stretch）

**Goal**: SQL 子集 SQLLineage 列级基线，佐证工具列级也失效、模型救回。
**Independent Test**: SQLLineage 列 preds 并入 baseline 报告与模型列对照。
**Depends on**: US1 列 metric（独立，可与 US2/US3 并行）。

- [ ] T026 [P] [US4] 在 `eval/baselines/sqllineage_baseline.py` 加列级抽取（`get_column_lineage()`），非 SQL/解析失败→列弃权（None）
- [ ] T027 [US4] `eval_baselines_c.py --with-columns` 在 SQL 子集出 SQLLineage 列基线 vs 模型列对照行

---

## Phase 7: Polish & 收尾（跨切面）

- [X] T028 全量单测真跑 —— ✅ **289 passed 零回归**（sft_qlora `--lora-r/--lora-alpha` 加参无破坏，SC-008）
- [X] T029 成本记账 —— ✅ 从 label 真实 usage 算 **≈¥25.04**（gold ¥5.46+silver ¥19.58，6798 调用）**SC-007 PASS 4× 裕度**；写入 `out/PAPER-EVIDENCE-067.md`
- [ ] T030 [P] 更新 `ml/lineage-extractor/publish.py` + HF：发布 `run-col-*` 权重卡（列级能力+诚实边界：列循环性/小 n/宽 CI）与列 gold（`--include-real-gold` 按需）
- [ ] T031 [P] 在 `CLAUDE.md` Knowledge Map 加 067 条目（列级血缘：teacher 免费吐列/条件列 metric 门①正交/门②同集/run-col-* 家族/成本）
- [ ] T032 更新记忆 `weft-067-column-lineage.md`（真跑结果：列级 P/R、门② 结论、成本、scale 曲线）
- [ ] T033 合并回 main（按并发多 Agent 硬规则：先 `git worktree list` + 读 sibling，无冲突再合）+ push；`git worktree remove` 067

---

## Dependencies & 执行顺序

```
Setup(T001-3: gitignore/凭据/既有权重) → Foundational(T004-5 canon_col)
   ├─ US1(T006-15)  ← MVP,无 GPU 训练;门① 单测 + 列 gold + 冻结基线
   │     └─ US2(T016-21)  ← 需 US1 列 gold/metric + T003 权重;3B 先行门②
   │            └─ US3(T022-25)  ← 需 US2 门② PASS + T003 既有 0.5/1.5B
   ├─ US4(T026-27)  ← 可选,仅需 US1 列 metric(独立)
   └─ Polish(T028-33)  ← 全部之后
```
- **门②（T020-21）是硬闸**：FAIL 则 US3 不执行、既有模型/曲线不动。
- **US4 独立**：只依赖 US1 的列 metric，可与 US2/US3 并行。

## Parallel 机会
- Phase 2/3 测试：T004 / T006 / T007 / T008 [P]（不同测试文件/独立断言块）。
- Phase 5 扩训：T022 / T023 [P]（不同模型，5070 12.8G 建议逐档串行更稳）。
- Polish：T030 / T031 [P]（HF vs CLAUDE.md，不同文件）。

## MVP 范围
**US1（T001–T015）** = 最小可交付：无 GPU 训练即交付「诚实评列 + 门① 表级零扰动证明 + 重训前基线」（T015 冻结基线为推理档，可 CPU 回退）。后续 US2/US3 为能力增量。
