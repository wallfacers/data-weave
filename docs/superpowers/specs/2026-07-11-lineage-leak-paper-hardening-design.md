# 血缘小模型实证研究：可投论文加固（065）

**日期**：2026-07-11
**分支/worktree**：`065-lineage-paper` @ `../dw-065-lineage-paper`（从 main `6845e4e` 拉）
**性质**：实证研究论文的方法学加固，不是新平台能力。工作全部落在 `ml/lineage-extractor/`。
**前序**：041（脚本血缘+泄漏曲线）· 047（抗泄漏消融）· 052/054（蒸馏+信封）· 059（语料扩增+去偏+语义 grounding）· 063（分层复核信封）——三个核心发现均已完成并合入 main。

---

## 1. 目标与非目标

**目标**：把已完成的三个发现打磨成一篇能过审的实证论文（MSR 为主，EMSE 为备），补齐评审必看的方法学缺口——**不新增 GPU / 不新增凭据 / 不做人工标注**。

**非目标（本特性显式不做）**：
- 列级血缘（column-level lineage）——已探明是空壳（gold/评测/serving 全表级），做它要从零建列级评测基建且踩表级同样的硬伤。**列为论文 future work，本期不碰**。
- 大规模人工裁决 gold（破循环论证用）——此头条下非必需；诚实承认 n 限制 + CV 去偏作为缓解。
- 补 deepseek(m3) 凭据复现 teacher 三方对比——`.env` 缺 `DEEPSEEK_ANTHROPIC_TOKEN`，脚注说明即可，不作关键路径。

---

## 2. 头条与脊椎（已定）

**头条主声明**：泄漏曲线 + 诚实去偏 + 分层部署信封。**明确不靠"3B 超 teacher"**（n≈49 撑不起该论断，且 gold 是 teacher 派生 → 循环论证）。

**脊椎 = 泄漏科学（方法学）**，另两个降为支撑。理由：
- 对 n≈49 最免疫——泄漏曲线显著性来自「单调三点趋势 37→22→11% + 合成 0.99 vs 真实 0.24–0.32 塌缩」，是模型内的巨大效应，不依赖小 gold。
- 脚本血缘当脊椎太危险（它是能力声明，而 041 真实脚本结果偏负面：JVM 幻觉 0.347、domain shift）→ 降为**动机**。
- 分层信封研究新颖度偏弱（校准+HITL 是成熟技术）→ 降为**落地/so-what**。

**一条弧线**（三发现拧成一个故事，泄漏科学是分析核心）：
> 脚本血缘领域（确定性 SQL 解析器结构性失效）→ 逼你上学习模型 → 用合成语料训练 → 诱发记忆泄漏（**脊椎**）→ 诚实去偏量出真实（不高的）性能 → 分层信封告诉你怎么照样安全部署。

---

## 3. 论文贡献（最终四条）

- **C1（脊椎）**：首次系统量化「合成语料训练的小模型在代码血缘抽取上的记忆泄漏」，逐规模（0.5/1.5/3B）：逐字泄漏 37→22→11% 单调降；合成 held-out 近满分 0.99 vs 真实集塌到 0.24–0.32。跨语言（JVM）复现强化普适性。
- **C2**：诚实去偏方法学——来源隔离（the-stack 训练 vs GitHub-fresh 测试）+ content-hash 去污染 + 嵌套/留一 CV 暴露样本内校准的乐观偏置（自我证伪：分层膝点 0.95→0.85）。
- **C3**：部署信封——校准置信度分层（自动采纳 vs 复核）作为落地上手段；诚实结论"召回回收价值全在复核层"。
- **动机/领域**：命令式 ETL（Python/Shell/Spark）血缘，确定性 SQL 解析器结构性失效 → 为何需要学习式抽取器。

---

## 4. 当前基建现状（grounding，决定工作量）

- **数据全 gitignored 且当前 checkout 物理不存在**：`realeval/gold/*.jsonl`、pool、silver、teacher_labels 都不在盘上，走 HF 分发。条数只能从 `out/` 报告反推：gold A `real.jsonl` 139/59 非空；gold C 153/49 非空（仲裁后约 61 非空）。
- **可扩的自动 gold 路可用**：`collect_stack.py`（the-stack，`HF_TOKEN` 在）→ `teacher_label.py` → `build_gold_b.py`（纯函数 auto-gold，有单测）。但 auto-gold 是"弱下位替代"（偏向 teacher 都能找到的表，标签噪声天花板存在）。
- **凭据**：`.env` 有 `DASHSCOPE_API_KEY`(m1=qwen-max 可用)、`M2_MODEL`(m2 回退可用)、`GITHUB_TOKEN`、`HF_TOKEN`；**缺 deepseek(m3) key** → 三方 teacher 一致/对比跑不了。
- **显著性检验：全树没有**（无 bootstrap/McNemar/scipy）。唯一重采样代码 `conf_calibration_cv.py` 是给置信度校准做 k 折，非对比检验，不能直接复用。但 `eval/metrics.py` 的 `score_row` 已产逐脚本 counts、`aggregate` 只求和 → **加显著性只需在汇总层加一层脚本级重采样，不动打分逻辑**。
- **基线**：`eval/baselines/regex_baseline.py` 存在（唯一非 LLM 工具），接口通用可直接喂 gold C，但**只在 gold A 上跑过**；`llm_baseline.py` 是把大模型当基线。**没有 SQLLineage / Calcite 等库级工具基线**。

