# 063 召回回收 · 置信度分层复核信封 — 阶段结论（gold C / CV 去偏）

**日期**：2026-07-11 · **承接**：059（北极星达成，Run C ALL-p 0.684 / 召回 0.703；③ 语义 grounding 已接 serving）
**目标**：059 后唯一实质弱项=召回（3B 0.703 vs teacher 0.77-0.81）。把 052/054 置信度校准框架接进 serving，产出分层复核信封，回收召回、同时守自动入库精度。**约束：无新 GPU / 无付费 teacher / 无人工标注。**

## 召回天花板定界（探针实测，先定界防目标虚设）

gold C（61 非空脚本 / 148 真表，canon）：

| 召回来源 | 召回 |
|---|---|
| 模型（语义 grounding 后） | 0.703 |
| SQL-AST 通道单独 | 0.196 |
| **模型 ∪ SQL 并集（免费天花板）** | **0.764** |
| teacher（deepseek/qwen） | 0.77-0.81 |

**免费确定性手段召回天花板 ≈ 0.764，低于 teacher 带**——teacher 额外召回来自动态名/框架/配置驱动表，不付费/不重训够不着。故本特性不追 0.81，把 0.703 往 0.764 推、用分层守精度。

## ★核心结论：SC-001 达成——分层复核信封三方离线证明（thr=0.95）

| 方 | 模型独抽召回 | 自动∪复核召回 | 自动层 P（样本内） | 自动层召回 | 复核负载(候选/脚本) |
|---|---|---|---|---|---|
| **3B** | 0.703 | **0.764** | 1.000（n=7） | 0.047 | 2.16 |
| deepseek | 0.818 | 0.831 | 1.000（n=2） | 0.014 | 2.44 |
| qwen | 0.838 | 0.845 | 1.000（n=3） | 0.020 | 2.95 |

- **SC-001**：3B 自动∪复核召回 **0.764** vs 模型独抽 0.703（**+6.1pt 召回回收 surface 给人工复核队列**）。复核层把免费天花板的召回全部 surface，复核者从最可能对的先看（按 confidence 降序）。

## ★诚实核心：CV 去偏暴露自动层「治理严格」代价（SC-002）

无独立校准集（见下 R1）→ gold C 嵌套 CV 去偏。CV held-out 前沿（`out/calibrate-tiers.md`）：

| fit_thr | held-out precision | held-out recall | 采纳级集 |
|---|---|---|---|
| 0.99 / 0.97 | 1.000 | 0.047 | sql_qual |
| 0.95 | 0.786 | 0.149 | sql_qual + model_bare |
| 0.90 | 0.812 | 0.351 | + agree |
| **0.85** | **0.870** | **0.723** | + model_qual（真膝点） |

- **SC-002**：thr=0.95 自动层 CV held-out precision **1.000**（治理安全），但**只有 sql_qual（n=7，折间抖 0.75-1.0）、召回仅 0.047**。
- **★关键诚实**：样本内累计乐观（sql_qual+model_bare=0.922）**不泛化**——CV held-out 只 0.786。故任何 ≥0.90 治理阈下自动层 recall~0.05；**召回回收价值全在复核层**（US1），自动层是小而准的快路。
- CV 真膝点在 **0.85**（held-out precision 0.870、recall 0.723）——需要更大自动层的部署可 env 放宽到 0.85。

## R1 诚实披露：无独立非泄漏带标集 → gold C 嵌套 CV 去偏

clarify Q1 原定「校准冻结于独立测试集 A」，实测三连否定：
1. **测试集 A**（`real.jsonl`）已随 052/054 worktree 删除、不存在；
2. **pool-c-held**（162 条）经 chash 核对 **153 条 ∈ gold C**（就是 gold C 的池，非独立）；
3. **pool-c-train**（3000 条，与 gold C 源隔离）但**模型训练过**（model 层泄漏，precision 虚高）。

→ **无既独立于 gold C、又未被模型见过的带标集**（数据现实）。经用户确认退回 **054 已验证的 gold C 嵌套 CV 去偏**（clarify 原选项 B）：部署常量取全 gold C 点估计、报告精度取留一/k 折 held-out。诚实边界：CV 只校同分布偏置、不覆盖分布漂移，真·独立 fresh 集仍待凭据/预算。

## 交付

- 代码：`realeval/calibrate_tiers.py`（gold C 点估计+CV 去偏，emit 冻结常量）、`realeval/tier_classify.py`（model∪SQL canon 分层，纯函数）、`realeval/tier_classify_constants.py`（冻结）、`realeval/rescore_tiered.py`（三方离线证明）、`serve/app.py`（分层接进 serving，env 可配阈+回滚）。
- 契约：`/extract` 加 `reviewReads/reviewWrites`（每项 tier/confidence）+ `tiered`；`reads/writes`=自动采纳层。env `LINEAGE_AUTOACCEPT_MIN_PRECISION`（默认 0.95）/`LINEAGE_TIERING`（默认 1，置 0 逐字节回滚 059）。
- 测试：`test_calibrate_tiers.py`（5）+ `test_tier_classify.py`（9）+ `test_dir_fix_serve.py`（19，含 059 grounding/dir_fix 隔离 + US1/2/3 分层）。全量 ml **234 绿**，零回归。
- 报告：`out/calibrate-tiers.md`、`out/rescore-tiered.md`。

## 与 059 的关系

059 ③ 语义 grounding（+4.2pt 精度）已在 serving 前段（剔非表 FP）；063 在其后加分层（召回回收）。管线：模型 → 语义 grounding → dir_fix → 置信度分层。北极星（精度超 teacher）不变；063 补上召回维度的可部署信封。
