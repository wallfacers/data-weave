# Tasks: 列级血缘（联合表+列重训 · 保表级曲线）

**Input**: Design documents from `specs/067-column-lineage/`
**Prerequisites**: plan.md · spec.md · research.md · data-model.md · contracts/ · quickstart.md
**Tests**: 已在 spec 明确要求（门① 正交单测 + 列裁决/列打分单测；CLAUDE.md「无测试=未完成」）→ 含测试任务。

**路径约定**：全部相对 `ml/lineage-extractor/`。数据/权重/preds 全 gitignored 走 HF。

**执行策略（MVP 优先）**：Phase 3（US1）即 MVP——不需 GPU 就交付「诚实评列 + 门① 表级零扰动证明」。US2 3B 先行为门② 闸，过门才 US3 扩训。US4 可选。

---

## Phase 1: Setup（共享基础）

- [ ] T001 在 `.gitignore` 追加 067 运行态产物忽略：`ml/lineage-extractor/out/run-col-*/`、`ml/lineage-extractor/out/preds/run-col-*.jsonl`、`ml/lineage-extractor/realeval/pool-c*/`、`ml/lineage-extractor/realeval/teacher_labels-c*/`、`ml/lineage-extractor/realeval/teacher_labels-silver/`、`ml/lineage-extractor/realeval/pool-silver/`、`ml/lineage-extractor/data/silver-col.jsonl`（gold/weights/preds 既有规则已覆盖）
- [ ] T002 核实 `.env` 凭据活性（DASHSCOPE=m1 / DEEPSEEK_ANTHROPIC=m3 / HF_TOKEN），`cd ml/lineage-extractor && PYTHONPATH=. python -c "from llm.clients import load_clients; print(list(load_clients()))"` 应含 m1+m3

---

## Phase 2: Foundational（阻塞所有 US 的共享纯函数层）

**Purpose**: `canon_col` 被 gold 列裁决（US1）与列打分（US1）共用，是所有列逻辑的地基。

- [ ] T003 [P] 写 `tests/test_canon_col.py`：按 `contracts/canon_col.md` 行为表逐条断言（小写/去空白/剥单多级前缀/别名/`*`→None/空→None/通配传染 `["amount","*"]`→None）
- [ ] T004 在 `eval/metrics.py` 实现纯函数 `canon_col(name)->str|None` 与 `canon_cols(cols)->set[str]|None`（三态：None/[]→None、含通配→None、否则归一非空集），使 T003 全绿；无 torch 依赖

**Checkpoint**: `PYTHONPATH=. python -m pytest tests/test_canon_col.py -q` 绿 → 可进任意 US。

---

## Phase 3: US1 — 列级评测基建 + 表级正交红线（Priority: P1）🎯 MVP

**Goal**: 能对任意 preds 诚实评列级 P/R/F1，且代码级证明表级 counts 逐字节零扰动。
**Independent Test**: 带列 gold 给冻结既有 3B preds 打分出列级指标 + 门① 正交单测绿。

### 测试先行（TDD）

- [ ] T005 [P] [US1] 写 `tests/test_metrics_columns.py::test_column_scoring_never_perturbs_table_counts`：对多态 fixtures（具体列/弃权/空），断言 `score_row` 带列 vs 列抹 None 下表级 `tp/fp/fn/halluc/pred_total/dir_total/dir_correct/invalid` 逐字节相等（门①）
- [ ] T006 [P] [US1] 在 `tests/test_metrics_columns.py` 加条件列打分正确性：TP/FP/FN 集合运算、gold 弃权跳过、pred 弃权记 `col_fn`、列幻觉、按 role（reads/writes）分算、`col_eval_tables` 计数
- [ ] T007 [P] [US1] 写 `tests/test_build_gold_columns.py`：按 `contracts/build_gold_column_mode.md` 断言列级裁决（双方交集/交集空→null/一方弃权→null/含 `*`→null/`columns=False` 零回归）

### 实现

