# Tasks: 血缘小模型实证研究——可投论文加固

**Feature**: `065-lineage-paper` | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**根目录**：所有源码路径相对 `ml/lineage-extractor/`。W1–W3 离线（无 GPU）。**硬约束**：不改 `eval/metrics.py` 打分逻辑；核心复现无需私有凭据；只认 `Tests run: N>0`（build-cache 会假绿）。

**MVP** = User Story 1（统计诚实层）。US1 独立交付即让论文在统计严谨性上站住。

**Story ↔ 工作包**：US1=W1 · US2=W2 · US3=W3 · US4=W4（可选）· Polish=W5 写作支撑。

---

## Phase 1: Setup

- [x] T001 在 `requirements.txt` 增加 `sqllineage`（工具基线）；**scipy 按设计不加**——McNemar 改手写精确二项（`math.comb`），US1 全程只依赖已在的 `numpy`（对 research R6 的修订，记录在 `significance.py`）。

---

## Phase 2: Foundational（阻塞所有 Story，必须先完成）

- [x] T002 [P] 新建 `eval/subset.py`：sql/script 子集分类器——按样本 `type`（SQL→sql；PYTHON/SHELL/SPARK→script）+ 内容兜底启发式，给每样本判 `subset ∈ {sql, script}`。**消化 rigor CHK010**（SC-003 招牌对比的归类地基）。附纯函数、边界（空/未知类型）确定性。
- [x] T003 逐脚本 counts 采集适配层 `realeval/counts_adapter.py`：对每样本调既有 `eval/metrics.py:score_row`（**不改**）取 tp/fp/fn/direction，派生 `exact_match = (fp==0 and fn==0)`，并用 `eval/subset.py` 附 `subset`。产出 `list[PerScriptCounts]`（data-model 稳定接缝）。依赖 T002。

**Checkpoint**：US1/US2 共用的逐脚本 counts + 子集口径就绪。

---

## Phase 3: User Story 1 - 统计诚实层（每个数字带 CI）(Priority: P1) 🎯 MVP

**独立测试**：对落盘 preds（3B/teacher @ gold C、gold A）算 counts，跑统计层，产出每指标 point+95%CI 与 3B-vs-teacher 的 diff-CI+McNemar p+"是否显著"判定；无需 GPU/新数据。

- [x] T004 [P] [US1] 测试先行 `tests/test_significance.py`：① bootstrap 同 seed 逐位可复现；② CI 含点估计且 `lo≤point≤hi`；③ paired-diff 在构造数据上 `significant` 判定正确；④ McNemar 已知 2×2（含全并列 b+c=0→p=1.0）；⑤ 空/单样本退化不崩。
- [x] T005 [US1] 实现 `eval/significance.py`：`bootstrap_metric_ci` / `paired_bootstrap_diff` / `mcnemar_exact`（契约见 `contracts/significance-api.md`）。numpy 手写 bootstrap（固定 seed），McNemar 用 `scipy.stats.binomtest`。**不 import metrics 打分逻辑**，只消费 counts。
- [x] T006 [US1] 编排 `realeval/significance_report.py`：读 preds→counts_adapter→significance，输出 `out/significance-c.md`——每指标 point+CI；3B-vs-teacher 的 diff-CI 与 McNemar p；**诚实判定"n≈49 下是否显著"**（SC-002）；报告顶部引用既有 `out/leak-curve.md` 泄漏曲线作为头条锚点（部分消化 rigor CHK005）。

**Checkpoint**：US1 可独立交付——SC-001（头条带 CI）/ SC-002（诚实报显著性）达成。

---

## Phase 4: User Story 2 - 工具基线（工具≈0/模型救回）(Priority: P2)

**独立测试**：regex+SQLLineage 在 gold C 上与模型同尺子分层跑分；SQL 子集 SQLLineage 可比、脚本子集工具≤0.10、模型脚本召回显著高于工具。

- [x] T007 [P] [US2] 测试先行 `tests/test_sqllineage_baseline.py`：① 纯 SQL（INSERT…SELECT/JOIN）正确分列 source/target；② Python/Shell/非 SQL/超长/二进制输入→返回空且**永不抛**；③ 输出符合 `{reads,writes}`（columns=None）契约。
- [x] T008 [US2] 实现 `eval/baselines/sqllineage_baseline.py`：`predict(row)` 用 `sqllineage.runner.LineageRunner`，非 SQL/解析失败 catch→空（契约见 `contracts/baseline-predict.md`）。既有 `regex_baseline` 不改，仅确认接口通用。
- [x] T009 [US2] 编排 `realeval/eval_baselines_c.py`：对 gold C 跑 regex+sqllineage+model-{0.5b/1.5b/3b}，经 counts_adapter 按 `subset` 分层，各行附 significance CI，产 `out/baselines-c.md`（`BaselineComparisonRow` 表）。**断言 SC-003**：SQLLineage@sql.recall 可比 / 工具@script.recall≤0.10 / model-3b@script 显著高于工具（diff-CI 不含 0 或 McNemar p<0.05）。

