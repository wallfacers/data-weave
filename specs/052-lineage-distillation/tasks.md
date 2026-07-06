# Tasks: 自训小模型血缘抽取达到生产可用（真实语料 teacher 蒸馏）

**Feature**: `052-lineage-distillation` | **Branch**: `052-lineage-distillation`

**Input**: [spec.md](./spec.md) · [plan.md](./plan.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/](./contracts/) · [quickstart.md](./quickstart.md)

工作目录均为 worktree `dw-052-lineage-distillation/ml/lineage-extractor/`（下称 ML 根）；后端路径为 `backend/dataweave-master/.../lineage/script/`。长命令按 [[wsl2-long-command-detach]] `setsid` 脱离 + 单次秒回轮询。GPU 训练/评测只读 GPU，不碰共享 PG/neo4j。

**测试策略**：新增管线模块（`teacher_label`/`build_silver`/`dir_fix`/sidecar 改造）须配 pytest 单测（CLAUDE.md「no test = not done」+ contracts 不变量）。既有评测 harness 作验收 harness。

---

## Phase 1: Setup（环境与数据资产）

- [ ] T001 从 `dw-041` worktree 复制 gitignore 数据资产到 ML 根：`.env`、`realeval/gold/`（含 real.jsonl 139 + real-jvm.jsonl 141）、`realeval/pool/`（175 候选起步）；确认 `.gitignore` 已覆盖 `.env`/`realeval/pool*`/`realeval/gold`（不入 git）
- [ ] T002 安装依赖：`pip install -r requirements.txt --break-system-packages`，确认含 sqlglot/peft/bitsandbytes/transformers/torch/fastapi/openai/anthropic/python-dotenv
- [ ] T003 [P] teacher 端点连通性冒烟：用 `llm/clients.py` 各调 m1/m2 一次样例脚本，确认返回 JSON 无鉴权错（配额/端点核验，写 `out/teacher-smoke.txt`）
- [ ] T004 [P] GPU 与 base 模型就绪核验：`nvidia-smi` 空闲 + `Qwen/Qwen2.5-Coder-3B-Instruct` 可下载/已缓存（12G bf16 装载预检）

---

## Phase 2: Foundational（阻塞所有 US 的共享底座）

**⚠️ 必须先于 Phase 3+ 完成**——被 US1 银标方向与 US3 审计（hash 工具）、US1 银标方向与 US2 sidecar（dir_fix 核心）共用。

- [ ] T005 [P] content-hash 工具 `realeval/hashutil.py`：脚本内容规范化 → sha256；去重 + train∩test 污染检测函数（data-model 实体 1/4，SC-006）
- [ ] T006 [P] `tests/test_hashutil.py`：同内容同 hash、空白/换行规范化、集合重叠检测正确
- [ ] T007 从 `realeval/channel_router.py` 抽取 dir_fix 核心为可复用模块 `realeval/dir_fix.py`：表集取模型、SQL 可识别表方向取 sqlglot AST；**移植 050 健壮性补丁**（片段窗封顶 800、跳 Jinja/`$CONDITIONS` 模板标记、`update\s+\w+\s+set` 排除 MERGE 内裸 UPDATE、逐片段 SIGALRM 限时）（research R6，被 US1+US2 共用）
- [ ] T008 [P] `tests/test_dir_fix.py`：dir_fix 策略（非 override）、方向修正正确、畸形/超大片段不回溯爆内存、超时片段跳过

**Checkpoint**：hash 工具 + dir_fix 核心单测绿，US1/US2/US3 可并行推进各自阶段。

---

## Phase 3: User Story 1 - 真实语料蒸馏出达标的自托管模型（Priority: P1）🎯 MVP

**Goal**：采真实语料 → 双 teacher 打标 → 高精银标 → 从干净 base 蒸馏 3B → 在 held-out 测试集 B 上严格全过验收门。

**Independent Test**：跑完 T009–T021，读 `out/eval-distill-b.md` 的 `gate_pass`：recall≥.80 ∧ 方向≥.73 ∧ 幻觉≤.15 ∧ precision≥.50，且 verbatim_leak≈0、污染=0。

### 数据管线