- [ ] T008 [US1] 在 `eval/metrics.py` `score_row` 加**独立列打分块**（不改表级 8 key 赋值代码）：按 `contracts/metrics_column_scoring.md` 独立重算表对齐对 → 条件列 col_* 计数；使 T005/T006 绿
- [ ] T009 [US1] 在 `eval/metrics.py` `aggregate` 加 `col_precision/col_recall/col_f1/col_hallucination`（表级返回值不变）；补/跑既有 `metrics.py` 单测确认零回归（SC-008）
- [ ] T010 [US1] 在 `realeval/build_gold_b.py` 加 `_col_map` + `decide_tables(..., columns=False)` 开关与列级交集裁决（表级逻辑不动），使 T007 绿；`build_gold_b.py` main 加 `--columns` flag
- [ ] T011 [US1] 在 `realeval/significance_report.py`（与 `eval_baselines_c.py`）报告追加列级指标行（表级行原样保留，自动发现 `out/preds/*.jsonl`）

### 数据 + 基线（teacher API，无 GPU）

- [ ] T012 [US1] 重建带列 gold：`collect_stack --target 400 --out realeval/pool-c` → `teacher_label --teachers m1,m3 --out realeval/teacher_labels-c` → `build_gold_b --min-agree 2 --columns --out realeval/gold/real-c.jsonl`；记 teacher usage（成本 ≈¥6-7）
- [ ] T013 [US1] 校验列 gold 具体列表实例数 ≥30（SC/FR-016）；不足则在剩余预算内补采 pool-c；记录实际 n
- [ ] T014 [US1] US1 冻结基线：`dump_model_preds MODEL=weights/weft-lineage-extractor-3b --gold real-c.jsonl --out out/preds/model-3b.jsonl` → `significance_report`，如实记既有 3B 列级 ≈0（before 锚，`out/PAPER-EVIDENCE.md` 溯源）

**Checkpoint**: 门① 单测绿 + 列 gold（n≥30）+ 冻结基线列级 before 数已出 → US1 交付，可独立评任何 preds。

---

## Phase 4: US2 — 列增强联合重训 3B + 表级曲线复现门（Priority: P2）

**Goal**: `run-col-3b` 列级 p≥0.70/r≥0.55/F1≥0.60，且表级同集不显著退化（门②）。
**Independent Test**: 重训 3B → 列级达标 + 门② harness 证表级落既有 3B CI 带内。
**Depends on**: US1（列 gold + 列 metric + 门①）。

### 测试先行

- [ ] T015 [P] [US2] 写 `tests/test_build_silver_columns.py`：按 `contracts/build_silver_column_preserve.md` 断言 `--keep-columns` False 零回归 / True 携带 m1 列 / m1 弃权→null

### 实现 + 数据（teacher API）

- [ ] T016 [US2] 在 `realeval/build_silver.py` 两处 `columns:None` 改为单 teacher m1 `canon_cols`，加 `--keep-columns`/`--teacher` flag（表级逻辑不动），使 T015 绿
- [ ] T017 [US2] 再生列增强银标：`collect_stack --target 3000 --out realeval/pool-silver` → `teacher_label --teachers m1 --out realeval/teacher_labels-silver` → `build_silver --teacher m1 --keep-columns --out data/silver-col.jsonl`；记 usage（≈¥25-40，累计核 ≤¥100 / SC-007）

### 重训 + 门②（GPU，WSL2 脱离规则）

- [ ] T018 [US2] `setsid` 脱离真跑联合重训 `run-col-3b`（`sft_qlora --data data/silver-col.jsonl --base Qwen2.5-Coder-3B --out out/run-col-3b`，超参沿用 059 固化 lr/warmup/max-grad-norm），落 merged 权重（不覆盖既有 3b）
- [ ] T019 [US2] dump `run-col-3b` preds（`out/preds/run-col-3b.jsonl`）→ `significance_report` + `eval_baselines_c`：判**门②**（表 p≥0.72/r≥0.80、vs 既有 3B 同集 McNemar 不显著退化、Δr 三档显著）与**列级达标**（SC-001/002/003）
- [ ] T020 [US2] 门② 判定分支：PASS→放行 US3；FAIL→在 `out/PAPER-EVIDENCE.md` 如实记「表列权衡」负结果、冻结既有模型/曲线、停 US3（记录决策）

**Checkpoint**: `run-col-3b` 列级达标 + 门② PASS（或诚实负结果落档）。

---

## Phase 5: US3 — 扩训 0.5/1.5B + 列级 scale 曲线（Priority: P3）

