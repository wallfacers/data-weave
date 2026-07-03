# Implementation Plan: 脚本任务血缘解析（Python/Shell 表与字段抽取）

**Branch**: `041-script-lineage-extraction` | **Date**: 2026-07-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/041-script-lineage-extraction/spec.md`

## Summary

把设计态血缘从 SQL 任务扩展到 Python/Shell 脚本任务：push/上线时对脚本源码做**三通道**静态抽取——①内嵌 SQL 词法挖掘后复用既有 Calcite 解析链路（SCRIPT_SQL，确定边）；②常见程序接口写表的规则推断（SCRIPT_INFERRED，UNVERIFIED 虚线边）；③**本期训练的专用小模型**（Qwen2.5-Coder-1.5B QLoRA 微调，合成数据全流程，独立推理 sidecar，SCRIPT_MODEL 边）补规则长尾，优先序 SQL > RULE > MODEL。产物走既有 `LineageStore.recordTaskIo`（replace-per-task 白得 FR-008）。新增 PG 两表承载人工修正（确认/剔除，语义键抑制）与未解析提示，修正动作过 GatedActionService 门禁；前端血缘图谱补边点击详情面板与来源区分。模型/数据集发布用户 Hugging Face 账号。技术决策全文见 [research.md](research.md)（D1-D12）。

## Technical Context

**Language/Version**: Java 25（backend master/api 模块）；TypeScript / React 19（frontend）；Python 3.11+（`ml/` 训练与推理 sidecar，独立于 backend）

**Primary Dependencies**: Spring Boot 4 / WebFlux、Apache Calcite、neo4j-java-driver（均既有，backend **零新增依赖**）；ml 侧：transformers + peft + trl + bitsandbytes + FastAPI + huggingface_hub（隔离在 `ml/` requirements，不进平台构建）

**Storage**: neo4j（血缘边唯一存储，source 新增 SCRIPT_SQL/SCRIPT_INFERRED/SCRIPT_MODEL 取值）；PostgreSQL/H2 新增 `lineage_edge_correction`、`lineage_unresolved_hint` 两表，`schema_version 0.6.2 → 0.7.0`；模型/数据集工件托管用户 Hugging Face 私有 repo

**Testing**: JUnit 5 + AssertJ（抽取器语料库单测 + h2 集成测试，遵循 backend 测试隔离不变量）；ml 侧评估脚本以退出码表达 SC-006 门槛判定；vitest + 浏览器实测（前端）

**Target Platform**: Linux server（既有部署形态不变）+ 推理 sidecar（开发机 12G GPU 常驻 / 生产可选 CPU 量化）

**Project Type**: web-service（backend 四模块 DDD）+ Next.js frontend + ml 训练/推理子项目

**Performance Goals**: push 端到端耗时增幅 ≤20%（SC-003）；单任务脚本抽取时间预算 2s（模型推理超时同预算，超时即弃，SC-007）

**Constraints**: 抽取零阻断；`task_def.content ≤ 4000` 字符界定输入上限；宁缺毋滥；模型输出双重校验（结构 + 表名脚本内可定位，幻觉率 ≤2%）；确定性解码；训练显存 ≤12G（QLoRA 1.5B）；不用租户生产脚本训练

**Scale/Scope**: 后端新增 1 个 service 包（约 6 类，含 ModelExtractor）+ 2 张 PG 表 + 3 个 REST 端点 + 门禁 case；前端改 2 个组件 + 新增 1 个边详情面板；`ml/lineage-extractor/` 新子项目（数据合成/训练/评估/serve 四模块）；训练集 ~10k 合成样本，held-out 评估集 ≥200 条

## Constitution Check

*GATE: 基于 constitution v1.2.0 逐条评估；Phase 1 设计后复评通过。*

| 原则 | 评估 | 结论 |
|---|---|---|
| I. Files-First | 不改任务定义文件契约（数据源坐标沿用任务绑定，Q1 裁决），pull/push 往返无新字段 | ✅ Pass |
| II. Server is Source of Truth | 抽取在服务端 push/上线时执行；push 幂等覆盖语义不变，血缘随版本整体替换 | ✅ Pass |
| III. Two-Legged Debugging | 不触碰执行器与 CLI 本地运行时 | ✅ Pass（不涉及） |
| IV. AI Lives in the Local Agent | **有记录偏差**：本期引入专用抽取小模型。合规姿态：推理为独立 sidecar 进程（不嵌入四模块），确定性解码、固定 schema、无对话/决策/工具面——非条文所指"agent 大脑"；不可用自动降级纯规则。用户 2026-07-03 指令构成书面批准。详见下方 Complexity Tracking 与 research D12 | ⚠️ Deviation recorded & approved |
| V. Reuse the Kernel | 复用 Calcite 抽取器、LineageStore replace 语义、坐标解析、GatedActionService 门禁（修正动作 L1 过闸 + agent_action 留痕，无旁路）；模型通道经 FR-010 可插拔点接入，不动内核 | ✅ Pass |

## Project Structure

### Documentation (this feature)

```text
specs/041-script-lineage-extraction/
├── plan.md              # 本文件
├── research.md          # Phase 0 技术决策（D1-D8）
├── data-model.md        # Phase 1 实体/表/图属性设计
├── quickstart.md        # Phase 1 手工验证路径
├── contracts/
│   └── lineage-script-api.md   # REST 契约（corrections + hints + 图查询扩展）
└── tasks.md             # /speckit-tasks 产出（尚未生成）
```

### Source Code (repository root)

```text
backend/
├── dataweave-master/src/main/java/com/dataweave/master/
│   ├── application/lineage/script/          # 新增包
│   │   ├── ScriptLineageExtractor.java      #   可插拔抽取器接口（FR-010）
│   │   ├── ScriptExtraction.java            #   抽取产物 record（edges+hints）
│   │   ├── EmbeddedSqlExtractor.java        #   D1：字面量挖掘+SQL嗅探→Calcite
│   │   ├── ApiPatternExtractor.java         #   D2：接口模式推断（含列清单字段级）
│   │   └── ScriptLineageService.java        #   编排：聚合/冲突消解/抑制/时间预算
│   ├── application/lineage/
│   │   └── LineageCorrectionService.java    # 新增：修正读写 + 抑制查询
│   ├── application/
│   │   ├── ProjectSyncService.java          # 改：5c-lineage 段并联脚本通道
│   │   ├── TaskService.java                 # 改：recordLineage 并联脚本通道
│   │   └── DefaultPlatformActionExecutor.java  # 改：新增 LINEAGE_* case
│   ├── domain/lineage/                      # 改：Neo4jLineageStore FLOWS_TO 补 source/confidence
│   └── lineage/                             # 改：FlowEdgeView 补 source/人工状态（如需）
├── dataweave-api/src/main/
│   ├── java/com/dataweave/api/interfaces/
│   │   └── LineageGraphController.java      # 改：+POST corrections、+GET hints/corrections
│   └── resources/
│       ├── schema.sql                       # 改：+2 表，version 0.7.0
│       └── data.sql                         # 改：policy_rules +3 行（LINEAGE_* L1）
frontend/
├── components/workspace/views/lineage/
│   ├── lineage-flow.tsx                     # 改：边 onClick+命中区+推断图例
│   └── edge-detail-panel.tsx                # 新增：边详情（来源/置信度/提示/修正按钮）
├── components/workspace/views/lineage-view.tsx  # 改：挂载边详情面板
├── lib/lineage-api.ts                       # 改：+corrections/hints 客户端
└── messages/{zh-CN,en-US}.json              # 改：lineageView 命名空间补 key（双语同步）
ml/lineage-extractor/                        # 新子项目（Python，独立 requirements/README，不进 backend 构建）
├── data/
│   ├── synth_pipeline.py                    #   D10：三源合成管线（HF 源数据集 → 模板注入 → JSONL）
│   └── templates/                           #   ≥30 种 Python/Shell 写表形态模板 + 负例模板
├── train/
│   └── sft_qlora.py                         #   D9/D11：Qwen2.5-Coder-1.5B QLoRA SFT（固定 seed）
├── eval/
│   └── evaluate.py                          #   SC-006 三指标 + 门槛退出码 + 报告生成
├── serve/
│   └── app.py                               #   D12：FastAPI sidecar，POST /extract（温度0）
├── publish.py                               #   HF Hub 私有 repo 发布（模型+数据集+评估卡）
└── requirements.txt / README.md
backend/dataweave-master/src/main/java/com/dataweave/master/
└── application/lineage/script/
    └── ModelExtractor.java                  # 新增：第三个抽取器（WebClient→sidecar，双重校验，探活旁路）