- [ ] T009 [US1] 扩采训练语料 `realeval/collect.py --profile wide --profile jvm --target 8000 --out realeval/pool`；用 T005 hash 工具**剔除测试集 A/B 候选**（FR-001/002）
- [ ] T010 [US1] 新增双 teacher 打标器 `realeval/teacher_label.py`：逐候选调 m1+m2 → `realeval/teacher_labels/{m1,m2}.jsonl`；content-hash 键**本地缓存 + `--resume` 续跑**（FR-003，data-model 实体 2，Edge Case 中断/限速）
- [ ] T011 [P] [US1] `tests/test_teacher_label.py`：缓存命中不重复调用、`--resume` 跳已标、`_error` 样本不污染产出
- [ ] T012 [US1] 新增银标构建器 `realeval/build_silver.py`：**交集为主 + 分歧经字面门救回**；字面子串硬门滤幻觉；方向 AST 优先、缺失取 teacher、方向分歧不可 AST 定弃边；**零合成名**；空样本 ~20% 配比；hash 去污染 → `data/silver.jsonl`（FR-004/005/006，contracts/silver-label.schema）
- [ ] T013 [US1] `tests/test_build_silver.py`：字面门（表名⊆content）、零合成名（∩templates 合成池=∅）、污染护栏（∩gold=∅）、配比 [0.15,0.25]、方向来源标注正确（contracts 5 不变量）
- [ ] T014 [US1] 组装训练集：`data/` 下把 `silver.jsonl` 转 SFT 格式（4 语言 SYSTEM_PROMPT，复用既有组装逻辑），产出 `data/out/train-distill.jsonl`

### 训练

- [ ] T015 [US1] 蒸馏 3B（**验收门交付主体**）：`train/sft_qlora.py --base-model Qwen/Qwen2.5-Coder-3B-Instruct --data data/out/train-distill.jsonl --out ../../../weft-lineage-weights/run-distill-3b`（从干净 base，SEED/r16α32/2ep/max2048，~61min，setsid 脱离）（FR-008/009，data-model 实体 5）
- [ ] T016 [P] [US1] 蒸馏 1.5B（消融对照，不设门）：同配方 `--base-model Qwen/Qwen2.5-Coder-1.5B-Instruct --out ../../../weft-lineage-weights/run-distill-1.5b`

### 测试集 B（达标唯一判据，非空≥100）

- [ ] T017 [US1] 采集测试集 B 候选：`realeval/collect.py --profile wide --target 3000 --out realeval/pool-b --exclude realeval/pool`（teacher 未参与训练打标，与训练语料 hash 隔离）
- [ ] T018 [US1] 预标 + 证伪裁决：`realeval/prelabel.py` → `realeval/tolabel-b.jsonl`；agent 逐条约定 A 证伪初裁 + 维护者抽查终审 → `realeval/gold/real-b.jsonl`，**非空金标 ≥100**（FR-007/SC-009，data-model 实体 4）

### 评测与门（US1 独立测试锚点）

- [ ] T019 [US1] 四方评测（系统数=模型+dir_fix，模型独跑数）：`realeval/eval_real.py --gold realeval/gold/real-b.jsonl --model .../run-distill-3b/merged --dir-fix --out out/eval-distill-b.md`；JVM 切片用 `jvm_slice_eval.py`（FR-017，contracts/eval-gate）
- [ ] T020 [P] [US1] 逐字泄漏审计：`realeval/leak_analysis.py --gold real-b.jsonl --train-pool data/silver.jsonl --out out/leak-distill.md`（SC-005，自有池口径）
- [ ] T021 [US1] 污染审计 + 门判定：用 T005 工具证 train∩test(A∪B)=0 写 `out/contamination-audit.md`；据 eval-gate 契约计算 `gate_pass`（SC-001~004 同时满足），未达标记录并触发升级路径（FR-010/016，research R7）

**Checkpoint**：US1 交付「被证明达标（或明确未达标+升级方向）的 3B 权重 + 报告」。这是 MVP。

---

## Phase 4: User Story 2 - 蒸馏模型接进后端三通道并全自动服务（Priority: P2）

**Goal**：蒸馏 3B + dir_fix 进 sidecar，接后端既有三通道，2s 预算内全自动出方向可靠血缘。

**Independent Test**：sidecar 加载蒸馏权重，经 `ScriptLineageService` 提交真实脚本，返回边方向由 AST 校正、无字面表时输出空、畸形片段不挂、延迟符合预算。

- [ ] T022 [US2] 在 `serve/app.py` 集成 T007 的 `dir_fix`：`/extract` 模型推理后就地方向修正，响应加 `dir_fixed` 字段；`do_sample=False` 确定性解码（FR-012，contracts/sidecar-extract §1/2）
- [ ] T023 [US2] sidecar 健壮性接入：`/extract` 路径套用 T007 的片段窗/模板跳过/SIGALRM 兜底，畸形超大脚本在预算内返回（FR-013，contracts/sidecar-extract §3）
- [ ] T024 [P] [US2] `tests/test_dir_fix_serve.py`：dir_fix 策略非 override、弃权空输出、畸形片段不爆内存、确定性同输入同输出、**处理路径零外部 API 调用**（SC-007，contracts §2/3/4/5）
- [ ] T025 [US2] 配置接线：sidecar `MODEL_DIR` 指向 `run-distill-3b/merged`、后端 `application.yml` 的 `lineage.model.endpoint` 指 8500（既有键，Java 侧不改）（FR-014）
- [ ] T026 [US2] 后端接缝集成测试：经 `ScriptLineageService` 提交含嵌入 SQL 的自由脚本，断言返回边方向正确、确定性通道优先、模型只接残差（FR-014/015，Java JUnit，仅接缝不改内核）
- [ ] T027 [US2] 延迟实测 vs 2s 预算：3B merged 单脚本 + dir_fix 端到端计时写 `out/latency-3b.md`；超预算则按 R8 调优（MODEL 通道预算/8bit 提速/异步）并回填（SC-008）

