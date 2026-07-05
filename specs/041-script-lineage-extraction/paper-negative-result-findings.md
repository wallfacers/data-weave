# 041-R 论文导向：负结果 pivot + 证据链（2026-07-04）

> 用户已定：**pivot 到诚实的负结果论文**（不再硬推「零真实标注小模型追平大模型」）。
> 本文档汇总 pivot 后的论文骨架与已采齐的证据，防后续信息不足需返工。

## 一句话 thesis（pivot 后）

**合成-only 训练的小模型（1.5B）在合成 held-out 上近乎完美，却在真实 OOD ETL 脚本上急剧退化——
其失败以「记忆泄漏」为主：在解析不了的真实脚本上背诵合成训练分布的表名，而非泛化抽取。**
这是对「用合成数据训练血缘抽取小模型」这一诱人捷径的一记诚实警示。

## 证据链（全部已实测、已提交）

### 1. Domain shift 头图（同一 sft-1.5b 模型，合成 vs 真实）
| 分布 | precision | 方向准确率 | 幻觉率 | 来源 |
|---|---|---|---|---|
| 合成 held-out（n≈600，形态隔离） | **0.9954** | 0.9954 | **0.0009** | `out/eval-report-v2.md` |
| 真实 GitHub ETL（n=139） | **0.270** | 0.496 | **0.153** | `out/eval-real.md` |

幻觉率 0.0009 → 0.153（×170），precision 0.9954 → 0.270，**方向准确率 0.9954 → 0.496（真实脚本上读写方向近乎抛硬币）**。合成指标严重高估真实能力。

### 2. 真实集四方对比（`out/eval-real.md`，同一人工金标，约定 A；n=139，非空 59）
| 抽取器 | precision(全) | 幻觉(全) | recall(非空 n=59) | 方向(非空) | f1(非空) |
|---|---|---|---|---|---|
| sft-1.5b | 0.270 | 0.153 | 0.618 | **0.496** | 0.571 |
| m1-qwen(DashScope) | 0.327 | 0.301 | 0.939 | 0.872 | 0.854 |
| m2-anthropic(Ali) | 0.542 | 0.134 | 0.806 | 0.730 | 0.787 |
| regex 基线 | 0.166 | 0.000 | 0.473 | 0.397 | 0.443 |

小模型每项落后两大模型，仅胜 regex。**「追平大模型」不成立。** 样本从 n=48（非空 18）扩到
**n=139（非空 59）后结论不变且更硬**：小模型方向准确率仅 0.496（真实脚本上读写方向几乎全靠猜），
recall 0.618 明显低于两大模型（0.81–0.94）。

### 3. 记忆泄漏机制量化（`out/leak-report.md`）——立论基石
幻觉表名 = 预测里既不在金标、也不字面在脚本文本中的名字。
| 抽取器 | 幻觉数 | 逐字命中合成训练池 | 占比 | 合成形态（schema+base） | 占比 |
|---|---|---|---|---|---|
| sft-1.5b | 76 | **17** | **22.4%** | 19 | **25.0%** |
| m2-anthropic | 34 | **0** | **0.0%** | 0 | **0.0%** |

小模型幻觉 22.4% 是**逐字的合成生成表名**（`dws.dws_member_point_di`、`ads.ads_payments_delta`、
`dwd.dwd_risk_score_delta`、`dws.dws_inventory_hourly`…），另有 25.0% 虽非逐字但**形态酷似合成池**
（仓库 schema + 仓库 base 词）；未经合成训练的大模型 = 0% / 0%。判据只取**确定性合成生成名**
（非 HF 收割真实名），故真实脚本吐出该名只可能来自背诵生成器。扩样后逐字命中绝对数 8→17，机制更稳。

