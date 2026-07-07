# Implementation Plan: Lineage Graph Explorer（血缘图探索器）

**Branch**: `052-lineage-graph-explorer` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/052-lineage-graph-explorer/spec.md`

## Summary

把血缘从「手绘 SVG 网格 Demo」升级为数据中台可用的**血缘图探索器**：前端弃用 `lineage-flow.tsx` 手绘 SVG，复用工作流 DAG 的 `@xyflow/react` 画布（`DagRenderer`）+ 新增分层布局 + 血缘节点组件 + 嵌入画布的可关闭详情面板（`DetailPanelShell`），三栏布局（catalog 树 | 画布 | 面板）；后端补齐 6 项**只读查询缺口**（按名搜索、影响分析返回边、真实可达总数、双向子图带边、两点间路径高亮、节点富属性）+ 服务端过滤。全部改动落在**读侧 + 运行态观测层**，不触碰调度内核、executor、写闸门与 PG schema，符合 constitution「内核复用」。

## Technical Context

**Language/Version**: Java 25（backend）· TypeScript / React 19 / Next.js 16（frontend）

**Primary Dependencies**: Spring Boot 4.0 / WebFlux · Neo4j Java driver（血缘唯一存储）· `@xyflow/react ^12.11`（已在，复用）· **新增 `@dagrejs/dagre`**（血缘分层布局，见 research）· shadcn/ui + hugeicons

**Storage**: Neo4j（血缘图，唯一）· PostgreSQL（只读 `catalog_node`/`tag` 供 owner/tag，**MVP 不改表、不 bump `schema_version`**）· 041 PG 表 `lineage_edge_correction`/`lineage_unresolved_hint`（既有，不动）

**Testing**: JUnit 5 + AssertJ；真 Neo4j IT（`Neo4jTestSupport` Testcontainer / `NEO4J_TEST_URI` 直连）+ h2 profile REST shape 契约 + 跨项目隔离回归；前端 vitest + Playwright 浏览器验证

**Target Platform**: Web（workspace view `lineage`）

**Project Type**: Web application（前后端双项目）

**Performance Goals**: 单视图规模对齐既有 `MAX_NODES=2000` / `MAX_DEPTH=20` 上限；画布在上限规模仍可交互缩放/平移；影响/路径查询强上限保护（`countCap`/`pathCap`）防指数爆炸

**Constraints**: reuse-first 硬规则（图/滚动/下拉/加载/刷新/卡片/图标全走规范组件，禁手写同类原语与手绘 SVG）；无分割线布局；语义 token；项目隔离零泄漏（FR-022）；所有查询只读、不经 PolicyEngine（写仍走既有 `/corrections` 闸门）

**Scale/Scope**: 6 个后端查询端点变更（2 新增 / 4 改） + 前端血缘 view 重写（画布/节点/面板/工具栏） + 2 个新共享 UI 原语（`Segmented`、`Stepper`，回填 DESIGN.md）

## Constitution Check

*GATE: 必须在 Phase 0 前通过；Phase 1 后复检。*

| 原则 | 结论 | 依据 |
|---|---|---|
| **I. Files-First** | ✅ 不适用/不违反 | 本特性是血缘**查询与展示**，不改任务/工作流的文件定义契约 |
| **II. Server is Source of Truth** | ✅ 不违反 | 不引入双向同步；血缘读查询严格项目隔离（FR-022），沿用 `TenantContext`+`project()` |
| **III. Two-Legged Debugging** | ✅ 不适用 | 不触碰 CLI/本地运行时/executor |
| **IV. AI Lives in Local Agent** | ✅ 不违反 | 不新增服务端 AI；**拆除不得损伤运行态观测**——血缘图属 ops 观测面，本特性正是**增强**该面，与内核一致 |
| **V. Reuse the Kernel** | ✅ 遵守 | 复用既有血缘查询服务/Neo4j reader/图渲染栈，不重写；**全部新查询只读，写操作仍经 PolicyEngine 闸门不放行**（`/corrections` 不变），无 gate 绕过 |

**门禁结果：PASS**，无违规，`Complexity Tracking` 留空。新增依赖 `@dagrejs/dagre` 属前端布局工具，非 UI 原语，不与 reuse-first（约束 UI 原语）冲突；理据见 research。

## Project Structure

### Documentation (this feature)

```text
specs/052-lineage-graph-explorer/
├── plan.md              # 本文件
├── spec.md              # 特性规范（已含 Clarifications）
├── research.md          # Phase 0：技术决策
├── data-model.md        # Phase 1：DTO / 图模型 / 视图状态
├── quickstart.md        # Phase 1：端到端验证指南
├── contracts/
│   └── lineage-query-api.md   # 血缘查询 REST 契约（新增/修改端点）
└── checklists/requirements.md # 规范质量清单（已通过）
```

### Source Code (repository root)

```text
backend/
  dataweave-master/src/main/java/com/dataweave/master/
    application/LineageQueryService.java        # 改：search()/impact edges+reachableTotal/neighborhood 下沉/pathsBetween()/富属性投影/WHERE 过滤
    lineage/
      SearchCandidate.java                      # 新增 DTO
      LineagePath.java                          # 新增 DTO
      ImpactResult.java                         # 改：+reachableTotal +totalIsLowerBound（edges 填充）
      GraphNodeView.java / LineageGraph.java    # 不改结构（attrs/edges 已开放，仅填充）
    infrastructure/neo4j/
      Neo4jLineageGraphReader.java              # 改：新 Cypher（search/impact edges/count/neighborhood 无向/paths/富属性/过滤）
      Neo4jSchemaInitializer.java               # 不改（MVP 走 CONTAINS，无新索引；fulltext 为 follow-up）
  dataweave-api/src/main/java/com/dataweave/api/interfaces/
    LineageGraphController.java                 # 改：+/search +/paths；neighborhood 带边；过滤参数透传
  dataweave-master/src/test/
    java/.../LineageSeamE2EIT.java（模板）+ 新增查询 IT
    resources/lineage-test/seed-lineage.cypher  # 改：补 :Task-[:WRITES]->:Table + 更多层/名以测 search/富属性
  dataweave-api/src/test/.../LineageGraphEndpointTest.java  # 改：新端点 h2 shape 契约 + 隔离回归

