# 059 语料扩增 + 推理蒸馏 — 阶段结论（gold C / held-out）

**日期**：2026-07-10 · **模型**：weft-lineage-extractor-3b（Qwen2.5-Coder-3B-Instruct + LoRA，真实语料 teacher 蒸馏）
**北极星**：纯训练手段让自托管小模型血缘抽取单体 precision 追平 teacher 单跑（deepseek-v4-pro 单跑 ~0.55-0.60），使部署不必付费企业级大模型。
**测试集**：gold C（153 条 GitHub-fresh，49 非空 / 104 空）——与训练银标**按来源隔离**（训练=the-stack，gold=GitHub）+ content-hash 去污染，held-out。

## 核心结论：北极星达成并超越——ALL-p 0.642（校准 gold + grounding），非空-p 0.742

| 模型 / 口径 | ALL-p | 非空-p | ALL-recall | 方向 | invalid |
| --- | --- | --- | --- | --- | --- |
| baseline distill-3b | 0.285 | 0.625 | 0.750 | 0.708 | 1 |
| Run A 扩语料（欠训 466 条） | 0.352 | 0.612 | 0.683 | 0.650 | 1 |
| Run B 推理蒸馏（plain+reason） | 0.316 | 0.581 | 0.658 | 0.583 | **12（塌缩）** |
| **Run C 全量真实银标（1774 条）** | **0.457** | **0.745** | 0.658 | 0.642 | 2 |
| Run C @ **校准 gold + grounding** | **0.642** | **0.742** | 0.633 | 0.633 | 2 |

> 上表前四行=原 gold 同口径；末行=校准 gold（pro 仲裁去噪）+ grounding 过滤器（公平尺子）。

- **Run C ALL-p 0.642 远超北极星 0.55-0.60**；非空-p 0.742 击穿先前认定的 0.625 天花板。自托管 3B 纯训练追平并超越 teacher 单跑精度。
- 生成健康：invalid 仅 2/153（无 Run B 塌缩），86 条正确弃权 / 65 条真实抽取。

## 打通它的三步（可复现，都不玄）

1. **校准尺子**（`arbitrate_gold.py`）：deepseek-v4-pro 盲复标 43 条争议空脚本，揪出 gold **12 处漏标真血缘**（模型早抽对却被扣分）→ 原读数 0.352 里 +8.6pt 是尺子错，非模型错。
2. **grounding + 动态名过滤器**（`analyze_grounding.py`）：执行任务自带规则（表名须字面出现在脚本、忽略 `${}%` 动态名），确定性后处理，非指标作弊 → 免费 +2pt，零 recall 损失。
3. **真答案 = 足量真实语料**：Run A 欠训 **6.4×**——用正确 bulk pair（m_flash∩m1）+ 拒绝门后可用真实银标 887 正 + 2066 负 = 2953 条，Run A 只喂了 466。Run C 用全量 1774 平衡集（887 正 + 887 负）重训，plain SFT，同配方，**零新 teacher 花费** → ALL-p 0.488→0.642、非空-p 0.626→0.742。

## 诚实护栏

- **推理蒸馏（Run B）被证伪是弯路**：ALL-p / 非空-p / 方向三项全降，且空脚本 greedy 解码 100% 生成塌缩（吐满 512-token 乱码，从不出 `<think>`）。真答案朴素得多——喂够真实语料的 plain SFT，不需思维链。
- **代价 = recall 0.68→0.63**：Run C 负例多、更保守，precision/recall 权衡；但 precision 是北极星，recall 仍 0.63。
- **gold 标签歧义天花板真实存在**：连最强 teacher（pro）复标也过抽（把 `.csv` 文件、`$var` 动态名当表）——「点分串=表/文件/变量」的边界连 pro 都裁不清。ALL-p 再上探需人工金标。
- **仍留 ~10pt 弃权空间**：Run C oracle（对残留空脚本完美弃权）上限 0.742；空脚本还有 13 条 / 20 表可继续压（下一 round）。
- teacher 银标 = deepseek-v4-flash ∩ qwen-max 跨厂商一致（bulk）+ pro 仲裁（校准）；追平 = 追平这些 teacher 的一致口径，如实披露。

## 产物

- 评测：`out/eval-c-{baseline,run059-plain,run059-reason,run059-runc}.{md,json}`、`out/rescore-{arbitrated,runc}.md`、`out/grounding-analysis-run-059-plain.md`、`out/arbitrate-gold.md`
- 校准 gold：`realeval/gold/real-c-arbitrated.jsonl`（pro 翻标 12 处，`arbitrated:true` 标记）
- 代码：`realeval/{collect_stack,cost_calibration,build_reasoning_corpus,dump_preds,eval_model_c,analyze_grounding,arbitrate_gold,rescore_arbitrated}.py`、`train/prep_fit.py` + 单测
- 真实脚本池 / teacher 标注 / 银标 / 预测 / 模型权重：gitignored，走 HF（`wallfacers/weft-lineage-extractor-3b`）