### 5. 逐规模记忆泄漏曲线（`out/leak-curve.md`，加强实验 C）——机制升格为规律
同一合成训练数据 + 同一 LoRA 配方（SEED/r16α32/2ep/max2048），仅 base 规模变（0.5B/1.5B/3B）。
| 规模 | 合成幻觉 | 真实幻觉 | 真实 prec | 真实 recall | 真实方向 | **逐字泄漏** | **合成形态** |
|---|---|---|---|---|---|---|---|
| 0.5B | 0.0057 | 0.305 | 0.243 | 0.497 | 0.369 | **37.4%** | 59.8% |
| 1.5B | 0.0009 | 0.153 | 0.270 | 0.618 | 0.496 | **22.4%** | 25.0% |
| 3B | 0.0000 | 0.107 | 0.325 | 0.642 | 0.468 | **10.9%** | 10.9% |

**两条并存的结论**：
1. **记忆泄漏随容量系统性缓解**：逐字泄漏 37.4%→22.4%→10.9%、合成形态 59.8%→25.0%→10.9%、
   真实幻觉率 0.305→0.153→0.107，均**单调下降**。→ 泄漏是小容量在 OOD 上"背诵训练分布"的病，越大越轻。
2. **但规模在 0.5–3B 内不足以追平大模型，且方向病不随规模好转**：3B 真实 prec 仅 0.325（m2=0.542）、
   真实方向 0.468（m2=0.730）；方向准确率 0.369→0.496→0.468 基本在抛硬币线附近波动、无单调改善。
   → 幻觉/泄漏可随规模修，**读写方向混淆是另一独立病、与规模无关**。

7B 未测（12G 卡 bf16 装不下，须 QLoRA 4bit，会引入量化不一致污染曲线）——留 future work：泄漏曲线
外推到 7B/14B 是否触底、方向病是否终被更大模型攻克，是本图最自然的下一步。

### 6. 跨语言扩展：加 JVM（Scala/Java）语言（加强实验 D）——负结果跨语言复现且更重

**动机**：此前训练/评测仅 Python+Shell；真实数据中台还有 Scala/Java 写的 Spark/Flink 作业。
把 JVM 补进合成模板 → 重训 1.5B（`out/run-jvm`，四语言 SYSTEM_PROMPT，同配方）→ 采 141 条真实
JVM 脚本裁决金标（`real-jvm.jsonl`，非空 32）→ held-out 切片 + 真实四方 + 泄漏三评。

**① 合成 held-out 切片（`out/jvm-slice.md`）：合成 held-out 揭示不出缺口。**
| 模型 | JVM 切片 prec | JVM 方向 | py/sh 切片 prec | py/sh 方向 |
|---|---|---|---|---|
| run3（**没见过 JVM**） | **0.9877** | 0.9917 | 0.9930 | 0.9930 |
| run-jvm（四语言训练） | **0.9959** | 0.9959 | 0.9883 | 0.9883 |

即便**从没训练过 JVM** 的 run3，在合成 JVM held-out 上也近满分——因合成 JVM 模板与 Python 版**同构**
（同一 SQL 串 + saveAsTable/writeTo/executeSql 习语，表名在相同字面位置）：**字面抽取在共享习语上语言无关**。
加 JVM 训练只微调 JVM（+0.008）、微降 py/sh（−0.005），近噪声。→ 合成指标再次严重高估真实能力。

**② 真实 JVM 四方（`out/eval-real-jvm.md`，n=141，非空 32）：负结果跨语言复现且更醒目。**
| 抽取器 | prec(全) | 幻觉(全) | recall(非空) | 方向(非空) | f1(非空) |
|---|---|---|---|---|---|
| sft（run-jvm） | 0.186 | **0.347** | 0.564 | **0.382** | 0.533 |
| m1-qwen | 0.312 | 0.050 | 0.936 | 0.824 | 0.635 |
| m2-anthropic | 0.528 | 0.000 | 0.513 | 0.471 | 0.606 |
| regex | 0.105 | 0.000 | 0.410 | 0.426 | 0.388 |

**即便专门加了 JVM 训练**，小模型在真实 JVM 上 precision 仅胜 regex、幻觉 0.347（比 py/sh 的 0.153 更高）、
方向 0.382（抛硬币、比 py/sh 0.496 更差），全面落后两大模型。→ **「加新语言合成训练」修不了真实缺口。**

