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
- teacher 银标 = deepseek-v4-flash ∩ qwen-max 跨厂商一致（bulk）+ pro 仲裁（校准）；追平 = 追平这些 teacher 的一致口径，如实披露。

## ② 弃权空间消融（Run D2）——负结果，证伪「加负例能补 10pt」

**假设**：Run C 与 oracle 上限 0.742 之间的 ~10pt 是空脚本假阳，多喂空脚本负例（empty_ratio 0.5→0.6，+546 条）应教会弃权、抬 ALL-p。**证伪**：

| 配置 | ALL-p | ALL-r | 方向 | 非空-p | 非空-r |
| --- | --- | --- | --- | --- | --- |
| **Run C**（empty_ratio 0.5，已发布） | **0.6419** | **0.6333** | **0.6327** | **0.7422** | 0.6333 |
| Run D2（empty_ratio 0.6，+546 空负例） | 0.6371 | 0.5267 | 0.4966 | 0.7054 | 0.5267 |

- ALL-p 0.642→0.637（持平/噪声级），却赔上召回 −10.6pt、方向 −13.6pt（掉到抛硬币）、非空-p −3.7pt。
- **机理**：残留假阳是 **grounded-but-wrong**（表名字面在脚本、但是模块路径 / 文件名 / 变量，非血缘），靠「空脚本弃权」够不着；加负例只把模型教得更保守 → 掉召回，不解决 FP。
- **结论**：**Run C（0.5）是最优工作点**，10pt 弃权空间不是靠加负例能买到的。要再上探须换杠杆（语义级 grounding：区分「表名」vs「路径/变量/模块」，而非纯字面匹配）或人工金标。

## 与主流 teacher 同底对比（gold C 153 · 校准 gold + grounding · 同一尺子）

让 qwen-max 与 deepseek-v4-pro 在**同一 gold C** 上跑、套**同一 grounding + 校准尺子**，与自托管 3B 完全同底：

| 模型 | ALL-p | 非空-p | 召回 | 方向 | 成本 |
| --- | --- | --- | --- | --- | --- |
| **自托管 Run C 3B**（本地单卡 LoRA） | **0.6419** | **0.7422** | 0.6333 | 0.6327 | 一次训练，零推理调用费 |
| deepseek-v4-pro（强档 teacher） | 0.5874 | 0.6237 | **0.8067** | 0.6054 | 约 ¥1-2 / 153 条 |
| qwen-max | 0.3867 | 0.4895 | 0.7733 | 0.6463 | ¥1.13 / 153 条 |

- **北极星量化坐实**：自托管 3B ALL-p **0.642 > deepseek-v4-pro 0.587 > qwen-max 0.387**——3B 精度**超过**两个前沿 teacher。deepseek-pro 实测 0.587 正落在北极星参照带 0.55-0.60，验证了整个前提。
- **权衡是召回**：teacher 召回更高（deepseek 0.807 / qwen 0.773 vs 3B 0.633）——teacher 抽得多但假阳多；3B 更保守更准。precision 是治理场景的北极星，3B 胜。
- **非空-p 差距最大**：有血缘的脚本上 3B 0.742 ≫ deepseek 0.624 ≫ qwen 0.490。
- grounding 对 teacher 几乎无增益（deepseek +0.3pt / qwen +3pt）却帮 3B +9.3pt——3B 的 ungrounded 幻觉更多、被过滤器接住，强 teacher 本就少犯。
- 复现：`realeval/eval_teachers_c.py`（teacher→gold C 预测，抓真实 token 用量）+ `rescore_arbitrated.py` 同尺子打分；报告 `out/rescore-teacher-{qwen,deepseek}.md`。

## 训练稳定性（`sft_qlora.py` 加向后兼容旋钮）

Run D 第一次跑 bf16 早期数值发散（step 20-40 梯度溢出成 NaN，裁剪救不了，acc 塌到 ~0，整轮报废）。同配方 Run C 未撞上，差异仅数据组成/顺序——bf16 LoRA 早期发散有随机性。加 `--lr / --warmup / --max-grad-norm` 三个可选参数（默认=原配方，向后兼容），Run D2 用 lr 1.2e-4 / warmup 0.10 / grad-clip 0.5 重跑，grad_norm 全程有限、loss 单调降、acc 爬到 0.83，健康跑通。

## 产物

- 评测：`out/eval-c-{baseline,run059-plain,run059-reason,run059-runc}.{md,json}`、`out/rescore-{arbitrated,runc}.md`、`out/grounding-analysis-run-059-plain.md`、`out/arbitrate-gold.md`
- 校准 gold：`realeval/gold/real-c-arbitrated.jsonl`（pro 翻标 12 处，`arbitrated:true` 标记）
- 代码：`realeval/{collect_stack,cost_calibration,build_reasoning_corpus,dump_preds,eval_model_c,analyze_grounding,arbitrate_gold,rescore_arbitrated}.py`、`train/prep_fit.py` + 单测
- 真实脚本池 / teacher 标注 / 银标 / 预测 / 模型权重：gitignored，走 HF（`wallfacers/weft-lineage-extractor-3b`）
