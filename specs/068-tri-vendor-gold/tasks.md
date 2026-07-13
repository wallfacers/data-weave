# Tasks: 三厂商共识 gold + 全档重训（Tri-Vendor Consensus Gold）

**Feature**: 068-tri-vendor-gold | **Worktree**: `/home/wallfacers/project/dw-068-tri-vendor-gold`
**工作目录**: `ml/lineage-extractor` | **解释器**: `python3` | 长命令必 `setsid` 脱离 + `timeout` 参数

**约束**：TDD 先测后码；复用 067 迁入池/标注（只新增 GPT 标注）；fresh Qwen base 非 warm-start；独立命名不覆盖 065/067；真跑取证；全 pytest 绿零回归。

---

## Phase 1: Setup

- [X] T001 校验 067 迁入资产就位：`ls realeval/pool-c realeval/pool-silver realeval/teacher_labels-c/{m1,m3}.jsonl realeval/teacher_labels-silver/{m1,m_flash}.jsonl realeval/gold/real-c.jsonl data/silver-col.jsonl out/run-col-3b-mit/merged weights/weft-lineage-extractor-3b`（全在=可复用）。
- [X] T002 校验 `.env` GPT 凭据（仅变量名）：`grep -oE '^GPT_[A-Z_]+=' .env` → `GPT_API_KEY/GPT_BASE_URL/GPT_MODEL`；绝不打印 key 值。
- [X] T003 更新 `.gitignore` 加 068 新工件忽略：`realeval/gold/real-c-tri*.jsonl`、`data/silver-tri.jsonl`、`out/run-tri-*/`、`realeval/teacher_labels-{c,silver}/m_gpt.jsonl`、`out/preds/run-tri-*.jsonl`。

---

## Phase 2: Foundational（阻塞所有 US——gold/silver 都要 GPT 标注）

**TDD：先测后码。**

- [X] T004 [P] 写 `tests/test_gpt_backend.py`（mock httpx）：契约 5 条（无 stainless 头 / 解析 JSON+usage / 非200 弃权 / 解析失败弃权 / 无 key 不注册），见 `contracts/gpt-client.md`。先红。
- [X] T005 在 `llm/clients.py` 加 `_openai_raw_backend(base_url_env, key_env)`（httpx 裸 POST，不注入 stainless 头，不传 temperature，抓 usage）+ 注册 `m_gpt`（GPT_MODEL 默认 gpt-5.6-sol）与 `m_gpt_bulk`（gpt-5.6-luna），仅当 GPT_API_KEY 存在。跑绿 T004。
- [X] T006 真跑冒烟复核：`python3 -c` 走 `load_clients()['m_gpt'].extract(...)` 对 1 条 SQL，确认真返回含列血缘 + usage（非 mock）。

---

## Phase 3: User Story 1 - 三厂商共识 gold + 重评（破循环 G1）(P1) 🎯 MVP

**目标**：无需重训即交付破循环第一个数字。**独立测试**：产出 real-c-tri + 一致率 + 现有模型在其上 P/R。

- [X] T007 [P] [US1] 写 `tests/test_tri_consensus.py` gold 部分（mock 三 teacher 标注）：契约 6 条（三家全同→agree=3 / 两同一异→2-of-3 不入 3-of-3 / 仅一家→不入 / 一家弃权→列弃权 / 多数列裁决 / 通配→列弃权），见 `contracts/consensus.md`。先红。
- [X] T008 [US1] 扩展 `realeval/build_gold_b.py`：支持 `--teachers m1,m3,m_gpt --min-agree 2`，表级 min-agree=2 裁决 + 列级共识表上多数/交集弃权优先 + 附产 3-of-3 子集（`consensus.agree.table==3`）。跑绿 T007。
- [X] T009 [US1] GPT 标 gold 池（真跑，sol，~400 条，setsid 脱离 + timeout）：`teacher_label --pool realeval/pool-c --teachers m_gpt --out realeval/teacher_labels-c/m_gpt.jsonl`；轮询完成。
- [X] T010 [US1] 造三厂商共识 gold（真跑）：`build_gold_b --teachers m1,m3,m_gpt --min-agree 2 --columns --out realeval/gold/real-c-tri.jsonl`（+ real-c-tri-unan.jsonl）；核对非空/具体列实例计数。
- [X] T011 [P] [US1] 写 `realeval/agreement_report.py` + 真跑：GPT vs 067 gold（real-c）表级/列级一致率 → `out/agreement-068.md`（FR-004）。
- [X] T012 [US1] 重评现有模型（真跑）：dump preds + `significance_report` 让 model-3b（weights/weft-lineage-extractor-3b）与 run-col-3b-mit 在 real-c-tri 上出表级+列级 P/R + bootstrap CI → `out/significance-tri-c.md`（SC-001/002，破循环首个数字）。

