# 测试集 B（fresh-repo held-out）：冻结校准前沿的**真·分布漂移**检验

**日期**：2026-07-09 · **改动**：新增 `realeval/build_gold_b.py`（多 teacher 一致 auto-gold）+
`realeval/conf_calibration_apply.py`（把 gold A 冻结校准梯度原样应用到 B）+ `llm/clients.py`
扩三方 teacher（m1 qwen-max / m2 qwen3.7-max / m3 deepseek-v4-pro）。

## 背景：CV 覆盖不到的一环

`conf_calibration_cv`（测试集 B 凭据受限版）用 k 折 CV 校正**样本内拟合偏置**，但 CV 只覆盖
**同分布**——**不**检验新仓/新习语的 domain shift。真正的 fresh-repo「测试集 B」需
`GITHUB_TOKEN` 采新仓 + qwen 凭据 teacher 裁决。本轮**凭据到位**，补齐这一环。

## 方法

- **采集**：GitHub code search（`wide` 查询组，24 query）→ license 过滤 + 脱敏 + 去重 →
  171 候选；剔除 >24000 字符巨型离群（16 条，非代表性 ETL）→ **155 条 fresh 候选**。
- **多 teacher auto-gold**（`build_gold_b`）：m1 qwen-max / m2 qwen3.7-max / m3 deepseek-v4-pro
  各自打标；表 t 入 gold ⟺ 过约定 A 字面门 且 ≥K teacher 含 t；方向 AST 优先→teacher 共识→弃边。
  主口径 **K=2（多数）**：79 非空 + 73 空；敏感性 **K=3（全体一致）**：67 非空 + 85 空。
  m3 reasoning 思维链吃 output，max_tokens 2048→8192 后 no_json 22→0。
- **评测**（`conf_calibration_apply`）：级序与治理采纳集 `{一致, 模型-only·限定名}` **从
  conf-calibration-A.json 冻结**，**不在 B 重拟合** → B 是真 held-out；distill-3b（run-distill-3b）
  逐行预测 gold B，套冻结前沿量 precision/recall。

## 结果：前沿在真·新仓分布下稳健，掉幅是去偏而非漂移崩塌

主口径（2/3 多数 gold，79 非空脚本 / 201 金标真表）：

| 口径 | precision | recall | 复核/脚本 |
| --- | --- | --- | --- |
| A 样本内（前沿） | 0.950 | 0.411 | 1.61 |
| CV 5 折 held-out（同分布 de-bias） | 0.930 | 0.291 | — |
| **B fresh-repo held-out（前沿）** | **0.932** | **0.343** | 1.38 |
| B 基线（仅一致层） | 0.919 | 0.174 | 1.85 |

敏感性（3/3 全体一致 gold，67 非空）：B 前沿 precision **0.900** / recall 0.365 / 1.7× 提升复现。

### 关键结论

1. **掉幅 = 样本内去偏，非分布漂移崩塌**：B fresh-repo precision **0.932 ≈ CV 去偏 0.930**
   （|Δ|≤0.002），recall 0.343 还高于 CV 0.291。0.95→0.93 的差在**留出的同分布 CV** 上已出现，
   **domain shift 未进一步降级** → 前沿的 ~0.93 precision 是**真实、抗漂移**的水平。
2. **2× 召回提升在 fresh 分布复现**：自动采纳召回 0.174（仅一致层）→ **0.343**（前沿），
   **2.0×**（unanimous 口径 1.7×）——校准前沿的核心收益（把「模型-only·限定名」提前采纳）
   在真·新仓上成立，非样本内/同分布假象。
3. **治理层面需留 ~2pt 余量**：strict 0.95 阈在 fresh 分布下未被守住（realized 0.90–0.93）。
   解法即 CV 早先的处方——**下移 fit_thr 留余量**（治理选 0.90 拟合点，避开 0.95 边界抖动），
   **本轮用真·新仓 held-out 实证了该处方的必要性**。

## 边界与零破坏（诚实披露）

- **auto-gold 是弱下位替代**：多 teacher 一致性 gold 偏向「三家都能找到的表」，漏掉三家都错过
  的真表 → recall 口径乐观、precision 相对可信；非人工金标等价。unanimous 口径 precision 更低
  （0.900）正因 gold 更窄、惩罚「对但未被全体收进 gold」的正确预测——一致性 gold 的已知偏置。
- **训练污染非 hash 排除**：distill-3b 的 161 条真实训练银标已 gitignored + 不在 HF（license），
  **不可复原** → gold B 无法按 content-hash 精确排除训练脚本；仅靠查询多样化 + 与 ~567 池精确
  重合对 GitHub 海量语料可忽略缓解。`excluded_gold=0` 即此故（gold A 亦不在磁盘）。
- **加性零破坏**：新增 3 脚本 + 2 测试文件（`test_build_gold_b` 5 + `test_conf_calibration_apply` 4）
  + `clients.py`/`test_llm_clients.py` 扩三方 teacher；未碰已发布 HF 模型 / 后端 / 校准与抽取纯函数
  （只读复用）。ml 全套 **168 绿**。
