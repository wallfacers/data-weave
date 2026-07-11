# Tasks: 召回回收 · 置信度分层复核信封

**Input**: Design documents from `specs/063-recall-tiered-envelope/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: 含测试任务——spec FR-009 + 项目规则「新功能必须有测试，无测试=未完成」。TDD：测试先行/同步。

**Organization**: 按 user story 分阶段，每个 US 可独立实现+测试+交付。

**改动范围**：全部在 `ml/lineage-extractor/`（worktree `dw-063-recall-tiered-envelope`）。不碰 backend/frontend。

## Path Conventions

- 代码根：`ml/lineage-extractor/`（下称 `<ml>`）
- 数据（gitignored，软链自 dw-059）：`<ml>/realeval/gold/`、`<ml>/out/`（CV 去偏只需 gold C 金标 + 既有预测，无需 pool-c-held/银标——R1）

---

## Phase 1: Setup（数据落位与环境）

**Purpose**: gitignored 数据软链到 063，确认既有内核可用。

- [x] T001 按 quickstart「前置」软链 dw-059 的 gitignored 数据到 `<ml>`：`realeval/gold/real-c-arbitrated.jsonl`、`out/preds-c-run-059-runc.jsonl`、`out/preds-c-teacher-{deepseek-pro,qwen-max}.jsonl`（均 gitignored，不入 commit；CV 不需 pool-c-held/teacher 标签）
- [x] T002 [P] 冒烟确认既有内核在 063 可导入：`cd <ml> && PYTHONPATH=. python3 -c "from realeval.channel_router import extract_sql_lineage; from realeval.confidence_calibration import _canonical_edges, TIERS; from realeval.conf_calibration_cv import main; from realeval.semantic_grounding import filter_pred_semantic; from realeval.dir_fix import apply_dir_fix; print('ok')"`
- [x] T003 [P] 冒烟确认 gold C（153 行）与 preds-c（153 行）对齐，三方预测（3b/deepseek/qwen）齐备

---

## Phase 2: Foundational（阻塞所有 US 的共享核）

**Purpose**: 冻结校准常量 + 分层纯函数 = 所有 US 的地基，必须先完成。

### 冻结校准（research R1 修正：无独立非泄漏带标集 → gold C 嵌套 CV 去偏；复用既有 gold C 预测，无需 dump/silver）

- [x] T004 数据准备：对既有 gold C 预测 `out/preds-c-run-059-runc.jsonl` **先过语义 grounding**（部署管线一致），产出按行 idx 对齐的 `out/preds-c-grounded-idx.jsonl`（`conf_calibration_cv`/`confidence_calibration` 需 model_by_idx）。〔原 T004 pool-c-held dump 取消——R1 证伪 pool-c-held⊇gold C〕
- [x] T005 〔取消〕build_silver 银标不需要——CV 直接在 gold C 金标 + 既有预测上做（R1/R2）。保留占位以稳定后续编号
- [x] T006 [P] 写 `<ml>/tests/test_calibrate_tiers.py`（先行）：断言校准常量表结构（五级 tier×{precision,n,calibrated_rank}）、全 gold C 点估计与 CV held-out 均输出、校准序按 precision 降序、sql_bare 低置信
- [x] T007 实现 `<ml>/realeval/calibrate_tiers.py`：在 **gold C**（`real-c-arbitrated.jsonl`）+ grounded 预测上跑 `confidence_calibration.calibrate`（全 gold C 点估计 → 部署常量）+ `conf_calibration_cv`（k 折/留一 CV 去偏 → held-out 报告口径）→ `--emit-constants` 写 `realeval/tier_classify_constants.py`，报告 `out/calibrate-tiers.md`（点估计 vs CV held-out 并列，披露样本小/边界抖动）
- [x] T008 运行 T007 产出冻结常量 `realeval/tier_classify_constants.py` + `out/calibrate-tiers.md`；确认 CV held-out 前沿在 ≥0.95 阈下的 precision（诚实口径）；`test_calibrate_tiers.py` 转绿

### 分层纯函数（contracts/tier-classify.md）

- [x] T009 [P] 写 `<ml>/tests/test_tier_classify.py`（先行，真实夹具）：sql_qual 进 auto、sql_bare 进 review、model-only 漏抽经 SQL 通道补回进 review、`t`+`db.t` canon 合并为一条 agree、空输入 `tiered=False`、`thr=0` 全进 auto、`thr=0.95` 仅累计≥0.95 前缀进 auto、review 按 confidence 降序、agree 方向冲突取 SQL-AST
- [x] T010 实现 `<ml>/realeval/tier_classify.py` 的 `classify_tiers(model_pred, content, thr=0.95)`：`extract_sql_lineage(exec_gated=True)` 取 S、`_canonical_edges(S,M)` 打 tier、读冻结常量赋 confidence、按校准序累计切 auto/review、review 降序、方向 FR-010（agree 冲突取 AST target）；纯函数无 torch
- [x] T011 跑 `test_tier_classify.py` 转绿；确认确定性（同输入同阈同输出）

**Checkpoint**: 冻结常量 + `classify_tiers` 就绪且单测绿 → 各 US 可并行推进。

---

## Phase 3: User Story 1 — 复核者拿到召回回收的候选队列 (P1) 🎯 MVP

**Goal**: serving 产出复核候选层，把并集召回（0.764）surface 给人工；离线证明召回回收 ≥+5pt。

**Independent Test**: gold C 上离线跑分层，`auto ∪ review` 召回 ≥0.76（vs 模型独抽 0.703），候选可按 confidence 排序。

- [x] T012 [P] [US1] 写 `<ml>/tests/test_dir_fix_serve.py` 新增用例（先行）：分层响应含 `reviewReads/reviewWrites`、模型漏抽经 SQL 补回的真表出现在 review、review 按 confidence 降序、`tiered` 标记正确
- [x] T013 [US1] 扩 `<ml>/serve/app.py`：`TableIo` 加 `tier:str`、`confidence:float`；`ExtractResponse` 加 `reviewReads`、`reviewWrites`、`tiered:bool`（默认值，向后兼容，见 contracts/extract-response.md）
- [x] T014 [US1] 扩 `<ml>/serve/app.py` 的 `postprocess`：链尾调 `classify_tiers`（解析→semantic grounding→dir_fix→**分层**），`reads/writes`=auto 层、`reviewReads/writes`=review 层，回填 `tiered`；`extract()` 组装两层响应
- [x] T015 [US1] 实现 `<ml>/realeval/rescore_tiered.py`：gold C 上对 `--preds label:path`（三方）跑 `classify_tiers`，量 auto∪review 召回、review 召回、复核负载（候选/脚本），报告 `out/rescore-tiered.md`
- [x] T016 [US1] 跑 T015 harness（三方：3b/deepseek/qwen）；确认 **SC-001**：3B `auto∪review` 召回 ≥0.76、相对 0.703 回收 ≥+5pt；`test_dir_fix_serve.py` US1 用例转绿

**Checkpoint**: serving 返回分层响应、复核层召回回收经离线证明 → US1 可独立交付（MVP）。

---

## Phase 4: User Story 2 — 自动入库只收高置信血缘 (P2)

**Goal**: 自动采纳层 held-out precision ≥ 治理阈；低置信候选降级复核，不自动入库。

**Independent Test**: gold C CV held-out 量自动层 precision ≥ 阈；裸名低置信候选不在 auto。

- [x] T017 [P] [US2] 写 `<ml>/tests/test_tier_classify.py` 补用例（先行）：`thr=0.95` 时低置信 tier（model_bare 使累计跌破阈）降级 review、仅达阈前缀留 auto；`test_dir_fix_serve.py` 补：`reads/writes` 只含 auto 层高置信表
- [x] T018 [US2] `rescore_tiered.py` 增自动层度量：auto 层 gold C **CV held-out** precision（`conf_calibration_cv` 口径，级序/前沿留出）+ 与模型平铺输出精度对照（无回归）
- [x] T019 [US2] 跑 harness 确认 **SC-002**：自动层 CV held-out precision ≥ 治理阈（默认 0.95→1.000）；报告如实披露 ≥0.95 自动层召回过低（约 0.05，仅 sql_qual）为治理严格代价 + CV 真膝点 0.85 数据（recall 约 0.72）；US2 测试转绿

**Checkpoint**: 自动层治理安全性经 held-out 证明 → US2 交付。

---

## Phase 5: User Story 3 — 运维可调治理阈并可回滚 (P3)

**Goal**: env 调阈（默认 0.95，可 0.90）；一键关分层退回旧单一输出。

**Independent Test**: 改 env 阈重跑，auto/review 随阈迁移；`LINEAGE_TIERING=0` 输出与旧单一清单等价。

- [x] T020 [P] [US3] 写 `<ml>/tests/test_dir_fix_serve.py` 补用例（先行）：`LINEAGE_AUTOACCEPT_MIN_PRECISION=0.90` 时 auto 层扩大/复核负载降；`LINEAGE_TIERING=0` 时响应等价旧单一 `reads/writes`（review 空、`tiered=False`、旧字段语义不变）；任意阈下 `auto∪review` 召回不变
- [x] T021 [US3] `<ml>/serve/app.py`：接 env `LINEAGE_AUTOACCEPT_MIN_PRECISION`（默认 `"0.95"`）→ `thr`；接 `LINEAGE_TIERING`（默认 `"1"`，置 0 完全跳过分层退回旧路径）；见 contracts/extract-response.md 配置表
- [x] T022 [US3] 跑 T020 用例转绿；确认 **SC-004**：0.95→0.85 自动层召回约 0.05→0.72 可验证（CV 真膝点；0.90 仍约 0.05）、`LINEAGE_TIERING=0` 逐字节等价 059 现状

**Checkpoint**: 阈可调 + 回滚安全阀就绪 → US3 交付。

---

## Phase 6: Polish & 收尾

- [x] T023 [P] 全量 ml 套件回归：`cd <ml> && PYTHONPATH=. python3 -m pytest -q` 全绿，无回归（含 059 既有测试）
- [x] T024 [P] 写 `<ml>/out/FINDINGS-063.md`：召回天花板定界（0.764）、三方分层对照、≥0.95 vs 0.90 代价、**R1 诚实披露（无独立集：测试集 A 删/pool-c-held⊇gold C/pool-c-train 泄漏→gold C CV 去偏）**、SC-001~005 达成/未达如实
- [x] T025 [P] 更新 `CLAUDE.md` Knowledge Map 加 063 条目（召回回收·分层复核信封，指向 specs/063）
- [x] T026 确认无 gitignored 数据/权重/preds 被 git add（`git status` 干净，只提交代码+spec+报告 md）

---

## Dependencies & 完成顺序

- **Setup（T001-T003）** → 阻塞全部。
- **Foundational（T004-T011）** → 阻塞所有 US（冻结常量 + `classify_tiers` 是共享核）。
  - T004（grounded 预测准备）→ T007 → T008；T005 取消；T006 先于 T007 实现；T009 先于 T010 → T011。
  - **T010 依赖 T008**：`tier_classify.py` import T008 生成的冻结常量 `tier_classify_constants.py`（T010 可写带回退默认的 import 以便先跑单测，正式值由 T008 覆写）。
- **US1（T012-T016）** = MVP，依赖 Foundational；T013→T014→T015→T016，T012 先行。
- **US2（T017-T019）** 依赖 Foundational + US1 的 `rescore_tiered.py`/serve 分层（复用同 harness/wiring，加断言）。
- **US3（T020-T022）** 依赖 US1 的 serve 分层（加 env 旋钮）。
- **Polish（T023-T026）** 依赖全部 US。

**US 间独立性**：US1 交付即 MVP（复核层召回回收 + serving 分层）。US2/US3 在同一 serve/harness 上加断言与旋钮，可独立测试各自 Independent Test。

## 并行机会

- Setup：T002、T003 并行。
- Foundational：T006（校准测试）与 T009（分层测试）并行；数据链 T004/T005 与测试编写并行。
- 各 US 内：`[P]` 测试任务先行，可与其他 US 的测试编写并行；实现任务串行（同文件 serve/app.py）。
- Polish：T023、T024、T025 并行。

## MVP 范围

**US1（T001-T016）** = 最小可交付：serving 产出分层响应 + 复核层把并集召回 0.764 surface 给人工，离线证明召回回收 ≥+5pt。US2（治理精度证明）、US3（阈可调/回滚）为增量。

## 格式校验

所有任务遵循 `- [ ] TID [P?] [US?] 描述 + 文件路径`：Setup/Foundational/Polish 无 Story 标签，US 阶段带 [US1/2/3]，含精确路径。