**✅ US1 检查点**：real-c-tri + unan 产出；一致率报告；现有模型重评数字。破循环 MVP 可交付。

---

## Phase 4: User Story 2 - 2-of-3 共识 silver + 全档重训（真涨点 G2）(P2)

**目标**：模型同时高 P 高 R。**独立测试**：run-tri-3b 表 P≥0.78/R≥0.75、列 P≥0.78/R≥0.82，门② 不显著退化。

- [X] T013 [P] [US2] 扩 `tests/test_tri_consensus.py` silver 部分：2-of-3 多数边数 ≥ 2-of-2 交集（同池）；exclude-gold 后与 gold chash 交集空。先红。
- [X] T014 [US2] 扩展 `realeval/build_silver.py`：支持三 teacher `--pair m1,m_flash,m_gpt --min-agree 2 --keep-columns`（2-of-3 多数，表+列，延续 067 列弃权优先）+ `--exclude-gold`。跑绿 T013。
- [X] T015 [US2] GPT 标 silver 池（真跑，luna 便宜档，~3000 条，setsid + timeout，最慢一步）：`teacher_label --pool realeval/pool-silver --teachers m_gpt_bulk --out realeval/teacher_labels-silver/m_gpt.jsonl`；轮询完成。
- [X] T016 [US2] 造 2-of-3 共识 silver（真跑）：`build_silver --pair m1,m_flash,m_gpt --min-agree 2 --keep-columns --exclude-gold realeval/gold/real-c-tri.jsonl --out data/silver-tri.jsonl`；核对边数 vs 067 silver-col（应多边）。
- [~] T017 [US2] 重训 run-tri-3b（真跑，fresh Qwen2.5-Coder-3B base + mit r32/e3，setsid ~2hr，轮询）：`sft_qlora --data data/silver-tri.jsonl --base-model Qwen/Qwen2.5-Coder-3B-Instruct --lora-r 32 --lora-alpha 64 --epochs 3 --out out/run-tri-3b`。
- [ ] T018 [US2] 评测 run-tri-3b 在 real-c-tri（真跑）：表级+列级 P/R + 门② McNemar（vs model-3b 与 run-col-3b-mit 同 gold）→ `out/significance-tri-3b.md`（SC-003/005）。
- [ ] T019 [US2] 校核 SC-003/005：表 P≥0.78/R≥0.75、列 P≥0.78/R≥0.82、vs 067 published McNemar 不显著退化；未达则如实记（不覆盖既有曲线）。

**✅ US2 检查点**：run-tri-3b 达标或诚实负结果；门② 判定。

---

## Phase 5: User Story 3 - held-out 厂商泛化 + scale + 治理路由（P3）

**目标**：破循环量化 + 涨点 stretch + 限制② 缓解。