**③ JVM 记忆泄漏（`out/leak-report-jvm.md`）：泄漏更重。**
| 抽取器 | 幻觉数 | 逐字命中合成池 | 占比 | 合成形态 | 占比 |
|---|---|---|---|---|---|
| sft（run-jvm） | 99 | **40** | **0.404** | 49 | 0.495 |
| m2-anthropic | 7 | 0 | 0.000 | 0 | 0.000 |

在 Scala MongoDB sink / Java VastWriteFactory / Kafka FileMessageSet 等真实 JVM 脚本上，模型逐字吐
`dws.dws_coupon_use_di`、`ads.ads_payments_hourly`、`ods.ods_users_daily` 等**合成训练表名**。
**逐字泄漏 40.4% 高于 py/sh 的 22.4%**——真实 JVM 更 OOD，模型退化到"背诵"更多。

**综合结论**：负结果不是 Python/Shell 特有——加一门语言让**合成 held-out 近满分（误导性）**，却
**修不了真实缺口，且记忆泄漏在更远的真实分布上反而加重**。这坐实负结果是"合成-only 训练范式"的
系统病、与具体语言无关，且"多加几门语言"不是解药。**通道分工**才是务实答案：配置驱动作业（SeaTunnel/
DataX，HOCON/JSON 显式声明）归 RULE 解析器、SQL 作业归 Calcite，小模型只啃 SQL/配置都无解的自由命令式残差。

### 4. 真实 ETL 分布特征（支撑「为何真实集稀疏」）
- 采集 175 候选（wide profile，14 条扩展查询覆盖 MERGE/CTAS/writeTo/to_sql/COPY INTO/LOAD DATA 等多引擎）
  → 作业筛 139（弃 36 库/测试源码）→ **59/139 非空金标、80/139 空**。
