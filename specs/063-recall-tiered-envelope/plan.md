# Implementation Plan: 召回回收 · 置信度分层复核信封

**Branch**: `063-recall-tiered-envelope` | **Date**: 2026-07-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/063-recall-tiered-envelope/spec.md`

## Summary

把 052/054 的置信度校准框架接进 059 的血缘抽取 serving，产出**分层复核信封**：抽取管线末端加一环确定性后处理，将「模型抽取表集 ∪ SQL-AST 通道表集」按「通道归属 × 名字限定性」分级，累计校准 precision ≥ 治理阈（默认 0.95）的进**自动采纳层**（可直接入库），其余进**复核候选层**（按 confidence 降序进人工队列）。目标：把复核者可见召回从模型独抽 0.703 推向免费天花板 0.764，同时自动层 CV held-out precision ≥ 阈。**分级校准常量经 gold C 嵌套 CV 去偏产出（点估计部署 + CV held-out 报告；research R1：无独立非泄漏带标集，退回 054 已验证的 CV 诚实法）。本轮只做 serving 侧产出 + 离线证明，不碰 backend/frontend。**

## Technical Context

**Language/Version**: Python 3.11（`ml/lineage-extractor/`）

**Primary Dependencies**: FastAPI（serving sidecar）、transformers + torch（模型推理，不变）、既有 realeval 模块（`channel_router` SQL-AST 通道 / `confidence_calibration` 分级 / `conf_calibration_cv` CV 去偏 / `semantic_grounding` / `dir_fix`）；pytest。

**Storage**: N/A（数据为 gitignored 文件：gold 集、teacher 标签、模型预测、固化校准常量；走 HF 不入 git）。

**Testing**: pytest（`cd ml/lineage-extractor && PYTHONPATH=. python3 -m pytest -q`）；纯函数无 GPU 单测 + 离线 harness 真跑。

**Target Platform**: Linux；serving sidecar CPU/GPU 皆可（分层为 CPU 后处理，模型推理复用既有）。

**Project Type**: 单项目（ML library + FastAPI 推理 sidecar）。

**Performance Goals**: 分层后处理 O(候选数)，微秒级；模型推理不变（平台侧 2s 超时预算不受影响）；确定性（同版本模型 + 同输入 + 同阈 → 同输出）。

**Constraints**: 不新增 GPU（CV 复用既有 gold C 预测，无需新 dump）；不付费 teacher；不新增人工标注；不碰 backend/frontend。

**Scale/Scope**: gold C 153 条（嵌套 CV：既定级又 held-out 评测）；serving 逐请求。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Files-First** / **II. Server SoT** / **III. Two-Legged Debugging**：N/A——本特性是 ML 抽取 sidecar 的后处理扩展，不涉及任务定义文件/服务端治理/CLI 调试腿。
- **IV. AI Lives in the Local Agent（不可让渡）**：✅ **合规**。血缘抽取 serving 是**独立进程 sidecar**（`serve/app.py` 头注已声明「不嵌入平台服务端，宪法原则 IV 合规姿态」），由平台 `ModelExtractor` 作为外部服务调用。本特性只扩 sidecar 的后处理链，**不向服务端注入 AI 大脑**；范围明确不碰 backend/frontend，无嵌入风险。
- **V. Reuse the Kernel（内核复用）**：✅ **合规且正向**。直接**复用**既有内核（`channel_router`/`confidence_calibration`/`conf_calibration_cv`/`dir_fix`/`semantic_grounding`），不重写；新增仅为分层组装 + 校准固化 + 离线证明的薄封装。

**结论**：无违反，无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/063-recall-tiered-envelope/
├── plan.md              # 本文件
├── research.md          # Phase 0：校准集替身等决策
├── data-model.md        # Phase 1：候选边/分层/校准表实体
├── quickstart.md        # Phase 1：冻结校准→gold C 评测→serving→测试 跑法
├── contracts/           # Phase 1：/extract 分层响应契约 + tier_classify 函数契约
│   ├── extract-response.md
│   └── tier-classify.md
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
ml/lineage-extractor/
├── realeval/
│   ├── channel_router.py          # 复用（SQL-AST 通道，exec_gated）
│   ├── confidence_calibration.py  # 复用（_canonical_edges 分级、calibrate、best_frontier）
│   ├── conf_calibration_cv.py     # 复用（留一/k折 CV 去偏）
│   ├── semantic_grounding.py      # 复用（059 ③，管线前段）
│   ├── dir_fix.py                 # 复用（AST 方向修正）
│   ├── tier_classify.py           # 【新】纯函数：model∪SQL canon 去重→打 tier→按阈切自动/复核
│   ├── calibrate_tiers.py         # 【新】冻结校准：gold C 全集点估计+CV去偏→固化 precision 常量表
│   └── rescore_tiered.py          # 【新】离线证明：gold C 三方分层对照报告
├── serve/
│   └── app.py                     # 【改】postprocess 链尾加分层；响应加 reviewReads/Writes + tier/confidence + tiered
└── tests/
    ├── test_tier_classify.py      # 【新】真实夹具单测
    ├── test_calibrate_tiers.py    # 【新】冻结校准常量结构/CV 去偏单测
    └── test_dir_fix_serve.py      # 【改】分层响应结构/阈生效/回滚/向后兼容
```

**Structure Decision**：单项目，全部改动落 `ml/lineage-extractor/`。新增 3 个 realeval 脚本（分层组装 / 冻结校准 / 离线证明）+ 扩 `serve/app.py` + 3 个测试文件（2 新 1 改）。不新增模块、不碰 backend/frontend。

## Complexity Tracking

> 无宪法违反，本节留空。