```

**Structure Decision**: 沿 DDD 分层——抽取器与编排器在 master 的 application 层（`lineage/script/` 新包），图写入仍收口 `LineageStore` 域接口；api 模块只加 REST 接口与 DDL/seed。前端只动血缘视图族与 API 客户端，不新建视图（FR-006）。

## 实施阶段划分（供 /speckit-tasks 分解）

1. **P1 通道**（US1，独立可交付）：script 包骨架 + EmbeddedSqlExtractor + 两触发点接线 + FLOWS_TO 补属性 + 语料库单测 → 图谱可见脚本任务确定边。
2. **P2 通道**（US2）：ApiPatternExtractor + hints 表/schema bump + ScriptLineageService 冲突消解/时间预算 + hints 查询端点。
3. **P3 修正闭环**（US3）：correction 表 + 门禁三 actionType + corrections 端点 + 抑制重放 + 前端边详情面板/权限门禁/图例 + 浏览器验证。
4. **P4 模型通道**（US4，训练线与 1-3 并行、接入依赖 US2 基建）：HF 数据集核实/认证 → 合成数据管线 → QLoRA 训练 → held-out 评估（SC-006 门槛闸）→ HF 发布 → 推理 sidecar → ModelExtractor 接入 + 降级验证。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 宪法原则 IV 内核条文「服务端无 AI 大脑（不嵌入推理/决策/agent 逻辑）」——本期引入服务端可调用的 ML 推理 | 规则模式表只能覆盖枚举过的接口形态，长尾脚本写法（自定义封装、变量中转链）需要泛化能力；用户 2026-07-03 明确指令小模型纳入本期（书面批准） | ① 纯规则交付（原方案 A）——被用户改判否决；② 推理嵌入 master JVM——直接违反条文且 JVM 跑 transformer 不现实，拒；③ 采纳姿态：独立 sidecar 进程 + 确定性抽取组件（非对话/决策 agent 大脑）+ 不可用自动降级，最大限度贴合条文精神；建议后续修宪明确区分「agent 大脑」与「专用 ML 组件」 |

## 风险与既有缝

- `TaskService.recordLineage` 的 tenant/project 硬编码 `1L,1L`（TaskService.java:480,494）是**既有缝**，本特性在该路径接线时沿用现状、不扩大也不修复（修复属独立技术债，避免越界改别人语义）；push 路径用真实租户。
- `FLOWS_TO` 补 `source/confidence` 会让 SQL 任务的边详情同步"变有值"，读侧兼容（`@JsonInclude(NON_NULL)` 原本就允许空）。
- 前端边命中区加粗需注意不破坏既有 impact 高亮样式（design contract：只用语义 token）。
- **模型达标风险**：SC-006 门槛（precision ≥85%/未覆盖子集召回 ≥60%/幻觉率 ≤2%）不保证一次达标；缓解：评估门槛闸设计使 US4 未达标不阻塞 US1-US3 交付（spec 假设），备选 encoder 路线已记录（research D9）。
- **HF 依赖**：数据集 id/许可以 implement 首任务经 HF MCP 核实为准（research D10 所列为知识内高置信候选，不排除需替换）。
- **生产推理形态**：12G GPU 在开发机；生产若无 GPU 用 CPU 量化推理（延迟放宽到异步可接受），或推迟 sidecar 生产部署——平台侧探活旁路保证任意形态下零阻断。
