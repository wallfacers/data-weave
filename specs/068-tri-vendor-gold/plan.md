# Implementation Plan: 三厂商共识 gold + 全档重训（Tri-Vendor Consensus Gold）

**Branch**: `068-tri-vendor-gold` | **Date**: 2026-07-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/068-tri-vendor-gold/spec.md`

## Summary

引入 GPT-5.6（OpenAI，跨于 qwen/deepseek 两中国厂商的第三个独立厂商）作 teacher，在 067 同一 gold/silver 池上造**三厂商共识**：gold 出两把尺（2-of-3 多数主尺 + 3-of-3 一致高置信子集）破循环可信度（G1）；silver 用 2-of-3 多数共识（比 067 的 2-of-2 交集多边→召回，仍≥2 厂商→精度）全档重训 `run-tri-{05,15,3b}`，让模型同时高 P 高 R（G2）。技术核心：GPT 经中转站用 httpx 裸 POST（OpenAI SDK 的 `x-stainless-*` 头触发 WAF）；训练 fresh 从原始 Qwen base（与 067 同 base/配方、只换 silver=干净隔离三厂商效果，延续 065/067 隔离消融方法学）。复用 067 迁入的池/标注/权重，最小新增。

## Technical Context

**Language/Version**: Python 3（`python3`，系统解释器已装 openai 2.44 / httpx / python-dotenv / torch / transformers / peft / trl）

**Primary Dependencies**: httpx（GPT 裸 POST，绕 WAF）、openai SDK（m1/m2 DashScope 仍用）、anthropic SDK（m3/m_flash）、transformers/peft/trl/bitsandbytes（QLoRA 训练）、Calcite 无关（本特性纯脚本血缘）

**Storage**: JSONL 文件（gold/silver/teacher_labels/preds，全 gitignored 走 HF）；LoRA 权重目录（gitignored）

**Testing**: pytest（GPT httpx 后端 mock、三 teacher 共识裁决、门①正交回归）；真跑取证（teacher 标注真调用 + 真训练 + 真评测）

**Target Platform**: 本机 Linux（WSL2），单卡 RTX 5070 12.8G

**Project Type**: ML 研究管线（`ml/lineage-extractor/`），非 DDD 后端——宪法 DDD 分层不适用，但守宪法测试/诚实/内核复用文化

**Performance Goals**: 无吞吐目标；质量目标见 SC（表 P≥0.78/R≥0.75、列 P≥0.78/R≥0.82、stretch 3-of-3 gold 表 P≥0.80）

**Constraints**: 无新 GPU（5070，067 已验 3B r32/e3 ~2hr/12G 未 OOM）；无人工标注（gold/silver 恒 teacher 银标=循环性如实声明）；成本 ≤¥100 从真实 usage 算；全 pytest 绿零回归；隔离硬规则不覆盖 065/067

**Scale/Scope**: gold 池 ~400 条（复用 067 pool-c）；silver 池 ~3000 条（复用 067 pool-silver）；三档模型 0.5/1.5/3B

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First**：本特性只产文件工件（gold/silver/权重/证据台账），不引服务端状态 —— ✅ 契合。
- **II. Server is Source of Truth**：不碰服务端治理面/调度/血缘落库 —— ✅ 无关（Out of scope 已列）。
- **III. Two-Legged Debugging（NON-NEGOTIABLE）**：不碰调度/worker/dispatch 链路 —— ✅ 无关。
- **IV. AI Lives in Local Agent（NON-NEGOTIABLE）**：不加服务端 AI 脑；抽取器是离线训练产物，serving 侧不在本特性范围 —— ✅ 契合。
- **V. Reuse the Kernel**：**核心遵守** —— 复用 067 已落地管线（teacher_label/build_gold_b/build_silver/metrics/significance/sft_qlora），最小新增（仅加 GPT 后端 + 三 teacher 共识分支 + 独立命名产物），不重写 —— ✅ 契合。
- **测试门**：新增/改动代码必带单测 + 真跑取证 —— ✅ 计划内（FR-014）。
- **诚实门（承 065/067）**：循环性降低不消除如实声明；负结果不覆盖既有曲线 —— ✅ 计划内（门②相对判据 + PAPER-EVIDENCE-068）。

**结论**：无违规，无 Complexity Tracking 条目。ml/ 管线不适用 DDD 分层，属既有约定（041→067 一贯），非偏离。

## Project Structure

### Documentation (this feature)

```text
specs/068-tri-vendor-gold/
├── plan.md              # 本文件
├── research.md          # Phase 0：技术未知点裁决（GPT 后端/共识语义/隔离）
├── data-model.md        # Phase 1：数据实体（三厂商 gold/silver/teacher 标注 schema）
├── quickstart.md        # Phase 1：真跑操作手册（标注→共识→训练→评测）
├── contracts/           # Phase 1：共识算法契约 + JSON schema + client 契约
│   ├── gpt-client.md
│   ├── consensus.md
│   └── metrics-orthogonality.md
├── checklists/
│   └── requirements.md  # 已由 speckit-specify 生成
└── tasks.md             # Phase 2：speckit-tasks 生成（非本命令）
```

### Source Code (repository root)

```text
ml/lineage-extractor/
├── llm/
│   └── clients.py               # [改] 加 _openai_raw_backend(httpx 裸 POST) + 注册 m_gpt/m_gpt_bulk
├── realeval/
│   ├── teacher_label.py         # [改] 支持 --teachers 含 m_gpt/m_gpt_bulk
│   ├── build_gold_b.py          # [改] 三 teacher min-agree=2 + 产 3-of-3 一致子集
│   ├── build_silver.py          # [改] 三 teacher 2-of-3 多数共识（表+列）
│   ├── agreement_report.py      # [新] GPT vs 067 gold 一致率（FR-004）
│   ├── heldout_vendor_eval.py   # [新] GPT 独立确认边子集 P/R（FR-009/门③）
│   ├── governance_routing.py    # [新] 三厂商一致/分歧→自动/复核路由 + 自动层精度（FR-015/限制②缓解）
│   ├── pool-c/ pool-silver/     # [复用] 067 迁入
│   ├── teacher_labels-c/        # [复用] {m1,m3}.jsonl + [新] m_gpt.jsonl
│   ├── teacher_labels-silver/   # [复用] {m1,m_flash}.jsonl + [新] m_gpt.jsonl
│   └── gold/
│       ├── real-c.jsonl         # [只读] 067 gold（对比基线）
│       ├── real-c-tri.jsonl     # [新] 三厂商 2-of-3 主尺 gold
│       └── real-c-tri-unan.jsonl# [新] 3-of-3 一致高置信子集
├── data/
│   ├── silver-col.jsonl         # [只读] 067 silver
│   └── silver-tri.jsonl         # [新] 2-of-3 共识 silver
├── metrics.py                   # [只读回归] 列打分正交（门①已钉死）
├── eval/significance_report.py  # [复用] McNemar/bootstrap
├── train/sft_qlora.py           # [复用] --lora-r/alpha（已有）
├── out/
│   ├── run-col-3b-mit/ run-col-{05,15}/ ...  # [只读] 067 eval 基线
│   ├── run-tri-05/ run-tri-15/ run-tri-3b/   # [新] 三厂商重训权重
│   ├── PAPER-EVIDENCE-068.md    # [新] 独立证据台账（不碰 065/067）
│   ├── governance-routing-068.md# [新] 治理路由报告（限制②缓解）
│   └── significance-tri-*.md    # [新] 独立评测报告
# HF 收尾（FR-016/SC-012）：本特性交付后更新 wallfacers/weft-lineage-extractor-* 模型卡说明
#   —— 三厂商可信度 + 限制②缓解（治理路由）+ 限制①仍为诚实边界（动态名/注释/临时视图刻意排除）
├── weights/weft-lineage-extractor-{05,15,3b}/ # [只读] 059 published（model-3b 基线）
└── tests/
    ├── test_gpt_backend.py      # [新] httpx 后端 mock
    ├── test_tri_consensus.py    # [新] 三 teacher 2-of-3 / 3-of-3 裁决
    └── test_metrics_columns.py  # [回归] 门①正交
```

**Structure Decision**: 复用 `ml/lineage-extractor/` 既有目录（067 管线），改动集中在 llm/clients.py + realeval/ 少数脚本 + 两个新脚本（agreement/heldout），产物一律独立命名。非新模块、非跨层重构。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
