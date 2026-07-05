# Implementation Plan: 抗泄漏消融（041-R 方案 B）

**Branch**: `047-lineage-antileak-ablation` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/047-lineage-antileak-ablation/spec.md`

## Summary

在同一 1.5B 规模、同一评测口径下，实测两条最直觉的抗泄漏补救能否修 041-R 已证的记忆泄漏 + domain-shift：**B1 真实表名增广**（打散过窄的合成命名分布）与 **B2 开放域弃权训练**（教模型没把握就输出空）。两支各产一版权重，复用既有评测器与真实金标，与基线 1.5B / 3B 逐列可比，汇成一张抗泄漏消融表并入论文。**成功 = 决定性且诚实的测量，非补救必须奏效**——修不动 / 部分修好 / 翻转结论三种产出均合格。

技术路径：全部增量落在 `ml/lineage-extractor/`，additive、不改既有内核；训练/评测复用 `train/sft_qlora.py`、`realeval/eval_real.py`、`realeval/leak_analysis.py`、`eval/evaluate.py`；只新增两个数据变体构造器 + 泄漏检测器的"按变体自有训练池评测"泛化 + 消融表汇编。

## Technical Context

**Language/Version**: Python 3.12（与既有 `ml/lineage-extractor/` 一致）

**Primary Dependencies**: transformers / peft / trl / torch(bf16) / datasets（已锁版本，见 `ml/lineage-extractor/requirements*.txt`）；评测复用 `eval.metrics` 纯 Python（无 torch 亦可单测）

**Storage**: 训练/评测数据 = jsonl（`data/out-b1/`、`data/out-b2/`）；权重 = safetensors，落 **worktree 外** `weft-lineage-weights/run-b1|run-b2`（`WEFT_WEIGHTS_DIR` 或 `parents[K]` 解析，gitignore）

**Testing**: pytest；数据变体构造器 + 泄漏池泛化做**无 GPU 单测**（确定性、配比、空标签、池追踪）；训练/真实评测为 GPU 实验运行（非单测，产物落 `out/`）

**Target Platform**: 单张 12G GPU（bf16 + LoRA，无需 QLoRA/量化，避免量化不一致污染对照）

**Project Type**: 离线 ML 研究管线（**不嵌入平台服务端**）——产物是论文证据，非线上能力

**Performance Goals**: 单支训练 ≤ 数小时（1.5B/2ep/10k 样本，与基线 run1 同量级）；评测复用既有管线，分钟级

**Constraints**: 消融纯净——每支与基线**仅差一个训练数据维度**，其余超参逐字一致；评测口径不变（金标约定 A，复用 `real.jsonl` n=139/非空 59）；泄漏度量 gold-无关

**Scale/Scope**: 2 个训练变体 + 2 版权重 + 3 类评测（真实四方 / 合成 held-out / 泄漏）×2 + 1 张消融表；真实非空评测集 59（可选 JVM 28）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| **I. Files-First** | ✅ 不适用/合规 | 纯离线研究产物（jsonl/权重/md），无平台定义文件改动 |
| **II. Server 为治理真相源** | ✅ 不适用 | 不碰 pull/push/版本快照/隔离，无服务端交互 |
| **III. 两条腿调试** | ✅ 不适用 | 不碰 CLI runtime/执行器 |
| **IV. AI 归位本地（NON-NEGOTIABLE）** | ✅ **合规** | 041-R 已 pivot 为**负结果论文**，小模型**不部署进服务端**；serve sidecar 本就独立进程、宪法 IV 合规姿态；本特性只产论文证据，**不向服务端嵌入任何 AI 推理/决策** |
| **V. 内核复用** | ✅ 合规 | 复用既有训练/评测管线，additive，不重写、不改调度/执行/门控内核 |

**结论：无违规，无需 Complexity Tracking。** 本特性是 041 科研支线的延续（与平台内核解耦），Constitution I–V 主要约束平台转型代码，此处以"不触碰服务端 AI / 不改内核"通过。

## Project Structure

### Documentation (this feature)

```text
specs/047-lineage-antileak-ablation/
├── plan.md              # 本文件
├── research.md          # Phase 0：两处设计决策（B1 注入策略+泄漏池追踪 / B2 负样本源+配比）
├── data-model.md        # Phase 1：B1/B2 训练集变体 + 变体训练池 + 消融表实体
├── quickstart.md        # Phase 1：端到端可复现验证（建变体→训→评→汇表）
├── contracts/           # Phase 1：新增/泛化脚本的 CLI 契约
│   ├── antileak-data.md         #   data/antileak.py 构造 B1/B2 变体
│   ├── leak-analysis-pool.md    #   leak_analysis.py --train-pool 泛化契约
│   └── ablation-table.md        #   消融表汇编契约
└── tasks.md             # Phase 2（/speckit-tasks 产出，本命令不建）
```

### Source Code (repository root)

```text
ml/lineage-extractor/
├── data/
│   ├── synth_pipeline.py         # 既有：合成管线（build_pools / synth_table_names）——复用，不改
│   ├── templates.py              # 既有：模板（TRAIN/HELDOUT）——B2 复用其习语构造弃权负样本模板家族
│   ├── antileak.py               # 【新增】B1/B2 训练集变体构造器（确定性 + 落变体训练池文件）
│   └── out-b1/ , out-b2/         # 【新增】变体训练/held-out jsonl + pool.json（gitignore 大件按既有约定）
├── train/
│   └── sft_qlora.py              # 既有：--data/--out/--base-model 已参数化——直接复用训 run-b1/run-b2
├── eval/
│   └── evaluate.py               # 既有：合成 held-out 评测——复用（--model 指向变体权重）
├── realeval/
│   ├── eval_real.py              # 既有：真实四方——复用（--model 指向变体权重）
│   ├── leak_analysis.py          # 【泛化】新增 --train-pool <file>：按变体自有训练池测泄漏（默认回放合成池，向后兼容）
│   └── ablation_table.py         # 【新增】汇编 基线/B1/B2/3B 同口径消融表 → md/json
└── tests/
    ├── test_antileak_data.py     # 【新增】变体构造器单测（确定性/配比/空标签/池追踪）——无 GPU
    └── test_leak_pool.py         # 【新增】--train-pool 泛化单测——无 GPU

weft-lineage-weights/            # worktree 外 sibling（gitignore）
├── run-b1/merged , run-b2/merged # 【新增】两版权重
└── run1 run2 run3 run-{0.5b,3b,jvm}  # 既有基线/曲线权重
```

**Structure Decision**: 单一 ML 管线子项目（`ml/lineage-extractor/`），全部改动 additive。既有脚本已高度参数化（`--data/--out/--base-model/--model`），B 主要靠**新增两个数据变体构造器**驱动，训练/评测**零改动复用**；唯一对既有脚本的修改是把 `leak_analysis.py` 的训练池来源从"硬编码回放合成池"泛化为"可传变体自有池"（向后兼容，默认行为不变），这是消融纯净与泄漏度量诚实性的必需（见 research.md 决策 3）。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
