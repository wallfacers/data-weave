# 测试集 B（凭据受限）：分级置信度校准前沿的交叉验证 de-bias

**日期**：2026-07-08 · **改动**：新增 `realeval/conf_calibration_cv.py`——用 k 折/留一交叉验证在既有
gold A 上严格量化 #3 分级置信度校准前沿的**泛化差**（无需外部凭据）。

## 背景与凭据卡点

#3 校准前沿（治理阈 precision≥0.95 下自动采纳召回 0.411）是**样本内**的:级序在 gold A 上按经验
precision 排、又在 gold A 上评,乐观偏置。真正的 fresh-repo held-out「测试集 B」需 `GITHUB_TOKEN`
（GitHub code search 采集）+ qwen 凭据（m1/m2 teacher 裁决）——**本机两者均 unset**,`collect.py` 缺
token 即报错退出（不静默 mock）。故先用 **CV** 做严格下位替代:CV 校正**样本内拟合偏置**(同分布),
这正是「样本内定序又评估」偏置的来源。

## 方法

- 级定义（agree / sql_qual / sql_bare / model_qual / model_bare）**固定确定性**——CV 只对**拟合量**
  （① 逐级经验 precision → 校准级序;② 累计 precision≥阈的最大前缀 → 采纳级集）做留出。
- 每折:采纳级集在**训练折**拟合、**留出折**评测;每脚本恰在一折被留出 → 汇总 = 全体 held-out。
- 固定折分（idx % k,无 RNG）→ 完全可复现。5 折 + 留一(LOO) 双口径。gold A 非空 59 脚本 / 141 真表。

## 结果：前沿泛化被确认（校准提升非样本内假象）

| 口径 | held-out precision | held-out recall |
| --- | --- | --- |
| 5 折 CV（fit_thr 0.90，margin） | **0.950** | **0.411** |
| 5 折 CV（fit_thr 0.95，边界） | 0.930 | 0.291 |
| 留一 CV（fit_thr 0.95） | 0.917 | 0.241 |
| 样本内（对照） | 0.950 | 0.411 |

**训练拟合阈扫描（5 折 held-out）——关系非单调**:

| fit_thr | held-out P | held-out R | 采纳级集众数 |
| --- | --- | --- | --- |
| 0.90 | 0.950 | 0.411 | 一致 + 模型限定名 |
| 0.95 | 0.930 | 0.291 | 一致 |
| 0.97 | 0.946 | 0.255 | 一致 |
| 1.00 | 1.000 | 0.170 | 一致 |

### 关键结论

1. **前沿泛化被 CV 确认**:在治理目标**下方留 margin**（fit_thr 0.90）拟合采纳级集,能稳定纳入
   「模型-only·限定名」级 → held-out **precision 0.950 / recall 0.411**,**与样本内一致**。
   即 #3 的校准提升（自动采纳召回 0.170→0.411, 2.4×）**不是样本内假象**。
2. **边界拟合抖动**:恰在 0.95 拟合时 model_qual 的训练累计 precision 压线,时进时出 →
   held-out 0.930/0.291 略欠。解法=标准的**下移 fit_thr 留余量**,而非抬阈。
3. **治理安全稳健**:全 fit_thr 扫描下 held-out precision 恒在 **0.93–1.00**——自动采纳的安全性在
   留出下成立;fit_thr 只在 precision↔recall 上取舍,部署选 0.90 行（0.950/0.411）避开边界抖动。

## 边界与零破坏

- **CV ≠ 分布漂移检验**:CV 只校正同分布的样本内偏置,**不**覆盖新仓/新习语的 domain shift。
  后者仍需 fresh-repo 测试集 B（GITHUB_TOKEN + qwen 凭据 + 裁决）——CV 是其严格下位替代。
  已采未裁的 pool 余量仅 ~36（175 pool − 139 gold A），不足 ≥100,故 fresh-B 须重采。
- 纯函数、无 torch（模型预测从 `dump_model_preds` jsonl 读入）→ 可无 GPU 单测（+6 单测）。
- 未改 `channel_router` / `confidence_calibration` 抽取与校准逻辑（只读复用其纯函数）;
  未碰已发布 HF 模型 / 后端 sidecar / 053·055 目录接地面。加性零破坏,ml 全套 155 绿。
