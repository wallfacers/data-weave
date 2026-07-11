# 065 论文头条陈述 → 证据项映射（FR-010：无裸数字比较）

每条进论文的头条/比较陈述都必须锚定到本表的一个可复现证据项。空白证据文件在对应脚本真跑后生成。

| 头条陈述（脊椎/支撑） | 证据项 | 产出脚本 | 状态 |
|---|---|---|---|
| **[脊椎] 逐规模记忆泄漏 37→22→11%**（0.5/1.5/3B 逐字泄漏单调降） | `out/leak-curve.md` | 既有 `leak_analysis.py`（041/047/059） | 既有产物 |
| **[脊椎] 合成 0.99 vs 真实 0.24–0.32 塌缩** | `out/leak-curve.md` / `out/eval-c-*.md` | 既有 eval + `leak_analysis.py` | 既有产物 |
| **[C2] 诚实去偏：CV 暴露样本内校准乐观（膝点 0.95→0.85）** | `out/conf-calibration-cv.md` | 既有 `conf_calibration_cv.py` | 既有产物 |
| **[C2] 每个指标带 95% CI** | `out/significance-c.md` | `realeval/significance_report.py`（T006） | 待真跑（需 preds） |
| **[C2] 3B vs teacher 在 n≈49 下（不）显著** | `out/significance-c.md` McNemar/diff-CI | `realeval/significance_report.py` | 待真跑 |
| **[支撑·动机] 工具在脚本子集 recall≈0，模型救回** | `out/baselines-c.md` SC-003 裁决 | `realeval/eval_baselines_c.py`（T009） | 待真跑（工具部分 CPU 可即跑） |
| **[C3·落地] 分层信封：召回回收价值在复核层** | `specs/063-recall-tiered-envelope` 既有产物 + `out/*envelope*` | 既有 063 serving/eval | 既有产物 |

## 消化 rigor 缺口
- **CHK005**（泄漏曲线作可复现图有证据锚点）→ 本表前两行 + `significance-c.md` 顶部引用。
- **CHK006**（分层信封头条第三支有证据）→ 本表 C3 行锚定 063 既有产物。

## 可复现边界（呼应 benchmark/README 的 C1 界定）
- 逐字泄漏重算需原始训练语料，**不在 benchmark 发布范围**；泄漏曲线以预算好的分析产物在论文/附录给出。
- 其余（CI、工具对照、真实 vs 合成 eval）从发布 harness 无凭据可复算。
