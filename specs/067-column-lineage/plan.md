# Implementation Plan: 列级血缘（联合表+列重训 · 保表级曲线）

**Branch**: `067-column-lineage` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/067-column-lineage/spec.md`

## Summary

把列级血缘从空壳（gold/评测/serving 全表级）推进到**模型级·可评测·诚实可发表**。核心洞察：teacher 早已免费吐列，只在 `build_gold_b`/`build_silver` 两处被抹 `None`。技术路径 = 停止丢列 + 列级一致裁决 + 与表级**物理隔离**的条件列 metric + 联合表+列重训新模型家族 `run-col-*`（3B 先行门）。两道硬门守红线：门①（代码级）列打分对表级 counts 逐字节零扰动；门②（经验级）`run-col-3b` 与既有 3B 在同一重建列 gold 上表级不显著退化。065 数据已丢但 `collect_stack` 免费再生语料，teacher 重标 ≈¥35-47 < ¥100。

## Technical Context

**Language/Version**: Python 3.11（ml/lineage-extractor；torch 2.10.0+cu128）

**Primary Dependencies**: transformers / peft / trl（QLoRA 训练）、datasets（the-stack-dedup 流式）、openai + anthropic SDK（teacher 打标）、sqllineage 1.5.8（US4 可选列基线）；纯函数层无 torch 依赖（`metrics.py`/`canon_col` 可独立单测）

**Storage**: 文件（jsonl gold/silver/preds；merged 权重目录）；全 gitignored 走 HF，不入代码仓

**Testing**: pytest（`ml/lineage-extractor/tests/`，AAA + 纯函数单测）；GPU 训练/评测为真跑取证（非单测）

**Target Platform**: 本机 Linux + RTX 5070（12.8G，sm_120）离线训练/推理；teacher 走云 API

**Project Type**: ML 研究子系统（离线抽取器 + 评测 harness），单目录 `ml/lineage-extractor/`

**Performance Goals**: 非延迟敏感；成本目标 teacher 累计 ≤¥100（真实 token 用量佐证）；列级 p≥0.70/r≥0.55/F1≥0.60（3B，条件表命中，n≥30）

**Constraints**: 无新 GPU（本机 5070）· 无人工标注（列 gold 恒 teacher 共识银标）· teacher ≤¥100 · 门① 表级 counts 逐字节不变 · 门② 表级曲线复现（3B 表 p≥0.72/r≥0.80 同集不显著退化 + 单调 + Δr 三档显著）

**Scale/Scope**: gold ~130 行（重建，非空 ~100，含具体列表实例 ≥30）；训练银标 ~2-3k 脚本；3 档模型（0.5/1.5/3B）；改动集中 5 文件 + 2 测试文件

## Constitution Check

*GATE: 本特性为纯 ML 研究子系统，不触及 Weft 平台转型内核。*

| 原则 | 适用性 | 结论 |
|---|---|---|
| I. Files-First | N/A | 不涉任务/目录文件契约 |
| II. Server=真相源 | N/A | 不涉 pull/push/隔离 |
| III. 两条腿调试 | N/A | 不涉 CLI 运行时/执行器 |
| IV. AI 归位本地 | **合规** | 不在服务端嵌 AI；本特性是离线 ML 研究流水线，与服务端无 AI 大脑约束正交 |
| V. 内核复用 | **合规** | 复用既有 `metrics.py`/`build_gold_b`/`build_silver`/`sft_qlora`/significance harness，纯增量不重写 |

**质量门（CLAUDE.md）**：新功能必带测试（门① 正交单测 + 列裁决/列打分单测）；纯函数改动 `cd ml/lineage-extractor && PYTHONPATH=. python -m pytest` 全绿；GPU 环节走 WSL2 脱离规则真跑取证。**无违规，无需 Complexity Tracking。**

## Project Structure

### Documentation (this feature)

```text
specs/067-column-lineage/
├── plan.md              # 本文件
├── research.md          # Phase 0：决策合并（列裁决/隔离/成本/门②）
├── data-model.md        # Phase 1：实体（列 gold/银标/metric counts/模型家族）
├── quickstart.md        # Phase 1：端到端再生→重训→评测命令
├── contracts/           # Phase 1：函数级契约（无 HTTP，纯函数/CLI 契约）
│   ├── canon_col.md
│   ├── metrics_column_scoring.md
│   ├── build_gold_column_mode.md
│   └── build_silver_column_preserve.md
└── tasks.md             # Phase 2（/speckit-tasks 产出，本命令不建）
```

### Source Code (repository root)

```text
ml/lineage-extractor/
├── eval/
│   ├── metrics.py                    # ★加 canon_col + 条件列打分(独立 col_* key,门①隔离)
│   └── baselines/
│       └── sqllineage_baseline.py    # ★US4 可选:加列级抽取(get_column_lineage)
├── realeval/
│   ├── build_gold_b.py               # ★decide_tables 加列级一致裁决(双 teacher 交集)
│   ├── build_silver.py               # ★停止抹列,单 teacher m1 列(SFT 容噪)
│   ├── collect_stack.py              # 复用(免费再生语料,零改动)
│   ├── teacher_label.py              # 复用(列本就免费吐,零改动)
│   ├── dump_model_preds.py           # 复用(predict 已透传列,零改动)
│   ├── significance_report.py        # ★报告加列级指标行
│   ├── eval_baselines_c.py           # ★报告加列级基线行
│   └── rebuild_col_gold.py           # ★新:编排 collect→双teacher→build_gold_b 列模式
├── train/
│   └── sft_qlora.py                  # 零改动(schema 已含 columns,仅训练数据变)
├── tests/
│   ├── test_metrics_columns.py       # ★新:门①正交 + 条件列打分 + 三态语义
│   └── test_build_gold_columns.py    # ★新:列级一致裁决(交集/弃权)
└── out/                              # preds/报告(gitignored 走 HF)
```

**Structure Decision**: 单目录 ML 子系统，改动集中 5 个既有文件（纯增量）+ 1 个新编排脚本 + 2 个新测试文件。纯函数层（`metrics.py`/`canon_col`/`decide_tables` 列逻辑）与 torch 解耦，可独立单测——门① 正交性即在此层用逐字节相等断言钉死。

## Phased Delivery（对齐 spec US 优先级 + 3B 先行门）

- **Phase A（US1，MVP）**：`canon_col` + `build_gold_b` 列模式 + `metrics.py` 条件列打分 + 门① 正交单测 + 重建带列 gold。产出：能对任意 preds 诚实评列，且证明表级零扰动。**不需 GPU**。
- **Phase B（US2）**：`build_silver` 列保留 + 再生列增强银标 + 联合重训 `run-col-3b` + 门② 同集校验。**GPU（3B 先行闸）**。过门 → Phase C；不过 → 记表列权衡负结果、停。
- **Phase C（US3）**：扩训 `run-col-05`/`run-col-15` + 列级 scale 曲线 + 表级单调复核。**GPU**。
- **Phase D（US4，可选）**：SQLLineage 列级基线对照（SQL 子集）。
- **贯穿**：证据溯源写入 `out/` 报告（呼应 065 PAPER-EVIDENCE 无裸数字），成本记账 ≤¥100。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
