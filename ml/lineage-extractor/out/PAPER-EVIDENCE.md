# 065 论文头条陈述 → 证据项映射（FR-010：无裸数字比较）

每条进论文的头条/比较陈述都必须锚定到本表的一个可复现证据项。

## 数据来源诚实声明（★关键，先读）

原始 059/063 的人工仲裁 gold A/C（139/59、153/49）在 **2026-07-12 中午一次磁盘清空**中随
gitignored 目录一起丢失（HF 未上传真值、git 从未提交）。本轮**从零重建**了评测集 `realeval/gold/real-c.jsonl`：

- **重建管线**：`collect_stack`（the-stack-dedup 流式，ETL 筛，399 候选）→ `teacher_label --teachers m1,m3`
  （qwen-max + deepseek-v4-pro 跨厂商双 teacher）→ `build_gold_b --min-agree 2`（双 teacher 边级一致裁决）
  → 059 协议下采样空行至 empty_ratio 0.20 → **130 行 / 非空 104 / 空 26**。
- **性质**：这是 **teacher 共识 silver**，非原人工 gold；**非空 n=104 反而高于原 49**，直接缓解论文最初
  的 n≈49 统计功效硬伤。
- **与 059 已发布数的关系**：不同评测集，**不直接可比**；但重建集上 model-3b 非空 precision **0.743**
  与 059 发布的非空-p **0.742** 几乎重合 = 意外的跨集可复现性信号（可作旁证，不作头条）。
- **teacher 循环性**：gold 由 m1∩m3 派生，故 teacher 对该 gold 的 precision≈1.0 属**构造性循环**——
  这正是本文**不以"超 teacher"为头条**、改以泄漏科学为脊椎的根本原因（见下）。

| 头条陈述（脊椎/支撑） | 证据项 | 真实数字（本轮真跑） | 状态 |
|---|---|---|---|
| **[脊椎] 逐规模记忆泄漏 37→22→11%**（0.5/1.5/3B 逐字泄漏单调降） | `out/leak-curve.md` | 既有产物（gold-independent，不受磁盘清空影响） | 既有产物 |
| **[脊椎] 合成 0.99 vs 真实 0.24–0.32 塌缩** | `out/leak-curve.md` / `out/FINDINGS-059.md` | 既有产物 | 既有产物 |
| **[C2] 诚实去偏：CV 暴露样本内校准乐观（膝点 0.95→0.85）** | `out/FINDINGS-testset-b-cv.md` | 既有产物 | 既有产物 |
| **[C2] 每个指标带 95% CI** | `out/significance-c.md` | model-3b p 0.743[0.620,0.856]/r 0.832[0.750,0.900]/f1 0.785[0.700,0.859]，n=104 | ✅ 真跑 |
| **[C2] 3B vs teacher 诚实（不）显著** | `out/significance-c.md` | vs m1 precision Δ−0.023[−0.086,+0.039] **不显著**（McNemar p=0.096）；vs m3 Δ−0.123 显著更差 | ✅ 真跑 |
| **[支撑·动机] 工具在脚本子集 recall≈0，模型救回** | `out/baselines-c.md` | SQLLineage@script r **0.029**[0,0.121]；model-3b 0.588；Δr **+0.559**[+0.240,+0.811] 显著 | ✅ 真跑 |
| **[支撑] 工具即便在 SQL 上也弱** | `out/baselines-c.md` sql 子集 | SQLLineage@sql r 0.159；regex 0.539；model-3b 0.864（n=92） | ✅ 真跑 |
| **[C3·落地] 分层信封：召回回收价值在复核层** | `specs/063-recall-tiered-envelope` 既有产物 | 既有 063 serving/eval | 既有产物 |

## 消化 rigor 缺口
- **CHK005**（泄漏曲线作可复现图有证据锚点）→ 表首两行 + `significance-c.md` 顶部引用。
- **CHK006**（分层信封头条第三支有证据）→ C3 行锚定 063 既有产物。

## 可复现边界（呼应 benchmark/README 的 C1 界定）
- 逐字泄漏重算需原始训练语料，**不在 benchmark 发布范围**；泄漏曲线以预算好的分析产物在论文/附录给出。
- 本轮重建集（gold C′）的 CI、工具对照、3B-vs-teacher 均从发布 harness + `.env` 凭据可**原样复算**
  （collect→label→build_gold_b→dump_preds→significance/baselines 全脚本化，seed 固定）。
