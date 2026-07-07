# Contract: 验收门与评测报告（EvalReport）

达标判定的唯一契约。消费者：维护者（读报告拍板）+ 发布脚本（gate_pass 触发 swap/改卡）。

## 验收门（严格全过，仅卡 3B 交付主体，在测试集 B）

| SC | 指标 | 门槛 | m2 基线 | 现状 sft |
|---|---|---|---|---|
| SC-001 | recall(非空) | ≥ 0.80 | 0.806 | 0.62 |
| SC-002 | 方向(非空) | ≥ 0.73 | 0.730 | 0.50 |
| SC-003 | 幻觉率(全) | ≤ 0.15 | 0.134 | 0.15/0.35 |
| SC-004 | precision(全) | ≥ 0.50 | 0.542 | 0.27 |
| SC-005 | 逐字泄漏 | ≈ 0 | — | 22%/41% |

`gate_pass = SC-001 ∧ SC-002 ∧ SC-003 ∧ SC-004`（**同时**满足）；SC-005/006/007 为发布前置硬护栏。合成分数**不参与**判定。

## 报告必含项（FR-017/018 诚实护栏）

```md
# EvalReport（distill-3b, 测试集 B, canon 口径）

## 四方对比
| 抽取器 | precision(全) | 幻觉率(全) | recall(非空) | 方向(非空) | f1(非空) |
| distill-3b (系统: 模型+dir_fix) | … |
| distill-3b (模型独跑)           | … |   ← 不藏 AST 拐杖
| m1-qwen | … | m2-anthropic | … | regex | … |

## 泄漏审计（leak_analysis.py --train-pool 真实银标名池）
逐字背诵率 = …%（期望 ≈0）

## 污染审计
train ∩ test(A∪B) content-hash 重叠 = 0

## 披露
teacher = Qwen 系（m1 qwen-max / m2 qwen3-max 兼容端点）；"追平 m2"=追平同源 teacher。

## 判定
gate_pass = <true|false>（SC-001~004 同时满足）
```

## 判定后动作（FR-019/020）

- `gate_pass=true` → MAY 改写 HF 模型卡为生产卡+真实数字、swap 后端 `MODEL_DIR`；现有产物在此前保持原样。
- `gate_pass=false` → 判未达标，触发升级路径（难例挖掘第二轮 → 7B QLoRA → dir_fix 调参），**不**改卡、**不** swap；重训重评。

## 复用 harness

- `eval_real.py`（py/sh 四方）+ `jvm_slice_eval.py`（JVM 切片）→ four_way / system vs model-only。
- `leak_analysis.py --gold <B> --train-pool <silver 名池>` → verbatim_leak。
- content-hash 比对脚本 → contamination_overlap。
- `eval/metrics.py` canon 口径（点分尾段匹配，粒度真错不放水）。
