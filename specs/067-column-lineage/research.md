# Phase 0 Research: 列级血缘

所有 NEEDS CLARIFICATION 已在 brainstorming（设计 1-4 节全批准）+ clarify（6 项知情决策）阶段解决。本文合并关键决策，格式 Decision / Rationale / Alternatives。

## R1. 列数据来源 = teacher 免费吐列

- **Decision**: 不改 teacher 提示词；`SYSTEM_PROMPT` 已要求 `{"table":str,"columns":[str] or null}`，`_parse_lineage_json` 原样保留列。造列 gold/银标只需在 `build_gold_b`/`build_silver` **停止抹 `None`**。
- **Rationale**: 探明列数据一直在 teacher 输出流动，只在两处被丢弃；边际成本 ≈ 一次普通表级重标（列同一次 API 白送）。
- **Alternatives**: ①改提示词强化列输出——无必要，schema 已含；②平台 Calcite 确定性列——仅 SQL、无研究新颖度，降级为 US4 可选基线。

## R2. 列级一致裁决 = 双 teacher 交集 + 弃权优先

- **Decision**: `build_gold_b.decide_tables` 加列模式：仅在双 teacher 已一致的表上裁列；两方都给具体列集 → gold 列 = **交集**（`min-agree=2` 列粒度）；交集空 / 任一方弃权（null）/ 通配 → `columns=null`（该表列不评）。
- **Rationale**: 交集 = 高精度诚实 gold，延续 065 的表级 min-agree=2 与"弃权优先"哲学；列比表主观，宁缺毋滥。
- **Alternatives**: 并集（高召回但引入单方噪声，gold 不可信）；单 teacher 列（无一致性背书）。均拒。

## R3. 列语义三态 + `canon_col` 归一

- **Decision**: `null`=弃权（跳过不评）；`[]`=视同弃权（无法区分"确定无列"与"不知道"，保守）；具体集=评。`canon_col`：小写、去空白、剥表限定前缀（`t.col`/`db.t.col`→`col`）、`*`/通配→弃权信号。列匹配 = 归一后精确集合运算。
- **Rationale**: 三态防假阴/假阳；剥前缀消解"限定 vs 裸名"假错配（列已挂所属表 item 下，表身份不靠列名承载）。
- **Alternatives**: 子串/模糊匹配（引入假阳，不诚实）；保留限定名（假错配）。拒。

## R4. 条件列打分 + 表级物理隔离（门①）

- **Decision**: `metrics.py` 加**独立** col_* key（`col_tp/col_fp/col_fn/col_halluc/col_pred_total/col_eval_tables`），只对表级命中（TP）的表评列，沿用与表级同一 canon 设置，按 role 分算。表级 `tp/fp/fn/halluc/dir_*` 返回值**逐字节不变**。列打分走**独立代码块**（不复用表计数路径），从代码结构上排除扰动。
- **Rationale**: 门① 要求列碰不到表指标——独立 key + 独立块 + 逐字节相等断言单测 = 数学证明，不靠"跑出来碰巧一样"。
- **Alternatives**: 在表计数循环内联列（风险高，易误改表 counts）。拒。

## R5. 门② = 同重建集对比（非陈旧发布数）

- **Decision**: 065 gold C′ 原行已随 dw-065 worktree 删除丢失；用 `collect_stack` 同协议重建带列 gold。门② 表级校验改为「`run-col-3b` 与既有 3B **同在重建集**上评表级」：`run-col-3b` 表 p≥0.72/r≥0.80、落既有 3B 于该集 CI 带内、McNemar 不显著退化、Δr 三档显著。
- **Rationale**: exact 旧行不可复现；同集对比比"新数 vs 陈旧发布数"更 apples-to-apples，且更严（同分布消除评测集漂移混淆）。
- **Alternatives**: 沿用陈旧发布 0.743/0.832 作阈——评测集不同，不可比。拒。

## R6. 训练银标 = 免费再生语料 + 单 teacher m1

- **Decision**: `collect_stack` 从 the-stack-dedup 几分钟免费再生 ~2-3k ETL 脚本；单 teacher m1（qwen-max）打标（列白送）；`build_silver` 保列。无列标 item 保 `columns:null`（模型学弃权）。
- **Rationale**: 059 silver 已丢但语料可再生；SFT 对列噪容忍度高（模型平均化），单 teacher 省一半成本；gold 才需双 teacher 严裁。
- **Alternatives**: 双 teacher 全量银标（成本翻倍、无必要）；复用 059 silver（已丢，不存在）。拒。

## R7. 成本核算 ≤¥100

- **Decision**: gold 双 teacher ~400 候选 ≈¥6-7 + 银标单 teacher ~2-3k ≈¥25-40，**合计 ≈¥35-47**。`teacher_label` 回传真实 `_usage` token 用量记账。语料规模若受预算约束低于 059，如实披露（欠训风险由门② 捕获）。
- **Rationale**: 065 双 teacher 399 候选实测 ≈¥6-7 → 单条 ≈¥0.017/teacher；线性外推可控。
- **Alternatives**: 扩大语料追召回——受 ¥100 上限约束，非空 yield 低（065 教训），不值。

## R8. 联合监督重训 + 3B 先行门

- **Decision**: 表+列**同一 SFT 样本**联合监督（非列专训）；超参沿用 059 已固化 bf16 稳定性配置（lr/warmup/max-grad-norm）；3B 先训作为门② 前置闸，过门才扩 0.5/1.5B；新家族 `run-col-*` 落盘不覆盖既有表模型。
- **Rationale**: 联合监督守表级不忘；3B 先行省 GPU 时间先证伪表级扰动；059 发现"Run A 欠训 6.4×"→ 沿用固化配置减差异。
- **Alternatives**: 列专训/继续微调既有模型（灾难遗忘风险，破门②）；三档齐训（不先证伪，浪费 GPU）。拒。

## R9. US1 冻结基线的角色

- **Decision**: 既有 3B（`columns:null` 训练）列输出 ≈ null/零星幻觉 → 报为「重训前 ≈0」before/after 锚点，非调优数字。
- **Rationale**: 建立"重训前模型不会列"的诚实对照，凸显重训增益。
- **Alternatives**: 跳过基线（丢失 before/after 叙事）。拒。

## R10. 稀疏兜底

- **Decision**: 列评测最小可报 n=30（具体列表实例）；低于下限先在剩余预算内补采，仍稀疏则报"n 披露 + 宽 CI"方向性结果，不静默 pass/fail。
- **Rationale**: 交集裁决使 gold 列稀疏，小 n 下 P/R 不稳；诚实披露优于假达标（065 文化）。
- **Alternatives**: 放宽到并集充数（牺牲 gold 可信度）。拒。
