# Phase 0 Research: 血缘小模型可投论文加固

所有决策以「不改既有打分逻辑 + 无凭据可复现 + 诚实不挑数据」为约束。基线事实来自对 `ml/lineage-extractor/` 的两轮探查（表级评测基建现状）。

## R1 — 指标置信区间：脚本级 bootstrap

- **Decision**: 对每个聚合指标（precision/recall/F1/方向准确率）用**脚本级 percentile bootstrap**：以脚本为重采样单位（有放回抽 N 个脚本的逐脚本 counts），每次重采样后用既有 `aggregate` 重新求和算指标，取 2.5/97.5 分位为 95% CI。默认 `n_resamples=10000`，固定 `seed` 保证确定性。
- **Rationale**: precision/recall 是比率指标（tp/(tp+fp)），不能对指标值直接做正态 CI；对**脚本**重采样再重聚合正确传播了"少量脚本主导"的不确定性。percentile 法实现最简、无需分布假设、对小样本（n≈49）稳健，用 numpy 即可（已在依赖）。
- **Alternatives considered**: ① 正态近似 CI——比率+小样本下不成立，弃；② BCa bootstrap——更精但实现复杂、对本场景边际收益低，列为可选增强；③ 对 tp/fp/fn 逐项 CI——不直接给出指标 CI，弃。

## R2 — 配对显著性：bootstrap 差值 + McNemar

- **Decision**: 两种互补检验，按陈述类型选用：
  - **精度/召回/F1 差**（连续比率）：**paired bootstrap of the difference**——同一批脚本索引对 A、B 两模型同步重采样，算差值分布的 95% CI；CI 不含 0 即"显著"。
  - **逐脚本精确匹配对错**（二元）：**McNemar 精确检验**——把每脚本判「reads∪writes 集合是否与 gold 精确匹配」为对/错，构造 2×2 discordant（b=A对B错、c=A错B对），用精确二项检验（`scipy.stats.binomtest(min(b,c), b+c, 0.5)`）出 p 值；正确处理并列（并列样本不进 b/c）。
- **Rationale**: 头条比较多是比率差（bootstrap-diff 天然配对、与 R1 同框架）；McNemar 给出经典配对检验的教科书式背书，审稿人熟悉。二者并列比单一检验更稳。样本 tie 多时 McNemar 自动缩小有效 n、如实反映"不显著"。
- **Alternatives considered**: ① 独立双样本 t 检验——忽略配对、样本非正态，弃；② 置换检验——与 bootstrap 等价但更慢，列可选；③ 仅 McNemar——丢失比率差的效应量，弃。

## R3 — 工具基线：regex（补跑）+ SQLLineage

- **Decision**: 两个非学习式基线，共用既有 `baselines/*.predict(row) -> {reads,writes}` 契约：
  - `regex_baseline`（既有，不改）——补跑至 gold C，作朴素下界。
  - `sqllineage_baseline`（新增）——用 `sqllineage`（`LineageRunner`）解析 SQL 抽 source/target 表，映射为 `{table, columns:None}`；**对非 SQL / 解析失败 catch 后返回空**（→ 脚本子集召回 0，坐实结构性失效）。
- **Rationale**: SQLLineage 是被广泛引用的第三方血缘工具（非自研稻草人），比自己用 sqlglot 拼更可引用；它内建于 sqlglot（已在依赖），加 `sqllineage` 依赖成本低。脚本上它必然失效（无 SQL 语法），正是"为何需要学习模型"的实证锚点。
- **Alternatives considered**: ① 直接用既有 sqlglot 自研解析当基线——可引用性弱（"你自己的代码"），弃为主基线；② Calcite（平台 `SqlTableExtractor`）——JVM 跨进程接入代价高、边际收益低，Clarify 已定不做；③ OpenLineage SQL parser——与 SQLLineage 定位重叠，不叠加。

## R4 — 可复现 benchmark：标签+指针+脚本

- **Decision**: 发布物形态（Clarify 已定）：
  - **标签记录**：仲裁后 gold C 的 `{repo, commit, path, reads, writes}`（不含源码正文）。
  - **源指针 + 抓取脚本**：`benchmark/fetch.py` 按 `repo@commit:path` 无凭据抓公开源文件（GitHub raw / the-stack 公开镜像），本地重建评测输入。
  - **评测脚本 + 基线 + 复现说明**：`eval/`、`baselines/`、`significance.py`、`realeval/eval_*_c.py` 的可复现子集 + `benchmark/README.md`。
  - **公开边界声明**：明示不含源码本体、不含合成训练集。
- **Rationale**: 对齐 MSR/OpenLineage/BigCode 的复现包惯例——发标签+指针而非源码，规避 GitHub 挖取源码的重分发许可风险，同时第三方仍可无凭据复算。合成集不发=防止训练数据泄漏使真实集评测失效。
- **Alternatives considered**: ① 全量源码快照——许可/重分发风险 + 需处理合成泄漏边界，弃；② 只发脚本不发数据——复现性等于零，弃；③ 发 HF dataset 内嵌源码——同许可风险，改为指针。

## R5 — auto-gold 扩容（W4 可选）

- **Decision**: 复用既有链 `collect_stack.py`（the-stack，`HF_TOKEN` 在）→ `teacher_label.py`（m1 qwen-max + m2 回退，`DASHSCOPE` 在）→ `build_gold_b.py`（纯函数一致 gold），把非空 gold 从 ~49 扩到目标 ~100；**按内容 sha256 dedup 排除已在 gold C 的样本**防污染。产物在论文中显式标注"teacher 派生、非独立真值、仅作稳健性"。默认**不执行**，仅当 W1–W3 落地后有余量 + 投稿周期允许。
- **Rationale**: 此头条下扩容非 blocker（曲线显著性不依赖它）；仅用于收紧 CI。三方一致因 deepseek(m3) 缺失退化为 m1+m2 双一致，标签噪声天花板存在，故只当稳健性。
- **Alternatives considered**: ① 人工裁决大 gold（破循环）——纯人力不可扩、此头条不需，弃；② 补 deepseek key 做三方——超范围，脚注声明即可。

## R6 — 统计依赖：加 scipy，bootstrap 用 numpy

- **Decision**: `requirements.txt` 新增 `scipy`（仅用 `scipy.stats.binomtest` 做 McNemar 精确检验）+ `sqllineage`。bootstrap/paired-diff 全用 `numpy`（已在）手写，固定 seed。不引 `statsmodels`（过重）。
- **Rationale**: scipy 是统计标准库、复现友好、审稿人预期；binomtest 精确、免手写二项。numpy 手写 bootstrap 透明可测、零额外依赖。
- **Alternatives considered**: ① 全手写含二项 p 值——`math.comb` 可行但易错、可读性差，弃；② statsmodels 的 mcnemar——引入重依赖换一个函数，弃。

## 未决外部事实（不阻塞 plan）

- **MSR 当前投稿周期 DDL**：外部事实，非技术未知；决定 W4 取舍与写作排期，不影响 W1–W3 可构建性。建议 WebSearch 核实后由用户拍板。
