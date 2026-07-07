# Implementation Plan: 自训小模型血缘抽取达到生产可用（真实语料 teacher 蒸馏）

**Branch**: `054-lineage-distillation` | **Date**: 2026-07-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/054-lineage-distillation/spec.md`

## Summary

放开 041-R"纯合成训练"自我约束：用大模型 teacher（m1-qwen-max、m2-anthropic）在**真实开源 ETL 语料**上打高精银标做训练分布，从根上消除 domain shift 与记忆泄漏；从干净 base 蒸馏 3B 自托管小模型，配 sidecar 内 AST dir_fix 方向修正，接进后端既有三通道路由。服务态**纯自托管**（大模型仅训练时当 teacher，离线一次性），在 held-out 真实测试集 B（非空≥100）上严格全过验收门（recall≥0.80/方向≥0.73/幻觉≤0.15/precision≥0.50/逐字泄漏≈0）方判"生产可用"。全程加性零破坏，达标才 swap 权重+改 HF 卡。

技术路线核心 = **序列级知识蒸馏**（teacher 硬标签，非 logit——teacher 是 API 无 logits）+ **构造性反泄漏**（训练集零合成名，物理上无从背诵）+ **确定性方向兜底**（sqlglot AST）。全部复用既有管线，新增=teacher 打标器、银标构建器、dir_fix 产品化。

## Technical Context

**Language/Version**: Python 3.12（ML 管线）；Java 25 / Spring Boot 4（后端，几乎不动）

**Primary Dependencies**: torch + transformers + peft + bitsandbytes（LoRA/QLoRA 训练）；fastapi + uvicorn（sidecar）；sqlglot（dir_fix 方向解析）；openai SDK（m1 DashScope 兼容）+ anthropic SDK（m2 阿里云兼容端点）——均已在 `llm/clients.py`；datasets/python-dotenv

**Storage**: JSONL（语料候选 / teacher 标注 / 银标 / 金标）；LoRA-merged 权重落 sibling `weft-lineage-weights/`（gitignore，超 GitHub 100MB 限）；neo4j（血缘底座，不动）；`.env`（凭据，gitignore）

**Testing**: pytest（`ml/lineage-extractor/tests/`，新模块须配单测）；既有评测 harness（`eval_real.py`/`jvm_slice_eval.py`/`leak_analysis.py`）作**验收 harness**；后端 Java JUnit（接缝，改动极小）

**Target Platform**: Linux + CUDA（RTX 5070 12G）训练；自托管 sidecar（GPU 优先，CPU 可退）服务

**Project Type**: ML 训练/评测管线 + 后端服务集成（无前端改动）

**Performance Goals**: 服务态单脚本 ≤2s（MODEL 通道预算可调优后固定，SC-008）；训练 3B ~61min bf16 LoRA

**Constraints**: 12G VRAM（3B bf16 峰值 11.9G / 7B 需 4bit QLoRA）；服务态推理路径零外部大模型 API（SC-007）；构造性反泄漏（训练集零合成名）

**Scale/Scope**: 银标语料 ~2000–4000 条；测试集 B 非空金标 ≥100；模型 3B 达标主体 + 1.5B 对照

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| **IV. AI 归位本地（服务端无 AI 大脑）— NON-NEGOTIABLE** | ✅ PASS | 血缘 sidecar 是**独立进程、数据处理型**推理（表名抽取），**不是**授权/编排/决策 agent 大脑；`serve/app.py` 头已声明合规姿态（独立进程、不嵌入平台服务端）。teacher 大模型**仅训练时**离线使用，**不进平台**运行态。不触碰 chat/AG-UI/IntentRouter（早已删除）。不损伤 ops 观测/调度内核。041 sidecar 已按此姿态合入 main。 |
| **V. 内核复用而非重写** | ✅ PASS | 复用后端 `ScriptLineageService` 三通道路由与 `ModelExtractor`/`ScriptLineageCorrectionGate`；复用既有 `collect.py`/`adjudicate_aid.py`/`train/sft_qlora.py`/评测 harness/`serve/app.py`；不新造引擎。 |
| **III. 本地两条腿调试 — NON-NEGOTIABLE** | ✅ N/A | 不触碰 CLI 运行时/执行器语义。 |
| **I. 文件优先 / II. 服务器为治理真相源** | ✅ N/A | 血缘输出经既有路径入 neo4j；不改任务/工作流定义文件格式与 pull/push。 |
| **写门审计（附加约束）** | ✅ PASS | 血缘写入沿用既有 `ScriptLineageCorrectionGate`，不新开旁路。 |

**结论**：无违规，Complexity Tracking 留空。**姿态记录**：teacher=训练时离线工具、student=服务时自托管——满足"服务端无 AI 大脑"内核；此判定在 Phase 1 设计后复核仍成立（sidecar 仅新增 dir_fix 后处理，不改变进程边界）。

## Project Structure

### Documentation (this feature)

```text
specs/054-lineage-distillation/
├── plan.md              # 本文件
├── research.md          # Phase 0：关键技术决策
├── data-model.md        # Phase 1：语料/标注/银标/金标/权重/报告 实体
├── quickstart.md        # Phase 1：端到端跑通步骤
├── contracts/           # Phase 1：银标 schema / sidecar extract API / 验收门
│   ├── silver-label.schema.md
│   ├── sidecar-extract.contract.md
│   └── eval-gate.contract.md
└── tasks.md             # Phase 2（/speckit-tasks 生成，本命令不建）
```

### Source Code (repository root)

```text
ml/lineage-extractor/                     # ML 管线（主战场）
├── llm/clients.py                        # [复用] m1/m2 teacher 薄封装（已存在）
├── realeval/
│   ├── collect.py                        # [复用] GitHub 语料采集
│   ├── adjudicate_aid.py                 # [复用] 约定 A 字面门/方向裁决
│   ├── teacher_label.py                  # [新增] 双 teacher 批量打标 + 缓存 + 续跑
│   ├── build_silver.py                   # [新增] 银标构建（交集为主+分歧字面门救回+空样本配比+污染剔除）
│   ├── channel_router.py                 # [复用/抽取] dir_fix 逻辑（供 sidecar 复用）
│   ├── eval_real.py / jvm_slice_eval.py  # [复用] 四方评测 harness
│   └── leak_analysis.py                  # [复用] --train-pool 逐字泄漏审计
├── data/                                 # [复用] 数据组装（银标→训练集）
├── train/sft_qlora.py                    # [复用] --base-model 已支持，换数据即可
├── serve/app.py                          # [改] 内置 dir_fix 后处理 + 050 sqlglot 健壮性补丁
├── eval/metrics.py                       # [复用] canon 口径指标
└── tests/                                # [新增] test_teacher_label / test_build_silver / test_dir_fix_serve

backend/dataweave-master/.../lineage/script/
├── ScriptLineageService.java             # [不动] 三通道编排
├── ModelExtractor.java                   # [不动或微调] 调 sidecar；dir_fix 已在 sidecar 侧
├── ScriptLineageExtractor.java           # [不动]
└── ScriptLineageCorrectionGate.java      # [不动] 血缘写门

sibling（worktree 外，gitignore）:
weft-lineage-weights/run-distill-3b/      # [新增产物] 蒸馏 3B merged 权重
```

**Structure Decision**: 主改动集中在 `ml/lineage-extractor/`（新增 2 个管线模块 + sidecar dir_fix 改造 + 单测）；后端 Java 侧近零改动（仅切 `MODEL_DIR` 与验证接缝）。前端无改动。数据资产（`.env`/`realeval/pool`/`realeval/gold`）从 `dw-041` worktree 复制入本 worktree（gitignore，不入 git）；权重落 sibling。

## Complexity Tracking

> 无 Constitution 违规，留空。
