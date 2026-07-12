# Implementation Plan: 血缘小模型实证研究——可投论文加固

**Branch**: `065-lineage-paper` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/065-lineage-paper/spec.md`

## Summary

把已完成并合入 main 的三个实证发现（逐规模记忆泄漏曲线、诚实去偏、分层复核信封）加固成一篇可投 MSR（备 EMSE）的论文。头条=泄漏曲线+诚实去偏+分层信封（不靠"超 teacher"，绕开 n≈49），脊椎=泄漏科学。技术路径=在**不改动既有打分逻辑（`eval/metrics.py` 的表名口径 tp/fp/fn）**的前提下，叠加三层评测证据基建：① 统计诚实层（脚本级 bootstrap CI + McNemar，消费既有逐脚本 counts）；② 工具基线（regex 补跑 gold C + 新增 SQLLineage，脚本子集"工具≈0/模型救回"分层对照）；③ 可复现 benchmark（标签+指针+脚本形态，无凭据第三方可复算）。W4 auto-gold 扩容为可选稳健性。全部工作落 `ml/lineage-extractor/`，隔离于 065 worktree，不重训模型、不碰后端/前端。

## Technical Context

**Language/Version**: Python 3.11+（既有 `ml/lineage-extractor/`）

**Primary Dependencies**: 已在=`numpy`/`pandas`/`sqlglot`/`transformers`/`torch`/`openai`/`anthropic`/`python-dotenv`；**需新增**=`scipy`（McNemar 精确二项检验）、`sqllineage`（库级工具基线，内建于 sqlglot）。评测核心离线，不需 GPU。

**Storage**: 文件——gold jsonl、落盘预测 preds、`out/` 报告；发布走 HuggingFace（标签+指针，不含源码本体/合成集）。

**Testing**: pytest（既有 `ml/lineage-extractor/tests/`）。新特性必带测试（宪法质量门 + CLAUDE.md）。

**Target Platform**: Linux 开发机；W1–W3 离线复算（复用落盘 preds，无 GPU）。

**Project Type**: 研究评测库/CLI（Python 脚本集），扩展既有 `eval/` + `realeval/` + 新增 `benchmark/`。

**Performance Goals**: 离线复算覆盖 ~49–200 非空脚本，秒级；bootstrap 默认 10k 重采样在 ~150 样本上亚秒~秒级；确定性由固定 seed 保证。

**Constraints**: ① **MUST NOT** 修改 `eval/metrics.py` 的指标定义/打分逻辑，统计层只消费其逐脚本 counts；② benchmark 核心复现 **MUST** 无需项目私有凭据；③ 发布 **MUST NOT** 含源码本体重分发 / 合成训练集泄漏；④ 全程隔离 065 worktree，不触碰其他特性。

**Scale/Scope**: gold ~49（非空，可选扩到 ~100–200）；3 个模型规模（0.5/1.5/3B）× 2 工具基线 × teacher(m1，m2 回退)；deepseek(m3) 凭据缺失，其对比脚注声明、不在关键路径。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Weft 宪法原则 I–V 治理的是 **Tasks-as-Code 平台内核**（文件优先/服务器真相源/两条腿调试/AI 归位本地/内核复用）。本特性是 `ml/lineage-extractor/` 内的**研究评测工具**，**不触碰**平台创作/同步/调度/执行器/MCP/前端任何面：

| 原则 | 适用性 | 判定 |
|---|---|---|
| I. Files-First | N/A | 不产生平台任务/目录定义；评测产物本就是 plain-text jsonl/md |
| II. Server 真相源 | N/A | 不涉及 pull/push/版本快照/隔离 |
| III. 两条腿调试 | N/A | 不涉及 CLI 任务运行时/执行器 |
| IV. AI 归位本地 | N/A | 不涉及服务端 AI/MCP；小模型是被研究对象，非平台嵌入的 AI 大脑 |
| V. 内核复用 | N/A | 不涉及调度/执行/版本/写闸门内核 |
| 质量门：测试必备 | **适用** | ✅ 每个新模块带 pytest（见 Phase 1 测试计划）；只认 Tests run>0 |
| 质量门：worktree 隔离 | **适用** | ✅ 已隔离 `../dw-065-lineage-paper`，不污染 main |

**结论**：无原则冲突，无需 Complexity Tracking。诚实性（不挑数据、如实报 CI/负结果）虽非成文原则，是本特性的脊椎约束，已写入 spec FR-010/FR-011 + SC-002/SC-003。**Gate PASS**。

## Project Structure

### Documentation (this feature)

```text
specs/065-lineage-paper/
├── plan.md              # 本文件
├── research.md          # Phase 0：方法学决策
├── data-model.md        # Phase 1：证据实体
├── quickstart.md        # Phase 1：复现步骤
├── contracts/           # Phase 1：模块接口契约（非 REST）
│   ├── significance-api.md
│   ├── baseline-predict.md
│   └── benchmark-manifest.schema.json
├── checklists/
│   └── requirements.md  # spec 质量清单（已 16/16）
└── tasks.md             # Phase 2（/speckit-tasks 产出，本命令不建）
```

### Source Code (repository root)

```text
ml/lineage-extractor/
├── eval/
│   ├── metrics.py                 # 既有：tables/score_row/aggregate —— 本特性不改
│   ├── significance.py            # 新增 W1：bootstrap CI + paired-diff + McNemar
│   └── baselines/
│       ├── regex_baseline.py      # 既有：补跑 gold C（不改接口）
│       ├── llm_baseline.py        # 既有
│       └── sqllineage_baseline.py # 新增 W2：SQLLineage predict(row)，脚本→空
├── realeval/
│   ├── eval_model_c.py            # 既有：3B @ gold C（复用逐脚本 counts）
│   ├── eval_teachers_c.py         # 既有：teacher @ gold C
│   ├── significance_report.py     # 新增 W1：汇总 CI + McNemar → out/
│   ├── eval_baselines_c.py        # 新增 W2：regex+SQLLineage @ gold C，SQL/脚本分层对照
│   ├── conf_calibration_cv.py     # 既有：CV 脚手架（借鉴折切分，不改）
│   └── expand_gold_c.py           # 新增 W4（可选）：the-stack+共识扩容，dedup 防污染
├── benchmark/                     # 新增 W3：可复现发布物
│   ├── build_manifest.py          # 产标签记录+repo/commit 指针+清单
│   ├── fetch.py                   # 无凭据按指针抓源文件（复现用）
│   └── README.md                  # 复现说明
├── out/                           # 既有：报告落盘（新增 significance/baseline/benchmark 报告）
├── tests/
│   ├── test_significance.py       # 新增：bootstrap 确定性/CI/McNemar 已知例
│   ├── test_sqllineage_baseline.py# 新增：SQL 解析/脚本→空
│   └── test_benchmark_manifest.py # 新增：清单 schema/无源码重分发/无合成泄漏
└── requirements.txt               # 加 scipy + sqllineage
```

**Structure Decision**: 单项目（Python 研究评测），扩展既有 `eval/`+`realeval/`+新增 `benchmark/`。稳定接缝=`eval/metrics.py` 的逐脚本 counts（统计层叠加其上，不改打分）+ `baselines/*.predict(row)`（工具基线共用契约）。

## Complexity Tracking

> Constitution Check 无违规，本节留空。