- [X] T020 [P] [US3] 写 `tests/test_governance_routing.py`（mock）：分层计数正确 / auto∪review=全集 / 精度只在 auto 层算，见 `contracts/metrics-orthogonality.md`。先红。
- [~] T021 [US3] 重训 run-tri-05 与 run-tri-15（真跑，fresh Qwen 0.5B/1.5B base + mit 配方，setsid，轮询）：`sft_qlora ... --base-model Qwen/Qwen2.5-Coder-{0.5,1.5}B-Instruct --out out/run-tri-{05,15}`。
- [ ] T022 [US3] scale 曲线（真跑）：三档 run-tri-{05,15,3b} 在 real-c-tri 表级 f1 单调 + 列级 → `out/significance-tri-scale.md`（SC-007）。
- [X] T023 [P] [US3] 写 `realeval/heldout_vendor_eval.py` + 真跑：GPT 独立确认边子集上 run-tri-3b 表级/列级 P/R → 报告（FR-009/门③/SC-006）。
- [ ] T024 [US3] 写 `realeval/governance_routing.py` + 真跑（跑绿 T020）：real-c-tri 按 `consensus.agree.table` 分 auto(3-of-3)/review(分歧) → auto 层模型精度 + 分歧占比 + 接 063 分层信封 → `out/governance-routing-068.md`（FR-015/SC-011/限制②）。
- [ ] T025 [US3] 校核 SC-004（3-of-3 gold 表 P≥0.80 且召回不降）+ SC-006 + SC-011；诚实记 scale/涨点方向。

**✅ US3 检查点**：held-out 泛化数字 + scale 曲线 + 治理路由报告。

---

## Phase 6: User Story 4 - 诚实台账 + 成本 + HF 收尾（P4）

- [ ] T026 [P] [US4] 成本核算（从 `teacher_labels-{c,silver}/m_gpt*.jsonl` 真实 usage）：GPT gold(sol)+silver(luna) 成本，合 067 复用零重标 → ≤¥100（SC-009），拆分可追溯。
- [ ] T027 [US4] 写独立证据台账 `out/PAPER-EVIDENCE-068.md`（**不碰 065/067 的**）：US1-US4 表 + 一致率 + 破循环论证 + 治理路由 + 成本 + 诚实边界（循环性降低不消除 / 限制①范围外 / 限制② 缓解非消灭人工复核）。
- [ ] T028 [US4] 更新 HF `wallfacers/weft-lineage-extractor-*` 模型卡说明（FR-016/SC-012）：三厂商共识可信度 + 限制② 治理路由缓解 + 限制①（动态名/注释/临时视图）仍为刻意排除的诚实边界。**对外操作，先给用户确认再推。**

**✅ US4 检查点**：台账 + 成本 + HF 卡（用户确认后）。

---

## Phase 7: Polish & 收尾

- [ ] T029 [P] 全 pytest 绿零回归：`python3 -m pytest -q`（含 test_metrics_columns.py 门① 回归必绿）。
- [ ] T030 [P] 更新记忆 `weft-068-tri-vendor-gold.md` + MEMORY.md 指针（真跑结果全录）。
- [ ] T031 [P] CLAUDE.md Knowledge Map 加 068 条目（三厂商破循环 + 治理路由 + 限制①②处置）。
- [ ] T032 合并回 main（守并发多 Agent 硬规则：先 `git worktree list` + 读 sibling、无冲突再合）+ push；HF 权重发布（run-tri-* + 保全 run-col-3b-mit，用户确认后）；`git worktree remove` dw-068。

---

## Dependencies & 并行

- **US 完成顺序**：Setup(P1) → Foundational(P2, GPT client 阻塞全部) → US1(P1) → US2(P2) → US3(P3) → US4(P4) → Polish。
- **US1 是 MVP**：仅需 Phase 1+2+3，无重训即交付破循环。
- **[P] 并行机会**：T004/T007/T011/T013/T020/T023/T026/T029/T030/T031 不同文件可并行；训练任务（T017/T021）串行占 GPU。
- **关键路径（时长）**：T015 silver 标注(~3000 条 GPT)→T016→T017 3B 训练(~2hr)→T021 05/15 训练→评测。gold 线（T009→T012）短、先出 MVP。

## Implementation Strategy

1. **先 MVP（US1）**：GPT client → 标 gold → 共识 gold → 重评 → **破循环首个数字**（半天，~¥25，无 GPU）。
2. **再涨点（US2）**：标 silver → 共识 silver → 3B 重训 → 门② 判定。
3. **补量化（US3）**：05/15 扩档 + held-out + 治理路由。
4. **收尾（US4+Polish）**：台账 + 成本 + HF 卡（用户确认）+ 合 main。