---

## 5. 工作包（到"可投"）

三发现已完成，剩下五块。W1+W2+W3 为过审必需，W4 可选，W5 贯穿。

### W1 — 统计诚实层（必需，低成本）
- 在汇总层新增脚本级 **bootstrap 置信区间** + **McNemar 配对检验** 模块；输入是 `metrics.py:score_row` 已产的逐脚本 counts 列表，输出 precision/recall/F1 的 95% CI 与配对 p 值。**不改 `metrics.py` 打分逻辑**。
- 每个头条数字带 CI。**诚实报告 teacher 对比在 n≈49 下不显著**——先发制人拆掉评审"n=49 撑不起超越"的枪，把硬伤化为诚实点。
- 复用 `conf_calibration_cv.py` 的确定性折切分模式作重采样脚手架，但统计量新写。

### W2 — 工具基线（必需，中成本）
- 现有 `regex_baseline` 补跑 gold C（现在只跑过 gold A），进同一张对比表。
- 新增 **SQLLineage**（Python 库，易接）作为真工具基线；接口对齐 `baselines/*.predict(row)`。
- **招牌对比图**：工具在脚本（Python/Shell/Spark）上 ≈ 0（结构性失效），学习模型救回 → 直接支撑领域动机与"为何需要学习式抽取器"。
- Calcite 库级基线可选（平台侧已有 `SqlTableExtractor`，但 JVM 跨进程，代价高）→ 先只做 SQLLineage，Calcite 留可选。

### W3 — 可复现 benchmark 发布（必需，中成本）
- 打包 eval harness + 仲裁后 gold C + 合成 held-out 为 HF dataset，附评测脚本与复现说明。
- 数据本就 gitignored 走 HF，形态已对；决定发布范围（至少：gold C 仲裁版 + 评测脚本 + 基线；合成 held-out 视泄漏考量决定是否附）。
- 解决评审 #2 必项（复现性）——公开可复现 > 大而私有。

### W4 — auto-gold 适度扩容（可选/稳健性，低-中成本）
- the-stack + m1(+m2 回退) 共识把非空 gold 49→~100，firm 住泄漏曲线/信封的数字。
- **此头条下非 blocker**；只当稳健性。须显式标注"稳健性、非独立真值"（仍 teacher 派生），不补 deepseek key。
- **默认先砍，留可选**——W1-W3 落地后视余量决定是否做。

### W5 — 论文写作（贯穿，中成本）
- Related work 扎实：平台侧 OpenLineage/DataHub/Marquez/Egeria；provenance（Buneman/Tan 一脉）；LLM-for-code 记忆化/污染。
- Delta 定位：命令式 ETL 血缘 + 小模型经济学 + 泄漏科学。
- 图表：泄漏曲线招牌图、合成 vs 真实塌缩、CV 去偏膝点、信封分层、工具 vs 模型脚本对比。

---

## 6. 显式 out-of-scope 声明（写进论文，诚实为卖点）
- 列级血缘：schema 有字段但 best-effort、评测表级，列为 future work。
- 大规模独立人工 gold：受凭据/人力限制未做；以来源隔离 + content-hash 去污染 + CV 去偏缓解；诚实报 CI。
- deepseek 三方 teacher 对比：凭据受限，脚注说明。
- 动态表名 / 注释 SQL / 临时视图 / 配置驱动作业：沿用既有 out-of-scope。

---

## 7. 风险与缓解
- **"记忆化是已知问题"**（评审可能说泄漏不新）→ 定位 delta：特指*合成训练诱发*的、*小模型*、*结构化抽取（血缘）*、*逐规模量化 + 来源隔离去偏*，这是真 gap。Related work 预先接住。
- **auto-gold 循环论证**（若做 W4）→ 头条不依赖它；W4 仅稳健性且显式标注。
- **MSR DDL 未知** → 需另查当前投稿周期截止时间，据此定 W4 取舍与写作排期。
- **SQLLineage 与本项目 gold 格式对齐** → W2 需一层适配，风险低。

---

## 8. 里程碑排序
1. W1 统计诚实层（解锁"每个数字带 CI"，最高性价比）。
2. W2 工具基线（招牌对比图，支撑动机）。
3. W3 benchmark 发布（复现性）。
4. W5 写作贯穿。
5. W4 视余量与 DDL 决定。

---

## 9. 待办决策（写作前需确认）
- MSR 当前投稿周期 DDL（另查）→ 决定排期与 W4 取舍。
- W3 发布范围：合成 held-out 是否附（泄漏考量）。
- SQLLineage 之外是否加 Calcite 库级基线（默认否）。