**Goal**: 列级逐规模 scale 曲线（理想单调）= 论文第二脊椎，且表级单调保住。
**Independent Test**: 三档 preds 出列级 scale 报告 + 表级单调复核。
**Depends on**: US2 门② PASS。

- [ ] T021 [P] [US3] `setsid` 脱离真跑扩训 `run-col-15`（`sft_qlora --base Qwen2.5-Coder-1.5B --out out/run-col-15`，同银标同超参）
- [ ] T022 [P] [US3] `setsid` 脱离真跑扩训 `run-col-05`（`sft_qlora --base Qwen2.5-Coder-0.5B --out out/run-col-05`）
- [ ] T023 [US3] dump `run-col-{05,15}` preds → `significance_report` 出**列级 scale 曲线**（0.5/1.5/3B 列 P/R/F1）+ 复核**表级单调保住**；非单调如实报中性/负结果
- [ ] T024 [US3] 列级 scale 结果 + 记忆↓泛化↑（若成立）写 `out/PAPER-EVIDENCE.md`（第二脊椎行，无裸数字溯源）

**Checkpoint**: 列级 scale 曲线 + 表级单调复核出档。

---

## Phase 6: US4 — 确定性列基线对照（Priority: P4，可选 stretch）

**Goal**: SQL 子集 SQLLineage 列级基线，佐证工具列级也失效、模型救回。
**Independent Test**: SQLLineage 列 preds 并入 baseline 报告与模型列对照。

- [ ] T025 [P] [US4] 在 `eval/baselines/sqllineage_baseline.py` 加列级抽取（`get_column_lineage()`），非 SQL/解析失败→列弃权（None）
- [ ] T026 [US4] `eval_baselines_c.py --with-columns` 在 SQL 子集出 SQLLineage 列基线 vs 模型列对照行

---

## Phase 7: Polish & 收尾（跨切面）

- [ ] T027 全量单测真跑：`cd ml/lineage-extractor && PYTHONPATH=. python -m pytest -q` 全绿零回归（SC-008）
- [ ] T028 成本记账核对：汇总 `teacher_label` usage，确认 gold+银标累计 ≤¥100（SC-007），写 `out/PAPER-EVIDENCE.md` 成本行
- [ ] T029 [P] 更新 `ml/lineage-extractor/publish.py` + HF：发布 `run-col-*` 权重卡（列级能力+诚实边界：列循环性/小 n/宽 CI）与列 gold（`--include-real-gold` 按需）
- [ ] T030 [P] 在 `CLAUDE.md` Knowledge Map 加 067 条目（列级血缘：teacher 免费吐列/条件列 metric 门①正交/门②同集/run-col-* 家族/成本）
- [ ] T031 更新记忆 `weft-067-column-lineage.md`（真跑结果：列级 P/R、门② 结论、成本、scale 曲线）
- [ ] T032 合并回 main（按并发多 Agent 硬规则：先 `git worktree list` + 读 sibling，无冲突再合）+ push；`git worktree remove` 067

---

## Dependencies & 执行顺序

```
Setup(T001-2) → Foundational(T003-4 canon_col)
   ├─ US1(T005-14)  ← MVP,无 GPU;门① 单测 + 列 gold + 冻结基线
   │     └─ US2(T015-20)  ← 需 US1 列 gold/metric;3B 先行门②
   │            └─ US3(T021-24)  ← 需 US2 门② PASS
   ├─ US4(T025-26)  ← 可选,仅需 US1 列 metric(独立)
   └─ Polish(T027-32)  ← 全部之后
```
- **门②（T019-20）是硬闸**：FAIL 则 US3 不执行、既有模型/曲线不动。
- **US4 独立**：只依赖 US1 的列 metric，可与 US2/US3 并行。

## Parallel 机会
- Phase 2/3 测试：T003 / T005 / T006 / T007 [P]（不同测试文件）。
- Phase 5 扩训：T021 / T022 [P]（不同模型，显存允许则串行更稳——5070 12.8G，建议逐档）。
- Polish：T029 / T030 [P]（HF vs CLAUDE.md，不同文件）。

## MVP 范围
**US1（T001-T014）** = 最小可交付：不需 GPU、不需重训，即交付「诚实评列 + 门① 表级零扰动证明 + 重训前基线」。后续 US2/US3 为能力增量。