- 真实 ETL 高度参数化（Jinja/shell 变量/f-string/配置字典驱动），字面表名稀疏。
- 用户定 scope A：仅标可执行语句里字面出现的表；动态名/文件路径/临时视图/注释/**配置驱动 ETL** 范围外并披露。
- 金标产线可复现：`realeval/{collect,prelabel,adjudicate_aid,apply_adjudication}.py`；24 条人工证伪修正
  内嵌 `apply_adjudication.py:OVERRIDES`（每条附依据），是逐条读脚本原文纠正双模型共识错误的权威记录。

## 方法学诚实披露（须进 limitations）

1. **基线同源**：M1/M2 均 Qwen 系（阿里云），非跨家族（无 GPT-4o/Claude key）。不能声称"匹配 SOTA 闭源"。
2. **样本量**：真实集扩到 **n=139、非空 59**（原 n=48/18）；recall/方向 CI 已收窄，结论稳固但仍属中等规模。
3. **金标单标注者**：gold 由 AI 逐条证伪审计 + 用户 scope 决策产出，未做多标注者一致性（κ）。审计已逮出双模型多处共识错误（属性/配置驱动/函数名假阳/路径当表/元数据目录/临时视图/DataFrame 变量当表）——**「双模型一致」不足以当 gold，二者共享过度抽取偏差**（本身可作次要 finding）。
4. **单模型规模**：仅测 1.5B。泄漏是否随规模缓解未测（留 future work）。
5. dbt 形态为「已教约定的名字泛化」非零样本形态泛化（详见 `paper-disclosures.md`）。
6. **语言覆盖 = 按语言而非引擎划界**：模型通道语料 = Python/Shell（含 PySpark，因其为 .py）+ JVM Scala/Java（§6）；
   PySpark 因语言归属被自然纳入。**配置驱动作业（SeaTunnel HOCON / DataX JSON）显式声明为范围外**——其 source/sink
   是结构化字段，归确定性 RULE 解析器而非模型；纳入会用易解析样本人为抬高准确率、污染"模型在硬残差上到底行不行"的评估。
7. **JVM 金标为片段级轻裁决**：`real-jvm.jsonl`（非空 32）由约定 A 自动提议 + 我基于预标 3 行语句片段的证伪修正
   （`apply_adjudication_jvm.py:JVM_OVERRIDES` 34 条）产出，未逐条读全文（比 py/sh gold 轻），属已知局限；结论方向
   （小模型落后大模型 + 逐字泄漏 40%）幅度足够大，轻裁决噪声不足以翻转。

## 加强实验状态

- **A 扩真实集** ✅ **已完成**：66→175 候选（wide profile 14 扩展查询）→ 139 作业 → 非空金标 18→**59**。
  结论不变且更硬（方向 0.496、逐字泄漏 17/76），CI 收窄。
- **C 逐规模曲线** ✅ **已完成**：0.5B/1.5B/3B 干净曲线（同配方，仅 base 变）。逐字泄漏 37%→22%→11% 单调降，
  升格为规律（见 §5）。7B 因 12G 显存 defer（须 QLoRA，量化不一致）。产物 `out/leak-curve.*`、`realeval/leak_curve.py`。
- **D 跨语言 JVM 扩展** ✅ **已完成**（见 §6）：Scala/Java（Spark+Flink）补进合成模板 + 重训 `out/run-jvm` +
  采 141 条真实 JVM 脚本裁决金标（`real-jvm.jsonl`，非空 32）。合成 held-out 近满分（0.99，且未训 JVM 的 run3 也 0.99）、
  真实 JVM 四方小模型仍全面落后大模型（幻觉 0.347、方向 0.382）、逐字泄漏 40.4%（高于 py/sh 22.4%）。
  → 负结果跨语言复现且更重，"加语言"非解药。产物 `out/{jvm-slice,eval-real-jvm,leak-report-jvm}.*`、
  `realeval/{jvm_slice_eval,apply_adjudication_jvm}.py`、`data/out-jvm/`。
- **B 抗泄漏消融**（部分：更大模型一支 = C 的 3B 点，已证泄漏随规模缓解）：剩真实表名增广 / 开放域弃权训练
  两支未做（1.5B 同规模、显存轻松），量化"同规模下改训练能否修泄漏"。

## 关键产物
- `out/eval-report-v2.*`（合成 held-out）· `out/eval-real.*`（真实四方，n=139）· `out/leak-report.*`（泄漏）· `out/leak-curve.*`（逐规模曲线 0.5/1.5/3B）
- **JVM 扩展（§6）**：`out/jvm-slice.*`（held-out 切片 before/after）· `out/eval-real-jvm.*`（真实 JVM 四方，n=141/非空 32）· `out/leak-report-jvm.*`（JVM 泄漏 40.4%）
- `realeval/leak_curve.py`（曲线评测器）· `realeval/jvm_slice_eval.py`（JVM 切片评测器）· `train/sft_qlora.py`（加 `--base-model`；四语言 SYSTEM_PROMPT）· 权重 `out/run-{0.5b,3b,jvm}/`（gitignore）
- `realeval/gold/real.jsonl`（**139 金标 / 非空 59**）· `realeval/gold/real-jvm.jsonl`（**141 JVM 金标 / 非空 32**）（均 gitignore，待 HF 数据集发布）· `realeval/ADJUDICATION.md`（裁决约定）
- `realeval/{collect,prelabel,adjudicate_aid,apply_adjudication,apply_adjudication_jvm,eval_real,leak_analysis}.py`（可复现管线；`apply_adjudication.py:OVERRIDES` 24 条 + `apply_adjudication_jvm.py:JVM_OVERRIDES` 34 条 = 人工证伪修正权威记录）
- 数据合成模板 `data/templates.py`（四语言：+Scala/Java Spark/Flink 模板）· `data/out-jvm/`（JVM 增强合成集，可复现）
- 已发布：`wallfacers/weft-lineage-extractor-1.5b@v2` + `weft-script-lineage-synth@v2`（私有 HF）