**Checkpoint**：US2 可独立交付——SC-003 招牌对比达成。

---

## Phase 5: User Story 3 - 可复现 benchmark（无凭据第三方复算）(Priority: P3)

**独立测试**：从发布物在无私有凭据环境按 README 复算头条表，落在 US1 的 CI 内。

- [ ] T010 [P] [US3] 测试先行 `tests/test_benchmark_manifest.py`：校验清单符合 `contracts/benchmark-manifest.schema.json`——记录**无 `content` 字段**、`disclosure.no_source_bodies/no_synthetic_train=true`、`credential_free=true`、抓取仅引用公开端点（**消化 rigor：无源码重分发/无合成泄漏**，FR-007）。
- [ ] T011 [US3] 实现 `benchmark/build_manifest.py`：从仲裁后 gold C 产 `manifest.json`（LabelRecord+SourcePointer，仅 repo/commit/path+标签，无源码正文）+ `labels.jsonl`。
- [ ] T012 [US3] 实现 `benchmark/fetch.py`：按 `repo@commit:path` 无凭据抓公开源（GitHub raw / the-stack 公开镜像）重建评测输入；失败可跳过并如实报缺失。
- [ ] T013 [US3] 撰写 `benchmark/README.md` 复现说明：三步复算（build→fetch→eval）、公开边界声明、**gold C 获取路径（HF）**（消化 rigor CHK029）、受限凭据分支（deepseek）与核心路径的显式分离（FR-008）。

**Checkpoint**：US3 可独立交付——SC-004（第三方复算）/ SC-005（招牌图可复现）达成。

---

## Phase 6: User Story 4 - auto-gold 扩容（可选/稳健性）(Priority: P4)

> **门控**：默认**不执行**。仅当 US1–US3 落地后有余量 + MSR 投稿周期允许时启动（待 DDL 确认）。

- [ ] T014 [US4] 实现 `realeval/expand_gold_c.py`：复用 `collect_stack.py`（the-stack，HF_TOKEN）→ `teacher_label.py`（m1+m2 一致，deepseek 缺）→ `build_gold_b.py`，目标非空 ~100；按内容 sha256 **dedup 排除已在 gold C 的样本**防污染；产物打 `robustness_only=true` 标注"teacher 派生、非独立真值"。重跑 US1 CI 验 SC-006（关键指标 CI 收窄）。

---

## Phase 7: Polish & 跨切面（W5 写作支撑）

- [ ] T015 [P] 汇编 `out/PAPER-EVIDENCE.md`：把每条头条陈述映射到一个证据项（significance-c.md / baselines-c.md / leak-curve.md / 既有信封产物），落实 FR-010（无裸数字比较）+ **消化 rigor CHK005/CHK006**（泄漏曲线 + 信封均有证据锚点）。
- [ ] T016 [P] 落地时更新 `CLAUDE.md` Knowledge Map 加 065 条目 + 记忆刷新（**提交留到实现完成/合并时**，现在不写）。
- [ ] T017 全量 `pytest`（`ml/lineage-extractor/tests/`）确认 `test_significance` + `test_sqllineage_baseline` + `test_benchmark_manifest` + 既有回归全绿；WSL2 长跑按硬规则 setsid 脱离；只认 `Tests run: N>0`。

---

## Dependencies & 执行顺序

- **Setup(T001)** → **Foundational(T002→T003)** → 各 Story。
- **T002 先于 T003**（分类器供适配层）；T003 供 US1/US2 共用。
- **US 独立性**：US1（T004-T006）、US2（T007-T009）、US3（T010-T013）三者在 Foundational 后**可并行推进**（不同文件、无相互依赖）。US4 门控、独立。
- **Story 内 TDD**：每 Story 测试任务（T004/T007/T010）先于其实现。
- **Polish(T015-T017)** 在 US1–US3 之后。

## 并行机会

- Foundational 后，三个 [P] 测试任务 **T004 / T007 / T010 可同时起**（不同测试文件）。
- 各 Story 的实现任务落不同文件，跨 Story 可并行（受人手约束）。
- T015 / T016 可与 T017 并行。

## 实现策略

1. **先交 MVP**：Setup + Foundational + US1 → 论文统计严谨性即成立（可先写 W5 的诚实性章节）。
2. **增量**：US2 补招牌对比图 → US3 补复现性 → 视 DDL 决定 US4。
3. **W5 写作**贯穿：每个 Story 的 `out/*.md` 即论文图表素材，随做随引。

## 遗留（rigor.md 未在任务中完全消化的项）

- CHK010→T002 ✅ / CHK029→T013 ✅ / CHK005·CHK006→T006·T015 ✅。
- **仍建议回填 spec** 的 FR 级缺口（招牌图产出 FR / sql-script 归类判据 FR）：任务已覆盖实现，但 spec 文本未显式立 FR——`/speckit-analyze` 会再标一次，届时可一并补 spec 或接受"任务已覆盖"。
