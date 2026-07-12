# 065 T014：auto-gold 扩容（US4，稳健性 / teacher 派生）

## 扩容规模
- base gold C′：130 行（非空 104 / 空 26）
- 新采集 the-stack 候选：258（尾部 the-stack 流式库 segfault，数据完整）
- m1∩m3 一致裁决（min-agree=2）+ 排除 gold C content-hash 重合后新样本：172 行（**非空仅 10** / 空 162）
- 维持 empty_ratio≈0.20 保留新样本：12（非空 10 / 空 2），全部打 `robustness_only=true`
- **扩容后合并集**：142 行（**非空 114** / 空 28）→ `realeval/gold/real-c-expanded.jsonl`

> **诚实标注（FR-009）**：新增样本全部带 `robustness_only=true`，系 m1∩m3 teacher 派生、**非独立真值**；
> 与 gold C′ 同源（the-stack）同构造（同 ETL 习语门 + 同 m1∩m3 teacher 对 + min-agree=2），
> 故 CI 变化属"样本更多"而非分布漂移。仅作稳健性证据，论文中须显式声明。

## SC-006 验证：非空 104→114，95%CI 宽度对比

| predictor / metric | base(n=104) | exp(n=114) | Δ宽度 |
|---|---|---|---|
| model-3b / precision | 0.236 | 0.232 | **−0.004 收窄 ✅** |
| model-3b / recall | 0.150 | 0.153 | +0.003 变宽 |
| model-3b / f1 | 0.159 | 0.169 | +0.010 变宽 |
| teacher-m1 / precision | 0.207 | 0.198 | **−0.009 收窄 ✅** |
| teacher-m1 / f1 | 0.134 | 0.131 | **−0.003 收窄 ✅** |
| teacher-m3 / precision | 0.108 | 0.135 | +0.027 变宽 |
| teacher-m3 / f1 | 0.062 | 0.081 | +0.019 变宽 |

（扩容后完整报告：`out/significance-c-expanded.md`；base：`out/significance-c.md`）

## 裁决与诚实结论
- **SC-006 字面达标**：至少一项关键指标 95%CI 收窄——model-3b precision（−0.004）、teacher-m1 precision（−0.009）/f1（−0.003）。
- **但边际且混合**：本 the-stack 批次 ETL 非空 yield 极低（258 候选 → 仅 +10 非空），样本增量不足以稳定收窄；
  model-3b recall/f1 与 teacher-m3 反因 10 条新非空样本的逐脚本方差略增而**变宽**。
- **这恰印证 US4 被标"可选 / 默认砍"的判断**：auto-gold 扩容受"teacher 都能找到的表"上界约束，边际收益递减，
  非头条依赖项。论文将其作为稳健性附录/脚注呈现，**不进头条**，并如实报告混合结果。
