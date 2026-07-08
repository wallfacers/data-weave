# Implementation Plan: 数据开发 LSP —— 血缘/依赖接地的创作上下文服务

**Branch**: `058-authoring-context` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/058-authoring-context/spec.md`

## Summary

为 CLI vibecoding 回路里的 AI 编码 agent 提供一组**确定性、按需查询 + 诊断**的血缘/依赖接地能力（类比 LSP，非线协议）：创作上下文（读写表的上游生产者/下游消费者 + 表/列血缘 + 数据源 schema）、复用推荐（写表目标重叠）、一致性诊断（含声明 DAG vs 实际血缘背离）。技术路线：新增一个**编排型**应用服务 `AuthoringContextService`（master 模块），**复用**既有 `LineageQueryService`（upstream/downstream/neighborhood/columnUpstream/impact/downstreamTaskLevels 已齐）、`ScriptLineageService`+Calcite（草稿抽取）、`CatalogGroundingService`（存在真伪）、`WorkflowEdgeRepository`（声明 DAG）、`TaskDef`/`Catalog`（复用候选）；对未 push 工作副本走**无状态** analyze 端点（零持久化）；同一能力经 **CLI 命令 + MCP 只读工具**双面薄封装暴露，并借此**修正既有 MCP 血缘查询漂移**。遍历深度由调用方（AI agent）自决、默认多跳，广度按邻域截断并显式标注。

## Technical Context

**Language/Version**: Java 25（后端 master/api）、Go（dw CLI）、Markdown（Skill 扩展）；无前端改动。

**Primary Dependencies**: Spring Boot 4.0 WebFlux；既有 `LineageQueryService` / `LineageGraphReader`（neo4j 图查）；`ScriptLineageService` + `SqlTableExtractor`（Calcite）；`CatalogGroundingService` + `DatasourceBoundCatalog`；`WorkflowEdgeRepository` / `WorkflowNodeRepository`；`TaskDefRepository` / 目录服务；MCP `McpToolRegistry` 框架；dw CLI（`cli/` Go，`client.go` HTTP + `main.go` 子命令分发）。

**Storage**: 只读——neo4j（血缘图）+ PostgreSQL/H2（task_def / workflow_edge / catalog 读）。**不新增任何表**；工作副本 analyze 为无状态、零持久化。

**Testing**: 后端 JUnit 5 + AssertJ（h2 + neo4j 夹具真跑）；CLI Go test（analyze 往返 + 输出契约）；Skill lint（`cli/sync/skill_lint_test.go` 既有机制）。

**Target Platform**: Docker 多节点 distributed 集群（后端）+ 开发者本机 CLI。

**Project Type**: web-service（共享后端服务 + REST analyze 端点 + MCP 工具）+ CLI（Go 子命令），双面暴露同一能力。

**Performance Goals**: 默认遍历深度下，企业级规模（数千任务定义）创作上下文 **< 5s**（SC-002）；调用方显式加深遍历延迟随之增长（可接受）。

**Constraints**: 全程**确定性**（服务内不调大模型）；工作副本分析**无状态**；**租户 + 项目隔离**贯穿；抽取**复用既有 extractors**（不 fork 第二套血缘抽取引擎）；虚构事实占比 = 0%（防幻觉，表名须字面/图/schema 可定位）。

**Scale/Scope**: 企业级——数千任务定义、深/宽血缘链路（上游可达数百）；深度自决 + 广度按邻域截断并标注。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 状态 | 说明 |
|---|---|---|
| I. Files-First | ✅ Pass | 消费文件化定义（工作副本），不改变磁盘表示；草稿即普通文件读入分析。 |
| II. Server is Source of Truth | ✅ Pass | 纯读 + 诊断，无写、无双向同步、无冲突合并；工作副本分析无状态、以服务端图谱为真相锚，草稿仅本地叠加。 |
| III. Two-Legged Debugging | ✅ Pass（含不变量） | 不涉执行运行时；**草稿血缘抽取 MUST 复用平台既有 extractor（`ScriptLineageService`/Calcite），不 fork 第二套抽取引擎**——与本原则"不分叉第二执行引擎"同构。 |
| IV. AI Lives in Local Agent（不可让渡） | ✅ Pass | **服务端不嵌 AI 大脑**：`AuthoringContextService` 确定性、零 LLM；唯一 AI = 本地编码 agent 消费事实。双面 = MCP + dw CLI（本原则钦定的 agent 操作面）；Skill 扩展 = agent 知识层。不损伤观测/调度。 |
| V. Reuse the Kernel | ✅ Pass | 编排复用 `LineageQueryService`/`ScriptLineageService`/`CatalogGroundingService`/`WorkflowEdgeRepository`/MCP 框架，无重写；因纯读**无写操作**，写闸门不适用（无绕过风险）。 |

**Gate Result: PASS** — 无违规，无需 Complexity Tracking justification。记录一条硬不变量：**草稿抽取复用既有 extractor，禁止第二套抽取实现**（Principle III/V 派生）。

## Project Structure

### Documentation (this feature)

```text
specs/058-authoring-context/
├── plan.md              # 本文件
├── research.md          # Phase 0：关键技术决策
├── data-model.md        # Phase 1：实体/契约模型
├── quickstart.md        # Phase 1：端到端用法演示
├── contracts/           # Phase 1：analyze 端点 + CLI 命令 + MCP 工具契约
│   ├── rest-analyze.md
│   ├── cli-commands.md
│   └── mcp-tools.md
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令产物）
```

### Source Code (repository root)

```text
# 后端（master：编排服务 + 契约；api：REST 端点 + MCP 工具）
backend/dataweave-master/src/main/java/com/dataweave/master/application/authoring/
├── AuthoringContextService.java        # 编排核心：装配上下文/依赖/复用/诊断（确定性）
├── AuthoringContext.java               # 上下文包契约（reads/writes + 上下游 + 列血缘 + schema + 来源/接地态）
├── TaskDependencyView.java             # 声明(DAG)+推导(血缘)合并的依赖视图
├── ReuseCandidate.java                 # 写表目标重叠候选 + 确定性相似分
├── ConsistencyDiagnostic.java          # 诊断项（类别/实体/建议/严重度）
├── DraftLineage.java                   # 工作副本多草稿抽取归一产物（复用 ScriptLineageService/Calcite）
└── ReuseScorer.java                    # 确定性重叠度 + 名称相似打分（无 LLM）