**Checkpoint**：后端提交真实脚本 → 得到方向正确血缘，全自动无人工。

---

## Phase 5: User Story 3 - 诚实验收与加性发布（Priority: P3）

**Goal**：达标才宣布可用并改 HF 卡/swap 权重；没达标诚实迭代；全程加性零破坏。

**Independent Test**：生成四方对比+泄漏+污染报告 → 达标/未达标判定 → 仅 B 全过时触发 swap 与改卡。

- [ ] T028 [US3] EvalReport 汇编：整合 T019/T020/T021 为 `out/eval-distill-b.md` 终版，**同时含系统数与模型独跑数**、teacher 同源（Qwen 系）披露、`gate_pass`（FR-017/018，contracts/eval-gate 报告必含项）
- [ ] T029 [US3] `publish.py` 生产卡改写（**门控**）：仅当 `gate_pass=true`，把 HF 模型卡从「负结果/勿部署」改写为生产卡+真实 B 数字+四方对比+泄漏≈0；`--dry-run` 预览（FR-019）
- [ ] T030 [US3] 加性发布/回滚流程（**门控**）：达标 → swap 后端 `MODEL_DIR` + 推 HF；未达标 → 现有已发布产物与 sidecar 保持原样、走升级路径；新权重落 sibling（FR-019/020，data-model 状态流转）
- [ ] T031 [P] [US3] 未达标升级路径落地钩子：文档化并预留难例挖掘（对 B 错例定向补银标）→ 7B QLoRA（`--base-model ...-7B` + 4bit）→ dir_fix 策略调参 的重跑入口（FR-010，research R7）

---

## Phase 6: Polish & Cross-Cutting

- [ ] T032 [P] 全量 pytest 绿：ML 根 `pytest -q`，确认新增单测通过且**不回归 041 既有测试**（[[main-preexisting-red-tests]] 对照，非本特性红勿归因）
- [ ] T033 [P] 跑 quickstart 验证清单（7 项 SC 勾选），产出 `out/acceptance-summary.md`
- [ ] T034 [P] 更新 memory `weft-041-script-lineage.md`：052 蒸馏路径与达标/未达标结论、关键产物路径、升级路径状态
- [ ] T035 finishing-branch：与用户确认合并策略（外科式 delta patch 避 out/ 权重，参照 041-R 合并法）；`git worktree remove` 待合并后

---

## Dependencies & Execution Order

```
Phase 1 Setup (T001-T004)
   └─▶ Phase 2 Foundational (T005-T008)  ← 阻塞所有 US
          ├─▶ Phase 3 US1 P1 (T009-T021)  🎯 MVP
          │       └─▶ Phase 4 US2 P2 (T022-T027)   ← 最终集成需 US1 的 3B 权重
          │       └─▶ Phase 5 US3 P3 (T028-T031)   ← 需 US1 评测 + US2 服务
          └─▶ Phase 6 Polish (T032-T035)
```

- **US1 → US2**：US2 sidecar dir_fix 代码只依赖 Foundational（T007），但 T026/T027 最终集成/延迟测需 US1 的 `run-distill-3b`。
- **US1 + US2 → US3**：US3 判定/发布需 US1 评测报告 + US2 服务链。
- **US 独立性**：US1 是完整 MVP（不依赖 US2/US3 即交付达标模型）；US2/US3 建立在 US1 产物上。

## Parallel Opportunities

- Phase 1：T003 ∥ T004。
- Phase 2：T005/T006 ∥ T007/T008（不同文件）。
- US1：T011 ∥ T012 起步；T016（1.5B 对照）∥ T015（3B）**受单 GPU 串行约束**——实际顺序跑，非真并行；T020 ∥ T019 后处理。
- US3：T031 ∥ T028。
- Polish：T032/T033/T034 ∥。

⚠️ **单 GPU 硬约束**：T015/T016 及升级路径的 7B 训练**不能真并行**（12G 单卡），标 [P] 仅表逻辑独立，执行须串行排队。

## Implementation Strategy

- **MVP = US1（Phase 1-3）**：先拿到「达标或明确未达标+升级方向」的 3B 权重与诚实报告——这是整个特性成败的唯一硬结论，其余阶段建立其上。
- **增量交付**：US1 达标后再做 US2 服务集成、US3 加性发布；未达标则停在 US1 走升级路径，不推进发布（FR-020 零破坏）。
- **诚实优先**：任何阶段都同时报系统数与模型独跑数、只认测试集 B、合成分数不作判据（延续 041-R 底线）。