frontend/
  components/workspace/
    flow-canvas-with-panel.tsx                  # 新增：从 DagDialogInner 抽出的「画布+可调宽嵌入面板」无 Dialog 壳（工作流/血缘共用）
    dag-dialog.tsx                              # 改：改用 FlowCanvasWithPanel（去重）
    nodes/lineage-node.tsx                      # 新增：血缘节点组件（DATASOURCE/TABLE/COLUMN/METRIC，层色/新鲜度/可展开列）
    nodes/lineage-node-types.ts                 # 新增：LineageNodeData 类型 + nodeTypes 注册
    views/lineage-view.tsx                      # 改：三栏（LineageTree | FlowCanvasWithPanel | 面板），编排方向/深度/搜索/影响/路径
    views/lineage/
      lineage-flow.tsx                          # 弃用/删除（手绘 SVG）
      lineage-tree.tsx                          # 改：修正列加载占位逻辑（真三级下钻）
      lineage-node-panel.tsx / lineage-edge-panel.tsx  # 新增：迁 edge/impact 业务逻辑进 DetailPanelShell（headerExtra Tabs）
      lineage-toolbar.tsx                       # 新增：搜索 + 方向 Segmented + 深度 Stepper + 粒度 Segmented + 影响/路径/导出/深链 + ViewRefreshControl
    lib/workspace/lineage-layout.ts             # 新增：dagre LR 分层 lineageToFlow(graph)→{nodes,edges}
    lib/workspace/lineage-selection-store.ts    # 新增：血缘选中态（不复用 useNodeDetailStore）
  components/ui/
    segmented.tsx                               # 新增共享原语（回填 DESIGN.md 原语速查表）
    stepper.tsx                                 # 新增共享原语（回填 DESIGN.md 原语速查表）
  lib/lineage-api.ts                            # 改：+search/+paths；启用 fetchUpstream 做方向；富属性/reachableTotal 类型
  messages/{zh-CN,en-US}.json                   # 改：lineageView 命名空间补键（双写对齐）
  DESIGN.md                                     # 改：原语速查表回填 Segmented / Stepper 条目 + 深章节
```

**Structure Decision**: 沿用既有双项目结构与 DDD 分层；后端改动集中在 `dataweave-master/application`+`infrastructure/neo4j` 与 `dataweave-api/interfaces`，前端集中在 `components/workspace`（画布/节点/视图/面板）+ 2 个 `components/ui` 新原语。视图注册键 `lineage`（`registry.tsx`/`views.ts`）保留不动。

## Complexity Tracking

> 无 Constitution 违规，本节留空。