backend/dataweave-api/src/main/java/com/dataweave/api/
├── interfaces/AuthoringContextController.java   # POST /api/authoring-context/analyze（无状态）+ GET by task
└── application/mcp/McpToolRegistry.java         # +query_authoring_context / query_task_deps /
                                                 #  query_reuse_candidates / query_lineage_diagnostics；
                                                 #  升级漂移的 query_lineage（补表/列血缘只读面）

# CLI（Go 子命令，薄封装 REST）
cli/
├── main.go                              # +context / deps / reuse / check 子命令分发
├── context/                             # 新增：请求装配 + 工作副本收集 + JSON 输出
│   ├── analyze.go                       #   收集工作副本草稿 → POST analyze → 打印结构化 JSON
│   └── analyze_test.go
└── client/client.go                     # 复用既有 HTTP 客户端

# Skill（agent 知识层扩展）
.claude/skills/weft-task-authoring/SKILL.md   # +“编辑前取 context、改完 check”回路教学
```

**Structure Decision**: 沿用 backend（master 领域/应用 + api 接口）+ cli（Go）双项目结构。新增集中在 master 的 `application/authoring/` 包（编排服务与契约）、api 的一个 REST 控制器 + MCP 工具注册、cli 的一个 `context/` 子包与 4 个子命令、Skill 一处扩展。无前端改动、无新表。

## Complexity Tracking

> 无宪法违规，此节留空。

## Implementation Phases

> 分期与 spec 的 US1→US3 对齐；每期独立可测、独立可用。P1 是地基，P2/P3 建其上。

### Phase 1（US1，P1）：创作上下文接地 + 依赖 + 双面暴露 + 修 MCP 漂移

1. **契约类**：`AuthoringContext` / `TaskDependencyView` / `DraftLineage`（master `application/authoring/`）。
2. **`AuthoringContextService.context(...)`**：
   - 解析任务/草稿的读写表（草稿走 `ScriptLineageService.extract` + Calcite，**复用既有 extractor**）。
   - 读表→上游生产者、写表→下游消费者：调 `LineageQueryService.upstream/downstream/neighborhood` + `downstreamTaskLevels`；列血缘调 `expandColumns/columnUpstream`。
   - 依赖合并两源：声明 `WorkflowEdgeRepository` DAG 边 + 推导血缘边，分别标注来源（FR-006）。
   - 接地态经 `CatalogGroundingService`/`DatasourceBoundCatalog`（存在/推断/未接地，FR-003）。
   - 深度=调用方参数、默认多跳；广度超阈值经 `clampLimit`/邻域截断并标注（FR-018）。
   - 部分事实源不可用 → 返回可得部分 + 标注缺失，绝不整体失败（FR-005）。
3. **工作副本模式**：`analyze` 无状态——多草稿一起抽取，草稿间跨任务依赖先在草稿内解析再回退服务端图谱；草稿覆盖同名已 push 版本（FR-004/FR-019）。
4. **REST**：`AuthoringContextController` — `POST /api/authoring-context/analyze`（工作副本内容）+ `GET /api/authoring-context/{taskDefId}`（已 push 任务），租户/项目隔离。
5. **MCP**：`query_authoring_context` + `query_task_deps` 只读工具；**升级 `query_lineage`** 补表/列血缘只读查询（FR-015）。
6. **CLI**：`dw context <task|工作副本>` / `dw deps <task>`，收集工作副本草稿 → analyze → `--json` 输出。
7. **Skill**：`weft-task-authoring` 教 agent 编辑前取 context。
8. **测试**：多层链路夹具（A←T1←B←T2）验上下游装配 + 草稿等价 + 未接地不虚构 + 部分降级；CLI 往返；两面语义一致（SC-006）。

### Phase 2（US2，P2）：复用推荐

1. `ReuseCandidate` + `ReuseScorer`（写表目标重叠为主 + 名称相似加权，确定性，FR-007/008）。
2. `AuthoringContextService.reuseCandidates(...)`：以草稿写表目标对既有 `TaskDef`/表定义做重叠检索 + 打分排序；无重叠返回空（FR-009）。
3. CLI `dw reuse <task|草稿>` + MCP `query_reuse_candidates`。
4. 测试：重叠命中/空候选/排序稳定（SC-003 夹具）。

### Phase 3（US3，P3）：一致性诊断

1. `ConsistencyDiagnostic` + `AuthoringContextService.diagnose(...)`：
   - 悬空上游（读表无生产者/未接地）。
   - 下游列契约破坏（写表列被下游列血缘消费而本次移除/改名）。
   - 重复已有定义（复用为强提示）。
   - **声明 DAG vs 实际血缘背离**：缺依赖（有数据流未声明）/僵依赖（声明无数据流）——对比 `WorkflowEdge` 与血缘边（FR-010）。
2. 建议性、不阻断 push（FR-012）。
3. CLI `dw check <task|草稿>` + MCP `query_lineage_diagnostics`。
4. 测试：缺依赖/僵依赖/列契约破坏/一致零误报（SC-004 夹具）。

## Risks & Mitigations

| 风险 | 缓解 |
|---|---|
| `clampDepth` 现有硬顶与"深度自决"冲突 | Phase 0 研究：为 authoring 路径放开深度上限（保留广度 `clampLimit` + 邻域截断护栏），见 research.md 决策 D2 |
| 草稿抽取被误实现成第二套引擎 | 硬不变量：`DraftLineage` 只调 `ScriptLineageService`/Calcite；code review + 测试锁定 |
| 工作副本多草稿跨任务解析复杂度 | 先草稿内解析再回退服务端图谱（FR-019）；夹具覆盖草稿互引用 |
| MCP `query_lineage` 升级破坏既有调用方 | 保留旧字段兼容 or 新增工具并存（Phase 0 决策 D4，plan 级） |
| 深/宽链路致上下文包过大、延迟 | 深度自决但广度按邻域截断并显式标注；SC-002 只约束默认深度 |
| 与并行 057-system-settings 边界 | 无共享改面（本特性=血缘/依赖查询；057=系统设置）；worktree 隔离，集成前复核 |

## Verification

1. `cd backend && ./mvnw -q -pl dataweave-master -am compile` + `-pl dataweave-api compile` 零错误。
2. 后端 IT：多层链路夹具跑 context/deps/reuse/diagnose 全绿（h2 + neo4j）。
3. `curl -X POST /api/authoring-context/analyze`（工作副本）返回结构化上下文；`GET /{taskDefId}` 一致。
4. MCP：`query_authoring_context` 与 CLI `dw context` 同任务语义一致（SC-006）。
5. CLI：`cd cli && ./build.sh && go test ./...` 绿；`dw context/deps/reuse/check --json` 真跑。
6. Skill lint 通过。
